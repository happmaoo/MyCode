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
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private ScreenOffReceiver screenOffReceiver;
    private TextView lightLevelTextView;

    SharedPreferences sharedPref;
    SharedPreferences.Editor editor;
    Boolean enableapp;

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                // 如果没有权限，引导用户去设置页面
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "请开启修改系统设置权限", Toast.LENGTH_LONG).show();
            }
        }

        // 初始化SharedPreferences
        sharedPref = getSharedPreferences("app_config", MODE_PRIVATE);
        editor = sharedPref.edit();

        String config = sharedPref.getString("config", null);
        //EditText editText = findViewById(R.id.editTextTextMultiLine);
        //editText.setText(config);

        // 首次运行写入配置文件
        if (config == null) {
            editor.putString("config", "lmd:3/2\n0 50\n20 250");
            editor.putBoolean("enable", false);
            editor.apply();
            config = sharedPref.getString("config", null);
        }


        CheckBox checkbox = findViewById(R.id.checkBox);

        EditText editText = findViewById(R.id.editTextTextMultiLine);
        editText.setText(config);




        // 初始化并注册屏幕状态广播接收器
        screenOffReceiver = new ScreenOffReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); // 监听屏幕关闭
        filter.addAction(Intent.ACTION_SCREEN_ON);  // 监听屏幕开启
        //registerReceiver(screenOffReceiver, filter);


        Intent serviceIntent = new Intent(this, autobrightness.class);
        serviceIntent.putExtra("arg-config", config);



/*
        Intent broadcastIntent = new Intent("com.myapp.ACTION");
        broadcastIntent.putExtra("arg1", "run");
        sendBroadcast(broadcastIntent);

 */


        lightLevelTextView = findViewById(R.id.text);

        // 注册广播接收器
        IntentFilter filter2 = new IntentFilter("com.myapp.LIGHT_LEVEL_UPDATE");
        //registerReceiver(lightLevelReceiver, filter2);


        enableapp = sharedPref.getBoolean("enable", false);

        if(enableapp){

            checkbox.setChecked(true);
            registerReceiver(screenOffReceiver, filter);
            // 针对 API 26 及更高版本，使用 startForegroundService
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            registerReceiver(lightLevelReceiver, filter2);

        }else{
            checkbox.setChecked(false);
        }


        // checkBox
        checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                editor.putBoolean("enable", true);
                editor.apply();
                Toast.makeText(MainActivity.this, "已启用.", Toast.LENGTH_SHORT).show();

                registerReceiver(screenOffReceiver, filter);
                // 针对 API 26 及更高版本，使用 startForegroundService
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                registerReceiver(lightLevelReceiver, filter2);


            }else{
                editor.putBoolean("enable", false);
                editor.apply();
                Toast.makeText(MainActivity.this, "已停用.", Toast.LENGTH_SHORT).show();

                stopService(serviceIntent);
                unregisterReceiver(lightLevelReceiver);
            }
        });


    }

    public void save(View view) {

        //editTextTextMultiLine
        EditText editText = findViewById(R.id.editTextTextMultiLine);
        String inputText = editText.getText().toString();
        editor.putString("config", inputText);
        editor.apply(); // 使用apply异步保存，commit同步保存
        Toast.makeText(this, "保存完成，重新打开app.3.2.1..", Toast.LENGTH_LONG).show();


        // 延迟2秒执行重启应用操作
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // 先停止服务
                Intent serviceIntent = new Intent(MainActivity.this, autobrightness.class);
                serviceIntent.putExtra("arg1", "stop");
                stopService(serviceIntent);
                unregisterReceiver(lightLevelReceiver);

                // 获取并启动应用的启动Intent
                Intent restartIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (restartIntent != null) {
                    restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(restartIntent);
                }

                // 关闭当前Activity及其父Activity，并完全退出应用程序
                finishAffinity();
                System.exit(0);
            }
        }, 2000); // 延迟2000毫秒（即2秒）

    }



    @Override
    protected void onResume() {
        super.onResume();
        Log.d("TAG", "MainActivity, onResume");
        //Intent serviceIntent = new Intent(this, MyForegroundService.class);
        //serviceIntent.putExtra("arg1", "run");
        //startService(serviceIntent);
        if(enableapp) {// 注册广播接收器
            IntentFilter filter2 = new IntentFilter("com.myapp.LIGHT_LEVEL_UPDATE");
            registerReceiver(lightLevelReceiver, filter2);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d("TAG", "MainActivity, onPause");
        if(enableapp) {
            unregisterReceiver(lightLevelReceiver);
        }
        //Intent serviceIntent = new Intent(this, MyForegroundService.class);
        //stopService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}