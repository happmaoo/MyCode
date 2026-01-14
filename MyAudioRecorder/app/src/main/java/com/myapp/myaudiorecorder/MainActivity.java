package com.myapp.myaudiorecorder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Intent serviceIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serviceIntent = new Intent(this, MyAudioRecorderService.class);

        if (checkAndRequestPermissions()) {
            startAudioService();
        }
    }

    /**
     * 检查并申请权限
     */
    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // 录音权限 (必须)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        // Android 14+ 前台服务麦克风类型权限
        if (Build.VERSION.SDK_INT >= 34) { // API 34
            String micPermission = "android.permission.FOREGROUND_SERVICE_MICROPHONE";
            if (ContextCompat.checkSelfPermission(this, micPermission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(micPermission);
            }
        }

        // 存储权限 (针对旧版本，Android 10+ 建议使用 Context.getExternalFilesDir 不需权限)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), 888);
            return false;
        }
        return true;
    }

    /**
     * 处理权限申请回调
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 888) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startAudioService();
            } else {
                Toast.makeText(this, "需要录音权限才能运行", Toast.LENGTH_SHORT).show();
                // 引导用户去设置页面或关闭应用
            }
        }
    }

    private void startAudioService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onDestroy() {
        if (serviceIntent != null) {
            stopService(serviceIntent);
        }
        super.onDestroy();
    }
}