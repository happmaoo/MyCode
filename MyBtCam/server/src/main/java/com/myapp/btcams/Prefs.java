package com.myapp.btcams;

import android.content.Context;
import android.content.SharedPreferences;

/** 服务端设置项。 */
public final class Prefs {

    private static final String FILE = "btcams";
    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_DEFAULT_CAMERA = "default_camera";
    public static final String KEY_ROTATION = "rotation";
    public static final String KEY_ROTATION_MIGRATED = "rotation_migrated";
    public static final String KEY_OPEN_UI = "open_ui_on_connect";

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 开机自动运行监听服务。 */
    public static boolean isAutoStart(Context c) {
        return get(c).getBoolean(KEY_AUTO_START, false);
    }

    /** 默认摄像头：0 自动 / 1 后置 / 2 前置。 */
    public static int getDefaultCamera(Context c) {
        return get(c).getInt(KEY_DEFAULT_CAMERA, 0);
    }

    public static void setDefaultCamera(Context c, int value) {
        get(c).edit().putInt(KEY_DEFAULT_CAMERA, value).apply();
    }

    /** 画面方向：-1 自动（跟摄像头传感器方向），0/90/180/270 手动。 */
    public static int getRotation(Context c) {
        SharedPreferences sp = get(c);
        if (!sp.getBoolean(KEY_ROTATION_MIGRATED, false)) {
            // 老版本默认是 0（不旋转），升级后一次性改成「自动」，
            // 否则竖着拿手机推流就是横的/歪的
            int old = sp.getInt(KEY_ROTATION, 0);
            sp.edit().putInt(KEY_ROTATION, old == 0 ? -1 : old)
                    .putBoolean(KEY_ROTATION_MIGRATED, true).apply();
            return old == 0 ? -1 : old;
        }
        return sp.getInt(KEY_ROTATION, -1);
    }

    public static void setRotation(Context c, int value) {
        get(c).edit().putInt(KEY_ROTATION, value).apply();
    }

    /** 客户端连上时是否点亮主界面（推流本身不依赖界面）。 */
    public static boolean isOpenUiOnConnect(Context c) {
        return get(c).getBoolean(KEY_OPEN_UI, true);
    }
}
