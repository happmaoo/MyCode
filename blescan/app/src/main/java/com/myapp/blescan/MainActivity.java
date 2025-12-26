package com.myapp.blescan;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private BleScannerHelper mBleScannerHelper;
    private static final int PERMISSION_REQUEST_CODE = 101;

    TextView textView_log;



    // 1. 定义 Handler 处理 UI 更新
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {

            String newDeviceInfo = (String) msg.obj;
            // 将新设备信息追加到 TextView 末尾
            textView_log.append(newDeviceInfo);
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView_log = findViewById(R.id.textView_log);

        mBleScannerHelper = new BleScannerHelper(this);



        // 2. 设置监听器，通过 Handler 发送数据
        mBleScannerHelper.setOnScanResultListener(new BleScannerHelper.OnScanResultListener() {
            @Override
            public void onNewDeviceFound(String info) {
                Message message = Message.obtain();
                message.obj = info;
                mHandler.sendMessage(message);
            }
        });



        // Android 10 直接检查定位权限
        if (checkLocationPermission()) {
            mBleScannerHelper.startScan();
        } else {
            requestLocationPermission();
        }
    }

    private boolean checkLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mBleScannerHelper.startScan();
            } else {
                Toast.makeText(this, "Android 10 扫描 BLE 必须开启定位权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBleScannerHelper != null) mBleScannerHelper.stopScan();
        mHandler.removeCallbacksAndMessages(null);
    }
}