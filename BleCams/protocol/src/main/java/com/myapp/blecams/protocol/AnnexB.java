package com.myapp.blecams.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * H.264/AVC Annex-B（起始码风格）字节流的常用工具：NAL 切分、IDR/SPS/PPS 判断、
 * 从 SPS 里解析分辨率。服务端用它给帧打 flags，客户端用它给解码器喂 CSD 并判断分辨率变化。
 */
public final class AnnexB {

    private AnnexB() {
    }

    public static final int NAL_NON_IDR = 1;
    public static final int NAL_DPA = 2;
    public static final int NAL_DPB = 3;
    public static final int NAL_DPC = 4;
    public static final int NAL_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_AUD = 9;

    /** 一个 NAL：{@code start} 指向 NAL 头字节（起始码之后第一个字节）。 */
    public static final class Nal {
        public final int type;
        public final int start;
        public final int length;
        /** 前面起始码的长度（3 或 4）。 */
        public final int prefixLen;

        Nal(int type, int start, int length, int prefixLen) {
            this.type = type;
            this.start = start;
            this.length = length;
            this.prefixLen = prefixLen;
        }
    }

    /** 查找下一处起始码，返回其索引；找不到返回 -1。返回时 out[0]=起始码长度。 */
    private static int findStartCode(byte[] buf, int from, int to, int[] out) {
        for (int i = from; i + 2 < to; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0) {
                if (buf[i + 2] == 1) {
                    out[0] = 3;
                    return i;
                }
                if (i + 3 < to && buf[i + 2] == 0 && buf[i + 3] == 1) {
                    out[0] = 4;
                    return i;
                }
            }
        }
        return -1;
    }

    /** 把一段 Annex-B 流切成 NAL 列表。 */
    public static List<Nal> split(byte[] buf, int off, int len) {
        List<Nal> list = new ArrayList<>();
        if (buf == null || len <= 0) {
            return list;
        }
        int end = off + len;
        int[] pc = new int[1];
        int sc = findStartCode(buf, off, end, pc);
        if (sc < 0) {
            // 没有起始码，视为单个 NAL（裸 NAL 输入）
            if (len > 0) {
                list.add(new Nal(buf[off] & 0x1F, off, len, 0));
            }
            return list;
        }
        int i = sc;
        int prefix = pc[0];
        while (i < end) {
            int nalStart = i + prefix;
            if (nalStart >= end) {
                break;
            }
            int next = findStartCode(buf, nalStart, end, pc);
            int nalEnd = (next < 0) ? end : next;
            int type = buf[nalStart] & 0x1F;
            list.add(new Nal(type, nalStart, nalEnd - nalStart, prefix));
            if (next < 0) {
                break;
            }
            i = next;
            prefix = pc[0];
        }
        return list;
    }

    public static List<Nal> split(byte[] annexB) {
        return split(annexB, 0, annexB == null ? 0 : annexB.length);
    }

    public static boolean hasType(List<Nal> nals, int type) {
        for (Nal n : nals) {
            if (n.type == type) {
                return true;
            }
        }
        return false;
    }

    /** 是否含 IDR（关键帧）。 */
    public static boolean containsIdr(byte[] au) {
        return hasType(split(au), NAL_IDR);
    }

    /** 是否含 SPS/PPS（编解码配置）。 */
    public static boolean containsConfig(byte[] au) {
        List<Nal> nals = split(au);
        return hasType(nals, NAL_SPS) || hasType(nals, NAL_PPS);
    }

    /** 取出去掉起始码的裸 NAL 字节（Android MediaCodec 的 CSD 输入要求裸 NAL）。 */
    public static byte[] rawNal(byte[] buf, Nal nal) {
        byte[] out = new byte[nal.length];
        System.arraycopy(buf, nal.start, out, 0, nal.length);
        return out;
    }

    /** 从整段流中取出第一指定类型的裸 NAL，没有则返回 null。 */
    public static byte[] firstRawNal(byte[] buf, int type) {
        List<Nal> nals = split(buf);
        for (Nal n : nals) {
            if (n.type == type) {
                return rawNal(buf, n);
            }
        }
        return null;
    }

    /** 去掉 H.264 的防竞争字节（0x000003 中的 0x03）。 */
    public static byte[] toRbsp(byte[] buf, int off, int len) {
        byte[] out = new byte[len];
        int n = 0;
        for (int i = 0; i < len; i++) {
            byte b = buf[off + i];
            if (n >= 2 && out[n - 1] == 0 && out[n - 2] == 0 && b == 3 && i + 1 < len
                    && (buf[off + i + 1] & 0xFF) <= 3) {
                continue;
            }
            out[n++] = b;
        }
        byte[] r = new byte[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    /**
     * 从 SPS 解析出编码分辨率。
     *
     * @param spsBytes 含 SPS 的字节流（Annex-B 或裸 NAL 均可）
     * @return {@code {width, height}}，解析失败返回 null
     */
    public static int[] parseSize(byte[] spsBytes) {
        try {
            byte[] sps = firstRawNal(spsBytes, NAL_SPS);
            if (sps == null) {
                List<Nal> nals = split(spsBytes);
                if (!nals.isEmpty()) {
                    sps = rawNal(spsBytes, nals.get(0));
                }
            }
            if (sps == null || sps.length < 4) {
                return null;
            }
            Bits bits = new Bits(toRbsp(sps, 1, sps.length - 1));

            int profileIdc = bits.u(8);
            bits.u(8); // constraint_set flags
            bits.u(8); // level_idc
            bits.ue(); // seq_parameter_set_id

            int chromaFormatIdc = 1;
            int separateColourPlane = 0;
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 91 || profileIdc == 86
                    || profileIdc == 118 || profileIdc == 128 || profileIdc == 138 || profileIdc == 139
                    || profileIdc == 135 || profileIdc == 145) {
                chromaFormatIdc = bits.ue();
                if (chromaFormatIdc == 3) {
                    separateColourPlane = bits.u(1);
                }
                bits.ue(); // bit_depth_luma_minus8
                bits.ue(); // bit_depth_chroma_minus8
                bits.u(1); // qpprime_y_zero_transform_bypass_flag
                if (bits.u(1) == 1) {
                    int count = (chromaFormatIdc != 3) ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        if (bits.u(1) == 1) {
                            skipScalingList(bits, i < 6 ? 16 : 64);
                        }
                    }
                }
            }

            bits.ue(); // log2_max_frame_num_minus4
            int pocType = bits.ue();
            if (pocType == 0) {
                bits.ue(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (pocType == 1) {
                bits.u(1);
                bits.se();
                bits.se();
                int n = bits.ue();
                for (int i = 0; i < n; i++) {
                    bits.se();
                }
            }
            bits.ue(); // max_num_ref_frames
            bits.u(1); // gaps_in_frame_num_value_allowed_flag

            int picWidthInMbsMinus1 = bits.ue();
            int picHeightInMapUnitsMinus1 = bits.ue();
            int frameMbsOnlyFlag = bits.u(1);
            if (frameMbsOnlyFlag == 0) {
                bits.u(1); // mb_adaptive_frame_field_flag
            }
            bits.u(1); // direct_8x8_inference_flag

            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (bits.u(1) == 1) {
                cropLeft = bits.ue();
                cropRight = bits.ue();
                cropTop = bits.ue();
                cropBottom = bits.ue();
            }

            int chromaArrayType = (separateColourPlane == 0) ? chromaFormatIdc : 0;
            int subWidthC = (chromaFormatIdc == 1 || chromaFormatIdc == 2) ? 2 : 1;
            int subHeightC = (chromaFormatIdc == 1) ? 2 : 1;
            int cropUnitX = (chromaArrayType == 0) ? 1 : subWidthC;
            int cropUnitY = (chromaArrayType == 0) ? 1 : subHeightC * (2 - frameMbsOnlyFlag);

            int width = (picWidthInMbsMinus1 + 1) * 16 - cropUnitX * (cropLeft + cropRight);
            int height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16
                    - cropUnitY * (cropTop + cropBottom);
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                return null;
            }
            return new int[]{width, height};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断码流是否使用 CABAC（{@code entropy_coding_mode_flag}，PPS 语法元素）。
     *
     * <p>用途很实际：服务端把 profile 提到 Main/High 想省 10~15% 码率，但不少 Vendor 编码器
     * 会静默忽略 {@code KEY_PROFILE} 继续用 CABAC=0 的 Baseline。接收端解析 PPS 就能看出到底生效没。
     *
     * @param stream Annex-B 或含 PPS 的字节流
     * @return true=CABAC，false=CAVLC，null=解析不出
     */
    public static Boolean isCabac(byte[] stream) {
        try {
            byte[] pps = firstRawNal(stream, NAL_PPS);
            if (pps == null || pps.length < 2) {
                return null;
            }
            Bits bits = new Bits(toRbsp(pps, 1, pps.length - 1));
            bits.ue(); // pic_parameter_set_id
            bits.ue(); // seq_parameter_set_id
            return bits.u(1) == 1;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return profile_idc，解析失败返回 -1（用于确认编码器实际用的 profile）。 */
    public static int profileIdc(byte[] stream) {
        try {
            byte[] sps = firstRawNal(stream, NAL_SPS);
            if (sps == null || sps.length < 4) {
                return -1;
            }
            return new Bits(toRbsp(sps, 1, sps.length - 1)).u(8);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void skipScalingList(Bits bits, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int i = 0; i < size; i++) {
            if (nextScale != 0) {
                int delta = bits.se();
                nextScale = (lastScale + delta + 256) % 256;
            }
            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }

    /** 简单的 RBSP 位读取器。越界时抛异常，由 {@link #parseSize} 捕获。 */
    private static final class Bits {
        private final byte[] data;
        private int pos = 0;

        Bits(byte[] data) {
            this.data = data;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | r();
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (r() == 0) {
                zeros++;
                if (zeros > 31) {
                    throw new IllegalStateException("bad ue()");
                }
            }
            if (zeros == 0) {
                return 0;
            }
            return (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int k = ue();
            return (k % 2 == 1) ? (k + 1) / 2 : -(k / 2);
        }

        private int r() {
            int byteIndex = pos >>> 3;
            if (byteIndex >= data.length) {
                throw new IllegalStateException("eof");
            }
            int bit = 7 - (pos & 7);
            pos++;
            return (data[byteIndex] >>> bit) & 1;
        }
    }
}
