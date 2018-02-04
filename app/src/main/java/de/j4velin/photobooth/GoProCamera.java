package de.j4velin.photobooth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// TODO add keep-alive
public class GoProCamera implements ICamera {

    private final static String GOPRO_IP = "10.5.5.9";
    private final static Request PHOTO_MODE_REQUEST =
            new Request.Builder().url("http://" + GOPRO_IP + "/gp/gpControl/command/mode?p=1")
                    .build();
    private final static Request TAKE_PHOTO_REQUEST =
            new Request.Builder().url("http://" + GOPRO_IP + "/gp/gpControl/command/shutter?p=1")
                    .build();
    private final static Request GET_STATUS_REQUEST =
            new Request.Builder().url("http://" + GOPRO_IP + "/gp/gpControl/status").build();
    private final static Request GET_MEDIA_REQUEST =
            new Request.Builder().url("http://" + GOPRO_IP + ":8080/gp/gpMediaList").build();

    private final static OkHttpClient client = new OkHttpClient();
    private final static JsonParser JSON_PARSER = new JsonParser();
    private final List<CameraCallback> cameraCallbacks = new ArrayList<>(1);

    private Boolean gopro_ready = true;
    private boolean network_ready = false;

    private final Thread takePhotoThread = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                Response shutterResponse = client.newCall(TAKE_PHOTO_REQUEST).execute();
                if (shutterResponse.isSuccessful()) {
                    int statusCode = 1;
                    while (statusCode == 1) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                        }
                        Response statusResponse = client.newCall(GET_STATUS_REQUEST).execute();
                        if (statusResponse.isSuccessful()) {
                            JsonObject statusJson =
                                    JSON_PARSER.parse(statusResponse.body().string())
                                            .getAsJsonObject();
                            statusCode =
                                    statusJson.get("status").getAsJsonObject().get("8").getAsInt();
                        } else if (BuildConfig.DEBUG) {
                            Log.e(Main.TAG,
                                    "GoPro getStatus call failed: " + statusResponse.message());
                        }
                    }
                    Response mediaResponse = client.newCall(GET_MEDIA_REQUEST).execute();
                    if (mediaResponse.isSuccessful()) {
                        synchronized (gopro_ready) {
                            gopro_ready = true;
                        }
                        JsonArray mediaArray =
                                JSON_PARSER.parse(mediaResponse.body().string()).getAsJsonObject()
                                        .get("media").getAsJsonArray();
                        JsonObject mediaObject =
                                mediaArray.get(mediaArray.size() - 1).getAsJsonObject();
                        String folder = mediaObject.get("d").getAsString();
                        JsonArray elementArray = mediaObject.get("fs").getAsJsonArray();
                        String latestFile =
                                elementArray.get(elementArray.size() - 1).getAsJsonObject().get("n")
                                        .getAsString();

                        String url = " http://" + GOPRO_IP + ":8080/videos/DCIM/" + folder + "/" +
                                latestFile;

                        URLConnection connection = new URL(url).openConnection();
                        connection.setDoInput(true);
                        connection.connect();
                        InputStream input = connection.getInputStream();
                        Bitmap image = BitmapFactory.decodeStream(input);

                        for (CameraCallback callback : cameraCallbacks) {
                            callback.imageReady(image);
                        }
                        return;
                    } else if (BuildConfig.DEBUG) {
                        Log.e(Main.TAG, "GoPro getMedia call failed: " + mediaResponse.message());
                    }
                } else if (BuildConfig.DEBUG) {
                    Log.e(Main.TAG, "GoPro shutter call failed: " + shutterResponse.message());
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) e.printStackTrace();
            }
            synchronized (gopro_ready) {
                gopro_ready = true;
            }
        }
    });

    GoProCamera(final Context context) {
        checkNetworkState(context);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                checkNetworkState(context);
            }
        }, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
    }

    private void checkNetworkState(final Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        boolean wasReadyBefore = network_ready;
        network_ready = wm != null &&
                Formatter.formatIpAddress(wm.getDhcpInfo().serverAddress).contains(GOPRO_IP);
        if (BuildConfig.DEBUG) Log.d(Main.TAG, "GoPro network ready: " + network_ready);
        if (network_ready && !wasReadyBefore) {
            client.newCall(PHOTO_MODE_REQUEST).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (BuildConfig.DEBUG)
                        Log.e(Main.TAG, "Can change gopro mode: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (BuildConfig.DEBUG) Log.e(Main.TAG, "GoPro set to photo mode");
                }
            });
        }
    }

    @Override
    public void takePhoto() {
        synchronized (gopro_ready) {
            if (gopro_ready) {
                gopro_ready = false;
                takePhotoThread.start();
            }
        }
    }

    @Override
    public void addPhotoTakenCallback(CameraCallback callback) {
        cameraCallbacks.add(callback);
    }

    @Override
    public boolean cameraIsReady() {
        synchronized (gopro_ready) {
            return network_ready && gopro_ready;
        }
    }
}
