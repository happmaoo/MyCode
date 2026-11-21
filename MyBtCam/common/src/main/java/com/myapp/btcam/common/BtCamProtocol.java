package com.myapp.btcam.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.UUID;

/**
 * MyBtCamS / MyBtCamC 共用的经典蓝牙 SPP 传输协议。
 *
 * 报文结构（9 字节头 + payload）：
 *   magic(2, 0xB7C1) | version(1) | type(1) | flags(1) | payloadLength(4)
 *
 * 之所以自定义报文头，是因为 RFCOMM 的 InputStream 是不带边界的字节流，
 * 视频帧必须自己成帧，否则客户端无法区分「一帧 H.264」到哪里结束。
 *
 * 帧序号 seq 是保证「丢帧后解码不乱」的关键：
 * 服务端对每一帧编码结果分配连续 seq，无论这帧最终有没有发出去；
 * 客户端发现 seq 断裂，就丢弃后续非 IDR 帧、等待下一个 IDR 重新同步，
 * 避免丢掉中间 P 帧导致一整段画面花屏。
 */
public final class BtCamProtocol {

    private BtCamProtocol() {
    }

    /** 自定义 SPP UUID，两个 APP 必须一致（配对关系与此无关）。 */
    public static final UUID SPP_UUID =
            UUID.fromString("6b1c2a90-3f5a-11e6-b4b7-0002a5d5c51b");

    /** RFCOMM 服务记录名，会出现在 SDP 记录里。 */
    public static final String SERVICE_NAME = "MyBtCamS";

    public static final short MAGIC = (short) 0xB7C1;
    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 9;
    /** 视频 payload 头部：seq(4) + 采集时间戳(4) */
    public static final int VIDEO_HEAD_SIZE = 8;

    /** 客户端 -> 服务端：请求开流（含分辨率/码率/摄像头/方向）。 */
    public static final byte T_HELLO = 1;
    /** 服务端 -> 客户端：握手应答。 */
    public static final byte T_HELLO_ACK = 2;
    /** 服务端 -> 客户端：编码器配置（宽高 + SPS/PPS）。 */
    public static final byte T_CONFIG = 3;
    /** 服务端 -> 客户端：一帧 H.264 数据。 */
    public static final byte T_VIDEO = 4;
    /** 客户端 -> 服务端：停止推流（保持连接）。 */
    public static final byte T_STOP = 5;
    /** 客户端 -> 服务端：心跳。 */
    public static final byte T_PING = 6;
    /** 服务端 -> 客户端：心跳应答。 */
    public static final byte T_PONG = 7;
    /** 客户端 -> 服务端：运行中修改参数（payload 同 HELLO）。 */
    public static final byte T_PARAM = 8;
    /** 服务端 -> 客户端：状态/错误文本。 */
    public static final byte T_STATUS = 9;
    /** 客户端要求立即插入一个 IDR（丢帧/断开重连后快速恢复画面用）。 */
    public static final byte T_IDR_REQ = 10;

    public static final int FLAG_IDR = 0x01;

    public static final int FLAG_STATUS_INFO = 0;
    public static final int FLAG_STATUS_ERROR = 1;
    public static final int FLAG_STATUS_STREAMING = 2;

    public static final int CAMERA_AUTO = 0;
    public static final int CAMERA_BACK = 1;
    public static final int CAMERA_FRONT = 2;

    public static final int RESULT_OK = 1;
    public static final int RESULT_ERROR = 0;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static class Header {
        public byte type;
        public int flags;
        public int length;
    }

    public static class Request {
        public int width;
        public int height;
        public int bitrate;
        public int camera;
        public int rotation;
    }

    public static class Config {
        public int width;
        public int height;
        public byte[] sps;
        public byte[] pps;
    }

    /* ------------------------------------------------------------------ */
    /* 写                                                                  */
    /* ------------------------------------------------------------------ */

    /** T_PING payload：客户端当前时钟（毫秒，32 位环形） */
    public static byte[] pingPayload(long tsMs) {
        int v = (int) tsMs;
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** 解析 T_PING，取客户端时钟；payload 非法返回 Integer.MIN_VALUE */
    public static int parsePingTs(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return Integer.MIN_VALUE;
        }
        return readInt(payload, 0);
    }

    /** T_PONG payload：客户端时钟(4) + 服务端收到时刻(4) */
    public static byte[] pongPayload(int clientTs, long serverTsMs) {
        int sv = (int) serverTsMs;
        int cv = clientTs == Integer.MIN_VALUE ? 0 : clientTs;
        return new byte[]{(byte) (cv >>> 24), (byte) (cv >>> 16), (byte) (cv >>> 8), (byte) cv,
                (byte) (sv >>> 24), (byte) (sv >>> 16), (byte) (sv >>> 8), (byte) sv};
    }

    public static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static void writeHeader(OutputStream out, int type, int flags, int payloadLength)
            throws IOException {
        byte[] h = new byte[HEADER_SIZE];
        h[0] = (byte) (MAGIC >> 8);
        h[1] = (byte) MAGIC;
        h[2] = VERSION;
        h[3] = (byte) type;
        h[4] = (byte) flags;
        h[5] = (byte) (payloadLength >>> 24);
        h[6] = (byte) (payloadLength >>> 16);
        h[7] = (byte) (payloadLength >>> 8);
        h[8] = (byte) payloadLength;
        out.write(h);
    }

