package com.myapp.blecams.protocol;

/**
 * 视频档位。BLE 带宽极其有限，档位决定“每秒能塞进空口的字节数”，
 * 服务端会在档位上限内再按实测吞吐做二次下调。
 *
 * <p>换算关系：bitrate / 8 = 需要传输的字节速率。例如 BALANCED 的 60kbps ≈ 7.5kB/s，
 * 在 MTU=23（单包 20 字节）时至少需要 375 包/秒，而 7.5ms 连接间隔最多 ~133 包/秒，
 * 因此 60kbps 档位实际要求协商到 MTU >= 64 左右（推荐 185/247）。
 */
public final class VideoProfile {

    public final int id;
    public final String name;
    public final int width;
    public final int height;
    public final int fps;
    public final int bitrate;

    private VideoProfile(int id, String name, int width, int height, int fps, int bitrate) {
        this.id = id;
        this.name = name;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
    }

    /** 128x96@5fps 16kbps：MTU=23 的裸 BLE 4.0 链路（约 2kB/s 上限）也能跑。 */
    public static final VideoProfile ULTRA_LOW = new VideoProfile(0, "ULTRA_LOW", 128, 96, 5, 16000);

    /** 160x120@8fps 40kbps：需要 MTU >= 48 左右。 */
    public static final VideoProfile LOW = new VideoProfile(1, "LOW", 160, 120, 8, 40000);

    /** 320x240@10fps 60kbps：用户最初指定的档（0.078 bpp，画面很糊但流畅度优先）。 */
    public static final VideoProfile BALANCED = new VideoProfile(2, "BALANCED", 320, 240, 10, 60000);

    /**
     * 320x240@5fps 60kbps：同样的空口预算，每帧可用比特翻倍（0.156 bpp）。
     * 同样字节数下这是画质最好的一档，<b>推荐 BLE 上优先用它而不是 BALANCED</b>。
     */
    public static final VideoProfile HQ_5FPS = new VideoProfile(3, "HQ_5FPS", 320, 240, 5, 60000);

    /** 320x240@15fps 120kbps：需要 MTU >= 160、2M PHY / DLE 的理想链路。 */
    public static final VideoProfile HIGH = new VideoProfile(4, "HIGH", 320, 240, 15, 120000);

    /**
     * 176x144@12fps 60kbps：ESPAUTO（ESP32 + OV2640，HQVGA 240x180 JPEG）同量级的操作点。
     * bpp = 60000/(176*144*12) ≈ 0.18，比 320x240@10fps 的 0.078 高一倍多，
     * 代价是画面更小。“BLE 上看起来顺、看得清”基本就是这个折中。
     */
    public static final VideoProfile TINY = new VideoProfile(5, "TINY", 176, 144, 12, 60000);

    public static final VideoProfile[] ALL = {ULTRA_LOW, LOW, HQ_5FPS, BALANCED, HIGH, TINY};

    public static final VideoProfile DEFAULT = BALANCED;

    public static VideoProfile byId(int id) {
        for (VideoProfile p : ALL) {
            if (p.id == id) {
                return p;
            }
        }
        return DEFAULT;
    }

    public int bytesPerSecond() {
        return bitrate / 8;
    }

    public String describe() {
        return width + "x" + height + "@" + fps + "fps " + (bitrate / 1000) + "kbps";
    }

    @Override
    public String toString() {
        return name + "(" + describe() + ")";
    }
}
