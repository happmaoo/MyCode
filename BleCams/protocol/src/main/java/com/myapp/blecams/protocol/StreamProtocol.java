package com.myapp.blecams.protocol;

/**
 * BLE 视频分包协议 v2。
 *
 * <p>v2 相比 v1 把包头从 5 字节压到 2 字节：MTU 247 时每包可用载荷从 239 → 245 字节
 * （空口有效载荷率 +2.5%）。代价是包头里不再有 chunkIndex，帧内丢包不再能逐包定位：
 * <ul>
 *   <li>JPEG 模式：每帧自包含，靠 SOI/EOI 校验完整性，坏帧直接丢，下一帧立刻可用；</li>
 *   <li>AVC 模式：帧级 seq 跳号仍能检出丢帧并触发 IDR 重同步，最坏情况是花屏一个 GOP。</li>
 * </ul>
 *
 * <p>一条视频帧（JPEG 或一个 H.264 AU）被切成若干 notify 包，每包载荷：
 *
 * <pre>
 *  byte 0 : [7:4] version(=2)  [3:0] flags
 *  byte 1 : frameSeq (uint8，回绕 mod 256)
 *  byte 2.. : 帧数据切片
 *  最后一包的末尾再放 1 字节：整帧 CRC8(poly 0x07)
 *
 *  flags: 0x08 KEY      帧内关键帧（JPEG 恒为 1）
 *         0x04 CONFIG    帧首带解码配置（JPEG=APPn/量化表；AVC=SPS/PPS）
 *         0x02 FIRST    本帧第 1 包
 *         0x01 LAST     本帧最后 1 包（带 CRC 尾）
 * </pre>
 *
 * <p>接收端不需要长度表也不需要分包序号：FIRST 开新缓冲、LAST 收口并验 CRC、seq 变化即作废旧缓冲。
 * 漏检概率 1/256，比 v1 每包 2 字节的 chunkIndex 更省（每帧 1 字节 vs 每包 2 字节）。
 */
public final class StreamProtocol {

    private StreamProtocol() {
    }

    public static final int VERSION = 2;

    /** 包头长度（v2 = 2 字节）。 */
    public static final int HEADER_LEN = 2;

    /** ATT 开销：opcode(1) + handle(2)。notify 载荷上限 = negotiatedMtu - 3。 */
    public static final int ATT_OVERHEAD = 3;

    /** 单包数据上限：再大就会让单帧分包过少，帧间抖动难以抹平。 */
    public static final int MAX_CHUNK_PAYLOAD = 245;

    /** MTU=23 时 BLE 4.0 的默认 ATT 载荷。 */
    public static final int FALLBACK_MTU = 23;

    public static final int FLAG_KEY = 0x08;
    public static final int FLAG_CONFIG = 0x04;
    public static final int FLAG_FIRST = 0x02;
    public static final int FLAG_LAST = 0x01;

    // ---- 控制通道命令（客户端 -> 服务端，写在 CHAR_CONTROL 上） ----
    /** 0x01 请求 IDR（AVC 模式）/ 请求立即出帧（JPEG 模式）。 */
    public static final byte CMD_REQUEST_IDR = 0x01;
    /** 0x02 开始推流。 */
    public static final byte CMD_START = 0x02;
    /** 0x03 停止推流。 */
    public static final byte CMD_STOP = 0x03;
    /** 0x04 切换档位，参数 1 字节 profileId。 */
    public static final byte CMD_SET_PROFILE = 0x04;
    /** 0x05 设置码率，参数 4 字节 uint32 bps。 */
    public static final byte CMD_SET_BITRATE = 0x05;
    /** 0x06 设置帧率，参数 1 字节 fps。 */
    public static final byte CMD_SET_FPS = 0x06;
    /** 0x07 心跳。 */
    public static final byte CMD_PING = 0x07;
    /**
     * 0x08 上报客户端协商到的 MTU，参数 2 字节 uint16。
     * <p>Android 的 GATT <b>服务端</b>在 API 33 之前拿不到可靠协商值，所以分包大小由客户端上报决定。
     */
    public static final byte CMD_CLIENT_MTU = 0x08;
    /** 0x09 上报客户端实测接收吞吐，参数 4 字节 uint32 B/s。 */
    public static final byte CMD_CLIENT_STATS = 0x09;
    /** 0x0A 切换编码格式，参数 1 字节：0=H.264/AVC，1=JPEG。 */
    public static final byte CMD_SET_CODEC = 0x0A;
    /** 0x0B JPEG 质量下限/上限，参数 2 字节：min(高) max(低)，服务端在此区间自适应。 */
    public static final byte CMD_SET_QUALITY_RANGE = 0x0B;

    public static final int CODEC_AVC = 0;
    public static final int CODEC_JPEG = 1;

    public static final int MTU_MIN = 23;
    public static final int MTU_MAX = 517;

    /** 按 MTU 计算每包可携带的帧字节数。 */
    public static int chunkPayloadForMtu(int mtu) {
        int payload = mtu - ATT_OVERHEAD - HEADER_LEN;
        if (payload < 1) {
            payload = 1;
        }
        if (payload > MAX_CHUNK_PAYLOAD) {
            payload = MAX_CHUNK_PAYLOAD;
        }
        return payload;
    }

