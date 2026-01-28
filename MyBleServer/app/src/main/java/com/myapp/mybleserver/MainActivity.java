package com.myapp.mybleserver;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.DataOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private GattServiceConn gattServiceConn;
    EditText editText,editText_apps;
    TextView textView_rec;
    Button btn_send,btn_stop,button_save;

    MyApp app;

    private static final String BLE_DATA_RECEIVED = "com.myapp.BLE_DATA_RECEIVED";



    // 广播接收器
    private final BroadcastReceiver bleDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BLE_DATA_RECEIVED.equals(intent.getAction())) {
                String data = intent.getStringExtra("data");

                // 在主线程更新UI
                runOnUiThread(() -> {
                    textView_rec.append(data);
                });

            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editText = findViewById(R.id.editText);
        textView_rec = findViewById(R.id.textView_rec);
        btn_send = findViewById(R.id.btn_send);
        btn_stop = findViewById(R.id.btn_stop);

        button_save = findViewById(R.id.button_save);
        editText_apps = findViewById(R.id.editText_apps);

        // SharedPreferences
        app = (MyApp) getApplication();

        String apps = app.getString("apps","com.tencent.mobileqq\ncom.tencent.mm");
        editText_apps.setText(app.getString("apps","com.tencent.mobileqq\ncom.tencent.mm"));

        //通知权限
        checkAndRequestPermission(this);
        setNotifServiceEnabled(this,true);


        // 异步检查 root 权限，避免阻塞主线程
        checkRootInBackground();

        // 启动 GattService
        Intent intent = new Intent(this, GattService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 必须使用 startForegroundService
            startForegroundService(intent);
        } else {
            // Android 7 及以下使用 startService
            startService(intent);
        }

        // 注册广播监听
        IntentFilter filter = new IntentFilter(BLE_DATA_RECEIVED);
        registerReceiver(bleDataReceiver, filter);


        //-----------Button send----------------
        btn_send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String inputText = editText.getText().toString().trim();
                if (gattServiceConn != null && gattServiceConn.binding != null) {
                    gattServiceConn.binding.setMyCharacteristicValue(inputText);
                }
            }
        });

        //-----------Button stop----------------
        btn_stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 命令监听器服务去主动解绑 GattService 不然停不掉服务
                Intent unbindIntent = new Intent(MainActivity.this, MyNotificationListenerService.class);
                unbindIntent.setAction("ACTION_FORCE_UNBIND");
                startService(unbindIntent);



                setNotifServiceEnabled(MainActivity.this, false);


                // 2. 停止 GattService
                Intent intent = new Intent(MainActivity.this, GattService.class);
                stopService(intent);

                // 3. Activity 自身解绑并退出
                if (gattServiceConn != null) {
                    unbindService(gattServiceConn);
                    gattServiceConn = null;
                }


                finishAffinity();
                //System.exit(0); // 强制退出当前进程 ,不然退不掉

            }
        });


        //-----------Button save----------------
        button_save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String inputText = editText_apps.getText().toString().trim();
                app.setString("apps",inputText);
                Toast.makeText(MainActivity.this, "Saved.", Toast.LENGTH_SHORT).show();
                //app.setString("apps","com.tencent.mobileqq,net.dinglisch.android.taskerm");
            }
        });

    }




    @Override
    protected void onStart() {
        //每次 activity 进入前台
        super.onStart();
        gattServiceConn = new GattServiceConn();
        bindService(new Intent(this, GattService.class), gattServiceConn, Context.BIND_AUTO_CREATE);


    }



    @Override
    protected void onStop() {
        super.onStop();
        if (gattServiceConn != null) {
            unbindService(gattServiceConn);
            gattServiceConn = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        textView_rec.setText(app.getString("log",""));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册，避免内存泄漏
        //unregisterReceiver(bleDataReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 统一在这里处理残留资源的释放
        if (gattServiceConn != null) {
            try {
                unbindService(gattServiceConn);
            } catch (Exception e) {
                // 防止重复解绑
            }
            gattServiceConn = null;
        }

        // 使用 try-catch 保护，防止 Receiver 未注册时调用导致崩溃
        try {
            unregisterReceiver(bleDataReceiver);
        } catch (IllegalArgumentException e) {
            // 说明已经注销过了，忽略即可
        }

    }


    private void checkRootInBackground() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean hasRoot = checkRootAccess();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (hasRoot) {
                            // Root 权限已获取
                            Toast.makeText(MainActivity.this,
                                    "已获取Root权限", Toast.LENGTH_SHORT).show();
                        } else {
                            // 没有root权限
                            Toast.makeText(MainActivity.this,
                                    "未获取Root权限", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    public boolean checkRootAccess() {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            // 发送 exit 命令，否则 su 进程不会主动结束
            os.writeBytes("exit\n");
            os.flush();

            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }


    // 通知权限读取
    // 检查权限是否开启
    public static boolean isNotificationServiceEnabled(Context context) {
        String enabledListeners = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners"
        );
        return enabledListeners != null && enabledListeners.contains(context.getPackageName());
    }

    // 跳转到通知访问权限设置
    public static void requestNotificationPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    // 检查并跳转（如果未开启）
    public boolean checkAndRequestPermission(Context context) {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE}, 1);
            return false;
        }


        if (!isNotificationServiceEnabled(context)) {
            requestNotificationPermission(context);
            return false;
        }

        if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            String packageName = getPackageName();
            String[] permissions = {
                    "android.permission.BLUETOOTH_CONNECT",
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_ADVERTISE"
            };

            try {
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                for (String perm : permissions) {
                    os.writeBytes("pm grant " + packageName + " " + perm + "\n");
                }
                os.writeBytes("exit\n");
                os.flush();
                int result = p.waitFor();
                if (result == 0) {
                    Log.d("ROOT", "所有蓝牙权限已通过 Root 强制授予");
                }
            } catch (Exception e) {
                Log.e("ROOT", "Root 授权失败", e);
            }
        }

        return true;
    }



    // 启用 禁用 MyNotificationListenerService
    public void setNotifServiceEnabled(Context context, boolean enable) {
        ComponentName componentName = new ComponentName(context, MyNotificationListenerService.class);

        int newState = enable ?
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        context.getPackageManager().setComponentEnabledSetting(
                componentName,
                newState,
                PackageManager.DONT_KILL_APP);

    }



    private static class GattServiceConn implements ServiceConnection {
        DeviceAPI binding;

        public void onServiceConnected(ComponentName name, IBinder service) {
            // 这里的 service 就是 GattService 里的 DataPlane 实例
            binding = (DeviceAPI) service;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = null;
        }
    }
}