package com.myapp.blecams.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 分包协议 v2（2 字节头 + 每帧 1 字节 CRC8）的往返测试，纯 JVM：
 * {@code ./gradlew :protocol:testDebugUnitTest}
 *
 * <p>覆盖：MTU 换算、分包/组帧往返、帧内丢包被 CRC8 检出、JPEG 缺 EOI 检出、帧号 uint8 回绕、
 * SPS 分辨率与 profile 解析、PPS 的 CABAC 标志、状态文本往返、带宽与单位比特成本预算。
 */
public class StreamProtocolTest {

    /** 模拟服务端分帧：一帧切成若干 notify 包，最后一包带 CRC8 尾。 */
    private static List<byte[]> frameToChunks(byte[] frame, int seq, boolean key, boolean config,
                                              int chunkPayload) {
        List<byte[]> out = new ArrayList<>();
        int count = StreamProtocol.chunkCount(frame.length, chunkPayload);
        for (int i = 0; i < count; i++) {
            int off = i * chunkPayload;
            int len = Math.min(chunkPayload, frame.length - off);
            boolean last = i == count - 1;
            int flags = StreamProtocol.frameFlags(key, config, i == 0, last);
            out.add(last
                    ? StreamProtocol.buildLastChunk(flags, seq, frame, off, len)
                    : StreamProtocol.buildChunk(flags, seq, frame, off, len));
        }
        return out;
    }

    /** 单包帧（FIRST+LAST 同时置位）也要能过 CRC。 */
    private static List<byte[]> singlePacketFrame(byte[] frame, int seq) {
        List<byte[]> out = new ArrayList<>();
        out.add(StreamProtocol.buildLastChunk(
                StreamProtocol.frameFlags(true, true, true, true), seq, frame, 0, frame.length));
        return out;
    }

