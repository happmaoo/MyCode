package com.myapp.btcams;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * 调试用：用 Camera2 直接抓一张 640x480 的 JPEG，存到 files/probe.jpg。
 * 用来和推流的画面做对比，判断是不是编码链路把画面拉变形了。
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "Probe";
    private HandlerThread thread;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String camId = getIntent().getStringExtra("camera");
        if (camId == null) {
            camId = "1";
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no camera permission");
            finish();
            return;
        }
        thread = new HandlerThread("Probe");
        thread.start();
        handler = new Handler(thread.getLooper());
        final String id = camId;
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Log.i(TAG, "probe camera " + id + " orientation="
                    + ch.get(CameraCharacteristics.SENSOR_ORIENTATION));
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    startCapture(camera);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    finish();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.w(TAG, "camera error " + error);
                    camera.close();
                    finish();
                }
            }, handler);
        } catch (Exception e) {
            Log.e(TAG, "open failed", e);
            finish();
        }
    }

    private void startCapture(final CameraDevice camera) {
        try {
            final ImageReader reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image image = r.acquireNextImage();
                    try {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);
                        File out = new File(getFilesDir(), "probe.jpg");
                        FileOutputStream fos = new FileOutputStream(out);
                        fos.write(data);
                        fos.close();
                        Log.i(TAG, "probe saved " + out.getAbsolutePath()
                                + " " + data.length + " bytes");
                    } catch (Exception e) {
                        Log.e(TAG, "save failed", e);
                    } finally {
                        image.close();
                    }
                    camera.close();
                    finish();
                }
            }, handler);

            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            try {
                                CaptureRequest.Builder b =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                b.addTarget(reader.getSurface());
                                session.capture(b.build(), null, handler);
                            } catch (Exception e) {
                                Log.e(TAG, "capture failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.w(TAG, "configure failed");
                            camera.close();
                            finish();
                        }
                    }, handler);
        } catch (Exception e) {
            Log.e(TAG, "session failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }
}
