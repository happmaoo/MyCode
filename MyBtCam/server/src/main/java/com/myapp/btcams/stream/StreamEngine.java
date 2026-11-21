package com.myapp.btcams.stream;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.core.content.ContextCompat;

import com.myapp.btcam.common.BtCamProtocol;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端推流引擎（YUV 直通编码，不做 GL 缩放）。
 *
 * 为什么这样接：Camera2 的 YUV_420_888 输出本身就是按目标尺寸裁剪缩放的，
 * 宽高比例由相机自己保证（4:3 出 4:3、16:9 出 16:9），我们只把 NV21 按传感器方向
 * 旋转到正立、按 16 对齐补边，再喂给 MediaCodec 的 ByteBuffer 输入。
 * 全程没有「把一帧塞进另一个比例画布」的动作，所以画面不可能被拉宽或压扁。
 *
 * 之前用 GL(OES 纹理) 把 640x480 画到 320x240/240x320 画布上，
 * 在这台设备的相机上实测画面会被拉伸变形，因此改成现在这条链路。
 */
public class StreamEngine {

    private static final String TAG = "StreamEngine";
    private static final String MIME = "video/avc";
    private static final int FRAME_RATE = 15;

    public interface Listener {
        void onConfig(int width, int height, byte[] sps, byte[] pps);

        void onFailure(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final SppWriter writer;

    private HandlerThread thread;
    private Handler handler;

    private MediaCodec encoder;
    private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private int colorFormat;
    private byte[] spsPps;
    private boolean configSent;
    private long ptsUs;
    private long lastFeedAt;
    private long noInput;
    private long ticks;

    private ImageReader imageReader;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private String cameraId;
    private int sensorOrientation = 0;
    private String nrModesDesc = "-";
    private String edgeModesDesc = "-";
    private int fpsLower = 15;
    private int fpsUpper = 30;

    private volatile boolean running;
    private volatile boolean idrRequested;
    private volatile byte[] latestNv21;
    private volatile int latestPts;
    /** 正在编码的这一帧的采集时刻 */
    private volatile int feedPts;
    private volatile int latestGeneration;
    private int fedGeneration = -1;

    /** 相机出图尺寸（横屏原始） */
    private int imgW = 640;
    private int imgH = 480;
    /** 旋转后的有效尺寸（竖屏时宽高互换） */
    private int outW = 640;
    private int outH = 480;
    /** 编码器尺寸（16 对齐） */
    private int encW = 640;
    private int encH = 480;

    private int bitrate = 500000;
    private int rotation = 0;
    private int wantCamera;

    private final AtomicInteger seq = new AtomicInteger();
    private long capCount;
    private long capWindowAt;
    private long feedCount;
    private long feedWindowAt;
    private long skipCongested;
    private long lastCongestLog;
    private final CountDownLatch setupLatch = new CountDownLatch(1);
    private volatile boolean setupFailed;
    private String failureReason;

    public StreamEngine(Context context, SppWriter writer, Listener listener) {
        this.context = context.getApplicationContext();
        this.writer = writer;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** 客户端请求立刻出一个 IDR（丢了帧之后快速恢复画面）。 */
    public void requestIdr() {
        idrRequested = true;
    }

    public boolean start(int width, int height, int bitrate, int camera, int rotation) {
        if (running) {
            stop();
        }
        this.bitrate = bitrate;
        this.wantCamera = camera;
        this.rotation = rotation;
        this.configSent = false;
        this.setupFailed = false;
        this.latestNv21 = null;
        this.fedGeneration = -1;
        this.latestGeneration = 0;
        this.spsPps = null;

        thread = new HandlerThread("BtCamEngine", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        running = true;

        handler.post(new Runnable() {
            @Override
            public void run() {
                String error = resolveCamera(width, height);
                if (error == null) {
                    error = setupEncoder();
                }
                if (error != null) {
                    setupFailed = true;
                    failureReason = error;
                    setupLatch.countDown();
                    return;
                }
                setupLatch.countDown();
                openCamera();
            }
        });

        try {
            if (!setupLatch.await(8, TimeUnit.SECONDS)) {
                failureReason = "编码器初始化超时";
                setupFailed = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setupFailed = true;
            failureReason = "初始化被中断";
        }

        if (setupFailed) {
            Log.w(TAG, "start failed: " + failureReason);
            stop();
            return false;
        }
        Log.i(TAG, "stream start " + outW + "x" + outH + " @" + bitrate
                + " src " + imgW + "x" + imgH + " rot " + this.rotation
                + " sensor " + sensorOrientation);
        return true;
    }

    public void stop() {
        running = false;
        Handler h = handler;
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    closeCamera();
                    releaseEncoder();
                }
            });
        }
        HandlerThread t = thread;
        if (t != null) {
            t.quitSafely();
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        handler = null;
        thread = null;
    }

    /* ------------------------------------------------------------------ */
    /* 摄像头                                                              */
    /* ------------------------------------------------------------------ */

    private String resolveCamera(int wantW, int wantH) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                return "无法获取 CameraManager";
            }
            cameraId = pickCameraId(cm, wantCamera);
            if (cameraId == null) {
                return "设备没有可用摄像头";
            }
            CameraCharacteristics ch = cm.getCameraCharacteristics(cameraId);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 0 : so;

            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return "摄像头不支持配置查询";
            }
            Size yuvSizes[] = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (yuvSizes == null || yuvSizes.length == 0) {
                return "摄像头不支持 YUV_420_888 输出";
            }
            Size picked = pickSize(yuvSizes, wantW, wantH);
            imgW = picked.getWidth();
            imgH = picked.getHeight();

