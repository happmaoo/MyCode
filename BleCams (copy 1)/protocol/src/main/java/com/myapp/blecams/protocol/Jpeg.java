package com.myapp.blecams.protocol;

/**
 * JPEG 帧的轻量工具：判断格式、判断是否完整、从 SOFn 读分辨率。
 *
 * <p>MJPEG over BLE 的丢包恢复就靠这两个标记：<b>SOI(FFD8) 开头 + EOI(FFD9) 结尾</b>。
 * 帧内丢一包时末尾的 EOI 大概率缺失或结构被破坏，接收端直接丢掉整帧即可，
 * 不需要像 H.264 那样等待下一个 IDR —— 这也是 JPEG 抗丢包的根本原因。
 */
public final class Jpeg {

    private Jpeg() {
    }

    public static boolean isJpeg(byte[] data) {
        return data != null && data.length > 4
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    /** 是否以 EOI 收尾（允许尾部有 0x00 填充）。 */
    public static boolean isComplete(byte[] data) {
        if (!isJpeg(data)) {
            return false;
        }
        for (int i = data.length - 1; i > 1; i--) {
            int b = data[i] & 0xFF;
            if (b == 0x00) {
                continue; // 尾部填充
            }
            if (b == 0xD9 && (data[i - 1] & 0xFF) == 0xFF) {
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * 从 SOF0/SOF1/SOF2（含 SOF5/C5 等差分变体）读宽高。
     *
     * @return {@code {width, height}}，失败返回 null
     */
    public static int[] parseSize(byte[] data) {
        if (!isJpeg(data)) {
            return null;
        }
        int i = 2;
        while (i + 8 < data.length) {
            if ((data[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = data[i + 1] & 0xFF;
            // 无长度字段的标记：RSTn、SOI、EOI、TEM
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {
                i += 2;
                continue;
            }
            // 填充 0xFF
            if (marker == 0xFF) {
                i++;
                continue;
            }
            int segmentLen = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            boolean isSof = (marker >= 0xC0 && marker <= 0xCF)
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof) {
                int height = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                int width = ((data[i + 7] & 0xFF) << 8) | (data[i + 8] & 0xFF);
                if (width > 0 && height > 0) {
                    return new int[]{width, height};
                }
                return null;
            }
            i += 2 + Math.max(2, segmentLen);
        }
        return null;
    }
}
