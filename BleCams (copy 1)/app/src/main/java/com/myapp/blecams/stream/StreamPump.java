package com.myapp.blecams.stream;

import android.os.SystemClock;
import android.util.Log;

import com.myapp.blecams.protocol.AnnexB;
import com.myapp.blecams.protocol.StreamProtocol;
import com.myapp.blecams.protocol.VideoProfile;

import java.util.ArrayDeque;

/**
 * 把编码出来的 H.264 访问单元（AU）切片，按 BLE 链路实际能力推给 notify 队列。
 *
 * <p>三个要点：
 * <ol>
 *   <li><b>令牌桶限速</b>：注入空口的字节速率必须 ~= bitrate/8。不限制的话 Android 协议栈
 *       会照单全收（notify 一直返回 true），排队越堆越长，最终表现为“延迟无上限增长”。</li>
 *   <li><b>只能整帧丢到下一个 IDR</b>：H.264 的 P 帧依赖前帧，丢半段会花屏，所以拥塞时
 *       丢弃待发队列并要求编码器出关键帧。</li>
 *   <li><b>码率/帧率自适</b>：按实测发送速率下调码率，队列排空后再缓慢涨回档位上限。</li>
 * </ol>
 */
public class StreamPump {

    private static final String TAG = "StreamPump";

    /** notify 发送侧（{@link com.myapp.blecams.ble.BleCamServer}）。 */
    public interface Sink {
        /** 客户端已连接且已使能 CCCD。 */
        boolean isReady();

        /** 单个 notify 可携带的 AU 字节数（由协商 MTU 决定）。 */
        int getChunkPayload();

        /** 发送一个完整分包；false 表示协议栈暂时收不下。 */
        boolean sendNotification(byte[] packet);
    }

    /** 需要服务端配合的动作。 */
    public interface Control {
        void requestKeyFrame();

        void setBitrate(int bps);

        void setFps(int fps);
    }

    public interface Listener {
        void onStats(Stats stats);
    }

    public static final class Stats {
        public long framesSent;
        public long framesDropped;
        public long bytesSent;
        public long sendFailures;
        public int bytesPerSecond;
        public int outFps;
        public int queueBytes;
        public int queueFrames;
        public int bitrate;
        public int fps;
        public int chunkPayload;
    }

    private static final class Frame {
        final byte[] data;
        final boolean key;
        final boolean config;
        final int seq;
        final long enqueueMs;
        int offset;
        /** 被拥塞控制清出队列后置 true，发送线程立刻停止发它的剩余分包。 */
        volatile boolean abandoned;

        Frame(byte[] data, boolean key, long ptsUs, int seq) {
            this.data = data;
            this.key = key;
            this.config = AnnexB.containsConfig(data);
            this.seq = seq;
            this.enqueueMs = SystemClock.elapsedRealtime();
        }
    }

    /** 待发队列高水位：超过即丢帧，避免延迟无限增长。 */
    private static final int HIGH_WATER_BYTES = 6 * 1024;
    /** 队列帧数硬上限（约 0.5s@10fps）。 */
    private static final int MAX_QUEUE_FRAMES = 6;
    /** 单帧最长滞留时间。 */
    private static final long MAX_FRAME_AGE_MS = 700;
    /** 两次“跳到 IDR”的最小间隔。IDR 极贵（60kbps 下可能等于 1 秒的字节预算），不能频繁。 */
    private static final long DROP_COOLDOWN_MS = 2000;
    private static final int MIN_BITRATE = 12_000;

    private final Sink sink;
    private final Control control;
    private final Listener listener;

    private final Object lock = new Object();
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private int queueBytes;

    private Thread thread;
    private volatile boolean running;

    private volatile VideoProfile profile = VideoProfile.DEFAULT;
    private volatile int bitrate = profile.bitrate;
    private volatile int fps = profile.fps;
    private volatile boolean manualBitrate;

    private int nextSeq;
    private boolean dropUntilKey;
    private long lastDropMs;

    // 1 秒窗口统计
    private long windowStart = SystemClock.elapsedRealtime();
    private long windowBytes;
    private long windowFrames;
    private int lastBytesPerSecond;
    private int lastOutFps;
    /** 自适应统计窗口：500ms 一次，反应更快，但要连续多个窗口才动手。 */
    private static final long ADAPT_WINDOW_MS = 500;
    private int stalledWindows;
    private int cleanWindows;
    private long totalSent;
    private long totalDropped;
    private long totalBytes;
    private long totalFailures;

    // 令牌桶：下一次允许发送的绝对时刻（System.nanoTime 基准）
    private long allowAtNs;
    /** 允许的最大“排期提前量”，超过则截断（容忍约 60ms 突发）。 */
    private static final long MAX_PACING_HORIZON_NS = 60_000_000L;

