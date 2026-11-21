package com.myapp.mybleclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简文件共享 Provider：仅用于把 vi 编辑下载到本地的文件授权给外部编辑器读写。
 * URI 形如 content://com.myapp.mybleclient.editfile/<文件名>，文件位于 App 私有 edit/ 目录。
 * 用 startActivity 的 FLAG_GRANT_*_URI_PERMISSION 临时授权，编辑器返回即失效。
 */
public class EditFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.myapp.mybleclient.editfile";
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static { MATCHER.addURI(AUTHORITY, "*", 1); }

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(name));
    }

    private File dir() {
        File d = new File(getContext().getFilesDir(), "edit");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File fileFor(Uri uri) {
        if (MATCHER.match(uri) != 1) return null;
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0 || name.contains("/") || name.equals("..")) return null;
        return new File(dir(), name);
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(String.valueOf(uri));
        int m = ParcelFileDescriptor.MODE_READ_WRITE;
        if ("r".equals(mode)) m = ParcelFileDescriptor.MODE_READ_ONLY;
        else if ("w".equals(mode) || "wt".equals(mode)) m = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(f, m);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = fileFor(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
