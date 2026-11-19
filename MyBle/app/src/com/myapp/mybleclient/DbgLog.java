package com.myapp.mybleclient;

import android.annotation.TargetApi;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 调试日志落盘：设置页「Debug 日志」开关控制。
 * 优先写公共 Download/mybleclient.log（Android 10+ 用 MediaStore，无需权限；
 * 旧版/legacy/root 设备直接写文件），全部失败则回退到 app 专属 Download 目录。
 * 写失败一律静默，绝不影响 BLE 主流程。
 */
public final class DbgLog {
    private static final String NAME = "mybleclient.log";
    private static Writer w = null;
    private static final Object LOCK = new Object();

    private DbgLog() { }

    /** 由 BleService.addLog()/info() 调用。关闭开关时关闭Writer并停止写。 */
    public static void write(Context c, String s) {
        if (c == null || s == null) return;
        if (!P.debug(c)) { close(); return; }
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + s;
        synchronized (LOCK) {
            try {
                if (w == null) w = open(c);
                if (w == null) return;
                w.write(line);
                if (!line.endsWith("\n")) w.write("\n");
                w.flush();
            } catch (Throwable t) {
                close();   // 下次写入时重新尝试打开（例如权限刚被授予）
            }
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (w != null) {
                try { w.flush(); w.close(); } catch (Throwable ignored) { }
                w = null;
            }
        }
    }

    private static Writer open(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            Writer mw = openMediaStore(c);
            if (mw != null) return mw;
        }
        try {
            File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (pub != null) {
                if (!pub.exists()) pub.mkdirs();
                File f = new File(pub, NAME);
                boolean fresh = !f.exists() || f.length() == 0;
                Writer fw = new FileWriter(f, true);
                if (fresh) fw.write(header());
                return fw;
            }
        } catch (Throwable ignored) { }
        try {
            File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = c.getFilesDir();
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, NAME);
                boolean fresh = !f.exists() || f.length() == 0;
                Writer fw = new FileWriter(f, true);
                if (fresh) fw.write(header());
                return fw;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    @TargetApi(29)
    private static Writer openMediaStore(Context c) {
        try {
            Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] proj = new String[]{ MediaStore.MediaColumns._ID };
            // 部分 ROM（MIUI 等）会按 MIME 给 display name 追加 .txt，导致实际名字变成
            // "mybleclient.log.txt"，再用精确 ="mybleclient.log" 查不到、每次进程重启都 insert
            // 出 "mybleclient.log (1).txt"… 碎片文件。改为前缀 LIKE 匹配并取最早(_id 最小)的一条，
            // 无论后缀如何都复用同一个文件。
            String sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] args = new String[]{ "mybleclient.log%" };
            Uri uri = null;
            boolean fresh = false;
            Cursor cur = c.getContentResolver().query(col, proj, sel, args,
                    MediaStore.MediaColumns._ID + " ASC");
            if (cur != null) {
                try { if (cur.moveToFirst()) uri = ContentUris.withAppendedId(col, cur.getLong(0)); }
                finally { cur.close(); }
            }
            if (uri == null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                uri = c.getContentResolver().insert(col, cv);
                fresh = true;
            }
            if (uri == null) return null;
            OutputStream os = c.getContentResolver().openOutputStream(uri, "wa");
            if (os == null) return null;
            Writer mw = new OutputStreamWriter(os, "UTF-8");
            if (fresh) mw.write(header());
            return mw;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String header() {
        return "=== mybleclient debug log "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " ===\n";
    }
}
