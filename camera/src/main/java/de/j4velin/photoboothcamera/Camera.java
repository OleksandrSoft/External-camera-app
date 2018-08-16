package de.j4velin.photoboothcamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import de.j4velin.photobooth.common.CameraUtil;
import de.j4velin.photobooth.common.Config;
import de.j4velin.photobooth.common.Const;

public class Camera extends Activity {

    private final static int REQUEST_CODE_PERMISSION = 1;

    private final static String TAG = "photobooth.camera";
    private final static AtomicInteger imageCounter = new AtomicInteger(0);
    private File saveImagesFolder;

    private final CameraUtil cameraUtil = new CameraUtil();

    private ExecutorService imageSender;
    private TextureView cameraView;
    private volatile WifiListener wifiListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;
        getWindow().setAttributes(lp);
        setContentView(R.layout.camera);
        cameraView = findViewById(R.id.cameraview);
        cameraView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        imageSender = Executors.newSingleThreadExecutor();
        if (checkSelfPermission(
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CODE_PERMISSION);
        } else {
            setup();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraUtil.shutdown();
        imageSender.shutdown();
        wifiListener.keepRunning = false;
        wifiListener = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (checkSelfPermission(
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            setup();
        } else if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            finish();
        }
    }

    private void setup() {
        saveImagesFolder = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM).getAbsolutePath() + "/photobooth/");
        if (!saveImagesFolder.mkdirs()) {
            saveImagesFolder = getExternalFilesDir(Environment.DIRECTORY_DCIM);
        }
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (!new File(saveImagesFolder, i + ".jpg").exists()) {
                imageCounter.set(i);
                break;
            }
        }
        cameraUtil.setup(this, CameraCharacteristics.LENS_FACING_BACK,
                new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(final ImageReader imageReader) {
                        try {
                            Image image = imageReader.acquireLatestImage();
                            if (image != null) {
                                if (BuildConfig.DEBUG) Log.d(TAG, "Image available");
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                byte[] bytesToSend = new byte[buffer.remaining()];
                                buffer.get(bytesToSend);
                                image.close();
                                if (wifiListener != null) {
                                    imageSender.execute(
                                            new ImageSender(bytesToSend, wifiListener.out));
                                }
                                saveFile(bytesToSend);
                            }
                        } catch (Throwable t) {
                            if (BuildConfig.DEBUG) Log.e(TAG,
                                    "Capture failed: " + t.getMessage());
                        }
                    }
                }, cameraView);
        wifiListener = new WifiListener();
        new Thread(wifiListener).start();
    }

    private void saveFile(byte[] data) {
        File f = new File(saveImagesFolder, imageCounter.getAndIncrement() + ".jpg");
        try {
            f.createNewFile();
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(data);
            }
        } catch (Throwable t) {
            showToast("Saving file failed: " + t.getMessage());
            if (BuildConfig.DEBUG) {
                Log.e(TAG,
                        "Saving file failed: " + t.getMessage());
                t.printStackTrace();
            }
        }
    }

    private static class ImageSender implements Runnable {
        private final byte[] bytesToSend;
        private final DataOutputStream out;

        private ImageSender(byte[] bytesToSend, DataOutputStream out) {
            this.bytesToSend = bytesToSend;
            this.out = out;
        }

        @Override
        public void run() {
            try {
                out.writeInt(bytesToSend.length);
                out.write(bytesToSend);
                out.flush();
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) Log.e(TAG,
                        "Cant send result image: " + e.getMessage());
            }
        }
    }

    private static Optional<String> getDisplayIp(final String ipPrefix) {
        String[] ips = new String[255];
        for (int i = 1; i < 255; i++) {
            ips[i] = ipPrefix + (i - 1);
        }

        // TODO: change predicate to lambda expression when updating to 1.8
        return Arrays.stream(ips).parallel().filter(new Predicate<String>() {
            @Override
            public boolean test(String s) {
                try {
                    Socket socket = new Socket();
                    socket.connect(
                            new InetSocketAddress(s, Config.CAMERA_SOCKET_PORT),
                            1000);
                    socket.close();
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        }).findAny();
    }

    private void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Camera.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private class WifiListener implements Runnable {
        private boolean keepRunning = true;
        private DataOutputStream out;

        @Override
        public void run() {
            try {
                while (keepRunning) {
                    WifiManager wm = (WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wi = wm.getConnectionInfo();
                    int ip = wi.getIpAddress();
                    String ipPrefix = String.format("%d.%d.%d.", (ip & 0xff), (ip >> 8 & 0xff),
                            (ip >> 16 & 0xff));

                    if (ipPrefix.equals("0.0.0.")) {
                        ipPrefix = "192.168.43.";
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG,
                            "Try to find IP of display device, prefix=" + ipPrefix);

                    Optional<String> displayIp = getDisplayIp(ipPrefix);
                    if (!displayIp.isPresent()) {
                        showToast(
                                "Could not find any device with open port " + Config.CAMERA_SOCKET_PORT
                                        + " in range " + ipPrefix + "* - try again in " + (Config.SOCKET_CONNECT_RETRY_SLEEP / 1000) + " sec...");
                        try {
                            Thread.sleep(Config.SOCKET_CONNECT_RETRY_SLEEP);
                        } catch (InterruptedException ie) {
                            // ignore
                        }
                        continue;
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to " + displayIp.get());
                    showToast("Connecting to " + displayIp.get());

                    try (Socket socket = new Socket(displayIp.get(), Config.CAMERA_SOCKET_PORT)) {
                        socket.setKeepAlive(true);
                        out = new DataOutputStream(socket.getOutputStream());
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), "UTF-8"));
                        String inputLine;
                        while (keepRunning && (inputLine = in.readLine()) != null) {
                            if (BuildConfig.DEBUG) Log.d(TAG,
                                    "Line read over socket: " + inputLine);
                            if (!keepRunning) {

                            } else if (inputLine.equalsIgnoreCase(
                                    Const.COMMAND_TAKE_PHOTO)) {
                                cameraUtil.takePhoto();
                            } else if (inputLine.equalsIgnoreCase(Const.COMMAND_PING)) {
                                out.writeUTF(Const.COMMAND_PONG);
                                out.flush();
                            } else if (BuildConfig.DEBUG) {
                                Log.w(TAG, "Ignoring unknown command: " + inputLine);
                            }
                        }
                        if (BuildConfig.DEBUG) Log.i(TAG, "Socket connection closed");
                        out = null;
                    } catch (IOException ce) {
                        showToast("Can not connect to display: " + ce.getMessage());
                        if (BuildConfig.DEBUG) Log.e(TAG,
                                "Cant connect to socket: " + ce
                                        .getMessage() + ", retry in 5 sec");
                        try {
                            Thread.sleep(Config.SOCKET_CONNECT_RETRY_SLEEP);
                        } catch (InterruptedException ie) {
                            // ignore
                        }
                    }
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "connectThread exit");
            } catch (Throwable t) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG,
                            t.getClass().getSimpleName() + ": " + t.getMessage());
                    t.printStackTrace();
                }
                finish();
            }
        }
    }
}
