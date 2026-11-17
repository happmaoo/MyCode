package com.myapp.myfilemanager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局调试日志（设置页「输出调试日志」开关控制）。
 *
 * 设计要点：
 *  - 开关用 SD 卡上的文件而非 SharedPreferences：7z 解压跑在 :plugin.archive 独立进程，
 *    SP 不共享；文件开关对主进程与所有插件进程同时可见、即时生效。
 *  - 纯 Java 实现（不依赖 Android API），Fs / SevenZip / RootShell 等都能直接调用。
 *  - 覆盖整个 APP：应用启动与生命周期、界面操作、文件操作、命令执行、插件调用、7z 解压链路，
 *    以及 install() 安装的全局未捕获异常处理器（任何线程的崩溃都会写入日志）。
 *  - 单条日志自动截断、总大小 2MB 自动轮转，避免日志无限膨胀拖慢应用。
 *  - 所有写操作静默容错：开关关闭时只做一次文件存在性检查，绝不影响业务流程。
 */
public final class Dbg {
    private Dbg() {}

    public static final String DIR  = "/sdcard/Download";
    public static final String FLAG = DIR + "/fm_debug.on";
    public static final String LOG  = DIR + "/fm_debug.log";
    public static final String OLD  = DIR + "/fm_debug.old.log";

    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    private static final int MAX_LINE = 2000;
    private static final int MAX_MULTI = 12000;

    private static final String PROC = readProcName();
    private static final Object LOCK = new Object();
    private static volatile String appInfo = "";

    private static String readProcName() {
        FileInputStream f = null;
        try {
            f = new FileInputStream("/proc/self/cmdline");
            byte[] b = new byte[256];
            int n = f.read(b);
            if (n <= 0) return "?";
            String s = new String(b, 0, n, "UTF-8");
            int z = s.indexOf('\0');
            if (z > 0) s = s.substring(0, z);
            return s.trim().isEmpty() ? "?" : s.trim();
        } catch (Throwable t) {
            return "?";
        } finally {
            if (f != null) try { f.close(); } catch (Throwable ignored) {}
        }
    }

    /** 调试是否开启（跨进程：以开关文件是否存在为准）。 */
    public static boolean enabled() {
        try { return new File(FLAG).exists(); } catch (Throwable t) { return false; }
    }

    /** 由主进程注入版本/设备信息，写日志头时使用。 */
    public static void setAppInfo(String s) { appInfo = (s == null) ? "" : s; }

