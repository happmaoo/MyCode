package com.myapp.myfilemanager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;

/**
 * 文件图标工厂。
 *  - 全部主题均为真实位图：按 Util.T_* 类型编号从 assets/&lt;dir&gt;/&lt;kind&gt;.png 加载
 *    （128×128 透明 PNG，已统一居中；仅 Windows 98 像素主题用最近邻放大保留硬边，
 *      其余彩色主题用双线性平滑缩放）。
 *  - 默认主题为 elementary；扁平单色、Papirus、Qogir 三套主题已移除。
 * 主题注册表（名称/目录/缩放方式/说明）集中在此，Prefs 与设置页直接引用，避免多处维护。
 * APK 文件额外支持解析其自身的 application icon（异步解析 + 内存缓存，失败回退主题图标）。
 */
public final class IconFactory {
    private IconFactory() {}
    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();

    // 主题下标即设置页单选列表顺序，也作为持久化值；改动顺序须同步 Prefs 的旧值迁移。
    public static final int THEME_ELEMENTARY = 0;
    public static final int THEME_WIN9X      = 1;
    public static final int THEME_OXYGEN     = 2;
    public static final int THEME_BREEZE     = 3;
    public static final int THEME_ADWAITA    = 4;

    /** 默认主题：elementary。全新安装与越界值均落到这里。 */
    public static final int THEME_DEFAULT = THEME_ELEMENTARY;

    /** 主题总数（合法范围 0 .. THEME_COUNT-1）。 */
    public static final int THEME_COUNT = 5;

    /** 主题显示名（设置页单选列表顺序）。 */
    public static final String[] THEME_NAMES = {
        "elementary（默认）",
        "Windows 98 原版",
        "KDE Oxygen",
        "KDE Breeze",
        "Adwaita (GNOME)"
    };

    /** 主题风格与许可说明（设置页主题提示）。 */
    public static final String[] THEME_DESC = {
        "简洁彩色，应用默认主题。",
        "真实 Win98 像素图标，保留硬边。",
        "KDE 立体玻璃质感。",
        "KDE 现代渐变彩色。",
        "GNOME 柔和彩色。"
    };

    /** 各主题对应的 assets 子目录。 */
    private static final String[] THEME_DIRS = {
        "elementary", "v9", "oxygen", "breeze", "adwaita"
    };

    /** 是否用最近邻放大（仅 Win98 像素主题）。 */
    private static final boolean[] THEME_NEAREST = {
        false, true, false, false, false
    };

    /**
     * 旧版主题下标迁移（v2.7 及以前的 8 套）：
     * 0 扁平 / 1 Win98 / 2 Papirus / 3 Oxygen / 4 Breeze / 5 Adwaita / 6 Qogir / 7 elementary。
     * 已移除的 扁平、Papirus、Qogir 与越界值统一回退到默认 elementary。
     */
    public static int migrateTheme(int old) {
        switch (old) {
            case 1: return THEME_WIN9X;
            case 3: return THEME_OXYGEN;
            case 4: return THEME_BREEZE;
            case 5: return THEME_ADWAITA;
            case 7: return THEME_ELEMENTARY;
            default: return THEME_DEFAULT;   // 0 扁平 / 2 Papirus / 6 Qogir / 越界
        }
    }

    private static int theme = THEME_DEFAULT;
    private static AssetManager assets = null;
    private static volatile Context appCtx = null;

