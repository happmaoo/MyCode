package com.myapp.myfilemanager;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;

/** MD 扁平风格：统一配色、字体档位与控件样式。 */
public final class Ui {
    private Ui() {}

    /**
     * 从设置加载并重写着重色。hex 形如 "FD5555"（不含 #，支持 3/6/8 位）；空串或解析失败则回退默认色。
     * 应在构建任何界面之前调用（如 MainActivity.onCreate / SettingsActivity 初始化）。
     */
    public static void loadAccent(Context c) {
        String hex = Prefs.accent(c);
        PRIMARY = parseAccent(hex, PRIMARY_DEFAULT);
    }

    /** 解析十六进制颜色；空/非法时返回 fallback。自动补前缀 #，支持 #RGB / #RRGGBB / #AARRGGBB。 */
    public static int parseAccent(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.trim();
        if (h.length() == 0) return fallback;
        if (h.charAt(0) != '#') h = "#" + h;
        try {
            int c = Color.parseColor(h);
            // 未指定 alpha 时强制不透明，避免浅色块
            if ((c >>> 24) == 0) c = c | 0xFF000000;
            return c;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 默认着重色（teal）。可在「设置 → 显示 → 着重色」中自定义。 */
    public static final int PRIMARY_DEFAULT = 0xFF00897B;
    /** 当前着重色（运行期可变，由设置加载）。各处读取 Ui.PRIMARY 即取最新值。 */
    public static int PRIMARY = PRIMARY_DEFAULT;
    public static final int PRIMARY_SOFT = 0xFFCCCCCC;  // 选中背景（灰色）
    public static final int TEXT = 0xFF202124;          // 主文字
    public static final int SUB = 0xFF5F6368;           // 次文字
    public static final int HINT = 0xFF9AA0A6;          // 提示文字
    public static final int LINE = 0xFFE3E6E9;          // 细分隔线
    public static final int SURFACE = 0xFFF8F9FA;       // 浅灰面
    public static final int TAB_ACTIVE_BG = 0xFFE7EAEE; // 选中 Tab 的浅灰底

    /** 按字体档位 0小 / 1中 / 2大 取字号（sp）。 */
    public static float sp(int level, float s, float m, float l) {
        if (level <= 0) return s;
        if (level == 1) return m;
        return l;
    }

    /** 一行水平细分隔线。 */
    public static View line(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        return v;
    }

    /** 无填充的扁平文字按钮，仅保留系统水波纹按压反馈。 */
    public static Button flat(Context c, String text, int level) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(sp(level, 11, 12, 14));
        b.setTextColor(PRIMARY);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int hp = Util.dp(c, 10), vp = Util.dp(c, 6);
        b.setPadding(hp, vp, hp, vp);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) b.setBackgroundResource(tv.resourceId);
        else b.setBackgroundColor(0x00000000);
        return b;
    }

    /** MD1 默认风格按钮：直接用主题自带的 Button 样式（accent 填充、系统圆角/水波纹、
     *  标准高度），不套自定义背景。仅关掉自动转大写，保持文字原样。 */
    public static Button solid(Context c, String text, int level) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(sp(level, 12, 13, 15));
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }
}