            // 旋转角度：-1 = 自动（按传感器方向）
            int rot = rotation < 0 ? sensorOrientation : rotation;
            rot = ((rot % 360) + 360) % 360;
            rotation = rot;
            if ((rot % 180) != 0) {
                outW = imgH;
                outH = imgW;
            } else {
                outW = imgW;
                outH = imgH;
            }
            encW = align16(outW);
            encH = align16(outH);

            Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null && ranges.length > 0) {
                for (Range<Integer> r : ranges) {
                    if (r.getLower() <= FRAME_RATE && r.getUpper() >= FRAME_RATE) {
                        fpsLower = r.getLower();
                        fpsUpper = r.getUpper();
                        break;
                    }
                }
            }
            nrModesDesc = describeModes(ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                    new String[]{"OFF", "FAST", "HIGH_QUALITY", "ZERO_SHUTTER_LAG", "MINIMAL"});
            edgeModesDesc = describeModes(ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                    new String[]{"OFF", "FAST", "HIGH_QUALITY", "ZERO_SHUTTER_LAG"});
            Log.i(TAG, "camera " + cameraId + " sensor=" + sensorOrientation
                    + " NR modes=" + nrModesDesc + " edge modes=" + edgeModesDesc);
            return null;
        } catch (CameraAccessException | RuntimeException e) {
            return "摄像头访问失败: " + e.getMessage();
        }
    }

    private static Size pickSize(Size[] sizes, int wantW, int wantH) {
        Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            // 优先同比例，其次尺寸接近，避免把 16:9 的流当 4:3 用
            double ratio = s.getWidth() / (double) s.getHeight();
            double want = wantW / (double) wantH;
            int ratioPenalty = Math.abs(ratio - want) > 0.02 ? 1000 : 0;
            int score = Math.abs(s.getWidth() - wantW) + Math.abs(s.getHeight() - wantH) + ratioPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best == null ? sizes[0] : best;
    }

    private static int align16(int v) {
        return (v + 15) & ~15;
    }

    private static String describeModes(int[] modes, String[] names) {
        if (modes == null || modes.length == 0) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            int m = modes[i];
            sb.append(m >= 0 && m < names.length ? names[m] : String.valueOf(m));
        }
        return sb.append(']').toString();
    }

    private static String pickCameraId(CameraManager cm, int want) throws CameraAccessException {
        String back = null;
        String front = null;
        String first = null;
        for (String id : cm.getCameraIdList()) {
            Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (first == null) {
                first = id;
            }
            if (facing == null) {
                continue;
            }
            if (facing == CameraCharacteristics.LENS_FACING_BACK && back == null) {
                back = id;
            }
            if (facing == CameraCharacteristics.LENS_FACING_FRONT && front == null) {
                front = id;
            }
        }
        if (want == BtCamProtocol.CAMERA_FRONT) {
            return front != null ? front : (back != null ? back : first);
        }
        if (want == BtCamProtocol.CAMERA_BACK) {
            return back != null ? back : (front != null ? front : first);
        }
        return back != null ? back : (front != null ? front : first);
    }

    private void openCamera() {
        if (!running) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            fail("未授予相机权限");
            return;
        }
        try {
            imageReader = ImageReader.newInstance(imgW, imgH, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            latestNv21 = yuvToNv21(image);
                            latestPts = (int) SystemClock.elapsedRealtime();
                            latestGeneration++;
                            capCount++;
                            long now = SystemClock.elapsedRealtime();
                            if (capWindowAt == 0) {
                                capWindowAt = now;
                            } else if (now - capWindowAt >= 2000) {
                                Log.i(TAG, "capture " + (capCount * 1000 / (now - capWindowAt))
                                        + "fps (camera -> NV21)");
                                capCount = 0;
                                capWindowAt = now;
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "read image failed: " + t);
                    } finally {
                        if (image != null) {
                            try {
                                image.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    // 直接在回调线程里喂帧：相机出一帧就尝试编一帧。
                    // 之前用 handler.post/postDelayed 的节拍驱动，在真机上会被
                    // 消息队列和 removeCallbacks 拖到 0~1 次/秒，直接导致 0fps。
                    try {
                        feedOnce();
                    } catch (Throwable t) {
                        Log.w(TAG, "feed in callback failed: " + t);
                    }
                }
            }, handler);

            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    fail("摄像头已断开（可能被其他应用占用）");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    fail("摄像头错误码 " + error);
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            fail("打开摄像头失败: " + e.getMessage());
        }
    }

    private void createSession() {
        if (!running || cameraDevice == null || imageReader == null) {
            return;
        }
        try {
            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            startRepeating();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fail("创建预览会话失败");
                        }
                    }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            fail("创建会话异常: " + e.getMessage());
        }
    }

    private void startRepeating() {
        if (!running || cameraDevice == null || captureSession == null) {
            return;
        }
        try {
            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fpsLower, fpsUpper));
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // 每帧显式请求关闭降噪与锐化
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
            Log.i(TAG, "request NR=OFF edge=OFF");
            captureSession.setRepeatingRequest(builder.build(), null, handler);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "OFF modes rejected, fallback to defaults: " + e);
            try {
                CaptureRequest.Builder builder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(imageReader.getSurface());
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                captureSession.setRepeatingRequest(builder.build(), null, handler);
            } catch (Exception e2) {
                fail("启动预览失败: " + e2.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 编码                                                                */
    /* ------------------------------------------------------------------ */

    private String setupEncoder() {
        try {
            encoder = MediaCodec.createEncoderByType(MIME);
            MediaCodecInfo.CodecCapabilities caps =
                    encoder.getCodecInfo().getCapabilitiesForType(MIME);
            colorFormat = 0;
            for (int f : caps.colorFormats) {
                if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                    colorFormat = f;
                    break;
                }
            }
            if (colorFormat == 0) {
                for (int f : caps.colorFormats) {
                    if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        colorFormat = f;
                        break;
                    }
                }
            }
            if (colorFormat == 0) {
                colorFormat = caps.colorFormats.length > 0
                        ? caps.colorFormats[0]
                        : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME, encW, encH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // 低帧率下用 CBR 会让编码器把整秒预算都塞进少数几帧（实测出现 30~40KB/帧，
            // 链路直接被单帧撑爆）。VBR 按内容给码率，帧小很多，帧率才起得来。
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            } catch (Throwable ignored) {
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            ptsUs = 0;
            lastFeedAt = 0;
            Log.i(TAG, "encoder ready " + encW + "x" + encH + " bitrate=" + bitrate
                    + " colorFormat=" + colorFormat);
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "encoder setup failed", t);
            return "编码器初始化失败: " + t.getMessage();
        }
    }

    private void releaseEncoder() {
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Throwable ignored) {
            }
            try {
                encoder.release();
            } catch (Throwable ignored) {
            }
            encoder = null;
        }
    }

    private void scheduleFeed() {
        Handler h = handler;
        if (h != null) {
            h.post(feedTask);
        }
    }

    private final Runnable feedTask = new Runnable() {
        @Override
        public void run() {
            feedOnce();
        }
    };

    private void feedOnce() {
        if (!running || encoder == null) {
            return;
        }
        // 先把编码器输出掏空。输入缓冲和输出缓冲是同一组池子，
        // 只在「成功喂帧后」才 drain 的话，一旦某次拿不到输入缓冲就再也拿不到，
        // 编码器会卡到 1~2fps。
        drainEncoder();
        long now = SystemClock.elapsedRealtime();
        ticks++;
        if (lastFeedAt != 0 && now - lastFeedAt < (1000 / FRAME_RATE) - 5) {
            return;
        }
        byte[] nv21 = latestNv21;
        if (nv21 == null || latestGeneration == fedGeneration) {
            return;
        }
        // 注意：这里绝对不能用「拥塞就少喂帧」来降压。
        // 喂帧率一降，CBR 会把每秒码率预算分摊到更少的帧上，单帧反而变大，
        // 链路更堵 -> 帧更少 -> 帧更大，形成正反馈，画面就卡成 1~2fps。
        // 正确做法是保持稳定节拍喂帧，让每帧大小可控；真发不出去由 SppWriter 决定丢帧。
        if (writer.congested()) {
            skipCongested++;
            long now2 = SystemClock.elapsedRealtime();
            if (now2 - lastCongestLog >= 2000) {
                lastCongestLog = now2;
                Log.i(TAG, "queue backed up " + skipCongested + " ticks");
                skipCongested = 0;
            }
        }
        feedPts = latestPts;
        fedGeneration = latestGeneration;
        lastFeedAt = now;
        feedCount++;
        if (feedWindowAt == 0) {
            feedWindowAt = now;
        } else if (now - feedWindowAt >= 2000) {
            Log.i(TAG, "feed " + (feedCount * 1000 / (now - feedWindowAt)) + "fps, queue="
                    + writer.queueSize() + " (ticks " + (ticks * 1000 / (now - feedWindowAt)) + "/s)");
            feedCount = 0;
            feedWindowAt = now;
            ticks = 0;
        }
        try {
            int index = encoder.dequeueInputBuffer(2000);
            if (index < 0) {
                noInput++;
                if (noInput % 20 == 1) {
                    Log.w(TAG, "no encoder input buffer (" + noInput + ")");
                }
                drainEncoder();
                return;
            }
            if (idrRequested) {
                idrRequested = false;
                try {
                    Bundle params = new Bundle();
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    encoder.setParameters(params);
                    Log.i(TAG, "forced IDR requested");
                } catch (Throwable t) {
                    Log.w(TAG, "request sync failed: " + t);
                }
            }
            ByteBuffer buf = encoder.getInputBuffer(index);
            if (buf == null) {
                return;
            }
            buf.clear();
            ptsUs = System.nanoTime() / 1000L;
            byte[] rotated = rotateNv21(nv21, imgW, imgH, rotation);
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                writeNv12(buf, rotated, outW, outH, encW, encH);
            } else if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                writeI420(buf, rotated, outW, outH, encW, encH);
            } else {
                writeNv12(buf, rotated, outW, outH, encW, encH);
            }
            encoder.queueInputBuffer(index, 0, buf.position(), ptsUs, 0);
            drainEncoder();
        } catch (Throwable t) {
            Log.w(TAG, "feed failed: " + t);
        }
    }

    private void drainEncoder() {
        if (encoder == null) {
            return;
        }
        while (running) {
            int index;
            try {
                index = encoder.dequeueOutputBuffer(bufferInfo, 0);
            } catch (Throwable t) {
                break;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    MediaFormat format = encoder.getOutputFormat();
                    int w = format.getInteger(MediaFormat.KEY_WIDTH);
                    int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                    byte[] sps = bytesOf(format.getByteBuffer("csd-0"));
                    byte[] pps = bytesOf(format.getByteBuffer("csd-1"));
                    if (sps != null && pps != null && !configSent) {
                        configSent = true;
                        Log.i(TAG, "encoder output " + w + "x" + h);
                        listener.onConfig(w, h, sps, pps);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "read output format failed: " + t);
                }
                continue;
            }
            if (index < 0) {
                continue;
            }
            try {
                ByteBuffer buffer = encoder.getOutputBuffer(index);
                boolean codecConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean idr = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (buffer != null && bufferInfo.size > 0 && !codecConfig) {
                    buffer.position(bufferInfo.offset);
                    buffer.limit(bufferInfo.offset + bufferInfo.size);
                    byte[] data = new byte[bufferInfo.size];
                    buffer.get(data);
                    writer.submit(new Frame(seq.incrementAndGet(), idr, data, feedPts));
                }
            } catch (Throwable t) {
                Log.w(TAG, "dequeue output failed: " + t);
            } finally {
                try {
                    encoder.releaseOutputBuffer(index, false);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] bytesOf(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        ByteBuffer copy = buffer.duplicate();
        copy.position(0);
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return data;
    }

    /* ------------------------------------------------------------------ */
    /* YUV 转换                                                            */
    /* ------------------------------------------------------------------ */

    private static byte planeAt(ByteBuffer b, int idx, byte def) {
        return (idx >= 0 && idx < b.capacity()) ? b.get(idx) : def;
    }

    /** YUV_420_888 -> NV21（Y 平面 + VU 交错），兼容任意 rowStride/pixelStride */
    private static byte[] yuvToNv21(Image img) {
        int w = img.getWidth();
        int h = img.getHeight();
        Image.Plane y = img.getPlanes()[0];
        Image.Plane u = img.getPlanes()[1];
        Image.Plane v = img.getPlanes()[2];
        ByteBuffer yb = y.getBuffer();
        ByteBuffer ub = u.getBuffer();
        ByteBuffer vb = v.getBuffer();
        byte[] out = new byte[w * h + (w / 2) * (h / 2) * 2];
        int yRow = y.getRowStride();
        int yPix = y.getPixelStride();
        int pos = 0;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                out[pos++] = planeAt(yb, row * yRow + col * yPix, (byte) 16);
            }
        }
        int cw = w / 2;
        int ch = h / 2;
        int uRow = u.getRowStride();
        int uPix = u.getPixelStride();
        int vRow = v.getRowStride();
        int vPix = v.getPixelStride();
        int uvStart = w * h;
        for (int row = 0; row < ch; row++) {
            for (int col = 0; col < cw; col++) {
                out[uvStart + (row * cw + col) * 2] = planeAt(vb, row * vRow + col * vPix, (byte) -128);
                out[uvStart + (row * cw + col) * 2 + 1] = planeAt(ub, row * uRow + col * uPix, (byte) -128);
            }
        }
        return out;
    }

    /** NV21 旋转：90/270 顺逆时针，宽高互换 */
    private static byte[] rotateNv21(byte[] src, int w, int h, int degrees) {
        int rot = ((degrees % 360) + 360) % 360;
        if (rot == 0) {
            return src;
        }
        if (rot == 180) {
            byte[] dst = new byte[src.length];
            int frameSize = w * h;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    dst[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x];
                }
            }
            int cw2 = w / 2;
            int ch2 = h / 2;
            int uv = frameSize;
            for (int y = 0; y < ch2; y++) {
                for (int x = 0; x < cw2; x++) {
                    int si = uv + (y * cw2 + x) * 2;
                    int di = uv + ((ch2 - 1 - y) * cw2 + (cw2 - 1 - x)) * 2;
                    dst[di] = src[si];
                    dst[di + 1] = src[si + 1];
                }
            }
            return dst;
        }
        byte[] dst = new byte[src.length];
        int frameSize = w * h;
        boolean cw = (rot == 90);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx, dy;
                if (cw) {
                    dx = h - 1 - y;
                    dy = x;
                } else {
                    dx = y;
                    dy = w - 1 - x;
                }
                dst[dy * h + dx] = src[y * w + x];
            }
        }
        int cw2 = w / 2;
        int ch2 = h / 2;
        for (int y = 0; y < ch2; y++) {
            for (int x = 0; x < cw2; x++) {
                int dx, dy;
                if (cw) {
                    dx = ch2 - 1 - y;
                    dy = x;
                } else {
                    dx = y;
                    dy = cw2 - 1 - x;
                }
                int si = frameSize + (y * cw2 + x) * 2;
                int di = frameSize + (dy * ch2 + dx) * 2;
                dst[di] = src[si];
                dst[di + 1] = src[si + 1];
            }
        }
        return dst;
    }

    /** NV21 -> NV12，写到 16 对齐的编码尺寸，多出来的行列补中性值 */
    private static void writeNv12(ByteBuffer out, byte[] nv21, int srcW, int srcH, int w, int h) {
        for (int row = 0; row < h; row++) {
            if (row < srcH) {
                int s = row * srcW;
                for (int col = 0; col < w; col++) {
                    out.put(col < srcW ? nv21[s + col] : (byte) 16);
                }
            } else {
                for (int col = 0; col < w; col++) {
                    out.put((byte) 16);
                }
            }
        }
        int srcUvBase = srcW * srcH;
        for (int row = 0; row < h / 2; row++) {
            if (row < srcH / 2) {
                int s = srcUvBase + row * srcW;
                for (int col = 0; col < w / 2; col++) {
                    if (col < srcW / 2) {
                        out.put(nv21[s + col * 2 + 1]);
                        out.put(nv21[s + col * 2]);
                    } else {
                        out.put((byte) 128);
                        out.put((byte) 128);
                    }
                }
            } else {
                for (int col = 0; col < w / 2; col++) {
                    out.put((byte) 128);
                    out.put((byte) 128);
                }
            }
        }
    }

    /** NV21 -> I420（Y + U 平面 + V 平面） */
    private static void writeI420(ByteBuffer out, byte[] nv21, int srcW, int srcH, int w, int h) {
        int uvPlane = w * h / 4;
        byte[] u = new byte[uvPlane];
        byte[] v = new byte[uvPlane];
        for (int i = 0; i < uvPlane; i++) {
            u[i] = (byte) 128;
            v[i] = (byte) 128;
        }
        for (int row = 0; row < h; row++) {
            if (row < srcH) {
                int s = row * srcW;
                for (int col = 0; col < w; col++) {
                    out.put(col < srcW ? nv21[s + col] : (byte) 16);
                }
            } else {
                for (int col = 0; col < w; col++) {
                    out.put((byte) 16);
                }
            }
        }
        int srcUvBase = srcW * srcH;
        for (int row = 0; row < h / 2; row++) {
            if (row < srcH / 2) {
                int s = srcUvBase + row * srcW;
                for (int col = 0; col < w / 2; col++) {
                    if (col < srcW / 2) {
                        u[row * (w / 2) + col] = nv21[s + col * 2 + 1];
                        v[row * (w / 2) + col] = nv21[s + col * 2];
                    }
                }
            }
        }
        out.put(u);
        out.put(v);
    }

    /* ------------------------------------------------------------------ */
    /* 释放                                                                */
    /* ------------------------------------------------------------------ */

    private void closeCamera() {
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Throwable ignored) {
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Throwable ignored) {
            }
            cameraDevice = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Throwable ignored) {
            }
            imageReader = null;
        }
        latestNv21 = null;
    }

    private void fail(String reason) {
        Log.w(TAG, "failed: " + reason);
        running = false;
        if (listener != null) {
            listener.onFailure(reason);
        }
    }
}
