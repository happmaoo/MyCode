package com.myapp.blecams.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * CHAR_STATUS（read）的载荷：一行 key=value 文本，便于调试时用 nRF Connect / adb 直接看懂。
 *
 * <p>示例：{@code st=streaming;prof=2;res=320x240;fps=10;br=60000;mtu=247;cks=239;seq=1024;thr=7300;drop=3}
 */
public final class StatusInfo {

    /** 服务状态。 */
    public static final String STATE_IDLE = "idle";
    public static final String STATE_ADVERTISING = "advertising";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_STREAMING = "streaming";
    public static final String STATE_ERROR = "error";

    public volatile String state = STATE_IDLE;
    public volatile int profileId = VideoProfile.DEFAULT.id;
    /** 实际编码分辨率（相机不支持目标尺寸时会自动就近选择）。 */
    public volatile String resolution = "0x0";
    public volatile int fps;
    public volatile int bitrate;
    public volatile int mtu;
    public volatile int chunkSize;
    public volatile int lastSeq;
    /** 链路实测吞吐，单位字节/秒。 */
    public volatile int throughput;
    /** 因链路拥塞被丢弃的帧累计数。 */
    public volatile long droppedFrames;
    /** 待发队列长度，单位字节。 */
    public volatile int queueBytes;
    /** 实际码流 profile_idc（66=Baseline,77=Main,100=High），-1 = 未知。 */
    public volatile int profileIdc = -1;
    /** 实际熵编码：1=CABAC，0=CAVLC，-1 = 未知。用来验证 KEY_PROFILE 有没有真被编码器采纳。 */
    public volatile int cabac = -1;

    public String serialize() {
        StringBuilder sb = new StringBuilder(128);
        append(sb, "st", state);
        append(sb, "prof", String.valueOf(profileId));
        append(sb, "res", resolution);
        append(sb, "fps", String.valueOf(fps));
        append(sb, "br", String.valueOf(bitrate));
        append(sb, "mtu", String.valueOf(mtu));
        append(sb, "cks", String.valueOf(chunkSize));
        append(sb, "seq", String.valueOf(lastSeq));
        append(sb, "thr", String.valueOf(throughput));
        append(sb, "drop", String.valueOf(droppedFrames));
        append(sb, "q", String.valueOf(queueBytes));
        if (profileIdc >= 0) {
            append(sb, "pr", String.valueOf(profileIdc));
        }
        if (cabac >= 0) {
            append(sb, "cb", String.valueOf(cabac));
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String k, String v) {
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(k).append('=').append(v == null ? "" : v);
    }

    public static Map<String, String> parse(String text) {
        Map<String, String> map = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return map;
        }
        for (String part : text.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) {
                map.put(part.substring(0, i), part.substring(i + 1));
            }
        }
        return map;
    }

    public static int intOf(Map<String, String> map, String key, int def) {
        String v = map.get(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** STATUS 特征值有长度上限（受 MTU 限制），超长时服务端会自动截断，这里给个安全上限。 */
    public byte[] serializeBytes() {
        byte[] b = serialize().getBytes();
        if (b.length > 240) {
            byte[] t = new byte[240];
            System.arraycopy(b, 0, t, 0, 240);
            return t;
        }
        return b;
    }
}
