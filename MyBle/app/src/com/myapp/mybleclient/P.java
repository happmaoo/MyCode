package com.myapp.mybleclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * 设置持久化：统一用默认 SharedPreferences（设置页与主界面共用）。
 * 关 App、重启设备都保留；卸载才丢。
 */
public class P {
    static SharedPreferences sp(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    static String server(Context c) { return sp(c).getString("server", "Rolex"); }
    static String psk(Context c) { return sp(c).getString("psk", ""); }
    static boolean autoConnect(Context c) { return sp(c).getBoolean("auto_connect", false); }

    /** RSSI 轮询间隔，秒；越界回落 3 */
    static int rssiSec(Context c) {
        int v = sp(c).getInt("rssi_sec", 3);
        return (v < 1 || v > 60) ? 3 : v;
    }

    /** 零片超时毫秒：Run 后等结果第一片的时间；0=不提示；越界回落 3500 */
    static int zeroChunkMs(Context c) {
        int v = sp(c).getInt("zero_chunk_ms", 3500);
        if (v == 0) return 0;                       // 0 = 关闭提示
        return (v < 500 || v > 120000) ? 3500 : v;
    }

    /** 连接期间持有 CPU 唤醒锁（防熄屏后 ATT 停摆），耗电换稳定，默认关 */
    static boolean keepWake(Context c) { return sp(c).getBoolean("keep_wake", false); }

    /** 上传流水线：允许多个 WRITE_NO_RESPONSE 数据片同时在途（把一个连接事件的空位用起来，明显提速）。
     *  个别蓝牙栈对连发敏感，若上传不稳可关掉回退到严格单飞。默认开。 */
    static boolean uploadPipeline(Context c) { return sp(c).getBoolean("upload_pipeline", true); }
    static void saveUploadPipeline(Context c, boolean on) {
        sp(c).edit().putBoolean("upload_pipeline", on).apply();
    }

    /** 调试日志落盘：把 BleService 全部日志写到 Download/mybleclient.log，默认关 */
    static boolean debug(Context c) { return sp(c).getBoolean("debug_log", false); }
    static void saveDebug(Context c, boolean on) { sp(c).edit().putBoolean("debug_log", on).apply(); }

    // ------------------------------------------------------------- 外部编辑器（vi 编辑回写）

    /** 外部编辑器组件：包名 + Activity 类名，空则回落到系统 ACTION_EDIT 选择框 */
    static String editorPkg(Context c) { return sp(c).getString("editor_pkg", ""); }
    static String editorCls(Context c) { return sp(c).getString("editor_cls", ""); }
    static void saveEditor(Context c, String pkg, String cls) {
        sp(c).edit().putString("editor_pkg", pkg == null ? "" : pkg.trim())
                .putString("editor_cls", cls == null ? "" : cls.trim()).apply();
    }

    // ------------------------------------------------------------- 快捷命令

    /** 快捷命令原始文本（一行一条），空为未设置 */
    static String quickCmdsRaw(Context c) { return sp(c).getString("quick_cmds", ""); }

    /** 解析后的快捷命令：去空行/首尾空白、去重、上限 50 条 */
    static String[] quickCmds(Context c) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        for (String line : quickCmdsRaw(c).split("\n")) {
            String t = line.trim();
            if (t.length() > 0) set.add(t);
            if (set.size() >= 50) break;
        }
        return set.toArray(new String[set.size()]);
    }

    static void saveQuickCmds(Context c, String raw) {
        sp(c).edit().putString("quick_cmds", raw == null ? "" : raw).apply();
    }

    // ------------------------------------------------------------- 连接后自动执行

    /** 连接完成后自动执行的一条命令（空 = 不执行） */
    static String onConnectCmd(Context c) {
        return sp(c).getString("on_connect_cmd", "");
    }

    static void saveOnConnectCmd(Context c, String cmd) {
        sp(c).edit().putString("on_connect_cmd", cmd == null ? "" : cmd.trim()).apply();
    }

    // ------------------------------------------------------------- 发送历史
    static final int HIST_MAX = 50;
    /** 发送过的历史命令（最近在前），一行一条 */
    static String[] histCmds(Context c) {
        java.util.LinkedList<String> list = new java.util.LinkedList<String>();
        for (String line : sp(c).getString("cmd_hist", "").split("\n")) {
            String t = line.trim();
            if (t.length() > 0) list.add(t);
        }
        return list.toArray(new String[list.size()]);
    }
    /** 记录一条已发送命令：去重（相同项移到最前）、上限 HIST_MAX 条 */
    static void pushHist(Context c, String cmd) {
        String t = cmd == null ? "" : cmd.trim();
        if (t.length() == 0) return;
        java.util.LinkedList<String> list = new java.util.LinkedList<String>();
        for (String line : sp(c).getString("cmd_hist", "").split("\n")) {
            String x = line.trim();
            if (x.length() > 0 && !x.equals(t)) list.add(x);
        }
        list.addFirst(t);
        while (list.size() > HIST_MAX) list.removeLast();
        StringBuilder sb = new StringBuilder();
        for (String s : list) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
        sp(c).edit().putString("cmd_hist", sb.toString()).apply();
    }

    static void save(Context c, String server, String psk, boolean auto, int rssiSec, int zeroChunkMs,
                     boolean keepWake) {
        sp(c).edit().putString("server", server).putString("psk", psk)
                .putBoolean("auto_connect", auto).putInt("rssi_sec", rssiSec)
                .putInt("zero_chunk_ms", zeroChunkMs)
                .putBoolean("keep_wake", keepWake).apply();
    }

    static void savePsk(Context c, String psk) { sp(c).edit().putString("psk", psk).apply(); }

    static void saveServerPsk(Context c, String server, String psk) {
        sp(c).edit().putString("server", server).putString("psk", psk).apply();
    }
}
