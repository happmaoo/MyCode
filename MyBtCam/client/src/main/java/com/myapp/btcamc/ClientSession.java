package com.myapp.btcamc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.myapp.btcam.common.BtCamProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端会话：SPP 连接 + H.264 解码到 Surface。
 *
 * 流畅度优先的丢帧策略：
 * - 服务端每帧带连续 seq，收到断裂的 seq 说明中间被丢弃过，立刻清空本地队列并
 *   等待下一个 IDR（关键帧），不然会一直花屏到下一段关键帧。
 * - 解码器输入队列没有空闲 buffer 时也直接丢当前帧，并同样转入「等 IDR」状态。
 * - 队列很浅（6 帧），满了就清空，保证画面永远追最新的数据。
 */
// targetSdk 28：BLUETOOTH / BLUETOOTH_ADMIN 属于安装时授予的普通权限，
// 因此这里不需要 Android 12 的运行时请求流程。
@SuppressLint("MissingPermission")
public class ClientSession {

    private static final String TAG = "ClientSession";
    private static final String MIME = "video/avc";
    private static final int DECODE_QUEUE = 6;
    private static final long CONNECT_TIMEOUT_MS = 12000;

    /** 可选码率档位（与主界面下拉框一致）。 */
    public static final int[] BITRATE_STEPS = {1000000, 500000, 300000, 200000, 100000};

    public interface Callback {
        void onState(String text);

        void onConnected();

        void onDisconnected();

        void onVideoFormat(int width, int height);

        void onError(String message);

        /** 自动降码率后回调，界面需要同步下拉框。 */
        void onBitrateChanged(int bitrate);

        /**
         * 端到端延迟（毫秒）：从服务端采集这一帧，到本机解码显示出来。
         * queueDepth 是本地还压着没解码的帧数，stale 表示画面已经明显落后。
         */
        void onLatency(int latencyMs, int queueDepth, boolean stale);
    }

    private static class Frame {
        final int seq;
        final boolean idr;
        final byte[] data;

        Frame(int seq, boolean idr, byte[] data) {
            this.seq = seq;
            this.idr = idr;
            this.data = data;
        }
    }

    private static class Config {
        final int width;
        final int height;
        final byte[] sps;
        final byte[] pps;

        Config(int width, int height, byte[] sps, byte[] pps) {
            this.width = width;
            this.height = height;
            this.sps = sps;
            this.pps = pps;
        }
    }

    private final Callback callback;
    private final ArrayBlockingQueue<Frame> decodeQueue = new ArrayBlockingQueue<>(DECODE_QUEUE);
    private final AtomicReference<Config> pendingConfig = new AtomicReference<>();
    private volatile Config lastConfig;
    private final Object writeLock = new Object();

    private volatile boolean running;
    private volatile boolean connected;
    private volatile int lastSeq = -1;
    private volatile boolean discardUntilIdr = true;
    private volatile boolean releaseSurfaceRequested;

    private volatile BluetoothSocket socket;
    private volatile InputStream in;
    private volatile OutputStream out;

    private Thread ioThread;
    private Thread decodeThread;

    private MediaCodec decoder;
    private volatile Surface surface;
    private int videoWidth = 640;
    private int videoHeight = 480;
    private long frameCounter;

    private long lastIdrRequestAt;
    private long lastAutoAdjustAt;
    private long gapWindowStart;
    private int gapCountInWindow;
    private int lostInWindow;
    private volatile boolean autoBitrate = true;
    private long lastStatsAt;
    /** 服务端时钟 - 客户端时钟（32 位环形），由 PING/PONG 估算 */
    private volatile boolean clockReady;
    private int clockOffset32;
    private int pendingPingTs = Integer.MIN_VALUE;
    private long lastLatencyAt;
    private int lastLatencyMs = -1;
    /** 最近统计到的接收帧率 */
    public int getRxFps() {
        return rxFps;
    }
    private volatile int rxFps;
    private int framesInStats;
    private int gapsInStats;

    private int reqWidth = 640;
    private int reqHeight = 480;
    private int reqBitrate = 500000;

    public ClientSession(Callback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        return connected;
    }

    public int getRequestedWidth() {
        return reqWidth;
    }

    public int getRequestedHeight() {
        return reqHeight;
    }

    public int getRequestedBitrate() {
        return reqBitrate;
    }