    private static byte[] fakeAu(int nalBytes, boolean withConfig) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] prefix = {0, 0, 0, 1};
        if (withConfig) {
            out.write(prefix, 0, prefix.length);
            out.write(0x67); // SPS
            for (int i = 0; i < 8; i++) {
                out.write((byte) (i + 1));
            }
            out.write(prefix, 0, prefix.length);
            out.write(0x68); // PPS
            for (int i = 0; i < 4; i++) {
                out.write((byte) (i + 2));
            }
        }
        out.write(prefix, 0, prefix.length);
        out.write(0x65); // IDR slice
        for (int i = 0; i < nalBytes; i++) {
            out.write((byte) (i & 0xFF));
        }
        return out.toByteArray();
    }

    /** 造一个结构合法的 JPEG：SOI + SOS + 数据 + EOI。 */
    private static byte[] fakeJpeg(int bodyBytes) {
        byte[] d = new byte[4 + bodyBytes + 2];
        d[0] = (byte) 0xFF;
        d[1] = (byte) 0xD8; // SOI
        d[2] = (byte) 0xFF;
        d[3] = (byte) 0xDA; // SOS
        for (int i = 0; i < bodyBytes; i++) {
            d[4 + i] = (byte) (i & 0xFF);
        }
        d[d.length - 2] = (byte) 0xFF;
        d[d.length - 1] = (byte) 0xD9; // EOI
        return d;
    }

    private static final class Recorder implements AuReassembler.Sink {
        final List<byte[]> frames = new ArrayList<>();
        final List<Boolean> keys = new ArrayList<>();
        final List<int[]> gaps = new ArrayList<>();
        final List<String> corrupt = new ArrayList<>();

        @Override
        public void onAccessUnit(byte[] frame, boolean keyFrame, boolean hasConfig, int seq) {
            frames.add(frame);
            keys.add(keyFrame);
        }

        @Override
        public void onStreamGap(int lastSeq, int currentSeq) {
            gaps.add(new int[]{lastSeq, currentSeq});
        }

        @Override
        public void onCorruptFrame(int seq, String reason) {
            corrupt.add(seq + ":" + reason);
        }
    }

    private static void feedAll(AuReassembler r, List<byte[]> chunks) {
        for (byte[] c : chunks) {
            r.feed(c);
        }
    }

    @Test
    public void headerIsTwoBytes() {
        assertEquals(2, StreamProtocol.HEADER_LEN);
        byte[] packet = StreamProtocol.buildChunk(
                StreamProtocol.FLAG_KEY | StreamProtocol.FLAG_FIRST, 77, new byte[]{1, 2, 3}, 0, 3);
        assertEquals(5, packet.length);
        StreamProtocol.Header parsed = StreamProtocol.Header.parse(packet);
        assertNotNull(parsed);
        assertEquals(StreamProtocol.VERSION, parsed.version);
        assertEquals(77, parsed.frameSeq);
        assertTrue(parsed.isKey());
        assertTrue(parsed.isFirst());
        assertFalse(parsed.isLast());
        // v1 的 5 字节头（version 位 = 1）必须被拒收，避免新旧两端混用
        assertNull(StreamProtocol.Header.parse(new byte[]{0x12, 0, 1, 0, 2, 3, 4, 5}));
    }

    @Test
    public void mtuToChunkPayload() {
        // MTU 23 -> ATT 载荷 20 -> 减 2 字节头 = 18（v1 只有 15）
        assertEquals(18, StreamProtocol.chunkPayloadForMtu(23));
        assertEquals(60, StreamProtocol.chunkPayloadForMtu(65));
        assertEquals(180, StreamProtocol.chunkPayloadForMtu(185));
        assertEquals(242, StreamProtocol.chunkPayloadForMtu(247));
        assertEquals(StreamProtocol.MAX_CHUNK_PAYLOAD, StreamProtocol.chunkPayloadForMtu(517));
    }

    @Test
    public void roundTripAllMtus() {
        byte[] au = fakeAu(1000, true);
        for (int mtu : new int[]{23, 40, 65, 185, 247}) {
            int payload = StreamProtocol.chunkPayloadForMtu(mtu);
            Recorder rec = new Recorder();
            AuReassembler r = new AuReassembler(rec, false);
            feedAll(r, frameToChunks(au, 0, true, true, payload));
            assertEquals("mtu=" + mtu, 1, rec.frames.size());
            assertArrayEquals(au, rec.frames.get(0));
            assertTrue(rec.keys.get(0));
            assertEquals(0, rec.gaps.size());
            assertEquals(0, rec.corrupt.size());
        }
    }

    @Test
    public void singlePacketFrameRoundTrip() {
        byte[] jpeg = fakeJpeg(20);
        Recorder rec = new Recorder();
        AuReassembler r = new AuReassembler(rec, true);
        feedAll(r, singlePacketFrame(jpeg, 42));
        assertEquals(1, rec.frames.size());
        assertArrayEquals(jpeg, rec.frames.get(0));
    }

    @Test
    public void jpegRoundTrip() {
        byte[] frame = fakeJpeg(900);
        assertTrue(Jpeg.isJpeg(frame));
        assertTrue(Jpeg.isComplete(frame));

        Recorder rec = new Recorder();
        AuReassembler r = new AuReassembler(rec, true);
        feedAll(r, frameToChunks(frame, 3, true, true, StreamProtocol.chunkPayloadForMtu(185)));
        assertEquals(1, rec.frames.size());
        assertArrayEquals(frame, rec.frames.get(0));
    }

    /** 帧内丢中间包：包头里没有 chunkIndex，但 CRC8 尾能检出，整帧作废。 */
    @Test
    public void crcDetectsLostMiddlePacket() {
        int payload = StreamProtocol.chunkPayloadForMtu(185);

        byte[] jpeg = fakeJpeg(900);
        List<byte[]> chunks = frameToChunks(jpeg, 5, true, true, payload);
        assertTrue(chunks.size() >= 4);
        Recorder rec = new Recorder();
        AuReassembler r = new AuReassembler(rec, true);
        r.feed(chunks.get(0));
        for (int i = 2; i < chunks.size(); i++) {
            r.feed(chunks.get(i));
        }
        assertEquals(0, rec.frames.size());
        assertEquals(1, rec.corrupt.size());
        assertTrue(rec.corrupt.get(0).contains("crc8"));

        // AVC 模式同样受益：不依赖 JPEG 自身结构
        byte[] au = fakeAu(1200, true);
        Recorder rec2 = new Recorder();
        AuReassembler r2 = new AuReassembler(rec2, false);
        List<byte[]> avc = frameToChunks(au, 7, true, true, payload);
        r2.feed(avc.get(0));
        for (int i = 2; i < avc.size(); i++) {
            r2.feed(avc.get(i));
        }
        assertEquals(0, rec2.frames.size());
        assertEquals(1, rec2.corrupt.size());
    }

    @Test
    public void jpegWithoutEoiIsCorrupt() {
        byte[] frame = fakeJpeg(600);
        byte[] broken = new byte[frame.length - 2]; // 去掉 EOI
        System.arraycopy(frame, 0, broken, 0, broken.length);
        assertFalse(Jpeg.isComplete(broken));

        Recorder rec = new Recorder();
        AuReassembler r = new AuReassembler(rec, true);
        feedAll(r, frameToChunks(broken, 9, true, true, StreamProtocol.chunkPayloadForMtu(185)));
        assertEquals(0, rec.frames.size());
        assertEquals(1, rec.corrupt.size());
        assertTrue(rec.corrupt.get(0).contains("EOI"));
    }

    @Test
    public void detectsSeqGapAndWrap() {
        assertEquals(1, StreamProtocol.seqDelta(0, 255));
        assertEquals(-1, StreamProtocol.seqDelta(255, 0));
        assertEquals(0, StreamProtocol.seqDelta(100, 100));

        int payload = StreamProtocol.chunkPayloadForMtu(185);
        Recorder rec = new Recorder();
        AuReassembler r = new AuReassembler(rec, false);
        feedAll(r, frameToChunks(fakeAu(50, true), 254, true, true, payload));
        feedAll(r, frameToChunks(fakeAu(50, false), 255, false, false, payload));
        // 跳过 0，直接来 1
        feedAll(r, frameToChunks(fakeAu(50, true), 1, true, true, payload));
        assertEquals(3, rec.frames.size());
        assertEquals(1, rec.gaps.size());
        assertEquals(255, rec.gaps.get(0)[0]);
        assertEquals(1, rec.gaps.get(0)[1]);
    }

    @Test
    public void missingFirstDiscardsFrame() {
        int payload = StreamProtocol.chunkPayloadForMtu(65);
        List<byte[]> chunks = frameToChunks(fakeAu(400, true), 0, true, true, payload);
        assertTrue(chunks.size() > 2);

        Recorder rec = new Recorder();
        AuReassembler r = new AuReassembler(rec, false);
        for (int i = 1; i < chunks.size(); i++) {
            r.feed(chunks.get(i)); // 带 FIRST 的那一包没收到
        }
        assertEquals(0, rec.frames.size());
        assertEquals(1, r.getDroppedFrames());
    }

    @Test
    public void truncatedFrameThenNewFrameStartsClean() {
        int payload = StreamProtocol.chunkPayloadForMtu(65);
        List<byte[]> first = frameToChunks(fakeAu(400, true), 0, true, true, payload);

        Recorder rec = new Recorder();
        AuReassembler r = new AuReassembler(rec, false);
        r.feed(first.get(0));
        r.feed(first.get(1)); // 没有 LAST：服务端丢帧
        byte[] au2 = fakeAu(300, true);
        feedAll(r, frameToChunks(au2, 1, true, true, payload));
        assertEquals(1, rec.frames.size());
        assertArrayEquals(au2, rec.frames.get(0));
    }

    @Test
    public void annexBDetection() {
        byte[] key = fakeAu(64, true);
        assertTrue(AnnexB.containsIdr(key));
        assertTrue(AnnexB.containsConfig(key));
        assertEquals(3, AnnexB.split(key).size());
        assertFalse(AnnexB.containsConfig(fakeAu(64, false)));
        assertNotNull(AnnexB.firstRawNal(key, AnnexB.NAL_SPS));
        assertNotNull(AnnexB.firstRawNal(key, AnnexB.NAL_PPS));
    }

    /** 构造一个 320x240 的 baseline SPS 位流，验证分辨率与 profile 解析。 */
    @Test
    public void parseSpsResolution() {
        String bits = "01000010"          // profile_idc = 66 (baseline)
                + "00000000"              // constraint flags
                + "00011110"              // level_idc = 30
                + "1"                     // seq_parameter_set_id = 0
                + "1"                     // log2_max_frame_num_minus4 = 0
                + "1"                     // pic_order_cnt_type = 0
                + "1"                     // log2_max_pic_order_cnt_lsb_minus4 = 0
                + "1"                     // max_num_ref_frames = 0
                + "0"                     // gaps_in_frame_num_value_allowed_flag
                + "000010100"             // pic_width_in_mbs_minus1 = 19  -> 320
                + "0001111"               // pic_height_in_map_units_minus1 = 14 -> 240
                + "1"                     // frame_mbs_only_flag
                + "1"                     // direct_8x8_inference_flag
                + "0"                     // frame_cropping_flag
                + "0"                     // vui_parameters_present_flag
                + "1";                    // rbsp_stop_one_bit
        byte[] payload = fromBitString(bits);

        byte[] raw = new byte[1 + payload.length];
        raw[0] = 0x67; // NAL header, type 7 (SPS)
        System.arraycopy(payload, 0, raw, 1, payload.length);
        int[] size = AnnexB.parseSize(raw);
        assertNotNull(size);
        assertEquals(320, size[0]);
        assertEquals(240, size[1]);
        assertEquals(66, AnnexB.profileIdc(raw));

        // 3 字节起始码同样要能解析
        byte[] annex = new byte[3 + raw.length];
        annex[2] = 1;
        System.arraycopy(raw, 0, annex, 3, raw.length);
        int[] size2 = AnnexB.parseSize(annex);
        assertNotNull(size2);
        assertEquals(320, size2[0]);
        assertEquals(240, size2[1]);
    }

    /** entropy_coding_mode_flag 在 PPS 里，用来确认“Main+CABAC 到底生效没有”。 */
    @Test
    public void parsePpsEntropyMode() {
        // ue(pps_id)=0 -> "1"; ue(sps_id)=0 -> "1"; 第三位是 entropy_coding_mode_flag
        byte[] cabacNal = {0x68, fromBitString("11100000")[0]};
        assertEquals(Boolean.TRUE, AnnexB.isCabac(annexOf(cabacNal)));

        byte[] cavlcNal = {0x68, fromBitString("11000000")[0]};
        assertEquals(Boolean.FALSE, AnnexB.isCabac(annexOf(cavlcNal)));

        // 没有 PPS 时返回 null，不做瞎猜
        assertNull(AnnexB.isCabac(fakeAu(20, false)));
    }

    private static byte[] annexOf(byte[] nalWithHeader) {
        byte[] out = new byte[4 + nalWithHeader.length];
        out[3] = 1;
        System.arraycopy(nalWithHeader, 0, out, 4, nalWithHeader.length);
        return out;
    }

    private static byte[] fromBitString(String bits) {
        int len = (bits.length() + 7) / 8;
        byte[] out = new byte[len];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) == '1') {
                out[i / 8] |= (byte) (0x80 >>> (i % 8));
            }
        }
        return out;
    }

    @Test
    public void statusTextRoundTrip() {
        StatusInfo info = new StatusInfo();
        info.state = StatusInfo.STATE_STREAMING;
        info.profileId = 2;
        info.resolution = "320x240";
        info.fps = 10;
        info.bitrate = 60000;
        info.mtu = 247;
        info.chunkSize = 242;
        info.throughput = 7300;
        java.util.Map<String, String> parsed = StatusInfo.parse(info.serialize());
        assertEquals(StatusInfo.STATE_STREAMING, parsed.get("st"));
        assertEquals("320x240", parsed.get("res"));
        assertEquals(247, StatusInfo.intOf(parsed, "mtu", 0));
        assertEquals(242, StatusInfo.intOf(parsed, "cks", 0));
        assertEquals(7300, StatusInfo.intOf(parsed, "thr", 0));
        assertTrue(info.serializeBytes().length <= 240);
    }

    @Test
    public void profileBandwidthBudget() {
        // 60kbps = 7.5kB/s；MTU 23（18B/包）要 417 包/s，7.5ms 间隔最多 ~133 包/s -> 不可能
        assertEquals(7500, VideoProfile.BALANCED.bytesPerSecond());
        int atMtu23 = (int) Math.ceil(VideoProfile.BALANCED.bytesPerSecond()
                / (double) StreamProtocol.chunkPayloadForMtu(23));
        assertTrue(atMtu23 > 133);
        // MTU 247 时 31 包/s，可行
        int atMtu247 = (int) Math.ceil(VideoProfile.BALANCED.bytesPerSecond()
                / (double) StreamProtocol.chunkPayloadForMtu(247));
        assertTrue(atMtu247 <= 40);
        // ULTRA_LOW 是 MTU 23 唯一跑得动的档
        int ultraLow = (int) Math.ceil(VideoProfile.ULTRA_LOW.bytesPerSecond()
                / (double) StreamProtocol.chunkPayloadForMtu(23));
        assertTrue(ultraLow <= 133);
    }

    /** 单位比特成本：说明“降分辨率/降帧率”比换编码格式的杠杆大得多。 */
    @Test
    public void bitsPerPixelBudget() {
        double bpp320at10 = bitsPerPixel(60_000, 320, 240, 10);
        double bpp320at5 = bitsPerPixel(60_000, 320, 240, 5);
        double bpp160at10 = bitsPerPixel(60_000, 160, 120, 10);
        assertTrue(bpp320at10 < 0.09);            // 0.078 bpp，几乎不可看
        assertEquals(0.156, bpp320at5, 0.005);    // 帧率减半 -> 每帧可用比特翻倍
        assertEquals(4 * bpp320at10, bpp160at10, 0.0001); // 边长减半 -> 每像素比特正好 4 倍
    }

    private static double bitsPerPixel(int bitrate, int w, int h, int fps) {
        return bitrate / (double) (w * h * fps);
    }
}
