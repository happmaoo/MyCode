package com.myapp.autobrightness;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private ScreenOffReceiver screenOffReceiver;
    private TextView lightLevelTextView;

    SharedPreferences sharedPref;
    SharedPreferences.Editor editor;


    private BroadcastReceiver lightLevelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            // 获取当前的屏幕亮度
            int currentBrightness = 0;
            try {
                currentBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
            }

            // 从广播中获取光照强度数据
            float lightLevel = intent.getFloatExtra("lightLevel", -1);
            lightLevelTextView.setText("光线传感器: " + lightLevel + " lux" + "\n亮度: " +currentBrightness);
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        // 检查系统是否允许更改设置
        if (Settings.System.canWrite(this)) {

        } else {
            Toast.makeText(this, "错误, 请在app权限里允许 修改系统设置.", Toast.LENGTH_LONG).show();
        }


        // 初始化SharedPreferences
        sharedPref = getSharedPreferences("app_config", MODE_PRIVATE);
        editor = sharedPref.edit();

        String config = sharedPref.getString("config", null);
        //EditText editText = findViewById(R.id.editTextTextMultiLine);
        //editText.setText(config);

        // 首次运行写入配置文件
        if (config == null) {
            editor.putString("config", "10 50\n500 250\n5000 300\n10000 400\n20000 500\n90000 700");
            editor.apply();
            config = sharedPref.getString("config", null);
        }

        EditText editText = findViewById(R.id.editTextTextMultiLine);
        editText.setText(config);

        // 初始化并注册屏幕状态广播接收器
        screenOffReceiver = new ScreenOffReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); // 监听屏幕关闭
        filter.addAction(Intent.ACTION_SCREEN_ON);  // 监听屏幕开启
        registerReceiver(screenOffReceiver, filter);


        Intent serviceIntent = new Intent(this, autobrightness.class);
        serviceIntent.putExtra("arg1", "run");
        serviceIntent.putExtra("arg-config", config);

        // 针对 API 26 及更高版本，使用 startForegroundService
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }


        lightLevelTextView = findViewById(R.id.text);

        // 注册广播接收器
        IntentFilter filter2 = new IntentFilter("com.myapp.LIGHT_LEVEL_UPDATE");
        registerReceiver(lightLevelReceiver, filter2);




    }

    public void save(View view) {

        //editTextTextMultiLine
        EditText editText = findViewById(R.id.editTextTextMultiLine);
        String inputText = editText.getText().toString();
        editor.putString("config", inputText);
        editor.apply(); // 使用apply异步保存，commit同步保存
        Toast.makeText(this, "下次启动app生效.", Toast.LENGTH_LONG).show();
    }



    @Override
    protected void onResume() {
        super.onResume();
        Log.d("TAG", "MainActivity, onResume");
        //Intent serviceIntent = new Intent(this, MyForegroundService.class);
        //serviceIntent.putExtra("arg1", "run");
        //startService(serviceIntent);
        // 注册广播接收器
        IntentFilter filter2 = new IntentFilter("com.myapp.LIGHT_LEVEL_UPDATE");
        registerReceiver(lightLevelReceiver, filter2);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d("TAG", "MainActivity, onPause");
        unregisterReceiver(lightLevelReceiver);
        //Intent serviceIntent = new Intent(this, MyForegroundService.class);
        //stopService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 取消注册广播接收器
        unregisterReceiver(lightLevelReceiver);
    }
}