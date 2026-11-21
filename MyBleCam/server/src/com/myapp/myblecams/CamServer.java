package com.myapp.myblecams;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * MyBleCamS —— BLE 图传服务端（H.264 硬编码版）
 * 外设端：摄像头采集 NV21 -> MediaCodec H.264 编码 -> Annex-B 帧 -> AB CD 帧头 -> 分片 notify 推送
 */
public class CamServer {

    static final UUID SVC = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    static final UUID CH_VIDEO = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    static final UUID CH_CTRL = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    static final UUID CH_STATUS = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final byte[] FRAME_HEAD = new byte[]{(byte) 0xAB, (byte) 0xCD};
    static final byte CMD_CAM = 0x10, CMD_QUALITY = 0x11, CMD_MODE = 0x12;
    // 传输模式：0 画质优先（不丢帧），1 延迟优先（丢帧保实时，默认）
    static final int MODE_QUALITY = 0, MODE_LATENCY = 1;
    static final String MIME = "video/avc";

    interface Listener {
        void onLog(String s);
        void onFrameSent();
        void onStateChanged(int cam, int quality);
        void onClientState(boolean connected);
    }

    final Context ctx;
    final TextureView preview;
    final Listener listener;
    final Handler main = new Handler(Looper.getMainLooper());

    BluetoothAdapter adapter;
    BluetoothGattServer gattServer;
    BluetoothLeAdvertiser advertiser;
    BluetoothDevice client;
    boolean running = false;
    boolean subscribed = false;

    // 连接成功后执行的 shell 命令（设置页配置），输出回显到日志
    volatile String onConnectCmd = null;
    // 连接断开后执行的 shell 命令（设置页配置）
    volatile String onDisconnectCmd = null;
    // 广播名与默认摄像头/画质（设置页配置）
    volatile String serverName = "MyBleCamS";
    volatile int prefCam = -1, prefQuality = -1;

    // 摄像头（Camera2 API：支持关闭降噪/锐化等 ISP 处理）
    CameraManager camManager;
    CameraDevice camDevice;
    CameraCaptureSession camSession;
    ImageReader imgReader;
    String camIdStr;
    HandlerThread camThread;
    Handler camHandler;
    int camIdx = 0;         // 0 后 1 前
    int qualityIdx = 0;     // 0 低 1 中 2 高（默认 Low）
    volatile int latencyMode = MODE_LATENCY;   // 客户端下发的传输模式
    byte[] latestNv21;
    int imgW = 320, imgH = 240;
    int encW = 240, encH = 320;
    int rotDegrees = 90;   // 编码前把 NV21 旋转到竖屏：后置90 / 前置270
    int outW = 240, outH = 320;   // 旋转后的实际图像尺寸（竖屏）

    // H.264 编码器
    MediaCodec encoder;
    int colorFormat = 0;
    byte[] spsPps;
    long ptsUs = 0;
    final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

    // 发送队列：分片 byte[]（已含帧头或数据）
    final LinkedBlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<byte[]>(256);
    static final int MAX_BACKLOG = 6;   // 发送队列积压超过此片数就丢帧，保最新画面（低延迟优先）
    Thread sendThread;
    volatile boolean sendRunning = false;
    // 允许在途未确认的 notify 片数：burst 并发发送，突破“发一片等一片”的吞吐瓶颈。
    // 每个连接间隔协议栈可发出多个 ACL 包，在途多片能显著提升吞吐（中高画质不积压）
    static final int MAX_INFLIGHT = 4;
    int inflight = 0;
    final Object inflightLock = new Object();

    // 帧率/节拍
    long lastFrameMs = 0;
    int framesSent = 0;