    public StreamPump(Sink sink, Control control, Listener listener) {
        this.sink = sink;
        this.control = control;
        this.listener = listener;
    }

    public void setProfile(VideoProfile p) {
        if (p == null) {
            return;
        }
        profile = p;
        manualBitrate = false;
        bitrate = p.bitrate;
        fps = p.fps;
        control.setBitrate(bitrate);
        control.setFps(fps);
        control.requestKeyFrame();
    }

    public void setBitrateLimit(int bps) {
        if (bps < MIN_BITRATE) {
            bps = MIN_BITRATE;
        }
        manualBitrate = true;
        bitrate = bps;
        control.setBitrate(bps);
    }

    public void setFpsLimit(int f) {
        if (f <= 0) {
            return;
        }
        fps = f;
        control.setFps(f);
    }

    public int getCurrentBitrate() {
        return bitrate;
    }

    /** 下一个将要分配的帧号（用于 STATUS 展示）。 */
    public int peekNextSeq() {
        return nextSeq;
    }

    public int getCurrentFps() {
        return fps;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        allowAtNs = System.nanoTime();
        thread = new Thread(this::loop, "stream-pump");
        thread.start();
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        synchronized (lock) {
            clearLocked();
        }
    }

    /** 编码器线程调用，非阻塞。 */
    public void offer(long ptsUs, byte[] annexB, boolean keyFrame) {
        if (!running || annexB == null || annexB.length == 0) {
            return;
        }
        Frame f;
        synchronized (lock) {
            f = new Frame(annexB, keyFrame, ptsUs, nextSeq);
            nextSeq = (nextSeq + 1) & 0xFF;

            if (dropUntilKey) {
                // 正在等待 IDR：非关键帧直接丢
                if (!f.key) {
                    totalDropped++;
                    return;
                }
                clearLocked();
                dropUntilKey = false;
            }

            boolean congested = queueBytes + f.data.length > HIGH_WATER_BYTES
                    || queue.size() >= MAX_QUEUE_FRAMES;
            if (congested) {
                clearLocked();
                dropUntilKey = true;
                if (!f.key) {
                    totalDropped++;
                    return;
                }
                dropUntilKey = false;
            }

            queue.add(f);
            queueBytes += f.data.length;
            lock.notifyAll();
        }
    }

    private void loop() {
        Log.i(TAG, "pump started");
        while (running) {
            Frame frame;
            synchronized (lock) {
                while (queue.isEmpty() && running) {
                    try {
                        lock.wait(20);
                    } catch (InterruptedException e) {
                        running = false;
                        break;
                    }
                }
                frame = queue.peek();
            }
            if (!running) {
                break;
            }
            if (!sink.isReady()) {
                // 没人订阅：清空避免堆积，恢复后从 IDR 开始
                synchronized (lock) {
                    if (!queue.isEmpty()) {
                        clearLocked();
                        dropUntilKey = true;
                    }
                }
                sleep(100);
                continue;
            }
            if (frame == null) {
                sleep(5);
                continue;
            }
            if (SystemClock.elapsedRealtime() - frame.enqueueMs > MAX_FRAME_AGE_MS) {
                dropToNextKey();
                continue;
            }

            int payload = sink.getChunkPayload();
            if (payload <= 0) {
                sleep(20);
                continue;
            }
            int remaining = frame.data.length - frame.offset;
            boolean last = remaining <= payload;
            int len = last ? remaining : payload;
            if (len <= 0) {
                finishFrame(frame);
                continue;
            }

            long wait = paceWaitNs(len);
            if (wait > 0) {
                sleep(wait / 1_000_000L);
            }
            // 限速等待期间该帧可能已被拥塞控制丢弃
            if (frame.abandoned || frame.offset >= frame.data.length) {
                finishFrame(frame);
                continue;
            }

            int flags = StreamProtocol.frameFlags(frame.key, frame.config,
                    frame.offset == 0, last);
            byte[] packet = last
                    ? StreamProtocol.buildLastChunk(flags, frame.seq, frame.data, frame.offset, len)
                    : StreamProtocol.buildChunk(flags, frame.seq, frame.data, frame.offset, len);

            if (sink.sendNotification(packet)) {
                frame.offset += len;
                windowBytes += packet.length;
                totalBytes += packet.length;
                if (frame.offset >= frame.data.length) {
                    finishFrame(frame);
                    windowFrames++;
                }
            } else {
                totalFailures++;
                // 协议栈队列满：明显超过链路能力，退避并考虑跳帧
                sleep(15);
                if (totalFailures % 50 == 0) {
                    dropToNextKey();
                }
            }
            adaptIfNeeded();
        }
        Log.i(TAG, "pump exited");
    }

