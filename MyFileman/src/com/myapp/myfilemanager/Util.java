package com.myapp.myfilemanager;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Util {
    private Util() {}

    public static final int T_FOLDER = 0, T_PARENT = 1, T_IMAGE = 2, T_VIDEO = 3,
            T_AUDIO = 4, T_APK = 5, T_ZIP = 6, T_DOC = 7, T_TEXT = 8, T_CODE = 9,
            T_PDF = 10, T_OTHER = 11;

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static String size(long n) {
        if (n < 0) return "—";
        if (n < 1024) return n + " B";
        double v = n;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    public static String time(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    /** 属性面板用：精确到秒，不带纳秒与时区。 */
    public static String timeSec(long ms) {
        if (ms <= 0) return "—";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms));
    }

    public static String ext(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.US);
    }

    /** 依据名称/是否目录返回图标类别。 */
    public static int kind(String name, boolean dir, boolean parent) {
        if (parent) return T_PARENT;
        if (dir) return T_FOLDER;
        String e = ext(name);
        if (e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("gif")
                || e.equals("bmp") || e.equals("webp") || e.equals("heic") || e.equals("svg")) return T_IMAGE;
        if (e.equals("mp4") || e.equals("mkv") || e.equals("avi") || e.equals("mov")
                || e.equals("3gp") || e.equals("webm") || e.equals("flv") || e.equals("ts")) return T_VIDEO;
        if (e.equals("mp3") || e.equals("wav") || e.equals("flac") || e.equals("aac")
                || e.equals("ogg") || e.equals("m4a") || e.equals("wma") || e.equals("amr")) return T_AUDIO;
        if (e.equals("apk") || e.equals("apks") || e.equals("xapk")) return T_APK;
        if (e.equals("zip") || e.equals("rar") || e.equals("7z") || e.equals("tar")
                || e.equals("gz") || e.equals("bz2") || e.equals("xz")) return T_ZIP;
        if (e.equals("pdf")) return T_PDF;
        if (e.equals("doc") || e.equals("docx") || e.equals("xls") || e.equals("xlsx")
                || e.equals("ppt") || e.equals("pptx") || e.equals("wps")) return T_DOC;
        if (e.equals("txt") || e.equals("log") || e.equals("md") || e.equals("csv")
                || e.equals("ini") || e.equals("cfg") || e.equals("conf")) return T_TEXT;
        if (e.equals("java") || e.equals("kt") || e.equals("xml") || e.equals("json")
                || e.equals("html") || e.equals("htm") || e.equals("py") || e.equals("c")
                || e.equals("cpp") || e.equals("h") || e.equals("js") || e.equals("sh")
                || e.equals("gradle") || e.equals("properties")) return T_CODE;
        return T_OTHER;
    }

    /** 图标主色。 */
    public static int color(int kind) {
        switch (kind) {
            case T_FOLDER: return 0xFFFFB300;
            case T_PARENT: return 0xFF90A4AE;
            case T_IMAGE: return 0xFF43A047;
            case T_VIDEO: return 0xFFE53935;
            case T_AUDIO: return 0xFF8E24AA;
            case T_APK:   return 0xFF3DDC84;
            case T_ZIP:   return 0xFFFB8C00;
            case T_DOC:   return 0xFF1E88E5;
            case T_PDF:   return 0xFFD84315;
            case T_TEXT:  return 0xFF546E7A;
            case T_CODE:  return 0xFF00897B;
            default:      return 0xFF78909C;
        }
    }

    /** 图标上叠加的短标签（Windows 风格大图标）。 */
    public static String badge(int kind, String name) {
        switch (kind) {
            case T_FOLDER: return "";
            case T_PARENT: return "←";
            case T_APK: return "APK";
            case T_PDF: return "PDF";
            case T_ZIP: return "ZIP";
            case T_DOC: return "DOC";
            case T_TEXT: return "TXT";
            case T_CODE: return "</>";
            case T_IMAGE: return "IMG";
            case T_VIDEO: return "▶";
            case T_AUDIO: return "♫";
            default:
                String e = ext(name);
                return e.isEmpty() ? "•" : e.substring(0, Math.min(3, e.length())).toUpperCase(Locale.US);
        }
    }

    public static String parent(String path) {
        if (path == null || path.equals("/")) return "/";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        if (i <= 0) return "/";
        return p.substring(0, i);
    }

    public static String join(String dir, String name) {
        if (dir.equals("/")) return "/" + name;
        return dir.replaceAll("/+$", "") + "/" + name;
    }

    public static String base(String path) {
        if (path.equals("/")) return "/";
        String p = path.replaceAll("/+$", "");
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** shell 单引号转义（供 su -c 使用）。 */
    public static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
