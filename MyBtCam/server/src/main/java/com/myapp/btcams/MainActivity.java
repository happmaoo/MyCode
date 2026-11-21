package com.myapp.btcams;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    /** 给 SppService 判断「界面是否真的显示出来了」。 */
    public static volatile boolean sVisible;

    private static final int REQ_CAMERA = 10;

    private TextView stateView;
    private TextView connectedView;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(SppService.EXTRA_STATE);
            boolean connected = intent.getBooleanExtra(SppService.EXTRA_CONNECTED, false);
            updateStatus(state, connected);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        setSupportActionBar(toolbar);

        stateView = findViewById(R.id.text_state);
        connectedView = findViewById(R.id.text_connected);

        findViewById(R.id.btn_start).setOnClickListener(v -> startListening());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopListening());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        updateStatus(SppService.sState, SppService.sConnected);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(SppService.ACTION_STATUS);
        ContextCompat.registerReceiver(this, statusReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        updateStatus(SppService.sState, SppService.sConnected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sVisible = true;
    }

    @Override
    protected void onPause() {
        sVisible = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateStatus(String state, boolean connected) {
        if (stateView != null) {
            stateView.setText(state == null || state.isEmpty() ? "未启动" : state);
        }
        if (connectedView != null) {
            connectedView.setText(connected ? "已连接客户端" : "无客户端连接");
        }
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        Intent intent = new Intent(this, SppService.class);
        intent.setAction(SppService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
        updateStatus("正在监听", false);
    }

    private void stopListening() {
        Intent intent = new Intent(this, SppService.class);
        intent.setAction(SppService.ACTION_STOP);
        startService(intent);
        updateStatus("未启动", false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            updateStatus("相机权限已授予，可启动监听", false);
        } else if (requestCode == REQ_CAMERA) {
            updateStatus("未授予相机权限，客户端连接后无法推流", false);
        }
    }
}
