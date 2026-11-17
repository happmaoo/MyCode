package com.myapp.myfilemanager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;

/** 极简 ContentProvider：让文件能以 content:// 形式交给其它 App 打开（避免 Android 7+ 的 file:// 限制）。 */
public class FileProvider extends ContentProvider {
    public static final String AUTH = "com.myapp.myfilemanager.files";

    @Override
    public boolean onCreate() { return true; }

    private File fileOf(Uri uri) {
        String p = uri.getPath();
        if (p == null) p = "";
        // 还原文件名里被转义的 URI 保留字（对应 MainActivity.encSeg 的 %23/%25/%3F）
        p = p.replace("%3F", "?").replace("%23", "#").replace("%25", "%");
        return new File(p);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        final File f = fileOf(uri);
        if (f.exists()) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        // Root 文件：普通进程命名空间看不到，用 su -mm cat 经管道流式提供给目标 App（不落盘）。
        if (Fs.rootMode && Fs.rootSessionAlive() && Fs.exists(f.getPath())) {
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                final ParcelFileDescriptor writeEnd = pipe[1];
                new Thread(new Runnable() {
                    public void run() {
                        java.io.OutputStream out = new java.io.FileOutputStream(writeEnd.getFileDescriptor());
                        try { Fs.streamRootFileTo(f.getPath(), out); }
                        finally {
                            try { out.flush(); out.close(); } catch (Exception ignored) {}
                            try { writeEnd.close(); } catch (Exception ignored) {}
                        }
                    }
                }, "fp-root-stream").start();
                return pipe[0];   // 读端交给系统/目标 App
            } catch (Exception e) {
                throw new java.io.FileNotFoundException(f.getPath() + " (" + e.getMessage() + ")");
            }
        }
        throw new java.io.FileNotFoundException(f.getPath());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f = fileOf(uri);
        String[] cols = (projection == null)
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        long size = f.length();
        // root 文件普通进程看不到，用 su stat 取真实大小，避免目标 App 把 0 字节当空文件
        if (size == 0 && Fs.rootMode && Fs.rootSessionAlive()) {
            Fs.ExecResult r = Fs.shell("stat -L -c %s " + Util.sq(Fs.rootPath(f.getPath())) + " 2>/dev/null", true);
            if (r.ok()) {
                try { size = Long.parseLong(r.out.trim()); } catch (Exception ignored) {}
            }
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = size;
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String name = fileOf(uri).getName();
        String ext = Util.ext(name);
        if (ext.isEmpty()) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) return mime;
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
