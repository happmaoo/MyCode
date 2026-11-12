package com.myapp.myfilemanager;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.myapp.myfilemanager.Prefs;
import com.myapp.myfilemanager.plugin.PluginInfo;
import com.myapp.myfilemanager.plugin.PluginManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「插件」页面：列出已发现的插件（内置 + 外部 APK），展示其名称、版本、能力、支持格式。
 * 数据在后台线程经 PluginManager 获取，避免阻塞界面；插件进程异常时自动跳过。
 */
public class PluginsActivity extends Activity {
    private LinearLayout listBox;
    private TextView empty;
    private PluginManager pm;
    /** 各插件「关联格式」输入框，key = 插件 id，供右上角「保存」统一提交。 */
    private final Map<String, EditText> assocInputs = new HashMap<String, EditText>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        pm = new PluginManager(this);
        load();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶部应用栏（MD1：白底、青绿返回、粗体标题）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(Util.dp(this, 12), Util.dp(this, 12), Util.dp(this, 12), Util.dp(this, 12));
        Button back = Ui.flat(this, "返回", 1);
        back.setTextColor(Ui.PRIMARY);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("插件");
        title.setTextSize(18);
        title.setTextColor(Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(Util.dp(this, 8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右上角全局「保存」：一次性提交所有插件的关联格式
        Button saveAll = Ui.flat(this, "保存", 1);
        saveAll.setTextColor(Ui.PRIMARY);
        saveAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveAllAssoc(); }
        });
        bar.addView(saveAll);
        root.addView(bar);
        root.addView(Ui.line(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));

        // 列表
        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(Util.dp(this, 12), Util.dp(this, 12), Util.dp(this, 12), Util.dp(this, 12));
        sv.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("正在加载插件…");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(Ui.sp(1, 13, 14, 16));
        empty.setTextColor(Ui.HINT);
        empty.setPadding(0, Util.dp(this, 32), 0, 0);
        listBox.addView(empty);

        return root;
    }

    private void load() {
        new Thread(new Runnable() {
            public void run() {
                final List<PluginInfo> infos = pm.listPlugins();
                runOnUiThread(new Runnable() {
                    public void run() { render(infos); }
                });
            }
        }).start();
    }

    private void render(List<PluginInfo> infos) {
        listBox.removeAllViews();
        if (infos == null || infos.isEmpty()) {
            empty.setText("未发现插件");
            listBox.addView(empty);
            return;
        }
        for (PluginInfo info : infos) listBox.addView(card(info));
    }

    private View card(final PluginInfo info) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFF8F9FA);
        card.setPadding(Util.dp(this, 14), Util.dp(this, 12), Util.dp(this, 14), Util.dp(this, 12));
        int m = Util.dp(this, 10);
        LinearLayout.MarginLayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = m;
        card.setLayoutParams(lp);

        // 标题行：名称 + 启用复选框（右对齐）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(info.name != null ? info.name : info.id);
        name.setTextSize(16);
        name.setTextColor(Ui.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final CheckBox enabled = new CheckBox(this);
        enabled.setText("启用");
        enabled.setTextSize(Ui.sp(1, 11, 12, 14));
        enabled.setTextColor(Ui.SUB);
        enabled.setChecked(Prefs.pluginEnabled(this, info.id));
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton btn, boolean on) {
                Prefs.setPluginEnabled(PluginsActivity.this, info.id, on);
                if (!on) {
                    // 禁用：立即释放本页绑定的该插件，并通知主界面释放其持有的绑定（退出插件进程）
                    pm.release();
                    Toast.makeText(PluginsActivity.this,
                            (info.name != null ? info.name : info.id) + " 已禁用，已释放资源",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(PluginsActivity.this,
                            (info.name != null ? info.name : info.id) + " 已启用",
                            Toast.LENGTH_SHORT).show();
                }
                // 广播给主界面：释放/重新评估插件绑定（禁用后不再占用独立进程）
                sendBroadcast(new Intent(PluginManager.ACTION_PLUGIN_STATE));
            }
        });
        head.addView(enabled);
        card.addView(head);

        TextView sub = new TextView(this);
        sub.setText("id: " + info.id + "   版本: " + info.versionCode
                + "   要求主程序 ≥ " + info.minHostVersion);
        sub.setTextSize(Ui.sp(1, 11, 12, 14));
        sub.setTextColor(Ui.SUB);
        sub.setPadding(0, Util.dp(this, 4), 0, 0);
        card.addView(sub);

        // 能力
        StringBuilder caps = new StringBuilder();
        if (info.capabilities != null) {
            for (String c : info.capabilities) {
                caps.append(capLabel(c)).append("  ");
            }
        }
        TextView cap = new TextView(this);
        cap.setText("能力：" + (caps.length() > 0 ? caps.toString().trim() : "无"));
        cap.setTextSize(Ui.sp(1, 11, 12, 14));
        cap.setTextColor(Ui.PRIMARY);
        cap.setPadding(0, Util.dp(this, 4), 0, 0);
        card.addView(cap);

        // 支持格式
        if (info.extensions != null && info.extensions.length > 0) {
            TextView ext = new TextView(this);
            ext.setText("支持格式：" + join(info.extensions));
            ext.setTextSize(Ui.sp(1, 11, 12, 14));
            ext.setTextColor(Ui.SUB);
            ext.setPadding(0, Util.dp(this, 2), 0, 0);
            card.addView(ext);
        }

        // 关联格式：输入框（预填默认/已存值，逗号分隔）。统一由右上角「保存」提交，不在卡片内重复放置按钮。
        LinearLayout assocRow = new LinearLayout(this);
        assocRow.setOrientation(LinearLayout.HORIZONTAL);
        assocRow.setGravity(Gravity.CENTER_VERTICAL);
        assocRow.setPadding(0, Util.dp(this, 8), 0, 0);
        TextView lbl = new TextView(this);
        lbl.setText("关联格式：");
        lbl.setTextSize(Ui.sp(1, 11, 12, 14));
        lbl.setTextColor(Ui.SUB);
        assocRow.addView(lbl);
        final EditText input = new EditText(this);
        input.setText(Prefs.pluginAssocRaw(this, info.id));
        input.setHint("jpg,gif,...");
        input.setTextSize(Ui.sp(1, 11, 12, 14));
        input.setSingleLine(true);
        input.setPadding(Util.dp(this, 6), Util.dp(this, 4), Util.dp(this, 6), Util.dp(this, 4));
        input.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(ip);
        assocRow.addView(input);
        assocInputs.put(info.id, input);   // 登记到全局表，供右上角「保存」统一读取
        card.addView(assocRow);
        return card;
    }

    private static String capLabel(String c) {
        if ("archive".equals(c)) return "压缩包浏览";
        if ("viewer".equals(c)) return "文件预览";
        return c;
    }

    /** 右上角「保存」：一次性提交所有插件的关联格式（按 id 读取输入框内容写入设置）。 */
    private void saveAllAssoc() {
        int n = 0;
        for (Map.Entry<String, EditText> e : assocInputs.entrySet()) {
            String raw = e.getValue().getText().toString().trim();
            Prefs.setPluginAssoc(this, e.getKey(), raw);
            n++;
        }
        // 关联格式变化后通知主界面重新评估插件接管（读取最新关联集合）
        sendBroadcast(new Intent(PluginManager.ACTION_PLUGIN_STATE));
        Toast.makeText(this, "已保存 " + n + " 个插件的关联格式", Toast.LENGTH_SHORT).show();
    }

    private static String join(String[] a) {
        if (a == null || a.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(a[i]);
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        if (pm != null) pm.release();
        super.onDestroy();
    }
}
