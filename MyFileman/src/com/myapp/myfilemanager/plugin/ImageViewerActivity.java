package com.myapp.myfilemanager.plugin;

import android.app.Activity;
import com.myapp.myfilemanager.R;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.myapp.myfilemanager.Util;
import com.caverock.androidsvg.SVG;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 图片查看器插件的预览界面，运行在独立进程 :plugin.imageviewer。
 * 通过宿主传来的真实文件路径加载图片（普通路径直接解码；root 专属路径走 su cat 回退），
 * 提供双指缩放 / 拖拽 / 双击放大的查看体验。
 * 位图走 BitmapFactory；SVG / SVGZ 走 AndroidSVG 解析后渲染成随视图尺寸的高清位图（GPU 缩放）。
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
        com.myapp.myfilemanager.Dbg.log("ACTIVITY", "ImageViewerActivity.onCreate");
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
        // SVG / SVGZ 是 XML 文本，BitmapFactory 无法解码，单独走矢量渲染分支。
        if (isSvgPath(path)) {
            status.setText(name + " [SVG] 渲染中…");
            loadSvg();
            return;
        }
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

    /** SVG / SVGZ 走这里：AndroidSVG 解析后渲染成位图（长边 800px），复用位图查看路径。 */
    private void loadSvg() {
        final boolean gzip = isSvgz(path);
        SVG svg = null;

        // 1) 公共目录等普通路径：直接读文件；SVGZ 需先 gzip 解压。
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            if (gzip) in = new GZIPInputStream(in);   // 关闭时级联关底层文件流，释放 Inflater
            svg = SVG.getFromInputStream(in);
        } catch (Throwable ignored) {
        } finally { closeQuietly(in); }

        // 2) root 专属路径回退：su cat 读出字节流再解析。
        if (svg == null) svg = loadSvgViaSu(gzip);
        if (svg == null) {
            status.setText("无法加载 SVG（文件损坏或该路径需要 root 才能读取）");
            return;
        }

        int[] wh = svgSize(svg);
        final int w = wh[0], h = wh[1];
        final String info = name + " [SVG " + w + "x" + h + "] (" + humanSize(fileSize(path)) + ")";
        final SVG parsed = svg;   // 匿名线程内引用需 effectively final

        // 后台线程渲染位图，完成后回主线程显示。
        new Thread(new Runnable() {
            public void run() {
                Bitmap rendered = null;
                try {
                    rendered = renderSvgBitmap(parsed, w, h);
                } catch (Throwable t) {
                    com.myapp.myfilemanager.Dbg.log("IMAGEVIEWER", "SVG 渲染失败: " + t);
                }
                if (rendered == null) {
                    runOnUiThread(new Runnable() {
                        public void run() { if (!isFinishing()) status.setText("SVG 渲染失败"); }
                    });
                    return;
                }
                final Bitmap b = rendered;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        status.setText(info);
                        img.setBitmap(b, true);   // SVG 位图允许初始放大铺满视图（居中适应）
                    }
                });
            }
        }, "svg-render").start();
    }

    /** root 专属路径：su cat 读出 SVG 字节流再解析；SVGZ 同样先解压。 */
    private SVG loadSvgViaSu(final boolean gzip) {
        Process pr = null;
        InputStream is = null;
        try {
            String q = "'" + path.replace("'", "'\\''") + "'";
            pr = execSu("cat " + q);
            is = pr.getInputStream();
            if (gzip) is = new GZIPInputStream(is);   // 关闭时级联关底层进程流
            return SVG.getFromInputStream(is);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(is);
            if (pr != null) pr.destroy();
        }
    }

    /**
     * 取 SVG 文档的原始尺寸：优先文档宽高（百分比/缺失时为 -1），其次 viewBox，最后兜底正方形。
     * 只用于底部信息栏展示；实际渲染分辨率由视图尺寸决定（见 renderSvgBitmap）。
     */
    private static int[] svgSize(SVG svg) {
        float w = -1, h = -1;
        try { w = svg.getDocumentWidth(); h = svg.getDocumentHeight(); } catch (Throwable ignored) {}
        if (w <= 0 || h <= 0) {
            RectF vb = null;
            try { vb = svg.getDocumentViewBox(); } catch (Throwable ignored) {}
            if (vb != null && vb.width() > 0 && vb.height() > 0) { w = vb.width(); h = vb.height(); }
        }
        if (w <= 0 || h <= 0) { w = 512; h = 512; }
        return new int[]{ Math.max(1, Math.round(w)), Math.max(1, Math.round(h)) };
    }

    /**
     * 把 SVG 渲染成位图：长边固定 800px（宽高按内容原始比例）。
     * 用 renderToPicture()（无参）走库的标准路径——文档尺寸/viewBox/百分比单位/物理单位
     * 全部由库按规范自行解析（带参版本对特殊文档会算错缩放，导致内容缩在角落），
     * 拿到 Picture 后由我们自己等比缩放绘制到位图，缩放决策完全自主、不依赖库的 viewport 推导。
     */
    private Bitmap renderSvgBitmap(final SVG svg, final int dw, final int dh) throws Throwable {
        Picture pic = svg.renderToPicture();
        int pw = pic.getWidth(), ph = pic.getHeight();
        if (pw <= 0 || ph <= 0) { pw = dw; ph = dh; }
        if (pw <= 0 || ph <= 0) { pw = 512; ph = 512; }

        float k = 800f / Math.max(pw, ph);
        int rw = Math.max(1, Math.round(pw * k)), rh = Math.max(1, Math.round(ph * k));
        Bitmap b = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        c.drawPicture(pic);
        return b;
    }

    private static boolean isSvgPath(String p) {
        if (p == null) return false;
        String s = p.toLowerCase(Locale.US);
        return s.endsWith(".svg") || s.endsWith(".svgz");
    }

    private static boolean isSvgz(String p) {
        return p != null && p.toLowerCase(Locale.US).endsWith(".svgz");
    }

    private static void closeQuietly(InputStream is) {
        if (is != null) try { is.close(); } catch (Throwable ignored) {}
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
            Process pr = execSu("wc -c < " + q);
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

    /** su 是否支持 -mm（mount master，进 root 挂载命名空间）；首次调用探测一次后缓存。 */
    private static Boolean suMmOk;
    private static boolean suSupportsMm() {
        if (suMmOk == null) {
            boolean ok = false;
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"su", "-mm", "-c", "echo ok"});
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(pr.getInputStream()));
                String line = br.readLine();
                br.close();
                pr.destroy();
                ok = line != null && "ok".equals(line.trim());
            } catch (Throwable t) { ok = false; }
            suMmOk = ok;
        }
        return suMmOk;
    }

    /** 启动 root 命令：优先 su -mm（Magisk 私有目录必需 mount master，普通 su 命名空间看不到）；
     *  不支持 -mm 的 su 回退普通 su -c。返回已启动的进程，调用方读其 stdout。 */
    private Process execSu(String cmd) throws java.io.IOException {
        if (suSupportsMm())
            return Runtime.getRuntime().exec(new String[]{"su", "-mm", "-c", cmd});
        return Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
    }

    /** root 专属路径：su cat 读出字节流再解码。 */
    private Bitmap loadViaSu(String p) {
        try {
            String q = "'" + p.replace("'", "'\\''") + "'";
            Process pr = execSu("cat " + q);
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