    private void finishFrame(Frame frame) {
        synchronized (lock) {
            if (queue.peek() == frame) {
                queue.poll();
                queueBytes -= frame.data.length;
                totalSent++;
            }
        }
    }

    /**
     * 令牌桶：限制每秒注入空口的字节数 = bitrate/8 * 1.2（留出关键帧突发和 ATT 头开销）。
     *
     * <p>实现方式是维护“下一次允许发送的绝对时刻”，发送后按本包字节数把它往后推；
     * 空闲时不累积过去的时间，最多容忍 60ms 的突发，避免延迟无上限增长。
     *
     * @return 发送前需要等待的纳秒数（0 表示立刻可发）
     */
    private long paceWaitNs(int bytes) {
        long now = System.nanoTime();
        if (allowAtNs < now) {
            allowAtNs = now;
        }
        double rateBytesPerSec = Math.max(MIN_BITRATE, bitrate) / 8.0 * 1.2;
        long costNs = (long) (bytes / rateBytesPerSec * 1_000_000_000.0);
        long waitNs = allowAtNs - now;
        long next = allowAtNs + costNs;
        long horizon = now + MAX_PACING_HORIZON_NS;
        allowAtNs = Math.min(next, horizon);
        return waitNs > 0 ? waitNs : 0;
    }

    private void dropToNextKey() {
        long now = SystemClock.elapsedRealtime();
        boolean requestIdr;
        synchronized (lock) {
            clearLocked();
            dropUntilKey = true;
            requestIdr = now - lastDropMs >= DROP_COOLDOWN_MS;
            if (requestIdr) {
                lastDropMs = now;
            }
        }
        if (requestIdr) {
            control.requestKeyFrame();
        }
    }

    private void requestDropToKeyLocked() {
        clearLocked();
        dropUntilKey = true;
    }

    /** 每秒根据实测吞吐微调码率/帧率。 */
    private void adaptIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        long dt = now - windowStart;
        if (dt < ADAPT_WINDOW_MS) {
            return;
        }
        lastBytesPerSecond = (int) (windowBytes * 1000L / dt);
        lastOutFps = (int) (windowFrames * 1000L / dt);
        windowBytes = 0;
        windowFrames = 0;
        windowStart = now;

        VideoProfile p = profile;
        int ceiling = manualBitrate ? bitrate : p.bitrate;
        int needed = bitrate / 8;
        int queueNow;
        int framesNow;
        synchronized (lock) {
            queueNow = queueBytes;
            framesNow = queue.size();
        }

        boolean stalled = lastBytesPerSecond < needed * 0.9f || queueNow > HIGH_WATER_BYTES / 2;
        boolean clean = queueNow < 1024 && lastBytesPerSecond > needed * 1.2f;
        stalledWindows = stalled ? stalledWindows + 1 : 0;
        cleanWindows = clean ? cleanWindows + 1 : 0;

        int newBitrate = bitrate;
        // 连续 2 个窗口（~1s）发不出去才降码率；用 500ms 窗口是为了更快反应
        if (stalledWindows >= 2) {
            newBitrate = clamp((int) (lastBytesPerSecond * 8L * 9 / 10), MIN_BITRATE, ceiling);
            stalledWindows = 0;
        } else if (cleanWindows >= 6) {
            newBitrate = clamp((int) (bitrate * 1.1f), MIN_BITRATE, ceiling);
            cleanWindows = 0;
        }
        if (Math.abs(newBitrate - bitrate) >= 1000) {
            bitrate = newBitrate;
            control.setBitrate(bitrate);
        }

        int curFps = fps;
        int newFps = curFps;
        if ((queueNow > HIGH_WATER_BYTES * 3 / 4 || framesNow >= MAX_QUEUE_FRAMES) && curFps > 3) {
            newFps = curFps - 1;
        } else if (queueNow < 256 && lastBytesPerSecond < (ceiling / 8) * 0.7f && curFps < p.fps) {
            newFps = curFps + 1;
        }
        if (newFps != curFps) {
            fps = newFps;
            control.setFps(newFps);
        }

        if (listener != null) {
            Stats s = new Stats();
            s.framesSent = totalSent;
            s.framesDropped = totalDropped;
            s.bytesSent = totalBytes;
            s.sendFailures = totalFailures;
            s.bytesPerSecond = lastBytesPerSecond;
            s.outFps = lastOutFps;
            s.queueBytes = queueNow;
            s.queueFrames = framesNow;
            s.bitrate = bitrate;
            s.fps = fps;
            s.chunkPayload = sink.getChunkPayload();
            listener.onStats(s);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private void clearLocked() {
        totalDropped += queue.size();
        for (Frame f : queue) {
            f.abandoned = true;
        }
        queue.clear();
        queueBytes = 0;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
