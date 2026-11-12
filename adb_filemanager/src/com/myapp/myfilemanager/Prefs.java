package com.myapp.myfilemanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 设置持久化：每行列数、字体大小、列表名称字号/字型、图标主题（0..IconFactory.THEME_COUNT-1，默认 elementary）、
 *  隐藏文件、Root、自定义命令、标签页会话、收藏夹、扩展名打开方式规则。 */
public final class Prefs {
    private Prefs() {}

    private static final String FILE = "settings";
    private static final String K_COLS = "columns";        // 每行列数，默认 5
    private static final String K_TEXT = "text_level";     // 0 小 / 1 中 / 2 大（界面文字）
    private static final String K_NAME_SP = "list_name_sp";      // 列表名称统一字号 sp
    private static final String K_FOLDER_SP_OLD = "folder_name_sp"; // 1.6 旧值，仅用于迁移读取
    private static final String K_LIST_FONT = "list_font";       // 列表字体：""默认 / serif / monospace / bold / 字体文件路径
    private static final String K_DEBUG_RX = "debug_rx";         // 调试：启动时显示接收到的全部参数
    private static final String K_ICON_THEME = "icon_theme";      // 主题下标（见 IconFactory.THEME_NAMES）
    private static final String K_ICON_THEME_VER = "icon_theme_ver"; // 1=旧 8 套下标 / 2=现 5 套下标
    private static final int THEME_VER = 2;
    private static final String K_CMD_TEXT = "cmd_text_level"; // 0 小 / 1 中 / 2 大（命令结果窗口）
    private static final String K_CMD_TIMEOUT = "cmd_timeout";  // 命令执行超时（秒），0 = 不限时
    private static final String K_HIDDEN = "show_hidden";
    private static final String K_ROOT = "root_enabled";
    private static final String K_SCRIPT_DIR = "script_dir";   // 侧栏 Bash 脚本目录
    private static final String K_CMDS = "custom_commands";
    private static final String K_CMD_VER = "cmd_ver";     // 预置命令版本，用于升级迁移
    private static final int CMD_VER = 2;
    private static final String K_FAVS = "favorites";            // 收藏路径（明文转义，换行分隔）
    private static final String K_OPEN_RULES = "open_rules";     // 扩展名打开方式规则
    private static final String K_ACCENT = "accent";             // 着重色（十六进制 RGB，如 FD5555；空=默认）

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int columns(Context c) {
        int v = sp(c).getInt(K_COLS, 5);
        return v < 2 ? 2 : (v > 10 ? 10 : v);
    }
    public static void setColumns(Context c, int v) {
        sp(c).edit().putInt(K_COLS, Math.max(2, Math.min(10, v))).apply();
    }

    public static int textLevel(Context c) {
        int v = sp(c).getInt(K_TEXT, 1);
        return v < 0 ? 0 : (v > 2 ? 2 : v);
    }
    public static void setTextLevel(Context c, int v) {
        sp(c).edit().putInt(K_TEXT, Math.max(0, Math.min(2, v))).apply();
    }

    /** 命令执行结果窗口的字号档位（0 小 / 1 中 / 2 大），独立于界面文字档位。 */
    public static int cmdTextLevel(Context c) {
        int v = sp(c).getInt(K_CMD_TEXT, 1);
        return v < 0 ? 0 : (v > 2 ? 2 : v);
    }
    public static void setCmdTextLevel(Context c, int v) {
        sp(c).edit().putInt(K_CMD_TEXT, Math.max(0, Math.min(2, v))).apply();
    }

    /** 命令执行超时（秒），0 = 不限时。所有自定义/接收命令共用同一上限。 */
    public static int cmdTimeout(Context c) {
        int v = sp(c).getInt(K_CMD_TIMEOUT, 0);
        return v < 0 ? 0 : v;
    }
    public static void setCmdTimeout(Context c, int v) {
        sp(c).edit().putInt(K_CMD_TIMEOUT, Math.max(0, v)).apply();
    }

    /** 列表项名称统一字号（文件夹与文件共用），与界面字体档位互不影响。
     *  兼容 1.6：若尚未写入新键，沿用旧的「文件夹名称字号」。 */
    public static int listNameSp(Context c) {
        SharedPreferences p = sp(c);
        if (p.contains(K_NAME_SP)) return clampSp(p.getInt(K_NAME_SP, 13));
        return clampSp(p.getInt(K_FOLDER_SP_OLD, 13));
    }
    public static void setListNameSp(Context c, int v) {
        sp(c).edit().putInt(K_NAME_SP, clampSp(v)).apply();
    }
    private static int clampSp(int v) { return v < 8 ? 8 : (v > 24 ? 24 : v); }

