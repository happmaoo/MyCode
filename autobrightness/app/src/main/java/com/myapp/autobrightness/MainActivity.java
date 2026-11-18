package com.myapp.autobrightness;

import androidx.appcompat.app.AppCompatActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "app_config";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_ENABLE = "enable";
    private static final String ACTION_LIGHT_UPDATE = "com.myapp.LIGHT_LEVEL_UPDATE";
    private static final String EXTRA_CONFIG = "arg-config";

    private static final String DEFAULT_CONFIG = "lmd:3/2\n0 50\n20 250";

    private TextView lightLevelTextView;
    private EditText editText;
    private CheckBox checkbox;

    private SharedPreferences sharedPref;

    /**
     * 程序自己调用 setChecked() 时置位，用来区分“用户点击”和“代码/系统回填状态”，
     * 否则回填动作会走一遍启用/停用逻辑，把持久化的开关值改掉。
     */
    private boolean settingCheckedSilently = false;

    /** 广播是否已注册。注册/注销严格配对，避免重复注册或 IllegalArgument 异常 */
    private boolean lightReceiverRegistered = false;

    /** 通知栏按钮改开关时，界面上的复选框要跟着变，不需要用户手动刷 */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (!KEY_ENABLE.equals(key)) {
                        return;
                    }
                    boolean enabled = sp.getBoolean(KEY_ENABLE, false);
                    setCheckBoxSilently(enabled);
                    if (enabled) {
                        registerLightReceiver();
                    } else {
                        unregisterLightReceiver();
                        if (lightLevelTextView != null) {
                            lightLevelTextView.setText("");
                        }
                    }
                }
            };

    private final BroadcastReceiver lightLevelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (lightLevelTextView == null) {
                return;
            }
            // 获取当前的屏幕亮度
            int currentBrightness = 0;
            try {
                currentBrightness = Settings.System.getInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS);
            } catch (Settings.SettingNotFoundException e) {
                Log.w(TAG, "读取当前亮度失败", e);
            }
            // 从广播中获取光照强度数据
            float lightLevel = intent.getFloatExtra("lightLevel", -1);
            lightLevelTextView.setText("光线传感器: " + lightLevel + " lux" + "\n亮度: " + currentBrightness);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        checkbox = findViewById(R.id.checkBox);
        editText = findViewById(R.id.editTextTextMultiLine);
        lightLevelTextView = findViewById(R.id.text);

        // 开关的勾选状态只认 SharedPreferences 这一个来源。
        // 关掉 View 的状态保存，防止系统在 Activity 重建时把旧的勾选状态回填回来，
        // 再经 OnCheckedChangeListener 反过来覆盖掉持久化的真实值。
        checkbox.setSaveEnabled(false);

        ensureDefaultConfig();

        editText.setText(sharedPref.getString(KEY_CONFIG, ""));

        boolean enabled = sharedPref.getBoolean(KEY_ENABLE, false);
        setCheckBoxSilently(enabled);

        checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (settingCheckedSilently) {
                return;
            }
            // 值没变（例如状态回填）就不要重写配置、更不要起停服务
            if (isChecked == sharedPref.getBoolean(KEY_ENABLE, false)) {
                return;
            }
            applyEnabled(isChecked);
        });

        checkWriteSettingsPermission();
    }

    /** 首次运行写入默认配置 */
    private void ensureDefaultConfig() {
        if (!sharedPref.contains(KEY_CONFIG)) {
            sharedPref.edit()
                    .putString(KEY_CONFIG, DEFAULT_CONFIG)
                    .putBoolean(KEY_ENABLE, false)
                    .apply();
        }
    }

    /** 静默设置勾选状态：不触发业务逻辑 */
    private void setCheckBoxSilently(boolean checked) {
        if (checkbox.isChecked() == checked) {
            return;
        }
        settingCheckedSilently = true;
        checkbox.setChecked(checked);
        settingCheckedSilently = false;
    }

    /** 用户通过开关启用/停用 */
    private void applyEnabled(boolean enabled) {
        // 同步写盘：勾完立刻按返回（甚至进程被系统回收）也不会丢状态
        sharedPref.edit().putBoolean(KEY_ENABLE, enabled).commit();

        Toast.makeText(this, enabled ? "已启用." : "已停用.", Toast.LENGTH_SHORT).show();

        if (enabled) {
            startBrightnessService(sharedPref.getString(KEY_CONFIG, ""));
            registerLightReceiver();
        } else {
            // 不 stopService：服务里注册了 prefs 监听，会自己切到“暂停”状态，
            // 通知栏那条通知保留下来（否则就没法从通知栏再打开了）
            unregisterLightReceiver();
            if (lightLevelTextView != null) {
                lightLevelTextView.setText("");
            }
        }
    }

    /** 启动（或把新配置推给）前台服务 */
    private void startBrightnessService(String config) {
        Intent serviceIntent = new Intent(this, autobrightness.class);
        serviceIntent.putExtra(EXTRA_CONFIG, config);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            // Android 12+ 后台启动前台服务受限时可能抛异常，不能让它把 app 拖崩
            Log.e(TAG, "启动服务失败", e);
            Toast.makeText(this, "启动服务失败，请把本应用加入后台白名单", Toast.LENGTH_LONG).show();
        }
    }

    private void registerLightReceiver() {
        if (lightReceiverRegistered) {
            return;
        }
        registerReceiver(lightLevelReceiver, new IntentFilter(ACTION_LIGHT_UPDATE));
        lightReceiverRegistered = true;
    }

    private void unregisterLightReceiver() {
        if (!lightReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(lightLevelReceiver);
        } catch (IllegalArgumentException e) {
            // 已经注销过了，无视即可
        }
        lightReceiverRegistered = false;
    }

    /** 检查“修改系统设置”权限，没有就引导用户去开 */
    private void checkWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (Settings.System.canWrite(this)) {
            return;
        }
        Toast.makeText(this, "请开启“修改系统设置”权限，否则无法调节亮度", Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "打开权限设置页失败", e);
        }
    }

    public void save(View view) {

        String inputText = editText.getText().toString().trim();
        sharedPref.edit().putString(KEY_CONFIG, inputText).commit();
        Toast.makeText(this, "保存完成.", Toast.LENGTH_LONG).show();

        if (inputText.isEmpty()) {
            Toast.makeText(this, "配置为空，将不再自动调节亮度", Toast.LENGTH_LONG).show();
        } else if (!inputText.matches("(?s).*lmd\\s*:.*")) {
            Toast.makeText(this, "配置缺少 lmd:灵敏度/过渡秒数 这一行，可能无法生效", Toast.LENGTH_LONG).show();
        }

        // 直接带着新配置再发一次启动指令即可，服务会重新解析配置。
        // 不再 stopService + 睡 500ms + startService（那是主线程阻塞和状态错乱的根源）。
        if (sharedPref.getBoolean(KEY_ENABLE, false)) {
            startBrightnessService(inputText);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");

        sharedPref.registerOnSharedPreferenceChangeListener(prefListener);

        // 每次回到前台都以持久化状态为准，避免 UI 和真实状态不一致
        boolean enabled = sharedPref.getBoolean(KEY_ENABLE, false);
        setCheckBoxSilently(enabled);

        if (enabled) {
            // 开关是“开”就保证服务真的在跑（被系统清掉后能自愈）
            startBrightnessService(sharedPref.getString(KEY_CONFIG, ""));
            registerLightReceiver();
        } else {
            unregisterLightReceiver();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
        sharedPref.unregisterOnSharedPreferenceChangeListener(prefListener);
        // 停到后台就不再收广播，注册/注销一一对应
        unregisterLightReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sharedPref.unregisterOnSharedPreferenceChangeListener(prefListener);
        unregisterLightReceiver();
    }
}
