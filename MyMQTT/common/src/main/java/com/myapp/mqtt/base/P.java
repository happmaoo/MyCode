package com.myapp.mqtt.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 全部设置项落盘（SharedPreferences），两端共用，按 app_role 给默认值。 */
public final class P {

    public static final String ROLE_CONTROL = "control";
    public static final String ROLE_EXECUTOR = "executor";

    private P() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("mqtt", Context.MODE_PRIVATE);
    }

    /** 角色：优先用设置页里选的（合并版 App 可在设置里切换），否则用 manifest meta-data 的默认值 */
    public static String role(Context c) {
        String override = sp(c).getString("role_override", null);
        if (ROLE_CONTROL.equals(override) || ROLE_EXECUTOR.equals(override)) return override;
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                String r = ai.metaData.getString("app_role");
                if (r != null && r.length() > 0) return r;
            }
        } catch (Exception ignore) {
        }
        return ROLE_CONTROL;
    }

    public static void setRole(Context c, String r) {
        if (ROLE_CONTROL.equals(r) || ROLE_EXECUTOR.equals(r)) {
            sp(c).edit().putString("role_override", r).apply();
        }
    }

    /** 是否走 TLS（ssl://）；不再设置证书路径，默认用系统信任 */
    public static boolean tls(Context c) {
        SharedPreferences p = sp(c);
        if (p.contains("tls")) return p.getBoolean("tls", false);
        // 老版本靠证书路径判断是不是 TLS；没证书时按端口猜（8883 就是 TLS），迁移一次
        boolean legacy = !p.getString("crt", "").isEmpty()
                || p.getInt("port", 1883) == 8883;
        if (legacy) p.edit().putBoolean("tls", true).apply();
        return legacy;
    }

    public static void setTls(Context c, boolean v) {
        sp(c).edit().putBoolean("tls", v).apply();
    }

    public static boolean isExecutor(Context c) {
        return ROLE_EXECUTOR.equals(role(c));
    }

    public static String host(Context c) {
        return sp(c).getString("host", "broker.hivemq.com");
    }

    public static void setHost(Context c, String v) {
        sp(c).edit().putString("host", v).apply();
    }

    public static int port(Context c) {
        return sp(c).getInt("port", 1883);
    }

    public static void setPort(Context c, int v) {
        sp(c).edit().putInt("port", v).apply();
    }

    /** 非空则走 TLS（ssl://），用该 crt 作为信任锚；为空走 tcp:// */
    public static String crtPath(Context c) {
        return sp(c).getString("crt", "");
    }

    public static void setCrtPath(Context c, String v) {
        sp(c).edit().putString("crt", v).apply();
    }

    public static String user(Context c) {
        return sp(c).getString("user", "");
    }

    public static String pass(Context c) {
        return sp(c).getString("pass", "");
    }

    public static void setAuth(Context c, String u, String p) {
        sp(c).edit().putString("user", u).putString("pass", p).apply();
    }

    public static int keepAlive(Context c) {
        return sp(c).getInt("keepalive", 60);
    }

    public static void setKeepAlive(Context c, int v) {
        sp(c).edit().putInt("keepalive", v).apply();
    }

    /** 0 / 1 / 2 */
    public static int qos(Context c) {
        return sp(c).getInt("qos", 0);
    }

    public static void setQos(Context c, int v) {
        sp(c).edit().putInt("qos", v).apply();
    }

    /** 连接超时秒数，0 = 不超时（一直等） */
    public static int connectTimeout(Context c) {
        return sp(c).getInt("timeout", 10);
    }

    public static void setConnectTimeout(Context c, int v) {
        sp(c).edit().putInt("timeout", v).apply();
    }

    public static boolean autoConnectOnBoot(Context c) {
        return sp(c).getBoolean("boot_auto", false);
    }

    public static void setAutoConnectOnBoot(Context c, boolean v) {
        sp(c).edit().putBoolean("boot_auto", v).apply();
    }

    /** 记录日志：把运行日志写到 Android/data/<包名>/files/logs/ 下的文件 */
    public static boolean logToFile(Context c) {
        return sp(c).getBoolean("log_file", false);
    }

    public static void setLogToFile(Context c, boolean v) {
        sp(c).edit().putBoolean("log_file", v).apply();
    }

    /** 执行端：连接成功后自动执行一次的 shell 命令（空 = 不执行） */
    public static String startupCmd(Context c) {
        return sp(c).getString("startup_cmd", "");
    }

    public static void setStartupCmd(Context c, String v) {
        sp(c).edit().putString("startup_cmd", v).apply();
    }

    /** 执行端：单条命令输出最多保留多少字符（默认 4000，超出的部分截断） */
    public static int outputLimit(Context c) {
        int v = sp(c).getInt("out_limit", 4000);
        if (v < 100) v = 100;
        if (v > 500000) v = 500000;
        return v;
    }

    public static void setOutputLimit(Context c, int v) {
        if (v < 100) v = 100;
        if (v > 500000) v = 500000;
        sp(c).edit().putInt("out_limit", v).apply();
    }

    /** Debug：勾上才在日志区显示时间戳、收发报文、订阅/状态等；不勾只显示命令输出 */
    public static boolean debug(Context c) {
        return sp(c).getBoolean("debug", false);
    }

    public static void setDebug(Context c, boolean v) {
        sp(c).edit().putBoolean("debug", v).apply();
    }

    public static boolean autoReconnect(Context c) {
        return sp(c).getBoolean("auto_reconnect", true);
    }

    public static void setAutoReconnect(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_reconnect", v).apply();
    }

    public static String clientId(Context c) {
        String id = sp(c).getString("client_id", null);
        if (id == null) {
            String rnd = Long.toHexString(System.nanoTime() & 0xffffffffL);
            id = "mqtt-" + role(c) + "-" + rnd.substring(rnd.length() - 4);
            sp(c).edit().putString("client_id", id).apply();
        }
        return id;
    }

    public static void setClientId(Context c, String v) {
        sp(c).edit().putString("client_id", v).apply();
    }

    /** 发布主题；控制端默认 control/command，执行端默认 executor/result */
    public static String pubTopic(Context c) {
        String def = isExecutor(c) ? "executor/result" : "control/command";
        return sp(c).getString("pub_topic", def);
    }

    public static void setPubTopic(Context c, String v) {
        sp(c).edit().putString("pub_topic", v).apply();
    }

    /** 订阅主题列表（连接后自动订阅）；首次给一个按角色定好的默认 */
    public static List<String> subTopics(Context c) {
        String raw = sp(c).getString("sub_topics", null);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            out.add(isExecutor(c) ? "control/command" : "executor/result");
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String t = arr.optString(i, "").trim();
                if (t.length() > 0) out.add(t);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static void setSubTopics(Context c, List<String> topics) {
        JSONArray arr = new JSONArray();
        for (String t : topics) {
            if (t != null && t.trim().length() > 0) arr.put(t.trim());
        }
        sp(c).edit().putString("sub_topics", arr.toString()).apply();
    }

    public static void addSubTopic(Context c, String t) {
        List<String> list = subTopics(c);
        String v = t.trim();
        if (v.length() == 0) return;
        for (String s : list) if (s.equals(v)) return;
        list.add(v);
        setSubTopics(c, list);
    }

    public static void removeSubTopic(Context c, String t) {
        List<String> list = subTopics(c);
        list.remove(t);
        setSubTopics(c, list);
    }

    /** 快捷指令默认内容：直接写 shell 命令，不用套 JSON */
    static final String DEFAULT_QUICK = "ls /\nuptime\ndf -h\ngetprop ro.product.model\nscreenshot";

    /** 常用指令（每行一条），供 ⌄CMD 面板的 Quick 页用 */
    public static String quickCmdsRaw(Context c) {
        String raw = sp(c).getString("quick_cmds", DEFAULT_QUICK);
        if (raw == null) return DEFAULT_QUICK;
        // 兼容旧版本存下的 JSON 形式：{"cmd":"ls /"} → ls /
        if (raw.contains("{\"cmd\"")) {
            StringBuilder b = new StringBuilder();
            for (String line : raw.split("\n")) {
                String t = line.trim();
                if (t.length() == 0) continue;
                if (t.startsWith("{")) {
                    try {
                        t = new JSONObject(t).optString("cmd", "");
                    } catch (Exception e) {
                        // 解析不了就原样保留
                    }
                }
                if (t.trim().length() == 0) continue;
                if (b.length() > 0) b.append('\n');
                b.append(t.trim());
            }
            String out = b.toString();
            sp(c).edit().putString("quick_cmds", out).apply();
            return out;
        }
        return raw;
    }

    public static void setQuickCmdsRaw(Context c, String raw) {
        sp(c).edit().putString("quick_cmds", raw).apply();
    }

    public static String[] quickCmds(Context c) {
        String raw = quickCmdsRaw(c);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.length() > 0) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    /** 发送历史：最近在前，最多 50 条，重复的提到最前 */
    /** shell 控制广播的口令；默认 mymqtt，可在 prefs 的 ctrl_token 改 */
    public static String ctrlToken(Context c) {
        return sp(c).getString("ctrl_token", "mymqtt");
    }

    public static String[] histCmds(Context c) {
        String raw = sp(c).getString("hist_cmds", "[]");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String t = arr.optString(i, "").trim();
                if (t.length() > 0) out.add(t);
            }
        } catch (Exception ignore) {
        }
        return out.toArray(new String[0]);
    }

    public static void addHist(Context c, String cmd) {
        String v = cmd == null ? "" : cmd.trim();
        if (v.length() == 0) return;
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        list.add(v);
        for (String s : histCmds(c)) {
            if (!s.equals(v) && list.size() < 50) list.add(s);
        }
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        sp(c).edit().putString("hist_cmds", arr.toString()).apply();
    }

    /** 一行摘要，设置页底部显示 */
    public static String summary(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("role=").append(role(c)).append("  clientId=").append(clientId(c));
        b.append("\nserver=").append(host(c)).append(":").append(port(c))
                .append(tls(c) ? " (tls)" : " (tcp)");
        b.append("  keepalive=").append(keepAlive(c)).append("s  qos=").append(qos(c))
                .append("  timeout=").append(connectTimeout(c) == 0 ? "off" : connectTimeout(c) + "s");
        b.append("\nuser=").append(user(c).isEmpty() ? "(anonymous)" : user(c));
        b.append("\nboot-auto-connect=").append(autoConnectOnBoot(c))
                .append("  auto-reconnect=").append(autoReconnect(c))
                .append("  debug=").append(debug(c))
                .append("  log-file=").append(logToFile(c));
        b.append("\npub=").append(pubTopic(c)).append("  sub=");
        List<String> subs = subTopics(c);
        for (int i = 0; i < subs.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(subs.get(i));
        }
        return b.toString();
    }

    /** 给外部（分享/导出）用的 JSON 快照 */
    public static JSONObject snapshot(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role(c));
            o.put("host", host(c));
            o.put("port", port(c));
            o.put("crt", crtPath(c));
            o.put("user", user(c));
            o.put("keepalive", keepAlive(c));
            o.put("qos", qos(c));
            o.put("pub", pubTopic(c));
            o.put("sub", new JSONArray(subTopics(c)));
        } catch (Exception ignore) {
        }
        return o;
    }

    // ===== vi 外部编辑器（控制端用）=====
    public static String editorPkg(Context c) { return sp(c).getString("editor_pkg", ""); }
    public static String editorCls(Context c) { return sp(c).getString("editor_cls", ""); }
    public static void setEditor(Context c, String pkg, String cls) {
        sp(c).edit().putString("editor_pkg", pkg == null ? "" : pkg.trim())
                .putString("editor_cls", cls == null ? "" : cls.trim()).apply();
    }
    // vi 下载文件的体积上限（KB）
    /** 控制端：mqttup 直读本机路径（需要 root）而不是弹文件选择器 */
    public static boolean rootUp(Context c) {
        return sp(c).getBoolean("root_up", false);
    }

    public static void setRootUp(Context c, boolean v) {
        sp(c).edit().putBoolean("root_up", v).apply();
    }

    public static int editMaxKb(Context c) {
        int v = sp(c).getInt("edit_max_kb", 256);
        return v < 32 ? 32 : Math.min(v, 4096);
    }


    /** 执行端：命令执行期间是否持 wakelock（默认开） */
    public static boolean wakeLock(Context c) {
        return sp(c).getBoolean("wakelock", true);
    }
    public static void setWakeLock(Context c, boolean v) {
        sp(c).edit().putBoolean("wakelock", v).apply();
    }
}
