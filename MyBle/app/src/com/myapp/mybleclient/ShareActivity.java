package com.myapp.mybleclient;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ClipData;
import android.net.Uri;
import android.os.IBinder;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * 系统「分享到 / 发送到」入口：接收其他 App 分享的文件，交给 BleService 上传到已连接的服务端。
 *
 * 两个关键点：
 *  1) 分享进来的是带临时读权限的 content:// Uri，本 Activity 结束后权限可能失效 —— 立即把
 *     内容复制进本 App 私有缓存，再以 file:// Uri 交给长期存活的 BleService，彻底规避权限问题。
 *  2) 从分享菜单启动时 App 往往没在前台，Service 可能未启动 —— 先 startService 让服务常驻，
 *     再 bindService 拿 LocalBinder；拿到后立即 unbind，服务靠 started 状态继续存活。
 *     若尚未连接，BleService 会用服务器名自动连接，就绪后自动上传队列。
 */
public class ShareActivity extends Activity {

    BleService svc;
    boolean bound = false;
    boolean delivered = false;
    final ArrayList<Uri> incoming = new ArrayList<Uri>();

    final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            svc = ((BleService.LocalBinder) b).getService();
            // 服务已由 startService 置为常驻，这里拿到 binder 后立即解绑即可
            try { unbindService(conn); } catch (Throwable ignored) { }
            bound = false;
            deliver();
        }
        @Override public void onServiceDisconnected(ComponentName name) { svc = null; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        String action = i.getAction();
        android.util.Log.d("ShareAct", "onCreate action=" + action + " data=" + i.getData()
                + " extras=" + i.getExtras());

        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) incoming.add(u);
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = null;
            try { u = i.getParcelableExtra(Intent.EXTRA_STREAM); } catch (Throwable ignored) { }
            if (u == null) u = i.getData();
            if (u != null) incoming.add(u);
        }

        // 兼容以 ClipData 携带的分享
        ClipData cd = i.getClipData();
        if (cd != null) {
            for (int k = 0; k < cd.getItemCount(); k++) {
                Uri u = cd.getItemAt(k).getUri();
                if (u != null && !incoming.contains(u)) incoming.add(u);
            }
        }

        // 纯文本分享（无文件 Uri）：落成一个 .txt 文件再走同一上传路径
        if (incoming.isEmpty()) {
            String text = i.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && text.length() > 0) {
                try {
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "shared_text.txt");
                    FileOutputStream out = new FileOutputStream(f);
                    try { out.write(text.getBytes("UTF-8")); } finally { out.close(); }
                    incoming.add(Uri.fromFile(f));
                } catch (Throwable ignored) { }
            }
        }

        android.util.Log.d("ShareAct", "incoming size=" + incoming.size()
                + (incoming.size() > 0 ? " first=" + incoming.get(0) : ""));
        if (incoming.isEmpty()) {
            Toast.makeText(this, "未收到可发送的文件", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Toast.makeText(this, "已收到 " + incoming.size() + " 个文件，准备发送…", Toast.LENGTH_SHORT).show();

        // 先 startService 保证常驻，再 bind 拿 binder
        Intent s = new Intent(this, BleService.class);
        try { startService(s); } catch (Throwable ignored) { }
        try { bindService(s, conn, Context.BIND_AUTO_CREATE); bound = true; }
        catch (Throwable t) {
            Toast.makeText(this, "无法启动传输服务: " + t, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /** 拿到服务后在后台线程把 Uri 内容复制进私有缓存，再交给服务入上传队列 */
    void deliver() {
        android.util.Log.d("ShareAct", "deliver svc=" + svc + " incoming=" + incoming.size());
        if (delivered || svc == null) return;
        delivered = true;
        final Context app = getApplicationContext();
        final ArrayList<Uri> src = new ArrayList<Uri>(incoming);
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<Uri> ready = new ArrayList<Uri>();
                for (Uri u : src) {
                    File f = cacheIncoming(app, u);
                    android.util.Log.d("ShareAct", "cached " + u + " -> " + (f == null ? "null" : f.length() + "B " + f.getPath()));
                    if (f != null && f.length() > 0) ready.add(Uri.fromFile(f));
                }
                final int n = ready.size();
                android.util.Log.d("ShareAct", "ready=" + n + " calling shareFiles");
                if (svc != null) svc.shareFiles(ready);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(app, n > 0 ? ("已提交 " + n + " 个文件，等待连接就绪后上传")
                                : "文件读取失败", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
        }, "share-copy").start();
    }

    /** 把分享进来的 content:// Uri 复制成本 App 私有缓存文件，返回其 file:// Uri 对应的 File */
    static File cacheIncoming(Context c, Uri u) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getContentResolver().openInputStream(u);
            if (in == null) return null;
            String name = fileName(c, u);
            File dir = new File(c.getCacheDir(), "share");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            out = new FileOutputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return f;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    static String fileName(Context c, Uri u) {
        String n = null;
        try {
            android.database.Cursor cur = c.getContentResolver().query(u,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try { if (cur.moveToFirst()) n = cur.getString(0); } finally { cur.close(); }
            }
        } catch (Throwable ignored) { }
        if (n == null) n = u.getLastPathSegment();
        if (n == null || n.length() == 0) n = "shared_file";
        n = n.replace('/', '_').replace('\\', '_');
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            try { unbindService(conn); } catch (Throwable ignored) { }
            bound = false;
        }
    }
}