    // APK 内嵌图标缓存：LruCache 按字节 LRU 淘汰（不再超限全清），切换主题不影响已解析 APK 图标。
    private static final LruCache<String, Bitmap> APK_CACHE = new LruCache<String, Bitmap>(maxKb(16)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private static final HashSet<String> APK_FAIL = new HashSet<String>();

    // 图片缩略图缓存：LruCache 按字节 LRU 淘汰，按 路径+大小+修改时间+目标像素 失效。
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>(maxKb(8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private static final HashSet<String> THUMB_FAIL = new HashSet<String>();

    /** 缓存上限：进程可用内存的 1/n（KB），至少 2MB，低内存设备不致 OOM。 */
    private static int maxKb(int n) {
        long max = Runtime.getRuntime().maxMemory() / 1024;
        return (int) Math.max(2048, max / n);
    }

    /** 注入 AssetManager / Application Context（幂等）。读取真实图标或解析 APK 前必须先调用。 */
    public static synchronized void attach(Context c) {
        if (c != null) {
            Context app = c.getApplicationContext();
            if (assets == null) assets = app.getAssets();
            if (appCtx == null) appCtx = app;
        }
    }

    /** 切换主题；越界值回退到默认 elementary。变化即清空位图缓存。 */
    public static synchronized void setTheme(int t) {
        int v = (t < 0 || t >= THEME_COUNT) ? THEME_DEFAULT : t;
        if (v == theme) return;
        theme = v;
        CACHE.clear();
    }

    public static synchronized int theme() { return theme; }

    public static synchronized Bitmap get(FileItem item, int px) {
        int kind = Util.kind(item.name, item.dir, item.parent);
        String badge = Util.badge(kind, item.name);

        String key = theme + "|" + kind + "|" + badge + "|" + px;
        Bitmap b = CACHE.get(key);
        if (b == null) {
            b = drawAsset(THEME_DIRS[theme], kind, px, THEME_NEAREST[theme]);
            if (b == null && theme != THEME_DEFAULT) {
                // 当前主题资源异常时，先回退默认主题位图，保证仍是彩色图标
                b = drawAsset(THEME_DIRS[THEME_DEFAULT], kind, px, THEME_NEAREST[THEME_DEFAULT]);
            }
            if (b == null) b = drawFallback(kind, badge, px);   // 极端情况兜底，绝不空白
            CACHE.put(key, b);
        }
        return b;
    }

    /**
     * 加载真实位图主题：assets/&lt;dir&gt;/&lt;kind&gt;.png（文件名即 Util.T_* 类型编号）。
     * 源图统一为 128×128 透明 PNG 并已居中，这里只做一次缩放到目标 px。
     * nearest=true 时关闭双线性，保留 Win98 原始像素硬边；否则平滑缩放得到抗锯齿效果。
     */
    private static Bitmap drawAsset(String dir, int kind, int px, boolean nearest) {
        if (assets == null || dir == null) return null;
        InputStream in = null;
        try {
            in = assets.open(dir + "/" + kind + ".png");
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inScaled = false;              // 不按屏幕密度自动缩放
            Bitmap src = BitmapFactory.decodeStream(in, null, opt);
            if (src == null) return null;

            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint();
            p.setFilterBitmap(!nearest);
            p.setAntiAlias(!nearest);
            c.drawBitmap(src, null, new Rect(0, 0, px, px), p);
            src.recycle();
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap canvas(int px) {
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
    }

    // ==================== 资源异常兜底（不是可选主题） ====================

    /**
     * 仅当 assets 位图全部读取失败时使用的中性占位图，避免出现空白图标。
     * 它不是图标主题，设置页不提供该选项，正常运行下不会显示。
     */
    private static Bitmap drawFallback(int kind, String badge, int px) {
        Bitmap b = canvas(px);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFE6E9EC);
        c.drawRoundRect(new RectF(0, 0, px, px), px * .22f, px * .22f, p);
        String t = (badge == null || badge.isEmpty()) ? "?" : badge;
        p.setColor(0xFF8A9096);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(t.length() >= 3 ? px * .24f : px * .32f);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(t, px * .5f, px * .5f - (fm.ascent + fm.descent) / 2, p);
        return b;
    }

    // ==================== APK 内嵌图标 ====================

    /** APK 图标缓存键：路径 + 大小 + 修改时间 + 目标像素，APK 被替换后自动失效。 */
    public static String apkKey(FileItem it, int px) {
        return "apk|" + it.path + "|" + it.size + "|" + it.modified + "|" + px;
    }

    public static synchronized Bitmap apkCached(String key) { return APK_CACHE.get(key); }
    public static synchronized boolean apkFailed(String key) { return APK_FAIL.contains(key); }

    public static synchronized void markApkFailed(String key) {
        APK_FAIL.add(key);
        if (APK_FAIL.size() > 240) APK_FAIL.clear();
    }

    public static synchronized void putApk(String key, Bitmap b) {
        if (b == null) return;
        APK_CACHE.put(key, b);   // LruCache 自动按字节淘汰最久未用
    }

    // ==================== 图片缩略图 ====================

    /** 缩略图缓存键：路径 + 大小 + 修改时间 + 目标像素，图片被替换后自动失效。 */
    public static String thumbKey(FileItem it, int px) {
        return "thumb|" + it.path + "|" + it.size + "|" + it.modified + "|" + px;
    }

    public static synchronized Bitmap thumbCached(String key) { return THUMB_CACHE.get(key); }
    public static synchronized boolean thumbFailed(String key) { return THUMB_FAIL.contains(key); }

    public static synchronized void markThumbFailed(String key) {
        THUMB_FAIL.add(key);
        if (THUMB_FAIL.size() > 480) THUMB_FAIL.clear();
    }

    public static synchronized void putThumb(String key, Bitmap b) {
        if (b == null) return;
        THUMB_CACHE.put(key, b);   // LruCache 自动按字节淘汰最久未用
    }

    /**
     * 在后台线程把图片文件解码为缩略图位图（最长边约 px*2，先按原图尺寸 inSampleSize 降采样，
     * 避免大图 OOM；FIT_CENTER 的 ImageView 会再按格子尺寸等比居中显示）。
     * 解码失败（如 svg/gif 等无法静态解码）返回 null，调用方回退主题图标。
     */
    public static Bitmap loadThumb(String path, int px) {
        if (path == null || px <= 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int target = px * 2;
            int s = 1;
            int longer = Math.max(w, h);
            while (longer / (s * 2) >= target) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0) return null;
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在后台线程解析 APK 自身的 application icon，等比居中绘制成 px×px 位图。
     * 用 getPackageArchiveInfo 读归档包，并显式设置 sourceDir/publicSourceDir，
     * 否则 loadIcon 拿不到包内资源。任何异常都返回 null，调用方回退主题图标。
     */
    public static Bitmap loadApkIcon(String path, int px) {
        if (path == null || px <= 0) return null;
        Context ctx = appCtx;
        if (ctx == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi == null || pi.applicationInfo == null) return null;
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = path;
            ai.publicSourceDir = path;
            Drawable d = ai.loadIcon(pm);
            if (d == null) return null;

            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0) iw = px;
            if (ih <= 0) ih = px;
            float k = Math.min((float) px / iw, (float) px / ih);
            int dw = Math.max(1, Math.round(iw * k));
            int dh = Math.max(1, Math.round(ih * k));
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            int left = (px - dw) / 2, top = (px - dh) / 2;
            d.setBounds(left, top, left + dw, top + dh);
            d.draw(c);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
