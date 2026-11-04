package com.myapp.blecams.protocol;

import java.io.ByteArrayOutputStream;

/**
 * 把 BLE 分包重新组装成完整的视频帧（JPEG 或 H.264 AU）。纯 Java，可在 JVM 单元测试里
 * 跑“分包 -&gt; 组帧”的往返校验。
 *
 * <p>v2 包头没有 chunkIndex，规则简化为：
 * <ul>
 *   <li>{@code FIRST} → 开新缓冲（上一帧若没收尾就作废）；</li>
 *   <li>非 {@code FIRST} 且 seq 与当前缓冲不一致，或没有进行中的缓冲 → 本帧作废；</li>
 *   <li>{@code LAST} → 收口成帧；JPEG 再用 SOI/EOI 校验完整性，坏帧直接丢；</li>
 *   <li>完整帧的 seq 相比上一个完整帧不是 +1 → 判定丢帧，回调
 *       {@link Sink#onStreamGap(int, int)}（AVC 模式据此 flush + 请求 IDR）。</li>
 * </ul>
 */
public class AuReassembler {

    public interface Sink {
        /**
         * 一个完整帧。JPEG 模式下就是整帧 JPEG；AVC 模式下是 Annex-B 访问单元。
         *
         * @param corrupted 结构不完整（例如 JPEG 缺 EOI），上层可选择丢弃
         */
        void onAccessUnit(byte[] frame, boolean keyFrame, boolean hasConfig, int seq);

        /** 检测到帧缺失（服务端丢帧或链路丢包）。 */
        void onStreamGap(int lastSeq, int currentSeq);

        /** 帧完整性校验失败。AVC 模式不会调用。 */
        default void onCorruptFrame(int seq, String reason) {
        }
    }

    private final Sink sink;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 * 1024);
    private final boolean validateJpeg;

    private int curSeq = -1;
    private int lastOrphanSeq = -1;
    private boolean curKey;
    private boolean curConfig;

    private int lastCompleteSeq = -1;
    private long totalFrames;
    private long totalBytes;
    private long droppedFrames;
    private long corruptFrames;

    /** @param validateJpeg JPEG 模式传 true，会用 SOI/EOI 校验整帧完整性 */
    public AuReassembler(Sink sink, boolean validateJpeg) {
        this.sink = sink;
        this.validateJpeg = validateJpeg;
    }

    public AuReassembler(Sink sink) {
        this(sink, true);
    }

    /** GATT 回调线程调用（同一实例不要并发调用）。 */
    public void feed(byte[] packet) {
        StreamProtocol.Header h = StreamProtocol.Header.parse(packet);
        int payloadLen = packet == null ? -1 : packet.length - StreamProtocol.HEADER_LEN;
        if (h == null || payloadLen < 0) {
            droppedFrames++;
            return;
        }

        if (h.isFirst()) {
            if (curSeq >= 0) {
                // 上一帧没有 LAST 就来了新帧：服务端拥塞丢帧或链路丢包
                droppedFrames++;
            }
            buffer.reset();
            curSeq = h.frameSeq;
            lastOrphanSeq = -1;
            curKey = h.isKey();
            curConfig = h.isConfig();
        } else if (curSeq < 0 || h.frameSeq != curSeq) {
            // 帧头那包丢了，或缓冲已被作废：整帧丢弃。同一帧的后续孤儿包只计一次。
            if (h.frameSeq != lastOrphanSeq) {
                droppedFrames++;
                lastOrphanSeq = h.frameSeq;
            }
            buffer.reset();
            curSeq = -1;
            return;
        }

        int dataLen = h.isLast() ? payloadLen - StreamProtocol.CRC_LEN : payloadLen;
        if (dataLen < 0) {
            droppedFrames++;
            buffer.reset();
            curSeq = -1;
            return;
        }
        buffer.write(packet, StreamProtocol.HEADER_LEN, dataLen);
        totalBytes += dataLen;

        if (h.isLast()) {
            byte[] frame = buffer.toByteArray();
            buffer.reset();
            int seq = curSeq;
            boolean key = curKey;
            boolean config = curConfig;
            curSeq = -1;

            int expectedCrc = packet[packet.length - 1] & 0xFF;
            boolean crcOk = StreamProtocol.crc8(frame, 0, frame.length) == expectedCrc;

            if (!crcOk || (validateJpeg && Jpeg.isJpeg(frame) && !Jpeg.isComplete(frame))) {
                // 帧内丢包：结构被破坏，整帧作废（JPEG 下一帧立即可用；AVC 需要 IDR 重同步）
                corruptFrames++;
                sink.onCorruptFrame(seq, crcOk ? "missing EOI" : "crc8 mismatch");
                if (lastCompleteSeq >= 0) {
                    sink.onStreamGap(lastCompleteSeq, seq);
                }
                return;
            }

            if (lastCompleteSeq >= 0 && StreamProtocol.seqDelta(seq, lastCompleteSeq) != 1) {
                sink.onStreamGap(lastCompleteSeq, seq);
            }
            lastCompleteSeq = seq;
            totalFrames++;
            sink.onAccessUnit(frame, key, config, seq);
        }
    }

    /** 丢弃半截帧（解码器 flush 后调用）。 */
    public void discardPartial() {
        if (curSeq >= 0) {
            buffer.reset();
            curSeq = -1;
        }
    }

    public void reset() {
        discardPartial();
        lastCompleteSeq = -1;
        lastOrphanSeq = -1;
    }

    public long getTotalFrames() {
        return totalFrames;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getDroppedFrames() {
        return droppedFrames;
    }

    public long getCorruptFrames() {
        return corruptFrames;
    }
}
