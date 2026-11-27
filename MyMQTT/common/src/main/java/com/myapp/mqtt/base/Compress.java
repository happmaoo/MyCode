package com.myapp.mqtt.base;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 传输层自动压缩。
 *
 * 规则：原始 UTF-8 字节 ≥ THRESHOLD 才考虑压缩；压完必须比原文至少小 MIN_GAIN 字节，否则照原样发。
 * 识别：gzip 数据的固定头是 0x1F 0x8B，而 UTF-8 文本永远不会以这两个字节开头，
 *      所以接收端只要看前两字节就能判断，不需要任何额外标记，纯文本消息格式不变。
 */
public final class Compress {

    /** 256 字节 ≈ 250 个英文字符 / 85 个汉字，低于这个压缩基本没赚头（gzip 固定头尾就 18 字节） */
    public static final int THRESHOLD = 256;

    /** 至少省 16 字节才值得压（MQTT 报文头 + CPU 开销） */
    private static final int MIN_GAIN = 16;

    private Compress() {
    }

    public static boolean isGzip(byte[] b) {
        return b != null && b.length > 2
                && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B;
    }

    /** 需要发的字节：能压就压，压不动就原样返回 */
    public static byte[] pack(byte[] raw) {
        if (raw == null || raw.length < THRESHOLD) return raw;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 32);
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(raw);
            gz.finish();
            gz.close();
            byte[] out = bos.toByteArray();
            if (out.length + MIN_GAIN < raw.length) return out;
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    /** 解压；不是 gzip 就原样返回，解压失败也退回原字节（绝不丢消息） */
    public static byte[] unpack(byte[] b) {
        if (!isGzip(b)) return b;
        try {
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(b));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(b.length * 4 + 64);
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8 * 1024 * 1024) break;   // 防御：单条消息解压不超过 8MB
            }
            gz.close();
            return bos.toByteArray();
        } catch (Exception e) {
            LogFile.write("[压缩] 解压失败 " + Fmt.size(b.length) + ": " + e.getClass().getSimpleName());
            return b;
        }
    }

    /** 收到字节 → 文本（自动识别 gzip） */
    public static String decode(byte[] b) {
        byte[] plain = unpack(b);
        try {
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            return new String(plain);
        }
    }
}