    /** 列表字体：空串=系统默认，serif/monospace/bold=内置字型，其余视为字体文件路径
     *  （Typeface.createFromFile）。旧版整型（0/1/2/3）首次读取时自动迁移为对应字符串。 */
    public static String listFont(Context c) {
        SharedPreferences p = sp(c);
        Object o = p.getAll().get(K_LIST_FONT);
        if (o instanceof Integer) {            // 旧版整型字型，迁移为字符串
            String id = fontIdOf(((Integer) o).intValue());
            p.edit().putString(K_LIST_FONT, id).apply();
            return id;
        }
        String v = (o instanceof String) ? (String) o : "";
        return v == null ? "" : v;
    }
    public static void setListFont(Context c, String id) {
        sp(c).edit().putString(K_LIST_FONT, id == null ? "" : id).apply();
    }

    /** 调试开关：启动（或收到外部 intent）时弹窗显示接收到的全部参数，便于排查「打开方式/分享」问题。 */
    public static boolean debugRx(Context c) {
        return sp(c).getBoolean(K_DEBUG_RX, false);
    }
    public static void setDebugRx(Context c, boolean v) {
        sp(c).edit().putBoolean(K_DEBUG_RX, v).apply();
    }
    private static String fontIdOf(int v) {
        switch (v) {
            case 1: return "serif";
            case 2: return "monospace";
            case 3: return "bold";
            default: return "";
        }
    }

    /** 图标主题：默认 elementary（IconFactory.THEME_DEFAULT），越界回退默认。
     *  v2.7 及以前使用 8 套主题的下标；覆盖安装后按 IconFactory.migrateTheme 一次性迁移到
     *  现在的 5 套下标，已删除的扁平/Papirus/Qogir 会归到默认 elementary。 */
    public static int iconTheme(Context c) {
        SharedPreferences p = sp(c);
        if (p.getInt(K_ICON_THEME_VER, 1) < THEME_VER) {
            int migrated = IconFactory.migrateTheme(p.getInt(K_ICON_THEME, 0));
            p.edit().putInt(K_ICON_THEME, migrated)
                    .putInt(K_ICON_THEME_VER, THEME_VER).apply();
            return migrated;
        }
        int v = p.getInt(K_ICON_THEME, IconFactory.THEME_DEFAULT);
        return (v < 0 || v >= IconFactory.THEME_COUNT) ? IconFactory.THEME_DEFAULT : v;
    }
    public static void setIconTheme(Context c, int v) {
        int t = (v < 0 || v >= IconFactory.THEME_COUNT) ? IconFactory.THEME_DEFAULT : v;
        sp(c).edit().putInt(K_ICON_THEME, t)
                .putInt(K_ICON_THEME_VER, THEME_VER).apply();
    }

    public static boolean showHidden(Context c) {
        return sp(c).getBoolean(K_HIDDEN, false);
    }
    public static void setShowHidden(Context c, boolean on) {
        sp(c).edit().putBoolean(K_HIDDEN, on).apply();
    }

