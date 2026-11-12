package com.myapp.myfilemanager;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.myapp.myfilemanager.Prefs;
import com.myapp.myfilemanager.Ui;
import com.myapp.myfilemanager.Util;

/** 关于页：展示版本号、应用介绍与更新历史。 */
public class AboutActivity extends Activity {

    private int textLv = 1;

    // 主要功能演进（从新到旧）
    private static final String[][] HISTORY = {
        {"v2.51", "自定义命令支持 %pid:插件id 调用插件（无视扩展名直接调用）；外部 APP 直接打开插件 Activity"},
        {"v2.50.0025", "地址栏长按 2 秒复制当前路径到剪贴板"},
        {"v2.50.0024", "支持从外部 APP 以 content:// 方式打开（复制并定位文件）"},
        {"v2.50.0023", "重写「外部打开」判定：直接看 Intent action，不再依赖解析出真实路径"},
        {"v2.50.0022", "从外部 APP 打开后，按返回键直接退回发起的外部 APP"},
        {"v2.50.0021", "自定义格式关联（如 apkm）生效：打通压缩包与图片查看器识别关卡"},
        {"v2.50.0018", "属性「计算大小」回退到独立结果窗口流式输出"},
        {"v2.50.0017", "插件关联格式改为全局保存按钮；修复单文件解压多套文件夹"},
        {"v2.50.0016", "插件设置增加「关联格式」输入框（逗号分隔、可保存）"},
        {"v2.50.0015", "解压仅冲突时提示覆盖；图片查看器去顶栏文字、底部显示格式行"},
        {"v2.50.0013", "解压按条目数分支（单文件直解 / 多文件建文件夹）+ 进度条"},
        {"v2.50.0012", "archive:// 工具栏重构：解压按钮、列表背景色、新建标签到内部存储"},
    };

    private TextView text(String s, float s0, float m, float l, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(Ui.sp(textLv, s0, m, l));
        t.setTextColor(color);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        textLv = Prefs.textLevel(this);
        getWindow().setBackgroundDrawableResource(android.R.color.white);
        setContentView(buildUi());
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.isEmpty()) return pi.versionName;
        } catch (Exception e) { /* ignore */ }
        return "未知";
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Util.dp(this, 6), Util.dp(this, 8), Util.dp(this, 8), Util.dp(this, 8));
        Button back = Ui.flat(this, "‹ 返回", textLv);
        back.setTextColor(Ui.SUB);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        top.addView(back);
        TextView title = text("关于", 16, 18, 20, Ui.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.line(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));

        // 内容
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int cp = Util.dp(this, 16);
        content.setPadding(cp, cp, cp, cp);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 应用图标 + 名称 + 版本号
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, Util.dp(this, 10));
        try {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.mipmap.ic_launcher);
            int sz = Util.dp(this, 56);
            head.addView(icon, new LinearLayout.LayoutParams(sz, sz));
        } catch (Exception e) { /* 无图标资源则跳过 */ }
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(Util.dp(this, 12), 0, 0, 0);
        TextView name = text("MyFileman", 18, 20, 22, Ui.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        meta.addView(name);
        TextView ver = text("版本 " + versionName(), 12, 13, 15, Ui.SUB);
        ver.setPadding(0, Util.dp(this, 4), 0, 0);
        meta.addView(ver);
        head.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(head);

        // 应用介绍
        TextView intro = text("MyFileman是我做的一款文件管理APP，当初是AI十分强大我写一些APP感觉还不错，后来感觉市面上的文件管理器功能太少，特别是自定义功能，玩法少不方便，所以就结合AI一点点写出来的，主打自定义功能。可以自定义按钮设置你想要的命令。也欢迎大家进行再加工，但是保留来源版权声明。", 13, 14, 16, Ui.TEXT);
        intro.setLineSpacing(Util.dp(this, 4), 1.2f);
        intro.setPadding(0, Util.dp(this, 4), 0, Util.dp(this, 12));
        content.addView(intro);

        // 更新历史
        TextView histTitle = text("更新历史", 15, 17, 19, Ui.PRIMARY);
        histTitle.setTypeface(null, Typeface.BOLD);
        histTitle.setPadding(0, Util.dp(this, 6), 0, Util.dp(this, 8));
        content.addView(histTitle);

        for (String[] row : HISTORY) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setPadding(0, Util.dp(this, 6), 0, Util.dp(this, 6));
            TextView v = text(row[0], 12, 13, 15, Ui.PRIMARY);
            v.setTypeface(null, Typeface.BOLD);
            v.setMinWidth(Util.dp(this, 96));
            item.addView(v);
            TextView d = text(row[1], 12, 13, 15, Ui.TEXT);
            d.setPadding(Util.dp(this, 8), 0, 0, 0);
            item.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            content.addView(item);
            content.addView(Ui.line(this), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        }

        return root;
    }
}
