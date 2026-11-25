package com.myapp.myalarmmanager;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 跑脚本 + 记录最近一次运行结果（方便验证，因为触发时没有界面） */
public class ScriptRunner {

    public static class Result {
        public int exit = -1;
        public String cmd = "";
        public String out = "";
    }

    private static final int MAX_CAPTURE = 4000;   // 单次最多留 4K 输出
    private static final int MAX_LOG = 6000;       // 日志文件滚动保留 6K
    private static final String LOG_NAME = "last_run.log";

    /** 主入口：优先直接执行脚本，失败再退回 sh 执行 */
    public static Result run(String path, boolean useRoot) {
        if (!useRoot) {
            Result r = exec(new String[]{"/system/bin/sh", path});
            r.cmd = "sh " + path;
            return r;
        }
        Result r = exec(new String[]{"su", "-c", path});
        r.cmd = "su -c " + path;

        if (r.exit != 0) {
            // 脚本没 chmod +x，或 shebang 不对：退回 sh 执行
            Result r2 = exec(new String[]{"su", "-c", "/system/bin/sh " + path});
            if (r2.exit == 0) {
                r2.cmd = "su -c \"/system/bin/sh " + path + "\"   (fallback)";
                r2.out = "直接执行 exit=" + r.exit + "\n" + r2.out;
                return r2;
            }
        }
        return r;
    }

    private static Result exec(String[] cmd) {
        Result r = new Result();
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() < MAX_CAPTURE) sb.append(line).append('\n');
            }
            br.close();
            r.exit = p.waitFor();
            r.out = sb.toString();
        } catch (Exception e) {
            r.out = "[异常] " + e + "\n";
            r.exit = -1;
        } finally {
            if (p != null) p.destroy();
        }
        return r;
    }

    /** 追加一条运行记录，并把文件截断到 MAX_LOG 以内 */
    public static void append(String dir, String src, Result r, String path) {
        if (dir == null) return;
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String entry = "[" + ts + "] src=" + src + "  exit=" + r.exit + "\n"
                + "path: " + path + "\n"
                + "cmd : " + r.cmd + "\n"
                + (r.out == null || r.out.isEmpty() ? "(无输出)\n" : r.out)
                + "--------\n";

        File f = new File(dir, LOG_NAME);
        String old = readText(f);
        String all = entry + old;
        if (all.length() > MAX_LOG) all = all.substring(0, MAX_LOG);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(all.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            close(fos);
        }
    }

    /** 只写一行阶段标记，用来区分“闹钟没响”和“响了但脚本失败” */
    public static void mark(String dir, String text) {
        if (dir == null) return;
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String entry = "[" + ts + "] " + text + "\n--------\n";
        File f = new File(dir, LOG_NAME);
        String all = entry + readText(f);
        if (all.length() > MAX_LOG) all = all.substring(0, MAX_LOG);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(all.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            close(fos);
        }
    }

    /** 后台跑一次并记日志（首轮立即执行、手动执行都用它） */
    public static void runAsync(Context ctx, String src) {
        final Context app = ctx.getApplicationContext();
        final String dir = app.getFilesDir().getAbsolutePath();
        final String path = app.getSharedPreferences(Cfg.PREFS, Context.MODE_PRIVATE)
                .getString(Cfg.KEY_SCRIPT, Cfg.DEFAULT_SCRIPT);

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (path == null || path.trim().isEmpty()) {
                    Result r = new Result();
                    r.out = "[跳过] 脚本路径为空\n";
                    r.cmd = "(none)";
                    append(dir, src, r, "");
                    return;
                }
                boolean root = app.getSharedPreferences(Cfg.PREFS, Context.MODE_PRIVATE)
                        .getBoolean(Cfg.KEY_ROOT, true);
                Result r = ScriptRunner.run(path.trim(), root);
                append(dir, src, r, path.trim());
            }
        }).start();
    }

    /** 毫秒时间戳 -> HH:mm:ss，给日志和状态栏用 */
    public static String fmt(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    public static String readLog(String dir) {
        return readText(new File(dir, LOG_NAME));
    }

    public static void clearLog(String dir) {
        new File(dir, LOG_NAME).delete();
    }

    private static String readText(File f) {
        if (!f.exists()) return "";
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int n = fis.read(b);
            return new String(b, 0, n, "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            close(fis);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

}
