package com.myapp.myfilemanager.plugin;

import android.app.Activity;
import com.myapp.myfilemanager.R;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.myapp.myfilemanager.Util;

import java.io.File;
import java.io.InputStream;

/**
 * 图片查看器插件的预览界面，运行在独立进程 :plugin.imageviewer。
 * 通过宿主传来的真实文件路径加载图片（普通路径直接解码；root 专属路径走 su cat 回退），
 * 提供双指缩放 / 拖拽 / 双击放大的查看体验。
 * 顶栏与底栏为白底 MD1 风格，并在底部注明图片格式、像素尺寸与文件大小。
 */
public class ImageViewerActivity extends Activity {
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_MIME = "mime";

    private ZoomImageView img;
    private android.widget.TextView status;
    private String path;
    private String name;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        path = getIntent().getStringExtra(EXTRA_PATH);
        name = (path == null || path.indexOf('/') < 0) ? "" : path.substring(path.lastIndexOf('/') + 1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);   // 白底（MD1 风格）

        // 顶栏：返回按钮 + 图片信息（文件名/像素/大小）。信息置于顶部，便于一眼看清；不占用图片区域。
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFEFEFEF);
        bar.setPadding(Util.dp(this, 6), Util.dp(this, 4), Util.dp(this, 6), Util.dp(this, 4));
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundColor(0x00000000);
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        back.setPadding(Util.dp(this, 10), Util.dp(this, 8), Util.dp(this, 10), Util.dp(this, 8));
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        bar.addView(back);
        status = new android.widget.TextView(this);
        status.setText("加载中…");
        status.setTextColor(0xFF5F6368);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        status.setTextSize(13);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        img = new ZoomImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(img);

        setContentView(root);
        load();
    }

    private void load() {
        if (path == null) { status.setText("未指定图片路径"); return; }
        // 先用 inJustDecodeBounds 取尺寸与格式（不解码全图，省内存）
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int w = opts.outWidth, h = opts.outHeight;
        String fmt = formatOf(opts.outMimeType, path);

        Bitmap bm = BitmapFactory.decodeFile(path);
        if (bm == null) bm = loadViaSu(path);   // root 专属路径回退
        if (bm == null) {
            status.setText("无法加载图片（该路径可能需要 root 才能读取）");
            return;
        }
        if (w <= 0 || h <= 0) { w = bm.getWidth(); h = bm.getHeight(); }
        if (fmt == null) fmt = formatOf(null, path);
        long size = fileSize(path);

        // 底部信息栏：name.jpg [600x800] (500.0 KB)
        status.setText(name + " [" + w + "x" + h + "] (" + humanSize(size) + ")");
        img.setBitmap(bm);
    }

    private static String formatOf(String mime, String p) {
        if (mime != null && mime.startsWith("image/")) {
            String s = mime.substring(6).toUpperCase();
            return "JPEG".equals(s) ? "JPG" : s;
        }
        if (p != null && p.indexOf('.') >= 0) return p.substring(p.lastIndexOf('.') + 1).toUpperCase();
        return "未知";
    }

    /** 取文件大小：普通路径用 File.length；root 专属路径回退 su wc -c。 */
    private long fileSize(String p) {
        File f = new File(p);
        if (f.exists()) return f.length();
        try {
            String q = "'" + p.replace("'", "'\\''") + "'";
            Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "wc -c < " + q});
            InputStream is = pr.getInputStream();
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = is.read()) != -1 && ch != '\n') sb.append((char) ch);
            is.close();
            pr.destroy();
            return Long.parseLong(sb.toString().trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String humanSize(long b) {
        if (b < 0) return "未知";
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format("%.1f %s", v, u[i]);
    }

    /** root 专属路径：经 su cat 读出字节流再解码。 */
    private Bitmap loadViaSu(String p) {
        try {
            String q = "'" + p.replace("'", "'\\''") + "'";
            Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + q});
            InputStream is = pr.getInputStream();
            Bitmap bm = BitmapFactory.decodeStream(is);
            is.close();
            pr.destroy();
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }
}
