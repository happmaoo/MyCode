package com.myapp.myblecamc;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * MyBleCamC 设置页
 * 传输模式：延迟优先（服务端丢帧保实时，默认） / 画质优先（服务端尽量不丢帧）
 */
public class SettingsActivity extends Activity {

    // 数组下标即协议值：0 = 画质优先(MODE_QUALITY)，1 = 延迟优先(MODE_LATENCY)
    static final String[] MODES = new String[]{
            "画质优先（尽量不丢帧，延迟可能增大）",
            "延迟优先（丢帧保实时，推荐）"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("设置");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        // ---- 要连接的服务端名称 ----
        TextView lblName = new TextView(this);
        lblName.setText("要连接的服务端名称（BLE 广播名）");
        lblName.setTextColor(0xFF333333);
        lblName.setTextSize(14);
        root.addView(lblName);

        final EditText etName = new EditText(this);
        etName.setHint(MainActivity.DEFAULT_SERVER_NAME);
        etName.setSingleLine(true);
        root.addView(etName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView lbl = new TextView(this);
        lbl.setText("传输模式（连接后自动下发给服务端）");
        lbl.setTextColor(0xFF333333);
        lbl.setTextSize(14);
        lbl.setPadding(0, dp(20), 0, 0);
        root.addView(lbl);

        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, MODES));
        root.addView(sp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("画质优先：所有编码帧都发送，画面更完整，但 BLE 带宽有限，高画质下延迟会累积到数秒。\n"
                + "延迟优先：画面跟不上时直接丢弃旧帧，只显示最新画面，延迟保持在 1 秒内。");
        hint.setTextColor(0xFF888888);
        hint.setTextSize(12);
        hint.setPadding(0, dp(16), 0, 0);
        root.addView(hint);

        setContentView(root);

        final SharedPreferences prefs = getSharedPreferences("myblecamc", MODE_PRIVATE);

        // 读回当前配置
        etName.setText(prefs.getString("server_name", MainActivity.DEFAULT_SERVER_NAME));
        int mode = prefs.getInt("mode", MainActivity.MODE_LATENCY);
        sp.setSelection(mode);

        // 保存按钮：服务端名称 + 传输模式一起保存
        Button btnSave = new Button(this);
        btnSave.setText("保存");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(20);
        btnSave.setLayoutParams(bp);
        root.addView(btnSave);
        btnSave.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) name = MainActivity.DEFAULT_SERVER_NAME;
                prefs.edit()
                        .putString("server_name", name)
                        .putInt("mode", sp.getSelectedItemPosition())
                        .commit();
                android.widget.Toast.makeText(SettingsActivity.this,
                        "已保存：服务端 " + name, android.widget.Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
