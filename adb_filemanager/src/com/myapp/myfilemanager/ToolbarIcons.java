package com.myapp.myfilemanager;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.InputStream;
import java.util.HashMap;

/**
 * 工具栏图标：从 assets/ui/&lt;name&gt;.png 读取，按当前屏幕密度统一缩放到 ICON_DP，
 * 并设置好 bounds，可直接传给 Button.setCompoundDrawables。
 *
 * 图标取自 elementary-xfce 主题（彩色实体风格），应用启动时由 attach() 注入 AssetManager。
 * 缩放结果按「名称@像素」缓存，避免重建命令栏时反复解码；返回 null 时调用方回退为文字按钮。
 */
public final class ToolbarIcons {
    private ToolbarIcons() {}

    static final int ICON_DP = 22;
    private static AssetManager am;
    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();

    public static void attach(Context c) {
        am = c.getApplicationContext().getAssets();
        CACHE.clear();
    }

    /** 找不到资源或解码失败时返回 null。默认 22dp。 */
    public static Drawable get(Context c, String name) { return get(c, name, ICON_DP); }

    /** 指定 dp 尺寸取图标（小于默认时用于文字按钮前缀等小场景）。 */
    public static Drawable get(Context c, String name, int dp) {
        if (am == null || name == null) return null;
        int px = Util.dp(c, dp);
        String key = name + "@" + px;
        Bitmap bmp = CACHE.get(key);
        if (bmp == null) {
            InputStream in = null;
            try {
                in = am.open("ui/" + name + ".png");
                Bitmap raw = BitmapFactory.decodeStream(in);
                if (raw != null) {
                    bmp = Bitmap.createScaledBitmap(raw, px, px, true);
                    if (bmp != raw) raw.recycle();
                    CACHE.put(key, bmp);
                }
            } catch (Exception ignored) {
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
            }
        }
        if (bmp == null) return null;
        Drawable d = new BitmapDrawable(c.getResources(), bmp);
        d.setBounds(0, 0, px, px);
        return d;
    }
}