    public CamServer(Context ctx, TextureView preview, Listener listener) {
        this.ctx = ctx;
        this.preview = preview;
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    void log(final String s) {
        main.post(new Runnable() { public void run() { listener.onLog(s); } });
    }

    // ===================== 启动 / 停止 =====================
    public boolean start() {
        if (running) return true;
        if (adapter == null || !adapter.isEnabled()) { log("蓝牙未开启"); return false; }
        // 应用设置页的默认摄像头/画质
        if (prefCam >= 0 && prefCam != camIdx) camIdx = prefCam;
        if (prefQuality >= 0 && prefQuality != qualityIdx) qualityIdx = prefQuality;
        running = true;
        setupGattServer();
        startAdvertising();
        startCamera();
        initEncoder();
        sendRunning = true;
        sendThread = new Thread(sendLoop, "cam-send");
        sendThread.start();
        new Thread(notifyLoop, "cam-notify").start();
        log("已启动：广播 " + serverName + "，H.264 硬编码，等待客户端连接");
        return true;
    }

    public void stop() {
        running = false;
        sendRunning = false;
        if (sendThread != null) { sendThread.interrupt(); sendThread = null; }
        stopEncoder();
        stopCamera();
        stopAdvertising();
        if (gattServer != null) {
            try { gattServer.close(); } catch (Throwable t) { }
            gattServer = null;
        }
        client = null;
        subscribed = false;
        sendQueue.clear();
        log("已停止");
    }

    // ===================== GATT Server =====================
    void setupGattServer() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        gattServer = bm.openGattServer(ctx, gattCb);

        BluetoothGattService svc = new BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic video = new BluetoothGattCharacteristic(
                CH_VIDEO,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0);
        video.addDescriptor(new BluetoothGattDescriptor(
                CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));

        BluetoothGattCharacteristic ctrl = new BluetoothGattCharacteristic(
                CH_CTRL,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        BluetoothGattCharacteristic status = new BluetoothGattCharacteristic(
                CH_STATUS,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        status.addDescriptor(new BluetoothGattDescriptor(
                CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        status.setValue(("MyBleCamS 2.0 H264").getBytes());

        svc.addCharacteristic(video);
        svc.addCharacteristic(ctrl);
        svc.addCharacteristic(status);
        gattServer.addService(svc);
    }

    final BluetoothGattServerCallback gattCb = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                client = device;
                subscribed = false;
                log("客户端连接 " + device.getAddress());
                main.post(new Runnable() { public void run() { listener.onClientState(true); } });
                execOnConnect();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (client != null && device.getAddress().equals(client.getAddress())) {
                    client = null;
                    subscribed = false;
                    log("客户端断开 " + device.getAddress());
                    main.post(new Runnable() { public void run() { listener.onClientState(false); } });
                    execOnDisconnect();
                }
            }
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (descriptor.getUuid().equals(CCCD) && value != null && value.length > 0) {
                boolean enable = value[0] != 0;
                if (descriptor.getCharacteristic().getUuid().equals(CH_VIDEO)) {
                    subscribed = enable;
                    log(enable ? "VIDEO 订阅生效，开始推流" : "VIDEO 订阅关闭");
                } else if (descriptor.getCharacteristic().getUuid().equals(CH_STATUS)) {
                    log("状态订阅生效");
                    // 客户端订阅状态后立即推送一次当前状态，供其同步下拉框
                    main.post(new Runnable() { public void run() { pushStateToClient(); } });
                }
            }
            if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic ch, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (value != null && value.length >= 4 && value[0] == (byte) 0xAA && value[1] == (byte) 0xFF) {
                handleControl(value[2], value[3]);
            }
            if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            synchronized (inflightLock) {
                if (inflight > 0) inflight--;
                inflightLock.notifyAll();
            }
        }
    };

    void handleControl(byte cmd, byte param) {
        switch (cmd) {
            case CMD_CAM:
                switchCamera(param == 1 ? 1 : 0);
                break;
            case CMD_QUALITY:
                setQuality(param & 0xFF);
                break;
            case CMD_MODE:
                setMode(param & 0xFF);
                break;
        }
    }

    // 客户端下发传输模式：1 延迟优先（丢帧保实时）/ 0 画质优先（尽量不丢帧）
    void setMode(int m) {
        if (m != MODE_QUALITY && m != MODE_LATENCY) return;
        if (m == latencyMode) return;
        latencyMode = m;
        log("传输模式 -> " + (m == MODE_QUALITY ? "画质优先（不丢帧）" : "延迟优先（丢帧保实时）"));
    }

    // ===================== 广播 =====================
    void startAdvertising() {
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) { log("不支持 BLE 广播"); return; }
        try { adapter.setName(serverName); } catch (Throwable t) { }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true).build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addManufacturerData(0xE5E5, new byte[]{0x01, 'C', 'A', 'M'})
                .build();
        advertiser.startAdvertising(settings, data, advCb);
    }

    void stopAdvertising() {
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advCb); } catch (Throwable t) { }
            advertiser = null;
        }
    }

    final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings s) { log("广播成功"); }
        @Override public void onStartFailure(int code) { log("广播失败 code=" + code); }
    };

    // ===================== 摄像头（Camera2）=====================
    // Camera2 采集：预览输出到 SurfaceView + YUV_420_888 ImageReader 输出到编码链路，
    // 采集请求中显式关闭降噪（NOISE_REDUCTION_MODE_OFF）与锐化（EDGE_MODE_OFF）
    void startCamera() {
        preview.setSurfaceTextureListener(texCb);
        if (preview.isAvailable()) openCamera();
    }

    TextureView.SurfaceTextureListener texCb = new TextureView.SurfaceTextureListener() {
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { openCamera(); }
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
        public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    };

    synchronized void openCamera() {
        if (camDevice != null || !running) return;
        try {
            if (camManager == null)
                camManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (camThread == null) {
                camThread = new HandlerThread("cam2");
                camThread.start();
                camHandler = new Handler(camThread.getLooper());
            }
            camIdStr = pickCameraId(camIdx);
            CameraCharacteristics cc = camManager.getCameraCharacteristics(camIdStr);
            pickSize(cc);
            if (imgReader != null) { try { imgReader.close(); } catch (Throwable t) { } }
            imgReader = ImageReader.newInstance(imgW, imgH, ImageFormat.YUV_420_888, 2);
            imgReader.setOnImageAvailableListener(readerCb, camHandler);
            camManager.openCamera(camIdStr, deviceCb, camHandler);
            log("Camera2 打开中: " + (camIdx == 0 ? "后" : "前") + " " + imgW + "x" + imgH);
        } catch (Throwable t) {
            log("摄像头打开失败: " + t);
        }
    }

    String pickCameraId(int idx) {
        try {
            for (String id : camManager.getCameraIdList()) {
                Integer f = camManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f == null) continue;
                if ((idx == 0 && f == CameraCharacteristics.LENS_FACING_BACK)
                        || (idx == 1 && f == CameraCharacteristics.LENS_FACING_FRONT)) return id;
            }
            return camManager.getCameraIdList()[0];
        } catch (Throwable t) {
            return "0";
        }
    }

    // 按画质档位挑 YUV_420_888 输出尺寸：目标 320x240 / 256x192 / 160x120，不支持时取最近
    void pickSize(CameraCharacteristics cc) {
        int[] dims = qualityIdx == 2 ? new int[]{320, 240}
                : (qualityIdx == 1 ? new int[]{256, 192} : new int[]{160, 120});
        imgW = dims[0]; imgH = dims[1];
        try {
            android.hardware.camera2.params.StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            Size picked = null;
            int best = Integer.MAX_VALUE;
            for (Size s : sizes) {
                int d = Math.abs(s.getWidth() - dims[0]) + Math.abs(s.getHeight() - dims[1]);
                if (d < best) { best = d; picked = s; }
            }
            if (picked != null) { imgW = picked.getWidth(); imgH = picked.getHeight(); }
        } catch (Throwable t) { }
    }

    final CameraDevice.StateCallback deviceCb = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) {
            camDevice = d;
            startSession();
        }
        @Override public void onDisconnected(CameraDevice d) {
            log("摄像头断开连接");
            closeCameraDevice();
        }
        @Override public void onError(CameraDevice d, int error) {
            log("摄像头错误 code=" + error);
            closeCameraDevice();
        }
    };

    synchronized void startSession() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || camDevice == null) { log("预览 Texture 未就绪"); return; }
            st.setDefaultBufferSize(imgW, imgH);
            Surface ps = new Surface(st);
            final CaptureRequest.Builder b = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(ps);
            b.addTarget(imgReader.getSurface());

            CameraCharacteristics cc = camManager.getCameraCharacteristics(camIdStr);
            String ispLog = "";
            // 关闭降噪：平台支持 OFF 才设置（legacy 级别设备可能只支持 FAST）
            boolean nrOff = false, edgeOff = false;
            try {
                int[] nrm = cc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
                if (nrm != null) for (int m : nrm) if (m == CaptureRequest.NOISE_REDUCTION_MODE_OFF) { nrOff = true; break; }
            } catch (Throwable t) { }
            try {
                int[] em = cc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
                if (em != null) for (int m : em) if (m == CaptureRequest.EDGE_MODE_OFF) { edgeOff = true; break; }
            } catch (Throwable t) { }
            if (nrOff) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
                ispLog += "降噪OFF ";
            } else {
                ispLog += "降噪不支持OFF ";
            }
            if (edgeOff) {
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
                ispLog += "锐化OFF";
            } else {
                ispLog += "锐化不支持OFF";
            }
            // 采集帧率按画质档位限制，减少不必要帧
            try {
                Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                for (Range<Integer> r : ranges) {
                    if (r.getLower() <= fps() && r.getUpper() >= fps()) {
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r);
                        ispLog += " fps=" + r;
                        break;
                    }
                }
            } catch (Throwable t) { }

            final String ispFinal = ispLog;

            camDevice.createCaptureSession(java.util.Arrays.asList(ps, imgReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            camSession = s;
                            try {
                                s.setRepeatingRequest(b.build(), null, camHandler);
                                applyPreviewTransform();
                                log("摄像头打开: " + (camIdx == 0 ? "后" : "前") + " " + imgW + "x" + imgH
                                        + " [" + ispFinal + "]");
                            } catch (Throwable t) {
                                log("预览请求失败: " + t);
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            log("相机会话配置失败");
                        }
                    }, camHandler);
        } catch (Throwable t) {
            log("相机会话启动失败: " + t);
        }
    }

    // ImageReader 回调：只取最新一帧（acquireLatestImage 跳过积压），转 NV21 喂编码链路
    final ImageReader.OnImageAvailableListener readerCb = new ImageReader.OnImageAvailableListener() {
        public void onImageAvailable(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img != null) latestNv21 = yuvImageToNv21(img);
            } catch (Throwable t) {
            } finally {
                if (img != null) { try { img.close(); } catch (Throwable t) { } }
            }
        }
    };

    synchronized void stopCamera() {
        closeCameraDevice();
        if (imgReader != null) {
            try { imgReader.close(); } catch (Throwable t) { }
            imgReader = null;
        }
        latestNv21 = null;
    }

    synchronized void closeCameraDevice() {
        if (camSession != null) { try { camSession.close(); } catch (Throwable t) { } camSession = null; }
        if (camDevice != null) { try { camDevice.close(); } catch (Throwable t) { } camDevice = null; }
    }

    // 预览旋转角度覆盖：-1 = 自动(按传感器方向)，否则 0/90/180/270（设置页可手动指定）
    volatile int previewRotation = -1;

    // 预览变换：TextureView 的矩阵作用于 buffer 像素空间 (0..imgW, 0..imgH)，
    // 输出映射到 view 局部坐标 (0..vw, 0..vh)。步骤：buffer 中心移到原点 -> 前置镜像
    // -> 旋转 -> 等比缩放 -> 移到 view 中心。等比缩放保证不变形，FIT 保证不裁切。
    void applyPreviewTransform() {
        try {
            int vw = preview.getWidth(), vh = preview.getHeight();
            if (vw <= 0 || vh <= 0 || imgW <= 0 || imgH <= 0) return;
            // 与客户端画面保持一致：客户端用 encodeRotation() 经 rotateNV21 转正，
            // 预览必须用同一角度（displayRotation 前置差 180°，镜像也不做）。
            int rot = previewRotation >= 0 ? previewRotation : encodeRotation();
            boolean mirror = false;

            // 旋转后 buffer 的有效宽高（90/270 时宽高交换）
            boolean swap = (rot % 180) != 0;
            float effW = swap ? imgH : imgW;
            float effH = swap ? imgW : imgH;
            float cx = vw / 2f, cy = vh / 2f;
            float scale = Math.min((float) vw / effW, (float) vh / effH);  // FIT 等比不裁切
            // TextureView 把横屏 buffer 各向异性拉伸铺满 view；setTransform 矩阵作用于 view 空间。
            // 目标：M = T(C)·S(s)·R·Mirror·T(-Cb)·D ，其中 D=diag(imgW/vw,imgH/vh) 抵消各向异性拉伸。
            // Android postXXX 为右乘（后写的先作用于点），故按点施加的反序书写。
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postTranslate(cx, cy);                            // 最后：原点 -> view 中心
            m.postScale(scale, scale);                          // 等比 FIT 缩放
            m.postRotate(rot);                                  // 旋转转正
            if (mirror) m.postScale(-1f, 1f);                   // 前置镜像
            m.postTranslate(-imgW / 2f, -imgH / 2f);            // buffer 中心 -> 原点
            m.postScale((float) imgW / vw, (float) imgH / vh);  // 先：抵消 TextureView 各向异性拉伸
            preview.setTransform(m);
            log("预览变换: view " + vw + "x" + vh + " buffer " + imgW + "x" + imgH
                    + " rot=" + rot + " mirror=" + mirror
                    + " scale=" + String.format(Locale.US, "%.3f", scale));
        } catch (Throwable t) { log("预览变换失败: " + t); }
    }

    // 设置页下发的预览旋转角度：-1 自动 / 0 / 90 / 180 / 270；运行中立即重算变换
    public void setPreviewRotation(int deg) {
        previewRotation = deg;
        if (running) main.post(new Runnable() { public void run() { applyPreviewTransform(); } });
    }

    static byte planeGet(ByteBuffer b, int idx, byte def) {
        return (idx >= 0 && idx < b.capacity()) ? b.get(idx) : def;
    }

    // YUV_420_888(Image) -> NV21（Y 平面 + VU 交错），兼容 rowStride/pixelStride 任意布局
    static byte[] yuvImageToNv21(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane y = img.getPlanes()[0];
        Image.Plane u = img.getPlanes()[1];
        Image.Plane v = img.getPlanes()[2];
        ByteBuffer yb = y.getBuffer(), ub = u.getBuffer(), vb = v.getBuffer();
        byte[] out = new byte[w * h + (w / 2) * (h / 2) * 2];
        int yRow = y.getRowStride(), yPix = y.getPixelStride();
        int pos = 0;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                out[pos++] = planeGet(yb, row * yRow + col * yPix, (byte) 16);
            }
        }
        int cw = w / 2, ch = h / 2;
        int uRow = u.getRowStride(), uPix = u.getPixelStride();
        int vRow = v.getRowStride(), vPix = v.getPixelStride();
        int uvStart = w * h;
        for (int row = 0; row < ch; row++) {
            for (int col = 0; col < cw; col++) {
                out[uvStart + (row * cw + col) * 2] = planeGet(vb, row * vRow + col * vPix, (byte) -128);     // V
                out[uvStart + (row * cw + col) * 2 + 1] = planeGet(ub, row * uRow + col * uPix, (byte) -128); // U
            }
        }
        return out;
    }

    public void setOnConnectCmd(String cmd) {
        onConnectCmd = (cmd == null || cmd.trim().isEmpty()) ? null : cmd.trim();
    }

    public void setOnDisconnectCmd(String cmd) {
        onDisconnectCmd = (cmd == null || cmd.trim().isEmpty()) ? null : cmd.trim();
    }

    public void setServerName(String n) {
        serverName = (n == null || n.trim().isEmpty()) ? "MyBleCamS" : n.trim();
    }

    // 应用默认摄像头/画质：未运行时立即生效，运行中下次 Start 生效
    public synchronized void applyDefaults(int cam, int quality) {
        prefCam = cam;
        prefQuality = quality;
        if (!running) {
            if (cam != camIdx || quality != qualityIdx) {
                camIdx = cam;
                qualityIdx = quality;
                log("默认设置: " + (camIdx == 0 ? "后置" : "前置") + " / " + qName(qualityIdx));
                notifyState();
            }
        }
    }

    // 客户端连接成功后后台执行配置的 sh 命令，输出回显日志
    void execOnConnect() {
        execShell(onConnectCmd, "连接");
    }

    // 客户端断开后后台执行配置的 sh 命令
    void execOnDisconnect() {
        execShell(onDisconnectCmd, "断开");
    }

    // 后台执行 sh 命令，输出（含 stderr）回显日志
    void execShell(final String cmd, final String tag) {
        if (cmd == null) return;
        new Thread(new Runnable() {
            public void run() {
                log("执行" + tag + "命令: " + cmd);
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    java.io.InputStream in = p.getInputStream();
                    java.io.InputStream err = p.getErrorStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] b = new byte[512];
                    int n;
                    long deadline = System.currentTimeMillis() + 8000;
                    while (System.currentTimeMillis() < deadline) {
                        boolean any = false;
                        while ((n = in.read(b)) > 0) { sb.append(new String(b, 0, n)); any = true; }
                        while ((n = err.read(b)) > 0) { sb.append(new String(b, 0, n)); any = true; }
                        boolean alive;
                        try { p.exitValue(); alive = false; } catch (IllegalThreadStateException e) { alive = true; }
                        if (!any && !alive) break;
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                    String out = sb.toString().trim();
                    if (out.isEmpty()) out = "(无输出)";
                    if (out.length() > 300) out = out.substring(0, 300) + "…";
                    log(tag + "命令输出: " + out);
                    try { p.destroy(); } catch (Throwable t) { }
                } catch (Throwable t) {
                    log("命令执行失败: " + t);
                }
            }
        }, "shell-cmd").start();
    }

    public synchronized void switchCamera(int idx) {
        if (idx == camIdx) return;
        camIdx = idx;
        stopCamera();
        openCamera();
        reinitEncoder();
        log("切换摄像头 -> " + (camIdx == 0 ? "后" : "前"));
        notifyState();
    }

    public synchronized void setQuality(int q) {
        if (q == qualityIdx) return;
        qualityIdx = q;
        stopCamera();
        openCamera();
        reinitEncoder();
        log("画质 -> " + qName(qualityIdx) + " " + imgW + "x" + imgH);
        notifyState();
    }

    void notifyState() {
        final int c = camIdx, q = qualityIdx;
        main.post(new Runnable() { public void run() { listener.onStateChanged(c, q); } });
        pushStateToClient();
    }

    // 通过 ffe3 状态特征 notify 状态帧 {0x5B, cam, quality} 给客户端，客户端同步下拉框
    void pushStateToClient() {
        try {
            if (client == null || gattServer == null) return;
            BluetoothGattService s = gattServer.getService(SVC);
            if (s == null) return;
            BluetoothGattCharacteristic st = s.getCharacteristic(CH_STATUS);
            if (st == null) return;
            st.setValue(new byte[]{(byte) 0x5B, (byte) camIdx, (byte) qualityIdx});
            gattServer.notifyCharacteristicChanged(client, st, false);
        } catch (Throwable t) { }
    }

    String qName(int i) { return i == 2 ? "High" : (i == 1 ? "Mid" : "Low"); }

    // ===================== H.264 编码器 =====================
    boolean isFront() {
        try {
            if (camManager == null)
                camManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            Integer f = camManager.getCameraCharacteristics(pickCameraId(camIdx))
                    .get(CameraCharacteristics.LENS_FACING);
            return f != null && f == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (Throwable t) { return camIdx != 0; }
    }

    int sensorOrientation() {
        try {
            if (camManager == null)
                camManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            Integer o = camManager.getCameraCharacteristics(
                    camIdStr != null ? camIdStr : pickCameraId(camIdx))
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (o != null) { log("相机传感器方向=" + o); return o; }
        } catch (Throwable t) { }
        log("读取相机方向失败，用默认值");
        return camIdx == 0 ? 90 : 270;
    }

    // 本地预览旋转角：官方公式，前置需镜像补偿（否则上下颠倒）
    int displayRotation() {
        int o = sensorOrientation();
        int degrees = 0;   // Activity 锁定竖屏，屏幕旋转 0
        if (isFront()) return (360 - (o + degrees) % 360) % 360;
        return (o - degrees + 360) % 360;
    }

    // 编码旋转角：把原始 NV21 顺时针转到正立（按传感器角度本身）
    int encodeRotation() {
        int o = sensorOrientation();
        int degrees = 0;
        return (o + degrees) % 360;
    }

    void initEncoder() {
        stopEncoder();
        rotDegrees = encodeRotation();
        outW = imgH;   // 旋转90/270后宽高互换 -> 竖屏
        outH = imgW;
        encW = align16(outW);
        encH = align16(outH);
        try {
            encoder = MediaCodec.createEncoderByType(MIME);
            MediaCodecInfo.CodecCapabilities caps = encoder.getCodecInfo().getCapabilitiesForType(MIME);
            int[] formats = caps.colorFormats;
            colorFormat = 0;
            for (int f : formats) if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) { colorFormat = f; break; }
            if (colorFormat == 0) for (int f : formats) if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) { colorFormat = f; break; }
            if (colorFormat == 0) colorFormat = formats.length > 0 ? formats[0] : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, encW, encH);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate());
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps());
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);   // 1 秒关键帧
            try { fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR); } catch (Throwable t) { }
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            ptsUs = 0;
            spsPps = null;
            log("H.264 编码器就绪 " + encW + "x" + encH + " fmt=" + fmtName(colorFormat) + " " + bitrate() / 1000 + "kbps/" + fps() + "fps");
        } catch (Throwable t) {
            log("H.264 编码器失败: " + t);
            stopEncoder();
        }
    }

    void reinitEncoder() {
        if (running) initEncoder();
    }

    void stopEncoder() {
        if (encoder != null) {
            try { encoder.stop(); } catch (Throwable t) { }
            try { encoder.release(); } catch (Throwable t) { }
            encoder = null;
        }
        spsPps = null;
    }

    static int align16(int v) { return (v + 15) & ~15; }
    String fmtName(int f) {
        if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return "NV12";
        if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return "I420";
        return "fmt" + f;
    }
    int fps() { return qualityIdx == 2 ? 8 : (qualityIdx == 1 ? 10 : 12); }
    int bitrate() { return qualityIdx == 2 ? 150000 : (qualityIdx == 1 ? 100000 : 60000); }
    int frameIntervalMs() { return 1000 / fps(); }
    static final int PIECE = 490;   // 每片载荷（MTU 517 - 开销，保守）

    // 目标帧率节拍：发送线程按 fps 控制喂帧
    final Runnable sendLoop = new Runnable() {
        public void run() {
            while (sendRunning) {
                if (encoder == null || !subscribed || client == null || latestNv21 == null) {
                    sleep(15);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now - lastFrameMs < frameIntervalMs()) { sleep(5); continue; }
                lastFrameMs = now;

                byte[] frame = latestNv21;
                if (!feedEncoder(frame)) { sleep(10); continue; }
                drainEncoder();
            }
        }
    };

    // NV21 -> 编码器输入色格，写入 input buffer（先旋转到竖屏）
    boolean feedEncoder(byte[] nv21) {
        try {
            int idx = encoder.dequeueInputBuffer(8000);
            if (idx < 0) return false;
            ByteBuffer buf = encoder.getInputBuffer(idx);
            buf.clear();
            ptsUs = System.nanoTime() / 1000L;

            // 传感器原始方向是横屏，旋转到竖屏后再编码
            byte[] rot = rotateNV21(nv21, imgW, imgH, rotDegrees);

            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                writeNV12(buf, rot, outW, outH, encW, encH);
            } else if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                writeI420(buf, rot, outW, outH, encW, encH);
            } else {
                writeNV12(buf, rot, outW, outH, encW, encH);
            }
            encoder.queueInputBuffer(idx, 0, buf.position(), ptsUs, 0);
            return true;
        } catch (Throwable t) {
            log("喂编码失败: " + t);
            return false;
        }
    }

    // 把 NV21 旋转到竖屏（degrees=90 顺时针 / 270 逆时针）
    static byte[] rotateNV21(byte[] src, int w, int h, int degrees) {
        int rot = ((degrees % 360) + 360) % 360;
        if (rot == 0) return src;
        byte[] dst = new byte[src.length];
        int frameSize = w * h;
        boolean cw = (rot == 90);   // 90 顺时针；270 逆时针

        // Y 平面（w x h）
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx, dy;
                if (cw) { dx = h - 1 - y; dy = x; }
                else    { dx = y; dy = w - 1 - x; }
                dst[dy * h + dx] = src[y * w + x];   // 目标宽=h
            }
        }
        // VU 交错平面，色度分辨率 w/2 x h/2
        int cw2 = w / 2, ch2 = h / 2;
        for (int y = 0; y < ch2; y++) {
            for (int x = 0; x < cw2; x++) {
                int dx, dy;
                if (cw) { dx = ch2 - 1 - y; dy = x; }
                else    { dx = y; dy = cw2 - 1 - x; }
                int si = frameSize + (y * cw2 + x) * 2;
                int di = frameSize + (dy * ch2 + dx) * 2;
                dst[di] = src[si];
                dst[di + 1] = src[si + 1];
            }
        }
        return dst;
    }

    // NV21(Y + VU交错) -> NV12(Y + UV交错)，尺寸按 encW/encH，多出行填中性值
    static void writeNV12(ByteBuffer out, byte[] nv21, int srcW, int srcH, int w, int h) {
        int yPlane = w * h;
        int uvPlane = yPlane / 2;
        // Y
        for (int row = 0; row < h; row++) {
            if (row < srcH) {
                int s = row * srcW;
                for (int col = 0; col < w; col++) {
                    out.put(col < srcW ? nv21[s + col] : (byte) 16);
                }
            } else {
                for (int col = 0; col < w; col++) out.put((byte) 16);
            }
        }
        // UV (NV12 = U,V 交错)；源 NV21 是 V,U 交错
        int srcUvBase = srcW * srcH;
        for (int row = 0; row < h / 2; row++) {
            if (row < srcH / 2) {
                int s = srcUvBase + row * srcW;
                for (int col = 0; col < w / 2; col++) {
                    if (col < srcW / 2) {
                        out.put(nv21[s + col * 2 + 1]); // U
                        out.put(nv21[s + col * 2]);     // V
                    } else {
                        out.put((byte) 128); out.put((byte) 128);
                    }
                }
            } else {
                for (int col = 0; col < w / 2; col++) { out.put((byte) 128); out.put((byte) 128); }
            }
        }
    }

    // NV21 -> I420 (Y 平面 + U 平面 + V 平面)
    static void writeI420(ByteBuffer out, byte[] nv21, int srcW, int srcH, int w, int h) {
        int yPlane = w * h, uvPlane = w * h / 4;
        byte[] u = new byte[uvPlane], v = new byte[uvPlane];
        for (int i = 0; i < uvPlane; i++) { u[i] = (byte) 128; v[i] = (byte) 128; }
        // Y
        for (int row = 0; row < h; row++) {
            if (row < srcH) {
                int s = row * srcW;
                for (int col = 0; col < w; col++) out.put(col < srcW ? nv21[s + col] : (byte) 16);
            } else {
                for (int col = 0; col < w; col++) out.put((byte) 16);
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
        out.put(u); out.put(v);
    }

    void drainEncoder() {
        while (encoder != null) {
            int idx;
            try { idx = encoder.dequeueOutputBuffer(encInfo, 0); }
            catch (Throwable t) { break; }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 从 format 里取 SPS/PPS 兜底
                try {
                    MediaFormat of = encoder.getOutputFormat();
                    ByteBuffer bb = of.getByteBuffer("csd-0");
                    if (bb != null && bb.remaining() > 0) {
                        spsPps = new byte[bb.remaining()];
                        bb.get(spsPps);
                    }
                } catch (Throwable t) { }
                continue;
            }
            if (idx < 0) continue;
            try {
                ByteBuffer buf = encoder.getOutputBuffer(idx);
                boolean cfg = (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean key = (encInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (buf != null && encInfo.size > 0) {
                    byte[] data = new byte[encInfo.size];
                    buf.position(encInfo.offset);
                    buf.limit(encInfo.offset + encInfo.size);
                    buf.get(data);
                    if (cfg) {
                        spsPps = data;
                    } else {
                        byte[] au = (key && spsPps != null && spsPps.length > 0) ? concat(spsPps, data) : data;
                        pushFrame(au);
                        framesSent++;
                        if (framesSent % 20 == 0) log("已推送 " + framesSent + " 帧(H264), 末帧 " + au.length + "B");
                        main.post(new Runnable() { public void run() { listener.onFrameSent(); } });
                    }
                }
            } catch (Throwable t) {
                log("取编码输出失败: " + t);
            } finally {
                try { encoder.releaseOutputBuffer(idx, false); } catch (Throwable t) { }
            }
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // 独立 notify 线程：burst 并发发送（最多 MAX_INFLIGHT 片在途），
    // 队列空时等待；队列有货时尽量打满链路带宽
    final Runnable notifyLoop = new Runnable() {
        public void run() {
            BluetoothGattCharacteristic vch = null;
            while (sendRunning) {
                try {
                    if (client == null || !subscribed || gattServer == null) { sleep(15); continue; }
                    // 在途片满时等待协议栈确认；确认回调丢失时超时清零兜底防卡死
                    synchronized (inflightLock) {
                        long t0 = System.currentTimeMillis();
                        while (inflight >= MAX_INFLIGHT && sendRunning
                                && System.currentTimeMillis() - t0 < 300) {
                            inflightLock.wait(20);
                        }
                        if (inflight >= MAX_INFLIGHT) inflight = 0;
                    }
                    byte[] chunk = sendQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (chunk == null) continue;
                    if (client == null || !subscribed) continue;
                    if (vch == null) {
                        BluetoothGattService s = gattServer.getService(SVC);
                        if (s != null) vch = s.getCharacteristic(CH_VIDEO);
                    }
                    if (vch == null) continue;
                    vch.setValue(chunk);
                    boolean ok = gattServer.notifyCharacteristicChanged(client, vch, false);
                    if (ok) {
                        synchronized (inflightLock) { inflight++; }
                    } else {
                        sleep(5);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    sleep(10);
                }
            }
        }
    };

    volatile long droppedFrames = 0;

    void pushFrame(byte[] au) {
        // 延迟优先：发送积压超过阈值时，清空队列里所有旧片并丢掉当前帧，
        // 之后收到关键帧再完整发送，延迟上限收敛到 1~2 帧（保最新画面）
        if (latencyMode == MODE_LATENCY && sendQueue.size() > MAX_BACKLOG) {
            int backlog = sendQueue.size();
            sendQueue.clear();
            droppedFrames++;
            if (droppedFrames == 1 || droppedFrames % 20 == 0)
                log("积压 " + backlog + " 片，清队丢帧保延迟（累计丢 " + droppedFrames + " 帧）");
            requestSyncFrame();   // 丢帧破坏 P 帧参考链，请求关键帧让客户端快速恢复
            return;
        }
        // 画质优先：不主动丢帧，全部入队（队列满时 offer 兜底丢弃最旧溢出）
        // 帧头：AB CD + 2字节大端长度（2字节最多 65535，超长截断保护）
        int len = Math.min(au.length, 65535);
        byte[] head = new byte[]{
                FRAME_HEAD[0], FRAME_HEAD[1],
                (byte) ((len >> 8) & 0xFF), (byte) (len & 0xFF)};
        offer(head);
        int off = 0;
        while (off < len && sendRunning) {
            int n = Math.min(PIECE, len - off);
            byte[] piece = new byte[n];
            System.arraycopy(au, off, piece, 0, n);
            offer(piece);
            off += n;
        }
    }

    // 向编码器请求同步帧（下一个关键帧尽快输出），丢帧后用于恢复解码参考链
    void requestSyncFrame() {
        try {
            MediaCodec e = encoder;
            if (e != null) {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                e.setParameters(b);
            }
        } catch (Throwable t) { }
    }

    void offer(byte[] chunk) {
        // 非阻塞入队：队列满（发送严重跟不上）直接丢弃该片，绝不阻塞编码线程
        sendQueue.offer(chunk);
    }

    void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