    /** 分包数量：向上取整。 */
    public static int chunkCount(int frameLen, int chunkPayload) {
        if (frameLen <= 0) {
            return 0;
        }
        return (frameLen + chunkPayload - 1) / chunkPayload;
    }

    /** 组 2 字节包头。 */
    public static byte[] header(int flags, int frameSeq) {
        return new byte[]{
                (byte) (((VERSION & 0x0F) << 4) | (flags & 0x0F)),
                (byte) (frameSeq & 0xFF)
        };
    }

    /** 组装一个完整 notify 载荷（非最后一包）。 */
    public static byte[] buildChunk(int flags, int frameSeq, byte[] src, int off, int len) {
        byte[] out = new byte[HEADER_LEN + len];
        byte[] h = header(flags, frameSeq);
        out[0] = h[0];
        out[1] = h[1];
        System.arraycopy(src, off, out, HEADER_LEN, len);
        return out;
    }

    /** 单包帧数据容量上限（最后一包要再留 {@link #CRC_LEN} 字节放校验码）。 */
    public static final int CRC_LEN = 1;

    /**
     * 组装**最后一包**：载荷尾部追加 1 字节整帧 CRC8。
     *
     * <p>为什么用帧级 CRC 而不是 v1 的每包 chunkIndex：chunkIndex 每包都要 2 字节，
     * 而 CRC8 每帧只花 1 字节（256 分之一漏检概率），却能检出“帧内丢中间包”这种
     * 靠 FIRST/LAST 边界根本看不出来的损坏。
     */
    public static byte[] buildLastChunk(int flags, int frameSeq, byte[] wholeFrame,
                                        int off, int len) {
        byte[] out = new byte[HEADER_LEN + len + CRC_LEN];
        out[0] = (byte) (((VERSION & 0x0F) << 4) | (flags & 0x0F));
        out[1] = (byte) (frameSeq & 0xFF);
        System.arraycopy(wholeFrame, off, out, HEADER_LEN, len);
        out[out.length - 1] = (byte) crc8(wholeFrame, 0, wholeFrame.length);
        return out;
    }

    /** CRC-8 (poly 0x07, init 0x00)。 */
    public static int crc8(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc & 0xFF;
    }

    /** 帧首尾标志：JPEG 模式用来给包打 KEY/CONFIG 位。 */
    public static int frameFlags(boolean key, boolean config, boolean first, boolean last) {
        return (key ? FLAG_KEY : 0)
                | (config ? FLAG_CONFIG : 0)
                | (first ? FLAG_FIRST : 0)
                | (last ? FLAG_LAST : 0);
    }

    public static int u8(int b) {
        return b & 0xFF;
    }

    public static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | (long) (b[off + 3] & 0xFF);
    }

    public static byte[] be16(int v) {
        return new byte[]{(byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    public static byte[] be32(long v) {
        return new byte[]{
                (byte) ((v >>> 24) & 0xFF),
                (byte) ((v >>> 16) & 0xFF),
                (byte) ((v >>> 8) & 0xFF),
                (byte) (v & 0xFF)
        };
    }

    /** uint8 序号差（处理回绕），结果范围 [-128, 127]。 */
    public static int seqDelta(int a, int b) {
        int d = (a - b) & 0xFF;
        return d > 0x7F ? d - 0x100 : d;
    }

    /** 解析出来的包头。 */
    public static final class Header {
        public final int version;
        public final int flags;
        public final int frameSeq;

        private Header(int version, int flags, int frameSeq) {
            this.version = version;
            this.flags = flags;
            this.frameSeq = frameSeq;
        }

        public boolean isKey() {
            return (flags & FLAG_KEY) != 0;
        }

        public boolean isConfig() {
            return (flags & FLAG_CONFIG) != 0;
        }

        public boolean isFirst() {
            return (flags & FLAG_FIRST) != 0;
        }

        public boolean isLast() {
            return (flags & FLAG_LAST) != 0;
        }

        /** 数据部分起始偏移。 */
        public int payloadOffset() {
            return HEADER_LEN;
        }

        /** @return 长度不足或版本不匹配返回 null。 */
        public static Header parse(byte[] packet) {
            if (packet == null || packet.length < HEADER_LEN) {
                return null;
            }
            int version = (packet[0] >>> 4) & 0x0F;
            if (version != VERSION) {
                return null;
            }
            return new Header(version, packet[0] & 0x0F, packet[1] & 0xFF);
        }
    }

    /** 控制命令构造辅助。 */
    public static byte[] cmd(byte id) {
        return new byte[]{id};
    }

    public static byte[] cmd(byte id, int arg8) {
        return new byte[]{id, (byte) (arg8 & 0xFF)};
    }

    public static byte[] cmdU16(byte id, int v16) {
        byte[] b = be16(v16);
        return new byte[]{id, b[0], b[1]};
    }

    public static byte[] cmdU32(byte id, long v32) {
        byte[] b = be32(v32);
        return new byte[]{id, b[0], b[1], b[2], b[3]};
    }

    public static byte[] cmd2(byte id, int a, int b) {
        return new byte[]{id, (byte) (a & 0xFF), (byte) (b & 0xFF)};
    }
}