    /** 安装全局未捕获异常处理器。每个进程各自调用一次（主进程 + 各插件进程）。 */
    public static void install() {
        try {
            final Thread.UncaughtExceptionHandler old =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (old instanceof CrashHandler) return;
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(old));
        } catch (Throwable ignored) {}
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler old;
        CrashHandler(Thread.UncaughtExceptionHandler o) { old = o; }
        public void uncaughtException(Thread t, Throwable e) {
            try { logMulti("CRASH", "线程=" + t.getName() + "\n" + stack(e)); }
            catch (Throwable ignored) {}
            // 交回系统默认处理器，保持原有的崩溃/上报行为不变
            if (old != null) old.uncaughtException(t, e);
        }
    }

    /** 格式化异常堆栈。 */
    public static String stack(Throwable t) {
        if (t == null) return "null";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(bos, true, "UTF-8");
            t.printStackTrace(ps);
            ps.flush();
            return bos.toString("UTF-8");
        } catch (Throwable ignored) {
            return String.valueOf(t);
        }
    }

    /** 开关：开启时清空日志并写入头部；关闭时只删除开关文件（日志保留）。 */
    public static boolean setEnabled(boolean on) {
        try {
            if (on) {
                File dir = new File(DIR);
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false;
                synchronized (LOCK) { writeHeader(false); }
                FileOutputStream ff = new FileOutputStream(FLAG, false);
                try { ff.write("1".getBytes("UTF-8")); } finally { ff.close(); }
                log("DBG", "调试已开启；日志=" + LOG);
                return true;
            } else {
                new File(FLAG).delete();
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeHeader(boolean rotated) throws Exception {
        FileOutputStream fo = new FileOutputStream(LOG, false);
        try {
            StringBuilder b = new StringBuilder();
            b.append("=== MyFileman 全局调试日志 ===\n");
            b.append("开始时间: ").append(new Date()).append('\n');
            b.append("进程: ").append(PROC).append('\n');
            if (!appInfo.isEmpty()) b.append("应用: ").append(appInfo).append('\n');
            if (rotated) b.append("（上一段日志已轮转为 fm_debug.old.log）\n");
            b.append("----------------------------------------\n");
            fo.write(b.toString().getBytes("UTF-8"));
        } finally {
            fo.close();
        }
    }

    /** 追加一行日志（自动单行化 + 截断）；未开启或写失败静默返回。 */
    public static void log(String tag, String msg) {
        if (!enabled()) return;
        writeLine(tag, clip(msg, MAX_LINE));
    }

    /** 记录异常（含堆栈，保留换行便于阅读）。 */
    public static void log(String tag, Throwable t) {
        logMulti(tag, stack(t));
    }

    /** 带说明文字的异常日志。 */
    public static void log(String tag, String msg, Throwable t) {
        logMulti(tag, (msg == null ? "" : msg) + "\n" + stack(t));
    }

    /** 追加多行内容（崩溃堆栈、命令输出等），保留换行、按更大上限截断。 */
    public static void logMulti(String tag, String msg) {
        if (!enabled()) return;
        writeLine(tag, clipRaw(msg, MAX_MULTI));
    }

    private static void writeLine(String tag, String body) {
        synchronized (LOCK) {
            try {
                rotateIfNeeded();
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date());
                String line = ts + " [" + PROC + "] [" + Thread.currentThread().getName()
                        + "] [" + tag + "] " + body + "\n";
                FileOutputStream fo = new FileOutputStream(LOG, true);
                try { fo.write(line.getBytes("UTF-8")); } finally { fo.close(); }
            } catch (Throwable ignored) {}
        }
    }

    /** 超过 2MB 时把当前日志改名为 .old.log，重新开一段，避免日志无限膨胀。 */
    private static void rotateIfNeeded() {
        try {
            File f = new File(LOG);
            if (!f.exists() || f.length() < MAX_LOG_BYTES) return;
            File old = new File(OLD);
            old.delete();
            f.renameTo(old);
            writeHeader(true);
        } catch (Throwable ignored) {}
    }

    public static long size() {
        try { return new File(LOG).length(); } catch (Throwable t) { return 0; }
    }

    /** 清空日志（保留开关状态）；返回是否成功。 */
    public static boolean clear() {
        try {
            synchronized (LOCK) { writeHeader(false); }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取日志末尾若干行，供设置页「查看日志」。 */
    public static String tail(int maxLines) {
        try {
            File f = new File(LOG);
            if (!f.exists()) return "（暂无日志，请先在上方开启调试开关）";
            byte[] data = new byte[(int) Math.min(f.length(), MAX_LOG_BYTES)];
            FileInputStream fi = new FileInputStream(f);
            int n;
            try { n = fi.read(data); } finally { fi.close(); }
            if (n <= 0) return "（日志为空）";
            String[] lines = new String(data, 0, n, "UTF-8").split("\n");
            int from = Math.max(0, lines.length - Math.max(1, maxLines));
            StringBuilder b = new StringBuilder();
            if (from > 0) {
                b.append("……（仅显示最后 ").append(lines.length - from)
                        .append(" 行，共 ").append(lines.length).append(" 行；")
                        .append("完整日志见 ").append(LOG).append("）\n\n");
            }
            for (int i = from; i < lines.length; i++) b.append(lines[i]).append('\n');
            return b.toString();
        } catch (Throwable t) {
            return "读取日志失败：" + t;
        }
    }

    /** 把长文本压成单行并截断，日志保持「一行一条」便于检索。 */
    public static String clip(String s, int max) {
        if (s == null) return "null";
        String t = s.replace("\r", "\\r").replace("\n", "\\n");
        return t.length() <= max ? t : t.substring(0, max) + "…(共" + t.length() + "字符)";
    }

    /** 只截断长度、保留换行（用于堆栈等需要格式的内容）。 */
    public static String clipRaw(String s, int max) {
        if (s == null) return "null";
        String t = s.replace("\r", "");
        return t.length() <= max ? t : t.substring(0, max) + "…(共" + t.length() + "字符)";
    }
}
