package com.myapp.blecams.receiver.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.myapp.blecams.protocol.AnnexB;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * H.264 硬解码到 Surface。
 *
 * <p>约定：AU 是 Annex-B 字节流（带起始码）。Android 的 AVC 解码器接受“整段 Annex-B 的
 * 一个 AU”作为一个输入 buffer；SPS/PPS 另外以<b>裸 NAL（无起始码）</b> + {@code
 * BUFFER_FLAG_CODEC_CONFIG} 喂入，两者都发可以兼容大多数 Vendor 实现。
 *
 * <p>分辨率来自第一个关键帧的 SPS 解析；检测到丢帧时 {@link #flushAfterGap()} 清空解码器，
 * 等下一个关键帧再恢复。
 */
public class AvcDecoder {

    private static final String TAG = "AvcDecoder";

    public interface Listener {
        void onResolution(int width, int height);

        void onDecodedFrame();

        void onError(String message);
    }

    private static final class Au {
        final byte[] data;
        final boolean key;

        Au(byte[] data, boolean key) {
            this.data = data;
            this.key = key;
        }
    }

    private final Listener listener;
    private final BlockingQueue<Au> queue = new ArrayBlockingQueue<>(4);

    private Thread thread;
    private MediaCodec codec;
    private volatile Surface surface;
    private int width;
    private int height;
    private volatile boolean running;
    private volatile boolean flushRequested;
    private long ptsCounter;

    public AvcDecoder(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::decodeLoop, "avc-decode");
        thread.start();
    }

    public void setSurface(Surface s) {
        surface = s;
        if (s == null) {
            stopDecoder();
            return;
        }
        // MediaCodec.setOutputSurface 只在“创建时就带了 Surface”时可用，
        // 这里统一用“下一个关键帧重建解码器”的方式换面，兼容性最稳。
        width = 0;
        height = 0;
        flushRequested = true;
    }

    public void submit(byte[] au, boolean key) {
        if (!running || au == null) {
            return;
        }
        Au item = new Au(au, key);
        if (!queue.offer(item)) {
            // 解码跟不上：丢最老的一帧，并标记需要重新同步
            Au oldest = queue.poll();
            if (oldest != null && !oldest.key) {
                flushRequested = true;
            }
            queue.offer(item);
        }
    }

    /** 丢帧后调用：清空解码器，等待下一个关键帧。 */
    public void flushAfterGap() {
        flushRequested = true;
        queue.clear();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        stopDecoder();
    }

    /** 优先硬件解码器。 */
    private static String findDecoderName() {
        String hardware = null;
        String any = null;
        try {
            for (MediaCodecInfo info : new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                        continue;
                    }
                    String n = info.getName().toLowerCase();
                    boolean software = n.contains("google") || n.contains("soft") || n.contains("cx2");
                    if (any == null) {
                        any = info.getName();
                    }
                    if (!software && hardware == null) {
                        hardware = info.getName();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enumerate decoders failed", e);
        }
        return hardware != null ? hardware : any;
    }

    private void decodeLoop() {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                if (flushRequested) {
                    flushRequested = false;
                    queue.clear();
                    MediaCodec c = codec;
                    if (c != null) {
                        try {
                            c.flush();
                        } catch (Exception ignored) {
                        }
                    }
                }
                Au au = queue.poll(20, TimeUnit.MILLISECONDS);
                if (au != null) {
                    if (au.key) {
                        int[] size = AnnexB.parseSize(au.data);
                        if (size == null) {
                            Log.w(TAG, "key au without parsable sps");
                        } else if (codec == null || size[0] != width || size[1] != height) {
                            createDecoder(size[0], size[1]);
                        }
                        if (codec != null) {
                            feedCodecConfig(au.data);
                            feedAu(au.data);
                        }
                    } else if (codec != null) {
                        feedAu(au.data);
                    }
                }
                drain(info);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            } catch (Exception e) {
                Log.e(TAG, "decode loop error", e);
                listener.onError("解码异常：" + e.getMessage());
                stopDecoder();
            }
        }
        stopDecoder();
    }

    private void createDecoder(int w, int h) {
        stopDecoder();
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            String name = findDecoderName();
            MediaCodec c = name == null
                    ? MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    : MediaCodec.createByCodecName(name);
            c.configure(fmt, surface, null, 0);
            c.start();
            codec = c;
            width = w;
            height = h;
            listener.onResolution(w, h);
            Log.i(TAG, "decoder created " + w + "x" + h + " by " + name);
        } catch (Exception e) {
            Log.e(TAG, "createDecoder failed", e);
            listener.onError("创建解码器失败：" + e.getMessage());
            codec = null;
        }
    }

    private void stopDecoder() {
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try {
                c.stop();
            } catch (Exception ignored) {
            }
            try {
                c.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** SPS/PPS 以裸 NAL + CODEC_CONFIG 喂入。 */
    private void feedCodecConfig(byte[] au) {
        byte[] sps = AnnexB.firstRawNal(au, AnnexB.NAL_SPS);
        if (sps != null) {
            queueConfig(sps);
        }
        byte[] pps = AnnexB.firstRawNal(au, AnnexB.NAL_PPS);
        if (pps != null) {
            queueConfig(pps);
        }
    }

    private void queueConfig(byte[] rawNal) {
        MediaCodec c = codec;
        if (c == null) {
            return;
        }
        int idx = c.dequeueInputBuffer(20_000);
        if (idx < 0) {
            return;
        }
        ByteBuffer b = c.getInputBuffer(idx);
        if (b == null) {
            return;
        }
        b.clear();
        b.put(rawNal);
        c.queueInputBuffer(idx, 0, rawNal.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
    }

    private void feedAu(byte[] au) {
        MediaCodec c = codec;
        if (c == null) {
            return;
        }
        int idx = c.dequeueInputBuffer(20_000);
        if (idx < 0) {
            Log.w(TAG, "no input buffer, au dropped");
            return;
        }
        ByteBuffer b = c.getInputBuffer(idx);
        if (b == null) {
            return;
        }
        b.clear();
        b.put(au);
        c.queueInputBuffer(idx, 0, au.length, nextPts(), 0);
    }

    private long nextPts() {
        return ++ptsCounter * 1000L;
    }

    private void drain(MediaCodec.BufferInfo info) {
        MediaCodec c;
        synchronized (this) {
            c = codec;
        }
        if (c == null) {
            return;
        }
        while (running) {
            int idx = c.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER
                    || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                break;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                break;
            }
            if (idx < 0) {
                break;
            }
            Surface s = surface;
            c.releaseOutputBuffer(idx, s != null);
            listener.onDecodedFrame();
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
        }
    }
}
