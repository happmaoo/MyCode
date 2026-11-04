package com.myapp.blecams.camera;

import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Camera2 取流：把相机帧直接输出到 MediaCodec 的 InputSurface（零拷贝）。
 *
 * <p>说明：相机的输出尺寸必须是它自己支持的尺寸，因此请求 320x240 时如果不被支持，
 * 会在同比例（4:3）里就近选一个，实际编码分辨率通过 {@link #getChosenSize()} 反馈给上层。
 */
public class Camera2Source {

    private static final String TAG = "Camera2Source";

    public interface Listener {
        /** 会话就绪，可以开始收帧。 */
        void onReady(int width, int height);

        void onFailed(String message);
    }

    private final Context appContext;
    private final Listener listener;

    private HandlerThread thread;
    private Handler handler;

    private CameraManager manager;
    private String cameraId;
    private CameraDevice device;
    private CameraCaptureSession session;

    private Surface encoderSurface;
    private Surface previewSurface;
    private Size chosenSize;
    private int fps = 10;

    private volatile boolean closing;

    public Camera2Source(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    /** 在相机支持的预览尺寸里，挑一个“宽高比尽量一致 + 面积最接近目标”的。 */
    public static Size chooseSize(Context context, int targetWidth, int targetHeight) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String id = pickBackCamera(cm);
            if (id == null) {
                return new Size(targetWidth, targetHeight);
            }
            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<Size> list = new ArrayList<>();
            if (map != null) {
                Size[] sizes = safeOutputSizes(map, android.graphics.SurfaceTexture.class);
                if (sizes == null || sizes.length == 0) {
                    sizes = safeOutputSizes(map, Surface.class);
                }
                if (sizes != null && sizes.length > 0) {
                    list = filter(sizes);
                }
            }
            if (list.isEmpty()) {
                return new Size(targetWidth, targetHeight);
            }
            final long targetArea = (long) targetWidth * targetHeight;
            final double targetAspect = (double) targetWidth / targetHeight;
            Comparator<Size> cmp = (a, b) -> {
                double sa = score(a, targetArea, targetAspect);
                double sb = score(b, targetArea, targetAspect);
                return Double.compare(sa, sb);
            };
            Collections.sort(list, cmp);
            return list.get(0);
        } catch (Exception e) {
            Log.w(TAG, "chooseSize failed", e);
            return new Size(targetWidth, targetHeight);
        }
    }

    private static List<Size> filter(Size[] sizes) {
        List<Size> out = new ArrayList<>();
        for (Size s : sizes) {
            if (s.getWidth() >= 160 && s.getHeight() >= 120
                    && (long) s.getWidth() * s.getHeight() <= 1920L * 1080L) {
                out.add(s);
            }
        }
        return out;
    }

    private static Size[] safeOutputSizes(StreamConfigurationMap map, Class<?> cls) {
        try {
            return map.getOutputSizes(cls);
        } catch (Exception e) {
            return null;
        }
    }

    /** 宽高比偏差权重高一些：比例不一致会带来裁切/拉伸。 */
    private static double score(Size s, long targetArea, double targetAspect) {
        double areaDiff = Math.abs((double) s.getWidth() * s.getHeight() - targetArea) / (double) targetArea;
        double aspectDiff = Math.abs((double) s.getWidth() / s.getHeight() - targetAspect) / targetAspect;
        return areaDiff + aspectDiff * 4.0;
    }

    private static String pickBackCamera(CameraManager cm) throws Exception {
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
            if (fallback == null) {
                fallback = id;
            }
        }
        return fallback;
    }

    public Size getChosenSize() {
        return chosenSize;
    }

    public String getCameraId() {
        return cameraId;
    }

    /**
     * @param encoderSurface MediaCodec.createInputSurface()
     * @param previewSurface 可为 null（无本地预览）
     * @param size           由 {@link #chooseSize} 得到，必须与编码器分辨率一致
     */
    public void open(Surface encoderSurface, Surface previewSurface, Size size, int fps) {
        this.encoderSurface = encoderSurface;
        this.previewSurface = previewSurface;
        this.fps = Math.max(1, fps);
        this.closing = false;

        thread = new HandlerThread("camera2-thread");
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            manager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
            cameraId = pickBackCamera(manager);
            if (cameraId == null) {
                fail("no camera");
                return;
            }
            chosenSize = size != null ? size : chooseSize(appContext, 320, 240);
            Log.i(TAG, "open camera " + cameraId + " size " + chosenSize + " fps " + this.fps);
            manager.openCamera(cameraId, stateCallback, handler);
        } catch (SecurityException se) {
            fail("no camera permission: " + se.getMessage());
        } catch (Exception e) {
            fail("open failed: " + e);
        }
    }

    /** 预览面变化（Activity 起来/销毁）时重建会话，不中断编码器。 */
    public void setPreviewSurface(Surface preview) {
        this.previewSurface = preview;
        Handler h = handler;
        if (h == null) {
            return;
        }
        h.post(this::recreateSession);
    }

    /** 改变帧率：重新下发 repeating request（通过 AE 目标帧率范围实现）。 */
    public void setFps(int fps) {
        if (fps == this.fps || fps <= 0) {
            return;
        }
        this.fps = fps;
        Handler h = handler;
        if (h != null) {
            h.post(this::startRepeating);
        }
    }

    private void recreateSession() {
        CameraDevice d = device;
        if (d == null || closing) {
            return;
        }
        try {
            if (session != null) {
                session.stopRepeating();
                session.close();
                session = null;
            }
        } catch (Exception ignored) {
        }
        createSession(d);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            if (closing) {
                camera.close();
                return;
            }
            device = camera;
            createSession(camera);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.w(TAG, "camera disconnected");
            camera.close();
            device = null;
            fail("camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "camera error " + error);
            camera.close();
            device = null;
            fail("camera error " + error);
        }
    };

    private void createSession(CameraDevice camera) {
        List<Surface> outputs = new ArrayList<>();
        if (encoderSurface != null) {
            outputs.add(encoderSurface);
        }
        if (previewSurface != null) {
            outputs.add(previewSurface);
        }
        if (outputs.isEmpty()) {
            fail("no output surface");
            return;
        }
        try {
            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (closing) {
                        s.close();
                        return;
                    }
                    session = s;
                    startRepeating();
                    Size sz = chosenSize;
                    if (sz != null && listener != null) {
                        listener.onReady(sz.getWidth(), sz.getHeight());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    fail("capture session configure failed");
                }
            }, handler);
        } catch (Exception e) {
            fail("createCaptureSession failed: " + e);
        }
    }

    private void startRepeating() {
        CameraCaptureSession s = session;
        CameraDevice d = device;
        if (s == null || d == null) {
            return;
        }
        try {
            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (encoderSurface != null) {
                b.addTarget(encoderSurface);
            }
            if (previewSurface != null) {
                b.addTarget(previewSurface);
            }
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            applyAutoFocus(b);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
            s.setRepeatingRequest(b.build(), null, handler);
        } catch (Exception e) {
            Log.w(TAG, "startRepeating failed", e);
        }
    }

    private void applyAutoFocus(CaptureRequest.Builder b) {
        try {
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);
            int[] modes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int chosen = CaptureRequest.CONTROL_AF_MODE_OFF;
            if (modes != null) {
                int prefer = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
                for (int m : modes) {
                    if (m == prefer) {
                        chosen = prefer;
                        break;
                    }
                }
                if (chosen == CaptureRequest.CONTROL_AF_MODE_OFF) {
                    for (int m : modes) {
                        if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                            chosen = m;
                            break;
                        }
                    }
                }
            }
            b.set(CaptureRequest.CONTROL_AF_MODE, chosen);
        } catch (Exception e) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
    }

    public void close() {
        closing = true;
        Handler h = handler;
        HandlerThread t = thread;
        if (h != null) {
            h.post(() -> {
                try {
                    if (session != null) {
                        session.stopRepeating();
                        session.close();
                    }
                } catch (Exception ignored) {
                }
                session = null;
                if (device != null) {
                    try {
                        device.close();
                    } catch (Exception ignored) {
                    }
                    device = null;
                }
            });
        }
        if (t != null) {
            try {
                t.quitSafely();
            } catch (Exception ignored) {
            }
        }
        thread = null;
        handler = null;
        encoderSurface = null;
        previewSurface = null;
    }

    private void fail(String msg) {
        Log.e(TAG, msg);
        if (listener != null) {
            listener.onFailed(msg);
        }
    }
}