    public static boolean rootEnabled(Context c) {
        return sp(c).getBoolean(K_ROOT, false);
    }
    public static void setRootEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ROOT, on).apply();
    }

    public static String scriptDir(Context c) {
        String s = sp(c).getString(K_SCRIPT_DIR, "");
        return s == null ? "" : s;
    }
    public static void setScriptDir(Context c, String dir) {
        sp(c).edit().putString(K_SCRIPT_DIR, dir == null ? "" : dir).apply();
    }

    // ---------------- 旧版 Base64 → 明文 一次性迁移 ----------------

    /** 旧版所有敏感字段都用 Base64 存储，这里在首次访问时整体迁移为明文（带转义），
     *  并写入 K_PLAIN 标记避免重复迁移。迁移以旧格式按位置解码后重新 esc 写入，不依赖内容猜测。 */
    private static void migrateIfNeeded(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean(K_PLAIN, false)) return;
        SharedPreferences.Editor e = p.edit();
        String v;
        v = p.getString(K_CMDS, null);
        if (v != null && v.length() > 0) e.putString(K_CMDS, migrateCmds(v));
        v = p.getString(K_RX_CMD, null);
        if (v != null && v.length() > 0) e.putString(K_RX_CMD, migrateRx(v));
        v = p.getString(K_FAVS, null);
        if (v != null && v.length() > 0) e.putString(K_FAVS, migrateLines(v));
        v = p.getString(K_OPEN_RULES, null);
        if (v != null && v.length() > 0) e.putString(K_OPEN_RULES, migrateRules(v));
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(K_TAB_PREFIX)) {
                v = p.getString(key, null);
                if (v != null && v.length() > 0) e.putString(key, migrateTab(v));
            }
        }
        e.putBoolean(K_PLAIN, true).apply();
    }

    private static String migrateCmds(String raw) {
        StringBuilder b = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.length() == 0) continue;
            String[] a = line.split("\\|", 5);
            if (a.length < 2) continue;
            if (b.length() > 0) b.append('\n');
            b.append(esc(b64dec(a[0]))).append('|').append(esc(b64dec(a[1])));
            for (int k = 2; k < a.length; k++) b.append('|').append(a[k]);
        }
        return b.toString();
    }

    private static String migrateRx(String raw) {
        String[] a = raw.split("\\|", 4);
        StringBuilder b = new StringBuilder(esc(a.length >= 1 ? b64dec(a[0]) : ""));
        for (int k = 1; k < a.length; k++) b.append('|').append(a[k]);
        return b.toString();
    }

    private static String migrateLines(String raw) {
        StringBuilder b = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.length() == 0) continue;
            if (b.length() > 0) b.append('\n');
            b.append(esc(b64dec(line)));
        }
        return b.toString();
    }

    private static String migrateRules(String raw) {
        StringBuilder b = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.length() == 0) continue;
            String[] a = line.split("\\|", 3);
            if (b.length() > 0) b.append('\n');
            b.append(esc(a.length >= 1 ? b64dec(a[0]) : "")).append('|')
             .append(esc(a.length >= 2 ? b64dec(a[1]) : "")).append('|')
             .append(esc(a.length >= 3 ? b64dec(a[2]) : ""));
        }
        return b.toString();
    }

    private static String migrateTab(String raw) {
        String[] a = raw.split("\\|", 5);
        if (a.length < 3) return raw;
        StringBuilder b = new StringBuilder();
        b.append(a[0]).append('|').append(esc(b64dec(a[1]))).append('|');
        String[] hs = a[2].split(",");
        StringBuilder hb = new StringBuilder();
        for (int i = 0; i < hs.length; i++) {
            if (hs[i].isEmpty()) continue;
            if (hb.length() > 0) hb.append(',');
            hb.append(esc(b64dec(hs[i])));
        }
        b.append(hb);
        if (a.length >= 4) b.append('|').append(a[3]);
        if (a.length >= 5 && a[4].length() > 0) b.append('|').append(esc(b64dec(a[4])));
        return b.toString();
    }

    // ---------------- 自定义命令 ----------------

    /**
     * 一条自定义命令：名称、命令模板，及（兼容旧版的）root/autoClose/showOrig 字段。
     * 新版选项改为写在模板前 5 行的 #options:// 注释里（见下方解析方法），
     * 字段仅作为「模板中无选项行时」的兜底，保证旧数据仍可正常运行。
     */
    public static class Cmd {
        public String name;
        public String tmpl;
        public boolean root;
        public boolean autoClose;
        public boolean showOrig = true;   // 默认显示原命令，保持旧版行为
        public Cmd(String n, String t) { this(n, t, false, false, true); }
        public Cmd(String n, String t, boolean r) { this(n, t, r, false); }
        public Cmd(String n, String t, boolean r, boolean ac) { this(n, t, r, ac, true); }
        public Cmd(String n, String t, boolean r, boolean ac, boolean so) {
            name = n; tmpl = t; root = r; autoClose = ac; showOrig = so;
        }

        /** 在 tmpl 前 5 行内查找首个 #options:// 行并解析选项；找不到返回 null。 */
        private int[] parseOptions() {
            if (tmpl == null) return null;
            String[] lines = tmpl.split("\n", -1);
            for (int i = 0; i < lines.length && i < 5; i++) {
                String ln = lines[i].trim();
                if (ln.startsWith("#options://")) {
                    // 默认值：root:0 / printown:1 / autoclose:0 / ena:2
                    int[] r = new int[]{0, 1, 0, 2};
                    String body = ln.substring("#options://".length());
                    String[] parts = body.split("/");
                    for (String p : parts) {
                        int c = p.indexOf(':');
                        if (c <= 0) continue;
                        String k = p.substring(0, c).trim();
                        String v = p.substring(c + 1).trim();
                        int val = 0;
                        try { val = Integer.parseInt(v); } catch (Exception ignored) { val = 0; }
                        if (k.equals("root")) r[0] = val;
                        else if (k.equals("printown")) r[1] = val;
                        else if (k.equals("autoclose")) r[2] = val;
                        else if (k.equals("ena")) r[3] = val;
                    }
                    return r;
                }
            }
            return null;
        }

        /** 模板前 5 行是否含 #options:// 选项行。 */
        public boolean hasOptions() { return parseOptions() != null; }
        /** 是否以 Root 方式执行（强制 mount-master）；无选项行时取字段值。 */
        public boolean optRoot()      { int[] o = parseOptions(); return o != null ? o[0] != 0 : root; }
        /** 是否在结果中输出原命令；无选项行时取字段值。 */
        public boolean optPrintOrig() { int[] o = parseOptions(); return o != null ? o[1] != 0 : showOrig; }
        /** 执行成功后是否自动关闭结果窗口；无选项行时取字段值。 */
        public boolean optAutoClose() { int[] o = parseOptions(); return o != null ? o[2] != 0 : autoClose; }
        /** 工具栏启用条件：1 常启 / 2 仅选中 1 个文件 / 3 选中 1 个以上文件或文件夹（可混合）即启用。 */
        public int optEna()           { int[] o = parseOptions(); return o != null ? o[3] : 2; }
        /** 是否指定了弹窗尺寸（size:宽x高）；返回 int[]{宽,高} 或 null（未指定走默认全屏窗口）。 */
        public int[] optSize() {
            if (tmpl == null) return null;
            String[] lines = tmpl.split("\n", -1);
            for (int i = 0; i < lines.length && i < 5; i++) {
                String ln = lines[i].trim();
                if (!ln.startsWith("#options://")) continue;
                String body = ln.substring("#options://".length());
                for (String p : body.split("/")) {
                    int c = p.indexOf(':');
                    if (c <= 0) continue;
                    if (!p.substring(0, c).trim().equals("size")) continue;
                    String v = p.substring(c + 1).trim();
                    int x = v.indexOf('x');
                    if (x < 0) x = v.indexOf('X');
                    if (x <= 0) continue;
                    try {
                        int w = Integer.parseInt(v.substring(0, x).trim());
                        int h = Integer.parseInt(v.substring(x + 1).trim());
                        if (w > 0 && h > 0) return new int[]{ w, h };
                    } catch (Exception ignored) {}
                }
            }
            return null;
        }

        /** 去掉前 5 行内的 #options:// 选项行后的实际命令体（执行与列表展示均用此）。 */
        public String body() {
            if (tmpl == null) return "";
            String[] lines = tmpl.split("\n", -1);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i < 5 && lines[i].trim().startsWith("#options://")) continue;
                if (i > 0) b.append('\n');
                b.append(lines[i]);
            }
            return b.toString();
        }
    }

    private static final String K_PLAIN = "plain_v1";   // 明文迁移标记：旧版 Base64 → 新明文

    /**
     * 明文转义：把会破坏「行/字段/历史」分隔符的特殊字符写成可还原的形式。
     * 由于命令模板里常含 | 、路径/历史可能含 , 或换行，不能直接按字面存储，
     * 故对 \\ | , 以及换行做转义；其余字符原样保存，保证 settings.xml 人可读可改。
     */
    private static String esc(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': b.append("\\\\"); break;
                case '|':  b.append("\\|"); break;
                case ',':  b.append("\\,"); break;
                case '\n': b.append("\\n"); break;
                default:   b.append(ch);
            }
        }
        return b.toString();
    }

    /** 还原 esc() 的转义；遇到孤立反斜杠等未知转义序列时原样保留。 */
    private static String unesc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case '\\': b.append('\\'); i += 2; continue;
                    case '|':  b.append('|');  i += 2; continue;
                    case ',':  b.append(',');  i += 2; continue;
                    case 'n':  b.append('\n'); i += 2; continue;
                    default:   b.append(ch);   i += 1; continue;
                }
            }
            b.append(ch);
            i += 1;
        }
        return b.toString();
    }

    /** 按未转义的换行切分（命令行模板里的 \n 不会触发切分）。 */
    private static String[] splitLines(String raw) {
        if (raw == null || raw.length() == 0) return new String[0];
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (ch == '\\' && i + 1 < raw.length()) {
                cur.append(ch).append(raw.charAt(i + 1));   // 转义序列整体保留在行内
                i += 2; continue;
            }
            if (ch == '\n') { out.add(cur.toString()); cur.setLength(0); i += 1; continue; }
            cur.append(ch); i += 1;
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /** 按未转义的 '|' 切分；limit>0 时最后一段包含剩余全部内容（含未转义 '|'）。 */
    private static String[] splitFields(String line, int limit) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (ch == '\\' && i + 1 < line.length()) {
                char n = line.charAt(i + 1);
                if (n == '|' || n == ',' || n == '\\' || n == 'n') {
                    cur.append(ch).append(n);   // 还原为转义序列，稍后统一 unesc
                    i += 2; continue;
                }
                cur.append(ch); i += 1; continue;
            }
            if (ch == '|') {
                out.add(unesc(cur.toString()));
                cur.setLength(0);
                if (limit > 0 && out.size() >= limit - 1) {
                    out.add(unesc(line.substring(i + 1)));
                    return out.toArray(new String[0]);
                }
                i += 1; continue;
            }
            cur.append(ch); i += 1;
        }
        out.add(unesc(cur.toString()));
        return out.toArray(new String[0]);
    }

    /** 按未转义的 ',' 切分，仅用于标签页历史的逐条路径。 */
    private static ArrayList<String> splitCommas(String s) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '|' || n == ',' || n == '\\' || n == 'n') {
                    cur.append(ch).append(n); i += 2; continue;
                }
                cur.append(ch); i += 1; continue;
            }
            if (ch == ',') { out.add(unesc(cur.toString())); cur.setLength(0); i += 1; continue; }
            cur.append(ch); i += 1;
        }
        out.add(unesc(cur.toString()));
        return out;
    }

    /** 旧版 Base64 解码（仅用于一次性明文迁移，迁移后不再需要新写入）。 */
    private static String b64dec(String s) {
        try {
            return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** 每条命令序列化为 esc(name)|esc(tmpl)|root|autoClose|showOrig，多条用换行连接。 */
    public static List<Cmd> commands(Context c) {
        migrateIfNeeded(c);
        ArrayList<Cmd> out = new ArrayList<Cmd>();
        String raw = sp(c).getString(K_CMDS, "");
        if (raw == null || raw.length() == 0) return out;
        for (String line : splitLines(raw)) {
            if (line.length() == 0) continue;
            String[] a = splitFields(line, 5);
            if (a.length < 2) continue;
            String name = a[0];
            String tmpl = a[1];
            if (name.length() == 0 && tmpl.length() == 0) continue;
            boolean root = a.length >= 3 && "1".equals(a[2]);
            boolean autoClose = a.length >= 4 && "1".equals(a[3]);
            boolean showOrig = a.length < 5 || "1".equals(a[4]);   // 缺省显示原命令
            out.add(new Cmd(name, tmpl, root, autoClose, showOrig));
        }
        return out;
    }

    public static void setCommands(Context c, List<Cmd> list) {
        StringBuilder b = new StringBuilder();
        for (Cmd x : list) {
            if (b.length() > 0) b.append('\n');
            b.append(esc(x.name)).append('|').append(esc(x.tmpl)).append('|')
             .append(x.root ? '1' : '0').append('|').append(x.autoClose ? '1' : '0')
             .append('|').append(x.showOrig ? '1' : '0');
        }
        sp(c).edit().putString(K_CMDS, b.toString()).apply();
    }

    private static ArrayList<Cmd> defaultCommands() {
        ArrayList<Cmd> d = new ArrayList<Cmd>();
        d.add(new Cmd("查看文件详情", "ls -ld %f", false));
        d.add(new Cmd("查看内容", "cat %f", false));
        d.add(new Cmd("计算 SHA256", "sha256sum %F", false));
        d.add(new Cmd("安装 APK", "pm install -r %f", true));
        return d;
    }

    /** 旧版预置（1.3 及之前），用于判断用户是否从未自定义过命令。 */
    private static boolean isOldDefaults(List<Cmd> list) {
        return list.size() == 3
                && "查看文件详情".equals(list.get(0).name)
                && "计算 SHA256".equals(list.get(1).name)
                && "安装 APK（Root）".equals(list.get(2).name);
    }

    /** 首次启动写入预置命令；覆盖安装时仅在用户没自定义过命令的情况下升级预置。 */
    public static void ensureDefaults(Context c) {
        migrateIfNeeded(c);   // 首次访问时把旧版 Base64 配置迁移为明文
        SharedPreferences p = sp(c);
        if (p.getInt(K_CMD_VER, 1) >= CMD_VER && p.contains(K_CMDS)) return;
        if (!p.contains(K_CMDS)) {
            setCommands(c, defaultCommands());
        } else if (isOldDefaults(commands(c))) {
            setCommands(c, defaultCommands());
        }
        p.edit().putInt(K_CMD_VER, CMD_VER).apply();
    }

    // ---------------- 接收文件时执行的命令 ----------------
    // 当本 APP 通过「打开方式」/「分享」收到文件后自动执行。复用 Cmd 结构（name 仅作内部标识）。

    private static final String K_RX_CMD = "rx_cmd";

    /** 读取「接收命令」；未设置（模板为空）时返回 tmpl==null 的占位对象。 */
    public static Cmd rxCommand(Context c) {
        migrateIfNeeded(c);
        String raw = sp(c).getString(K_RX_CMD, "");
        if (raw == null || raw.length() == 0) return new Cmd("接收命令", "", false);
        String[] a = splitFields(raw, 4);
        String tmpl = a.length >= 1 ? a[0] : "";
        boolean root = a.length >= 2 && "1".equals(a[1]);
        boolean autoClose = a.length >= 3 && "1".equals(a[2]);
        boolean showOrig = a.length < 4 || "1".equals(a[3]);   // 缺省显示原命令
        return new Cmd("接收命令", tmpl, root, autoClose, showOrig);
    }

    public static void setRxCommand(Context c, Cmd cmd) {
        String v = esc(cmd.tmpl) + "|" + (cmd.root ? '1' : '0') + "|"
                + (cmd.autoClose ? '1' : '0') + "|" + (cmd.showOrig ? '1' : '0');
        sp(c).edit().putString(K_RX_CMD, v).apply();
    }

    // ---------------- 标签页会话（记住上次打开的 Tab） ----------------

    private static final String K_TAB_COUNT  = "tab_count";
    private static final String K_TAB_ACTIVE = "tab_active";
    private static final String K_TAB_PREFIX = "tab_state_";
    private static final int MAX_TABS = 20;

    /** 单个标签页的可恢复状态：当前路径 + 前进/后退历史（不含选中项与滚动位置）。 */
    public static class TabState {
        public String path;
        public int pos;
        public ArrayList<String> history = new ArrayList<String>();
        public boolean locked = false;     // 锁定标签，关闭 APP 后再次打开仍记住
        public String lockRoot = null;     // 锁定根目录（锁定后只能在其子目录内活动），未锁定为 null
    }

    /** 一次会话快照：所有标签页 + 当前激活的标签页下标。 */
    public static class TabSession {
        public final ArrayList<TabState> tabs = new ArrayList<TabState>();
        public int active = 0;
    }

    /**
     * 保存标签页会话。导航、增删/切换标签页时即时写入，进程被回收或下次
     * 冷启动时由 loadTabs 恢复。路径做明文转义，避免使用不可读且易损坏的编码。
     */
    public static void saveTabs(Context c, TabSession s) {
        if (s == null || s.tabs.isEmpty()) return;
        int n = Math.min(s.tabs.size(), MAX_TABS);
        int active = (s.active >= 0 && s.active < n) ? s.active : 0;
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        // 标签页变少时，清掉上一次残留的多余记录
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(K_TAB_PREFIX)) e.remove(key);
        }
        e.putInt(K_TAB_COUNT, n);
        e.putInt(K_TAB_ACTIVE, active);
        for (int i = 0; i < n; i++) {
            TabState t = s.tabs.get(i);
            int maxPos = Math.max(0, t.history.size() - 1);
            StringBuilder b = new StringBuilder();
            b.append(Math.max(0, Math.min(t.pos, maxPos)))
             .append('|').append(esc(t.path)).append('|');
            for (int j = 0; j < t.history.size(); j++) {
                if (j > 0) b.append(',');
                b.append(esc(t.history.get(j)));
            }
            b.append('|').append(t.locked ? 1 : 0);
            if (t.lockRoot != null) b.append('|').append(esc(t.lockRoot));
            e.putString(K_TAB_PREFIX + i, b.toString());
        }
        e.apply();
    }

    /** 读取上次保存的标签页会话；无记录或数据损坏时返回 null（调用方回退到主目录）。 */
    public static TabSession loadTabs(Context c) {
        migrateIfNeeded(c);
        SharedPreferences p = sp(c);
        int n = p.getInt(K_TAB_COUNT, 0);
        if (n < 1 || n > MAX_TABS) return null;
        TabSession s = new TabSession();
        for (int i = 0; i < n; i++) {
            String raw = p.getString(K_TAB_PREFIX + i, null);
            if (raw == null) continue;            // 单条记录损坏：跳过，而不是丢弃整个会话
            String[] a = splitFields(raw, 5);
            if (a.length < 3) continue;
            TabState t = new TabState();
            try {
                t.pos = Integer.parseInt(a[0]);
            } catch (NumberFormatException ex) {
                continue;
            }
            t.path = a[1];
            ArrayList<String> hs = splitCommas(a[2]);
            for (String h : hs) {
                if (h.length() > 0) t.history.add(h);
            }
            if (a.length >= 4) t.locked = a[3].equals("1");
            if (a.length >= 5 && a[4].length() > 0) {
                String lr = a[4];
                t.lockRoot = (lr == null || lr.isEmpty()) ? null : lr;
            } else {
                t.lockRoot = null;
            }
            // 仅要求路径非空；压缩包虚拟路径（archive://...）同样合法，不应据此丢弃整个会话
            if (t.path == null || t.path.isEmpty() || t.history.isEmpty()) continue;
            if (t.pos < 0 || t.pos >= t.history.size()) t.pos = t.history.size() - 1;
            // 以当前路径为准，避免历史与路径不一致导致「返回」跳错
            t.history.set(t.pos, t.path);
            s.tabs.add(t);
        }
        if (s.tabs.isEmpty()) return null;        // 全部标签都无效才回退到主页
        int active = p.getInt(K_TAB_ACTIVE, 0);
        // 原 active 可能指向被跳过的标签，越界时回退到最后一个有效标签
        s.active = (active >= 0 && active < s.tabs.size()) ? active : s.tabs.size() - 1;
        return s;
    }

    // ---------------- 收藏夹 ----------------

    /** 收藏的路径列表（按加入顺序、自动去重）；路径做明文转义以兼容中文/空格/换行等特殊字符。 */
    public static ArrayList<String> favorites(Context c) {
        migrateIfNeeded(c);
        ArrayList<String> out = new ArrayList<String>();
        String raw = sp(c).getString(K_FAVS, "");
        if (raw == null || raw.length() == 0) return out;
        for (String line : splitLines(raw)) {
            if (line.length() == 0) continue;
            String p = unesc(line);
            if (p.length() > 0 && !out.contains(p)) out.add(p);
        }
        return out;
    }

    public static void setFavorites(Context c, List<String> list) {
        StringBuilder b = new StringBuilder();
        ArrayList<String> seen = new ArrayList<String>();
        for (String p : list) {
            if (p == null || p.length() == 0 || seen.contains(p)) continue;
            seen.add(p);
            if (b.length() > 0) b.append('\n');
            b.append(esc(p));
        }
        sp(c).edit().putString(K_FAVS, b.toString()).apply();
    }

    /** 加入收藏；已存在时返回 false。路径统一规范化（去掉末尾斜杠）以避免判重/判存在不一致。 */
    public static boolean addFavorite(Context c, String path) {
        if (path == null || path.length() == 0) return false;
        path = path.replaceAll("/+$", "");
        ArrayList<String> l = favorites(c);
        if (l.contains(path)) return false;
        l.add(path);
        setFavorites(c, l);
        return true;
    }

    public static void removeFavorite(Context c, String path) {
        if (path == null) return;
        path = path.replaceAll("/+$", "");
        ArrayList<String> l = favorites(c);
        if (l.remove(path)) setFavorites(c, l);
    }

    // ---------------- 扩展名打开方式 ----------------

    /** 一条打开规则：扩展名（小写、不带点）、MIME 类型（可空）、应用包名（可空）。 */
    public static class OpenRule {
        public String ext, mime, pkg;
        public OpenRule(String e, String m, String p) { ext = e; mime = m; pkg = p; }
    }

    private static String normExt(String e) {
        if (e == null) return "";
        String s = e.trim().toLowerCase(Locale.US);
        while (s.startsWith(".")) s = s.substring(1);
        return s.trim();
    }

    /** 每条规则序列化为 esc(ext)|esc(mime)|esc(pkg)，多条用换行连接。 */
    public static ArrayList<OpenRule> openRules(Context c) {
        migrateIfNeeded(c);
        ArrayList<OpenRule> out = new ArrayList<OpenRule>();
        String raw = sp(c).getString(K_OPEN_RULES, "");
        if (raw == null || raw.length() == 0) return out;
        for (String line : splitLines(raw)) {
            if (line.length() == 0) continue;
            String[] a = splitFields(line, 3);
            String e = normExt(a.length >= 1 ? a[0] : "");
            if (e.length() == 0) continue;
            String m = a.length >= 2 ? a[1].trim() : "";
            String p = a.length >= 3 ? a[2].trim() : "";
            out.add(new OpenRule(e, m, p));
        }
        return out;
    }

    public static void setOpenRules(Context c, List<OpenRule> list) {
        StringBuilder b = new StringBuilder();
        for (OpenRule r : list) {
            if (r == null) continue;
            String e = normExt(r.ext);
            if (e.length() == 0) continue;
            if (b.length() > 0) b.append('\n');
            b.append(esc(e)).append('|')
             .append(esc(r.mime == null ? "" : r.mime.trim())).append('|')
             .append(esc(r.pkg == null ? "" : r.pkg.trim()));
        }
        sp(c).edit().putString(K_OPEN_RULES, b.toString()).apply();
    }

    /** 着重色：返回用户保存的十六进制（不含 #，如 FD5555），未设置则返回空串（用默认色）。 */
    public static String accent(Context c) {
        String s = sp(c).getString(K_ACCENT, "");
        return s == null ? "" : s.trim();
    }
    public static void setAccent(Context c, String hex) {
        sp(c).edit().putString(K_ACCENT, hex == null ? "" : hex.trim()).apply();
    }

    /** 按扩展名查规则；后添加的规则优先，便于覆盖。 */
    public static OpenRule openRuleFor(Context c, String ext) {
        String e = normExt(ext);
        if (e.length() == 0) return null;
        ArrayList<OpenRule> l = openRules(c);
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).ext.equals(e)) return l.get(i);
        }
        return null;
    }

    // ---------------- 插件启用 / 禁用 ----------------

    /** 被禁用的插件 id 集合（默认空集 = 全部启用）。 */
    private static final String K_PLUGIN_DISABLED = "plugin_disabled";

    /** 插件是否启用（默认启用）。id 即 IPlugin.getInfo().id。 */
    public static boolean pluginEnabled(Context c, String id) {
        if (id == null) return true;
        Set<String> s = sp(c).getStringSet(K_PLUGIN_DISABLED, Collections.<String>emptySet());
        return !s.contains(id);
    }

    /** 设置插件启用/禁用。禁用会触发宿主释放其资源（解绑 → 插件进程退出）。 */
    public static void setPluginEnabled(Context c, String id, boolean enabled) {
        if (id == null) return;
        Set<String> s = new HashSet<String>(sp(c).getStringSet(K_PLUGIN_DISABLED, Collections.<String>emptySet()));
        if (enabled) s.remove(id); else s.add(id);
        sp(c).edit().putStringSet(K_PLUGIN_DISABLED, s).apply();
    }

    // ---------------- 插件关联格式 ----------------

    /**
     * 各插件默认关联格式（逗号分隔的扩展名，小写、不带点；复合后缀如 tar.gz 作为整体）。
     * 用户从未编辑过时回退此默认值，与插件自身声明的支持格式保持一致。
     * archive7z 不含 apk，避免「打开方式」与系统安装器冲突。
     */
    private static final Map<String, String> DEFAULT_PLUGIN_ASSOC = new HashMap<String, String>() {{
        put("archive7z", "7z,zip,rar,tar,gz,bz2,xz,tgz,tar.gz,zst,lz4,cab,iso,jar");
        put("imageviewer", "jpg,jpeg,png,gif,bmp,webp");
    }};
    private static final String K_PLUGIN_ASSOC_PREFIX = "plugin_assoc_";

    /** 读取插件「关联格式」原始字符串（用户编辑过的以用户为准，否则回退默认声明）。
     *  用于插件设置页 EditText 预填，逗号分隔、允许空白与首尾点。 */
    public static String pluginAssocRaw(Context c, String id) {
        if (id == null) return "";
        String v = sp(c).getString(K_PLUGIN_ASSOC_PREFIX + id, null);
        if (v != null) return v;
        String d = DEFAULT_PLUGIN_ASSOC.get(id);
        return d != null ? d : "";
    }

    /** 保存插件关联格式（原始字符串，逗号分隔；清空即「不关联任何格式」）。 */
    public static void setPluginAssoc(Context c, String id, String raw) {
        if (id == null) return;
        sp(c).edit().putString(K_PLUGIN_ASSOC_PREFIX + id, raw == null ? "" : raw).apply();
    }

    /**
     * 该插件的关联格式是否已被用户「保存」过（即 settings 里存在对应键）。
     * 一旦显式保存，关联集合即视为用户定义、对用户自定义格式（如 apkm）具有最高权威，
     * 覆盖插件自身硬编码的默认扩展名列表；未保存过则回退到插件原生匹配 + 默认值。
     */
    public static boolean pluginAssocConfigured(Context c, String id) {
        if (id == null) return false;
        return sp(c).contains(K_PLUGIN_ASSOC_PREFIX + id);
    }

    /**
     * 解析插件关联格式为小写扩展名集合（去掉点、空白、空项）。
     * 返回空集合表示「不关联任何格式」（即用户清空输入框并保存后的行为：该插件不再自动接管文件）。
     */
    public static Set<String> pluginAssocExts(Context c, String id) {
        Set<String> out = new HashSet<String>();
        for (String s : pluginAssocRaw(c, id).split(",")) {
            String e = normExt(s);
            if (e.length() > 0) out.add(e);
        }
        return out;
    }
}
