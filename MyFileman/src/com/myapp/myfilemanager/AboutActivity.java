package com.myapp.myfilemanager;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 关于页：正文用 WebView 加载 assets/about.html，运行时替换其中的占位符。
 * 占位符统一采用 %大写% 格式，例如：
 *   %APPICON%  应用图标（替换为内嵌 base64 的 <img> 标签）
 *   %APPNAME%  应用名称
 *   %VERSION%  版本号
 * 以后要改介绍 / 更新历史，只需编辑 assets/about.html，无需改动或重新编译 Java 代码。
 */
public class AboutActivity extends Activity {

    private int textLv = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dbg.log("ACTIVITY", "AboutActivity.onCreate");
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

    /** 读取 assets 下的 UTF-8 文本文件。 */
    private String readAsset(String name) {
        try {
            InputStream is = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "<p>无法读取 " + name + "：" + e.getMessage() + "</p>";
        }
    }

    /** 把应用图标转成 <img> 标签（base64 data URI），供 WebView 直接显示，无需额外资源文件。 */
    private String appIconTag() {
        try {
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            return "<img class=\"appicon\" src=\"data:image/png;base64," + b64 + "\"/>";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 用实际值替换 html 里的 %XXX% 占位符；未知占位符原样保留，便于排查与后续扩展。 */
    private String fillPlaceholders(String html) {
        return html.replace("%APPICON%", appIconTag())
                   .replace("%APPNAME%", getString(R.string.app_name))
                   .replace("%VERSION%", versionName());
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
        TextView title = new TextView(this);
        title.setText("关于");
        title.setTextSize(Ui.sp(textLv, 16, 18, 20));
        title.setTextColor(Ui.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.line(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));

        // 内容区：WebView 加载 assets/about.html（占位符已替换）
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.getSettings().setDefaultTextEncodingName("UTF-8");
        web.getSettings().setJavaScriptEnabled(false);   // 纯静态展示，不启用脚本
        String html = fillPlaceholders(readAsset("about.html"));
        // baseURL 指向 assets：占位符替换后用 loadData 呈现，同时支持 html 内引用 assets 相对资源
        web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }
}