    /** 是否允许拥塞时自动降码率。 */
    public void setAutoBitrate(boolean enabled) {
        autoBitrate = enabled;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    public void start(String mac, int width, int height, int bitrate) {
        stop();
        reqWidth = width;
        reqHeight = height;
        reqBitrate = bitrate;
        lastSeq = -1;
        discardUntilIdr = true;
        frameCounter = 0;
        decodeQueue.clear();
        pendingConfig.set(null);
        releaseSurfaceRequested = false;
        running = true;

        ioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ioLoop(mac);
            }
        }, "BtCamIO");
        ioThread.start();

        decodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                decodeLoop();
            }
        }, "BtCamDecode");
        decodeThread.start();
    }

    public void stop() {
        running = false;
        connected = false;
        closeSocket();
        Thread io = ioThread;
        if (io != null) {
            try {
                io.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        Thread dec = decodeThread;
        if (dec != null) {
            dec.interrupt();
            try {
                dec.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        ioThread = null;
        decodeThread = null;
        decodeQueue.clear();
        pendingConfig.set(null);
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
        if (surface == null) {
            releaseSurfaceRequested = true;
        } else if (lastConfig != null) {
            // 界面重建 / 屏幕开关后 Surface 是新的，用上一份 SPS/PPS 重新配置解码器
            pendingConfig.set(lastConfig);
        }
    }

    /** 运行中切换分辨率/码率：让服务端重建编码器，本地等新的 CONFIG。 */
    public void changeParams(int width, int height, int bitrate) {
        reqWidth = width;
        reqHeight = height;
        reqBitrate = bitrate;
        lastSeq = -1;
        discardUntilIdr = true;
        decodeQueue.clear();
        if (!connected) {
            return;
        }
        try {
            BtCamProtocol.Request request = new BtCamProtocol.Request();
            request.width = width;
            request.height = height;
            request.bitrate = bitrate;
            request.camera = BtCamProtocol.CAMERA_AUTO;
            request.rotation = -1;
            send(BtCamProtocol.T_PARAM, BtCamProtocol.requestPayload(request));
            callback.onState("正在切换 " + width + "x" + height + " ...");
        } catch (IOException e) {
            callback.onError("切换参数失败: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 连接与读取                                                          */
    /* ------------------------------------------------------------------ */

    private void ioLoop(String mac) {
        try {
            connectSocket(mac);
            connected = true;
            callback.onConnected();
            sendHello();
            startPing();
            readLoop();
        } catch (Exception e) {
            if (running) {
                String message = e.getMessage();
                callback.onError(message == null || message.isEmpty()
                        ? "蓝牙连接失败" : message);
            }
        } finally {
            boolean wasConnected = connected;
            connected = false;
            closeSocket();
            if (running) {
                running = false;
            }
            if (wasConnected) {
                callback.onDisconnected();
            }
        }
    }

    private void connectSocket(String mac) throws IOException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new IOException("本机不支持蓝牙");
        }
        if (!adapter.isEnabled()) {
            throw new IOException("请先打开蓝牙");
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            throw new IOException("服务端蓝牙地址不正确");
        }
        adapter.cancelDiscovery();

        BluetoothSocket s = null;
        try {
            s = device.createRfcommSocketToServiceRecord(BtCamProtocol.SPP_UUID);
            if (!connectWithTimeout(s)) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                s = null;
            }
        } catch (IOException e) {
            s = null;
        }
        if (s == null) {
            // 少数 ROM 的 SDP 有问题，退回 RFCOMM channel 1
            s = createFallbackSocket(device);
            if (s == null || !connectWithTimeout(s)) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                throw new IOException("连接失败：请确认服务端已配对、蓝牙已开启且监听服务在运行");
            }
        }
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        Log.i(TAG, "connected to " + mac);
    }

    private static boolean connectWithTimeout(BluetoothSocket s) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    s.connect();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        try {
            future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    private static BluetoothSocket createFallbackSocket(BluetoothDevice device) {
        try {
            Method method = device.getClass().getMethod("createRfcommSocket", int.class);
            Object result = method.invoke(device, 1);
            return result instanceof BluetoothSocket ? (BluetoothSocket) result : null;
        } catch (Exception e) {
            Log.w(TAG, "fallback socket failed: " + e);
            return null;
        }
    }

    private void sendHello() throws IOException {
        BtCamProtocol.Request request = new BtCamProtocol.Request();
        request.width = reqWidth;
        request.height = reqHeight;
        request.bitrate = reqBitrate;
        request.camera = BtCamProtocol.CAMERA_AUTO;
        request.rotation = -1; // -1 = 用服务端设置里的方向
        send(BtCamProtocol.T_HELLO, BtCamProtocol.requestPayload(request));
        callback.onState("已连接，请求服务端开流");
    }

    private void startPing() {
        Thread ping = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && connected) {
                    try {
                        // 时钟偏差漂移很慢，10 秒校准一次足够，少占链路
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        int ts = (int) SystemClock.elapsedRealtime();
                        pendingPingTs = ts;
                        send(BtCamProtocol.T_PING, BtCamProtocol.pingPayload(ts));
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        }, "BtCamPing");
        ping.setDaemon(true);
        ping.start();
    }

    private void readLoop() throws IOException {
        while (running) {
            BtCamProtocol.Header header = BtCamProtocol.readHeader(in);
            byte[] payload = BtCamProtocol.readPayload(in, header.length);
            switch (header.type) {
                case BtCamProtocol.T_HELLO_ACK: {
                    BtCamProtocol.Ack ack = BtCamProtocol.parseAck(payload);
                    if (!ack.isOk()) {
                        callback.onError(ack.reason.isEmpty() ? "服务端拒绝开始推流" : ack.reason);
                    } else {
                        callback.onState("服务端已开流，等待画面");
                    }
                    break;
                }
                case BtCamProtocol.T_CONFIG: {
                    BtCamProtocol.Config c = BtCamProtocol.parseConfig(payload);
                    if (c.sps != null && c.pps != null) {
                        Config config = new Config(c.width, c.height, c.sps, c.pps);
                        lastConfig = config;
                        pendingConfig.set(config);
                        Log.i(TAG, "config " + c.width + "x" + c.height);
                    }
                    break;
                }
                case BtCamProtocol.T_VIDEO:
                    onVideoFrame(header.flags, payload);
                    break;
                case BtCamProtocol.T_STATUS:
                    callback.onState(BtCamProtocol.textOf(payload));
                    break;
                case BtCamProtocol.T_PONG:
                    handlePong(payload);
                    break;
                default:
                    Log.d(TAG, "ignore message " + header.type);
                    break;
            }
        }
    }

    /** 用 PING/PONG 估算「服务端时钟 - 本机时钟」，两次往返取一半当作单程延迟。 */
    private void handlePong(byte[] payload) {
        if (payload == null || payload.length < 8) {
            return;
        }
        int clientTs = BtCamProtocol.readInt(payload, 0);
        int serverTs = BtCamProtocol.readInt(payload, 4);
        int now = (int) SystemClock.elapsedRealtime();
        int rtt = now - clientTs;
        // rtt 异常（为负或超过 5 秒）说明样本不可信，丢掉
        if (rtt < 0 || rtt > 5000) {
            return;
        }
        clockOffset32 = serverTs - clientTs - rtt / 2;
        clockReady = true;
        Log.i(TAG, "clock sync offset=" + clockOffset32 + "ms rtt=" + rtt + "ms");
    }

    /**
     * 端到端延迟：服务端采集时刻 -> 现在收到这一帧。
     * 两端时钟都用 elapsedRealtime 的低 32 位（环形），相减得到真实时差。
     */
    private void reportLatency(int pts) {
        if (!clockReady) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // pts 是服务端的时钟读数，换算到本机时钟要减去两机偏差：
        //   本机时刻 = 服务端时刻 - offset
        //   延迟 = 现在 - 本机时刻 = now - pts + offset
        int latency = (int) now - pts + clockOffset32;
        if (latency < 0) {
            // 时钟估计有偏差或序号错乱，别显示负数
            return;
        }
        int queue = decodeQueue.size();
        boolean stale = latency > 900 || queue >= 3;
        if (now - lastLatencyAt < 1000) {
            return;
        }
        lastLatencyAt = now;
        lastLatencyMs = latency;
        callback.onLatency(latency, queue, stale);
    }

    private void onVideoFrame(int flags, byte[] payload) {
        if (payload == null || payload.length < BtCamProtocol.VIDEO_HEAD_SIZE + 1) {
            return;
        }
        int seq = BtCamProtocol.readInt(payload, 0);
        int pts = BtCamProtocol.readInt(payload, 4);
        reportLatency(pts);
        boolean idr = (flags & BtCamProtocol.FLAG_IDR) != 0;
        framesInStats++;
        long nowStats = SystemClock.elapsedRealtime();
        if (lastStatsAt == 0) {
            lastStatsAt = nowStats;
        } else if (nowStats - lastStatsAt >= 2000) {
            int fpsNow = (int) (framesInStats * 1000 / (nowStats - lastStatsAt));
            rxFps = fpsNow;
            Log.i(TAG, "rx " + fpsNow + "fps gaps " + gapsInStats + "/"
                    + (nowStats - lastStatsAt) + "ms");
            framesInStats = 0;
            gapsInStats = 0;
            lastStatsAt = nowStats;
        }
        byte[] data = new byte[payload.length - BtCamProtocol.VIDEO_HEAD_SIZE];
        System.arraycopy(payload, BtCamProtocol.VIDEO_HEAD_SIZE, data, 0, data.length);

        if (lastSeq >= 0 && seq != lastSeq + 1) {
            int lost = seq - lastSeq - 1;
            if (lost < 0) {
                // seq 归零：服务端换了新的编码会话（切参数/重启推流），不是链路丢帧
                Log.i(TAG, "new stream seq=" + seq);
                decodeQueue.clear();
                discardUntilIdr = true;
                lastSeq = seq;
                return;
            }
            Log.w(TAG, "seq gap " + lastSeq + " -> " + seq + " (lost " + lost + ")");
            decodeQueue.clear();
            discardUntilIdr = true;
            gapsInStats++;
            noteCongestion(lost);
            requestIdr();
        }
        lastSeq = seq;

        if (!decodeQueue.offer(new Frame(seq, idr, data))) {
            // 解码端消费不过来：丢掉积压，从下一个关键帧重新对齐
            Log.w(TAG, "decode queue full, drop backlog");
            decodeQueue.clear();
            discardUntilIdr = true;
            requestIdr();
        }
    }

    /** 统计 3 秒内的丢帧次数，持续拥塞就自动降一档码率（流畅优先）。 */
    private void noteCongestion(int lost) {
        long now = SystemClock.elapsedRealtime();
        if (now - gapWindowStart > 4000) {
            gapWindowStart = now;
            gapCountInWindow = 0;
            lostInWindow = 0;
        }
        gapCountInWindow++;
        lostInWindow += lost;
        // 4 秒内丢了 10 帧以上（约 1/3 秒的画面）才认为链路真的跟不上
        if (!autoBitrate || lostInWindow < 10 || now - lastAutoAdjustAt < 6000) {
            return;
        }
        int current = reqBitrate;
        int next = -1;
        for (int i = 0; i < BITRATE_STEPS.length - 1; i++) {
            if (BITRATE_STEPS[i] == current) {
                next = BITRATE_STEPS[i + 1];
                break;
            }
        }
        if (next < 0 || next >= current) {
            return;
        }
        lastAutoAdjustAt = now;
        gapCountInWindow = 0;
        lostInWindow = 0;
        Log.i(TAG, "link congested, auto lower bitrate " + current + " -> " + next);
        changeParams(reqWidth, reqHeight, next);
        callback.onBitrateChanged(next);
        lastSeq = -1;
    }

    /** 丢帧后请服务端立刻插一个 IDR，避免干等一秒（限流 250ms）。 */
    private void requestIdr() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastIdrRequestAt < 250) {
            return;
        }
        lastIdrRequestAt = now;
        try {
            send(BtCamProtocol.T_IDR_REQ, null);
        } catch (Exception e) {
            Log.d(TAG, "request idr failed: " + e);
        }
    }

    private void send(byte type, byte[] payload) throws IOException {
        synchronized (writeLock) {
            OutputStream output = out;
            if (output == null) {
                throw new IOException("连接已断开");
            }
            BtCamProtocol.writeMessage(output, type, 0, payload);
            output.flush();
        }
    }

    private void closeSocket() {
        BluetoothSocket s = socket;
        socket = null;
        in = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 解码                                                                */
    /* ------------------------------------------------------------------ */

    private void decodeLoop() {
        while (running) {
            if (releaseSurfaceRequested) {
                releaseSurfaceRequested = false;
                releaseDecoder();
            }
            Config config = pendingConfig.get();
            if (config != null) {
                if (surface == null) {
                    sleepQuietly(20);
                    continue;
                }
                if (pendingConfig.compareAndSet(config, null)) {
                    configureDecoder(config);
                }
                continue;
            }
            Frame frame = null;
            try {
                frame = decodeQueue.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (frame != null) {
                feed(frame);
            }
            drain();
        }
        releaseDecoder();
        Log.i(TAG, "decode loop end");
    }

    private void configureDecoder(Config config) {
        releaseDecoder();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME,
                    config.width, config.height);
            if (config.sps != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(config.sps));
            }
            if (config.pps != null) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(config.pps));
            }
            decoder = MediaCodec.createDecoderByType(MIME);
            decoder.configure(format, surface, null, 0);
            // 关键：显式等比缩放（留黑边），不要被解码器默认的「铺满」策略拉变形
            try {
                decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            } catch (Throwable ignored) {
            }
            decoder.start();
            videoWidth = config.width;
            videoHeight = config.height;
            discardUntilIdr = true;
            lastSeq = -1;
            frameCounter = 0;
            callback.onVideoFormat(config.width, config.height);
            Log.i(TAG, "decoder configured " + config.width + "x" + config.height);
        } catch (Exception e) {
            Log.e(TAG, "configure decoder failed", e);
            releaseDecoder();
            callback.onError("解码器初始化失败: " + e.getMessage());
        }
    }

    private void feed(Frame frame) {
        MediaCodec codec = decoder;
        if (codec == null) {
            return;
        }
        if (discardUntilIdr) {
            if (!frame.idr) {
                return;
            }
            discardUntilIdr = false;
            try {
                codec.flush();
            } catch (Exception ignored) {
            }
            Log.d(TAG, "resync at idr seq=" + frame.seq);
        }
        try {
            int index = codec.dequeueInputBuffer(0);
            if (index < 0) {
                // 解码器暂时没有空闲 buffer，等一小会儿再试，避免白等一整个 GOP
                for (int i = 0; i < 4 && index < 0; i++) {
                    sleepQuietly(3);
                    index = codec.dequeueInputBuffer(0);
                }
            }
            if (index < 0) {
                Log.w(TAG, "no input buffer, drop until idr");
                discardUntilIdr = true;
                requestIdr();
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) {
                discardUntilIdr = true;
                return;
            }
            buffer.clear();
            buffer.put(frame.data);
            frameCounter++;
            long pts = System.nanoTime() / 1000; // 用真实单调时钟，避免渲染调度异常
            codec.queueInputBuffer(index, 0, frame.data.length, pts, 0);
        } catch (Exception e) {
            discardUntilIdr = true;
            Log.w(TAG, "feed failed: " + e);
        }
    }

    private void drain() {
        MediaCodec codec = decoder;
        if (codec == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    int w = format.getInteger(MediaFormat.KEY_WIDTH);
                    int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                    // 解码器报的宽高可能含对齐填充，真实画面尺寸要看 crop rect
                    int cw = -1;
                    int ch = -1;
                    try {
                        // crop-left / crop-right / crop-top / crop-bottom（解码器输出格式里的真实画面区域）
                        if (format.containsKey("crop-left") && format.containsKey("crop-right")
                                && format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
                            int left = format.getInteger("crop-left");
                            int right = format.getInteger("crop-right");
                            int top = format.getInteger("crop-top");
                            int bottom = format.getInteger("crop-bottom");
                            if (right - left > 0 && bottom - top > 0) {
                                cw = right - left + 1;
                                ch = bottom - top + 1;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    if (cw > 0 && ch > 0) {
                        w = cw;
                        h = ch;
                    }
                    if (w > 0 && h > 0) {
                        videoWidth = w;
                        videoHeight = h;
                        callback.onVideoFormat(w, h);
                        Log.i(TAG, "decoder output " + w + "x" + h);
                    }
                    continue;
                }
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, info.size > 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "drain failed: " + e);
        }
    }

    private void releaseDecoder() {
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Exception ignored) {
            }
            try {
                decoder.release();
            } catch (Exception ignored) {
            }
            decoder = null;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
