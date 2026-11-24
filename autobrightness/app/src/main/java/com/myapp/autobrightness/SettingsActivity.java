package com.myapp.autobrightness;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;

import java.util.Locale;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页：只有一套算法（连续 lmd 次偏离即切档），
 * 这里只提供一个复选框 + 输入框，用来开关“确认窗口期”这层时间兜底。
 * 值写进同一个 SharedPreferences，前台服务注册了 prefs 监听，改完立刻生效。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "app_config";

    private CheckBox cbWindow;
    private EditText etWindow;
    private SharedPreferences pref;
    /** 回显阶段置位：避免程序化 setChecked/setText 反过来触发写盘 */
    private boolean binding = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
            bar.setTitle("设置");
        }

        pref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        cbWindow = findViewById(R.id.cb_window);
        etWindow = findViewById(R.id.et_window);

        // 回显：窗口兜底默认开启，窗口期留空 = 10 秒
        binding = true;
        boolean enabled = pref.getBoolean(autobrightness.KEY_WINDOW_ENABLE, true);
        cbWindow.setChecked(enabled);
        int windowMs = pref.getInt(autobrightness.KEY_WINDOW_MS, 0);
        if (windowMs > 0) {
            etWindow.setText(String.format(Locale.US, "%.1f", windowMs / 1000f));
        }
        binding = false;
        etWindow.setEnabled(enabled);      // 没勾就不让填

        cbWindow.setOnCheckedChangeListener((view, checked) -> {
            if (binding) return;
            // commit 而不是 apply：勾完马上返回、服务也可能正在被回收，不能丢
            pref.edit().putBoolean(autobrightness.KEY_WINDOW_ENABLE, checked).commit();
            etWindow.setEnabled(checked);
        });

        etWindow.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (binding) return;
                String t = s.toString().trim();
                if (t.length() == 0) {
                    // 清空 = 回到默认（10 秒）
                    pref.edit().remove(autobrightness.KEY_WINDOW_MS).commit();
                    return;
                }
                try {
                    float sec = Float.parseFloat(t);
                    int ms = autobrightness.clampWindowMs(Math.round(sec * 1000f));   // 2–30 秒
                    pref.edit().putInt(autobrightness.KEY_WINDOW_MS, ms).commit();
                } catch (NumberFormatException ignored) {
                    // 输到一半（比如 "1." ）先不动，等输入完整
                }
            }
        });

        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
