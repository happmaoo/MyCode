package com.myapp.mybleserver;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.DataOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private GattServiceConn gattServiceConn;
    EditText editText;
    TextView textView_rec;
    Button btn_send;

    private static final String BLE_DATA_RECEIVED = "com.myapp.BLE_DATA_RECEIVED";



    // 广播接收器
    private final BroadcastReceiver bleDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BLE_DATA_RECEIVED.equals(intent.getAction())) {
                String data = intent.getStringExtra("data");

                // 在主线程更新UI
                runOnUiThread(() -> {
                    textView_rec.append(data + "\n");
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
        // 注册广播接收器
        //IntentFilter filter = new IntentFilter(BLE_DATA_RECEIVED);
        //registerReceiver(bleDataReceiver, filter);
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

        // 这里不停止服务，只解绑和取消注册广播
        if (gattServiceConn != null) {
            unbindService(gattServiceConn);
            gattServiceConn = null;
        }
        // 取消注册广播接收器
        unregisterReceiver(bleDataReceiver);
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