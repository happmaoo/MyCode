package com.myapp.mqtt.base;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/** 崩溃落盘：下次打开主界面时把上次崩溃堆栈显示在日志区，可直接 COPY 出来。 */
public final class CrashLog {

    private static final String FILE = "last_crash.txt";

    private CrashLog() {
    }

    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(app, t, e);
            } catch (Throwable ignore) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void write(Context ctx, Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time=" + new Date());
        pw.println("thread=" + t.getName());
        pw.println("version=" + BuildInfo.VERSION);
        e.printStackTrace(pw);
        pw.flush();
        File f = new File(ctx.getFilesDir(), FILE);
        try (FileWriter fw = new FileWriter(f, false)) {
            fw.write(sw.toString());
        } catch (Exception ignore) {
        }
    }

    public static String read(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignore) {
        }
        return sb.toString();
    }

    public static void clear(Context ctx) {
        new File(ctx.getFilesDir(), FILE).delete();
    }
}
