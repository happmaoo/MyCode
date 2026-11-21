package com.myapp.autobrightness;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import java.util.Locale;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置页：只做算法切换。
 * 写进同一个 SharedPreferences（键 algo），前台服务里注册了 prefs 监听，
 * 所以改完立刻生效，不需要重启服务，也不用重新点 Save。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "app_config";

    private RadioGroup rgAlgo;
    private EditText etWindow;
    private SharedPreferences pref;
    /** 回显阶段置位：避免程序化 setText 反过来触发写盘 */
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
        rgAlgo = findViewById(R.id.rg_algo);

        // 回显当前算法：默认阶梯
        int algo = pref.getInt(autobrightness.KEY_ALGO, autobrightness.ALGO_STEP);
        rgAlgo.check(algo == autobrightness.ALGO_COUNT ? R.id.rb_count : R.id.rb_step);

        // 确认窗口期：留空表示用 lmd×0.5s 自动算（输入框 hint 里给出参考值）
        etWindow = findViewById(R.id.et_window);
        binding = true;
        int windowMs = pref.getInt(autobrightness.KEY_WINDOW_MS, 0);
        if (windowMs > 0) {
            etWindow.setText(String.format(Locale.US, "%.1f", windowMs / 1000f));
        }
        binding = false;
        etWindow.setEnabled(algo != autobrightness.ALGO_COUNT);   // 只有阶梯阈值用得到
        etWindow.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (binding) return;
                String t = s.toString().trim();
                if (t.length() == 0) {
                    // 清空 = 回到自动（lmd×0.5s）
                    pref.edit().remove(autobrightness.KEY_WINDOW_MS).commit();
                    return;
                }
                try {
                    float sec = Float.parseFloat(t);
                    int ms = Math.round(sec * 1000f);
                    if (ms < 300) ms = 300;          // 下限 0.3 秒，避免设成 0 疯狂调亮度
                    if (ms > 10000) ms = 10000;      // 上限 10 秒
                    pref.edit().putInt(autobrightness.KEY_WINDOW_MS, ms).commit();
                } catch (NumberFormatException ignored) {
                    // 输到一半（比如 "1." ）先不动，等输入完整
                }
            }
        });

        rgAlgo.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int algo = (checkedId == R.id.rb_count)
                        ? autobrightness.ALGO_COUNT : autobrightness.ALGO_STEP;
                if (algo == pref.getInt(autobrightness.KEY_ALGO, autobrightness.ALGO_STEP)) {
                    return;   // 回显触发的回调，不重复写盘
                }
                // commit 而不是 apply：切完马上按返回、服务也可能正在被回收，不能丢
                pref.edit().putInt(autobrightness.KEY_ALGO, algo).commit();
                // 事件计数模式不用窗口期，输入框跟着禁用（值保留，切回来还在）
                etWindow.setEnabled(algo != autobrightness.ALGO_COUNT);
                Toast.makeText(SettingsActivity.this,
                        "已切换到：" + algoName(algo) + "，立即生效", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_done).setOnClickListener(v -> finish());
    }

    private String algoName(int algo) {
        return (algo == autobrightness.ALGO_COUNT) ? "事件计数（原逻辑）" : "阶梯阈值";
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