    public static void writeMessage(OutputStream out, int type, int flags, byte[] payload)
            throws IOException {
        writeHeader(out, type, flags, payload == null ? 0 : payload.length);
        if (payload != null && payload.length > 0) {
            out.write(payload);
        }
    }

    public static void writeRequest(OutputStream out, int type, Request r) throws IOException {
        writeMessage(out, type, 0, requestPayload(r));
    }

    public static byte[] requestPayload(Request r) {
        byte[] p = new byte[12];
        p[0] = (byte) (r.width >> 8);
        p[1] = (byte) r.width;
        p[2] = (byte) (r.height >> 8);
        p[3] = (byte) r.height;
        p[4] = (byte) (r.bitrate >>> 24);
        p[5] = (byte) (r.bitrate >>> 16);
        p[6] = (byte) (r.bitrate >>> 8);
        p[7] = (byte) r.bitrate;
        p[8] = (byte) r.camera;
        p[9] = (byte) r.rotation;
        return p;
    }

    public static byte[] statusPayload(String text) {
        return text == null ? new byte[0] : text.getBytes(UTF8);
    }

    public static byte[] ackPayload(int result, String reason) {
        byte[] r = reason == null ? new byte[0] : reason.getBytes(UTF8);
        byte[] p = new byte[1 + r.length];
        p[0] = (byte) result;
        System.arraycopy(r, 0, p, 1, r.length);
        return p;
    }

    /* ------------------------------------------------------------------ */
    /* 读                                                                  */
    /* ------------------------------------------------------------------ */

    public static Header readHeader(InputStream in) throws IOException {
        byte[] h = new byte[HEADER_SIZE];
        readFully(in, h, h.length);
        if ((short) (((h[0] & 0xFF) << 8) | (h[1] & 0xFF)) != MAGIC) {
            throw new IOException("bad magic");
        }
        if (h[2] != VERSION) {
            throw new IOException("bad version: " + h[2]);
        }
        Header header = new Header();
        header.type = h[3];
        header.flags = h[4] & 0xFF;
        header.length = ((h[5] & 0xFF) << 24) | ((h[6] & 0xFF) << 16)
                | ((h[7] & 0xFF) << 8) | (h[8] & 0xFF);
        if (header.length < 0) {
            throw new IOException("bad length");
        }
        return header;
    }

    public static byte[] readPayload(InputStream in, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] data = new byte[length];
        readFully(in, data, length);
        return data;
    }

    public static void readFully(InputStream in, byte[] dest, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(dest, offset, length - offset);
            if (read < 0) {
                throw new IOException("stream closed");
            }
            offset += read;
        }
    }

    public static Request parseRequest(byte[] payload) {
        Request r = new Request();
        if (payload == null || payload.length < 10) {
            return r;
        }
        r.width = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        r.height = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        r.bitrate = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16)
                | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        r.camera = payload[8];
        r.rotation = payload[9];
        return r;
    }

    public static byte[] configPayload(int width, int height, byte[] sps, byte[] pps) {
        int sl = sps == null ? 0 : sps.length;
        int pl = pps == null ? 0 : pps.length;
        byte[] p = new byte[8 + sl + pl];
        p[0] = (byte) (width >> 8);
        p[1] = (byte) width;
        p[2] = (byte) (height >> 8);
        p[3] = (byte) height;
        p[4] = (byte) (sl >> 8);
        p[5] = (byte) sl;
        p[6] = (byte) (pl >> 8);
        p[7] = (byte) pl;
        if (sl > 0) {
            System.arraycopy(sps, 0, p, 8, sl);
        }
        if (pl > 0) {
            System.arraycopy(pps, 0, p, 8 + sl, pl);
        }
        return p;
    }

    public static Config parseConfig(byte[] payload) {
        Config c = new Config();
        if (payload == null || payload.length < 8) {
            return c;
        }
        c.width = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        c.height = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        int sl = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
        int pl = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        if (sl > 0 && 8 + sl <= payload.length) {
            c.sps = new byte[sl];
            System.arraycopy(payload, 8, c.sps, 0, sl);
        }
        if (pl > 0 && 8 + sl + pl <= payload.length) {
            c.pps = new byte[pl];
            System.arraycopy(payload, 8 + sl, c.pps, 0, pl);
        }
        return c;
    }

    public static Ack parseAck(byte[] payload) {
        Ack r = new Ack();
        if (payload == null || payload.length < 1) {
            return r;
        }
        r.result = payload[0];
        if (payload.length > 1) {
            r.reason = new String(payload, 1, payload.length - 1, UTF8);
        }
        return r;
    }

    public static class Ack {
        public int result;
        public String reason = "";
        public boolean isOk() {
            return result == RESULT_OK;
        }
    }

    public static String textOf(byte[] payload) {
        return payload == null ? "" : new String(payload, UTF8);
    }
}
