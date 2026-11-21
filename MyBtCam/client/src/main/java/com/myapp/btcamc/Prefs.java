package com.myapp.btcamc;

import android.content.Context;
import android.content.SharedPreferences;

/** 客户端设置项。 */
public final class Prefs {

    private static final String FILE = "btcamc";
    public static final String KEY_SERVER_MAC = "server_mac";
    public static final String KEY_AUTO_CONNECT = "auto_connect";
    public static final String KEY_RES_W = "res_w";
    public static final String KEY_RES_H = "res_h";
    public static final String KEY_BITRATE = "bitrate";
    public static final String KEY_AUTO_BITRATE = "auto_bitrate";
    public static final String KEY_AUTO_RECONNECT = "auto_reconnect";

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getServerMac(Context c) {
        return get(c).getString(KEY_SERVER_MAC, "");
    }

    public static void setServerMac(Context c, String mac) {
        get(c).edit().putString(KEY_SERVER_MAC, mac).apply();
    }

    public static boolean isAutoConnect(Context c) {
        return get(c).getBoolean(KEY_AUTO_CONNECT, false);
    }

    public static void setAutoConnect(Context c, boolean value) {
        get(c).edit().putBoolean(KEY_AUTO_CONNECT, value).apply();
    }

    /** 链路拥塞时自动降码率（流畅优先）。 */
    public static boolean isAutoBitrate(Context c) {
        return get(c).getBoolean(KEY_AUTO_BITRATE, true);
    }

    public static void setAutoBitrate(Context c, boolean value) {
        get(c).edit().putBoolean(KEY_AUTO_BITRATE, value).apply();
    }

    /** 断线后自动重连（连续失败最多 10 次）。 */
    public static boolean isAutoReconnect(Context c) {
        return get(c).getBoolean(KEY_AUTO_RECONNECT, true);
    }

    public static void setAutoReconnect(Context c, boolean value) {
        get(c).edit().putBoolean(KEY_AUTO_RECONNECT, value).apply();
    }

    public static int getWidth(Context c) {
        return get(c).getInt(KEY_RES_W, 640);
    }

    public static int getHeight(Context c) {
        // 兼容旧版本存过的 320x320：小档位统一按 320x240 处理
        if (getWidth(c) == 320) {
            return 240;
        }
        return get(c).getInt(KEY_RES_H, 480);
    }

    public static void setResolution(Context c, int w, int h) {
        get(c).edit().putInt(KEY_RES_W, w).putInt(KEY_RES_H, h).apply();
    }

    public static int getBitrate(Context c) {
        return get(c).getInt(KEY_BITRATE, 500000);
    }

    public static void setBitrate(Context c, int bitrate) {
        get(c).edit().putInt(KEY_BITRATE, bitrate).apply();
    }
}
