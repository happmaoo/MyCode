package com.myapp.mqtt.base;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把运行日志写文件（设置里的「记录日志」勾上才写）。
 * 位置：Android/data/<包名>/files/logs/mqtt-YYYYMMDD.log（外部私有目录，无需权限；拿不到就退到内部 files/logs）
 */
public final class LogFile {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static Context app;

    private LogFile() {
    }

    public static void init(Context c) {
        app = c.getApplicationContext();
    }

    public static void write(String line) {
        Context c = app;
        if (c == null || line == null) return;
        if (!P.logToFile(c)) return;
        if (line.length() > 3000) line = line.substring(0, 3000) + " …(截断)";
        synchronized (LOCK) {
            try {
                File dir = dir(c);
                if (dir == null) return;
                if (!dir.exists() && !dir.mkdirs()) return;
                File f = new File(dir, "mqtt-" + DAY.format(new Date()) + ".log");
                FileWriter w = new FileWriter(f, true);
                try {
                    w.write(TS.format(new Date()) + " " + line + "\n");
                } finally {
                    w.close();
                }
            } catch (Exception ignore) {
            }
        }
    }

    /** 日志目录（设置页显示给人看用的） */
    public static String dirPath(Context c) {
        File d = dir(c);
        if (d == null) return "(unavailable)";
        qc(d);
        return d.getAbsolutePath();
    }

    private static File dir(Context c) {
        try {
            File d = c.getExternalFilesDir("logs");
            if (d != null) return d;
        } catch (Exception ignore) {
        }
        return new File(c.getFilesDir(), "logs");
    }

    /** 勾上后懒创建目录，避免设置页只读时也建 */
    private static void qc(File d) {
        if (d != null && !d.exists()) {
            try {
                d.mkdirs();
            } catch (Exception ignore) {
            }
        }
    }
}
