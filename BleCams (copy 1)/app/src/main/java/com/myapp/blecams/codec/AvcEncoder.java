package com.myapp.blecams.codec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import com.myapp.blecams.protocol.AnnexB;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H.264/AVC 硬编码器（MediaCodec + InputSurface）。
 *
 * <p>Camera2 直接把预览帧写进 {@link #getInputSurface()}，不经过 CPU 拷贝；编码输出以
 * “访问单元(AU)”为单位回调，关键帧 AU 会自动拼上缓存的 SPS/PPS，保证接收端任何时候
 * 拿到一个 IDR 都能独立起解。
 *
 * <p>动态调整：{@link #requestKeyFrame()} 与 {@link #setBitrate(int)} 通过
 * {@code MediaCodec.setParameters}（API 23+）实现，不需要重建编码器。
 */
public class AvcEncoder {

    private static final String TAG = "AvcEncoder";

    /** IDR 间隔（秒）。低码率下 IDR 很贵，默认拉长到 10s，按需由控制通道请求即时 IDR。 */
    private static final int I_FRAME_INTERVAL_SEC = 10;

    public interface AuCallback {
        /**
         * @param ptsUs    时间戳（来自相机/编码器）
         * @param annexB   Annex-B 字节流；关键帧自带 SPS/PPS
         * @param keyFrame 是否含 IDR
         */
        void onAccessUnit(long ptsUs, byte[] annexB, boolean keyFrame);
    }

    private final Object codecLock = new Object();
    private final AuCallback callback;
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong totalFrames = new AtomicLong();

    private MediaCodec codec;
    private Surface inputSurface;
    private Thread drainThread;
    private volatile boolean running;

    private byte[] cachedConfig = new byte[0];
    private final ByteArrayOutputStream auBuffer = new ByteArrayOutputStream(64 * 1024);
    private boolean auBuffering;
    private long auPts;

    private int width;
    private int height;

    public AvcEncoder(AuCallback callback) {
        this.callback = callback;
    }

    /**
     * 把本机所有 AVC/HEVC 编解码器打进 logcat（tag {@code AvcEncoder}），用来回答
     * “这台手机到底有没有软件 HEVC 编码器、我们最终选了谁”这类问题——这类事实只能在目标
     * 设备上实测，不要靠猜。跑一次 {@code adb logcat -s AvcEncoder} 就能看到结论。
     */
    private static void logCodecInventory() {
        try {
            for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                for (String type : info.getSupportedTypes()) {
                    if (type.contains("avc") || type.contains("hevc") || type.contains("h265")) {
                        Log.i("AvcEncoder", (info.isEncoder() ? "[ENC] " : "[DEC] ")
                                + info.getName() + " -> " + type);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "codec inventory failed", e);
        }
    }

    /** 优先选硬件编码器（支持 Surface 输入）。 */
    private static String findEncoderName() {
        logCodecInventory();
        String hardware = null;
        String any = null;
        try {
            for (MediaCodecInfo info : new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                        continue;
                    }
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    if (!containsColorFormat(caps.colorFormats,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                        continue;
                    }
                    if (any == null) {
                        any = info.getName();
                    }
                    String n = info.getName().toLowerCase();
                    boolean software = n.contains("google") || n.contains("soft") || n.contains("cx2");
                    if (!software && hardware == null) {
                        hardware = info.getName();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enumerate encoders failed", e);
        }
        return hardware != null ? hardware : any;
    }

    private static boolean containsColorFormat(int[] formats, int wanted) {
        if (formats != null) {
            for (int f : formats) {
                if (f == wanted) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param fps     目标帧率（同时应把相机 AE fps range 设成同值，否则实际帧率由相机决定）
     * @param bitrate 目标码率 bps，建议 20_000 ~ 150_000
     */
    public boolean prepare(int width, int height, int fps, int bitrate) {
        this.width = width;
        this.height = height;
        String name = findEncoderName();
        if (name == null) {
            Log.e(TAG, "no AVC encoder with surface input");
            return false;
        }
        Log.i(TAG, "using encoder " + name + " " + width + "x" + height + "@" + fps);
        try {
            codec = MediaCodec.createByCodecName(name);
        } catch (Exception e) {
            Log.e(TAG, "createByCodecName failed", e);
            return false;
        }
        try {
            configure(width, height, fps, bitrate, 0);
        } catch (Exception first) {
            Log.w(TAG, "configure with Main/CABAC failed, retry baseline", first);
            try {
                releaseQuietly();
                codec = MediaCodec.createByCodecName(name);
                configure(width, height, fps, bitrate, 1);
            } catch (Exception second) {
                Log.w(TAG, "configure with baseline profile failed, retry plain", second);
                try {
                    releaseQuietly();
                    codec = MediaCodec.createByCodecName(name);
                    configure(width, height, fps, bitrate, 2);
                } catch (Exception third) {
                    Log.e(TAG, "configure failed", third);
                    releaseQuietly();
                    return false;
                }
            }
        }
        inputSurface = codec.createInputSurface();
        return inputSurface != null;
    }

    private void configure(int w, int h, int fps, int bitrate, int profileAttempt) {
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(5000, bitrate));
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, fps));
        // IDR 间隔拉长：60kbps 下一个 320x240 的 IDR 可能吃掉整整 1 秒的字节预算，
        // 2s 一次的强制 IDR 会把画质和链路同时打爆。需要即时 IDR 由控制通道请求。
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC);
        try {
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        } catch (Exception ignored) {
        }
        if (profileAttempt == 0) {
            // 首选 Main + CABAC：低码率下通常比 Baseline/CAVLC 省 10~15% 字节。
            // 但不少 Vendor 编码器会静默忽略该参数，所以实际生效与否要用 AnnexB.isCabac() 验证。
            fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain);
            fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3);
        } else if (profileAttempt == 1) {
            // 回退：约束基线（无 B 帧、兼容性最好）
            fmt.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline);
            fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3);
        }
        codec.configure(fmt, null /*surface*/, null /*crypto*/, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    /** 实际码流里的 profile_idc（-1 = 还没拿到 SPS）。 */
    public int getActualProfileIdc() {
        return AnnexB.profileIdc(cachedConfig);
    }

    /** 实际是否用了 CABAC；null = 还没拿到 PPS。 */
    public Boolean getActualCabac() {
        return AnnexB.isCabac(cachedConfig);
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            codec.start();
        } catch (Exception e) {
            Log.e(TAG, "codec.start failed", e);
            return;
        }
        running = true;
        auBuffering = false;
        auBuffer.reset();
        drainThread = new Thread(this::drainLoop, "avc-drain");
        drainThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (drainThread != null) {
            try {
                drainThread.join(500);
            } catch (InterruptedException ignored) {
            }
            drainThread = null;
        }
        synchronized (codecLock) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
        }
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Exception ignored) {
            }
            inputSurface = null;
        }
        releaseQuietly();
        cachedConfig = new byte[0];
        auBuffer.reset();
        auBuffering = false;
    }

    private void releaseQuietly() {
        synchronized (codecLock) {
            if (codec != null) {
                try {
                    codec.release();
                } catch (Exception ignored) {
                }
                codec = null;
            }
        }
    }

    /** 请求下一个 IDR（链路丢帧/新客户端接入时调用）。 */
    public void requestKeyFrame() {
        synchronized (codecLock) {
            if (codec == null || !running) {
                return;
            }
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                codec.setParameters(b);
            } catch (Exception e) {
                Log.w(TAG, "requestKeyFrame failed", e);
            }
        }
    }

    /** 动态改码率（链路带宽自适应用），不重建编码器。 */
    public void setBitrate(int bps) {
        synchronized (codecLock) {
            if (codec == null || !running || bps <= 0) {
                return;
            }
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
                codec.setParameters(b);
            } catch (Exception e) {
                Log.w(TAG, "setBitrate failed", e);
            }
        }
    }

    public long getTotalBytes() {
        return totalBytes.get();
    }

    public long getTotalFrames() {
        return totalFrames.get();
    }

    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private void drainLoop() {
        final long timeoutUs = 10_000L;
        while (running) {
            int idx;
            synchronized (codecLock) {
                if (codec == null) {
                    break;
                }
                try {
                    idx = codec.dequeueOutputBuffer(info, timeoutUs);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "dequeue failed", e);
                    break;
                }
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "output format changed");
                continue;
            } else if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            } else if (idx < 0) {
                continue;
            }

            byte[] data;
            synchronized (codecLock) {
                ByteBuffer buffer = codec.getOutputBuffer(idx);
                if (buffer == null || info.size <= 0) {
                    codec.releaseOutputBuffer(idx, false);
                    continue;
                }
                data = new byte[info.size];
                buffer.position(info.offset);
                buffer.get(data, 0, info.size);
                codec.releaseOutputBuffer(idx, false);
            }

            int flags = info.flags;
            long pts = info.presentationTimeUs;

            if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // SPS/PPS：缓存起来，拼在每个关键帧前面
                cachedConfig = concat(cachedConfig, data);
                continue;
            }

            boolean partial = (flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0;

            if (partial) {
                if (!auBuffering) {
                    auBuffer.reset();
                    auPts = pts;
                    auBuffering = true;
                }
                auBuffer.write(data, 0, data.length);
                continue;
            }

            byte[] au;
            long auPtsFinal;
            if (auBuffering) {
                auBuffer.write(data, 0, data.length);
                au = auBuffer.toByteArray();
                auPtsFinal = auPts;
                auBuffer.reset();
                auBuffering = false;
            } else {
                au = data;
                auPtsFinal = pts;
            }
            if (au.length == 0) {
                continue;
            }

            boolean hasIdr = AnnexB.containsIdr(au);
            if (hasIdr && cachedConfig.length > 0 && !AnnexB.containsConfig(au)) {
                au = concat(cachedConfig, au);
            }
            totalBytes.addAndGet(au.length);
            totalFrames.incrementAndGet();
            callback.onAccessUnit(auPtsFinal, au, hasIdr);
        }
        Log.i(TAG, "drain loop exited");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
