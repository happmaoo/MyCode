package com.myapp.mywifidirect;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;



public class MainActivity extends AppCompatActivity implements WifiP2pManager.PeerListListener {

    private WiFiDirectService wifiService;
    private boolean isBound = false;
    private WiFiDirectBroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private Button button_start;
    private TextView textView_addr;

    // UI 组件
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private List<WifiP2pDevice> peers = new ArrayList<>();
    private List<String> deviceNames = new ArrayList<>();

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            wifiService = ((WiFiDirectService.LocalBinder) service).getService();
            isBound = true;
            // 绑定成功后初始化广播
            initP2P();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { isBound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化 UI
        textView_addr = findViewById(R.id.textView_addr);
        listView = findViewById(R.id.peer_list);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        listView.setAdapter(adapter);

        // 2. 动态申请定位权限 (Android 8.0+ 必须)
        checkPermissions();

        // 3. 启动并绑定前台服务
        Intent serviceIntent = new Intent(this, WiFiDirectService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);



        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                // 1. 获取点击的设备信息
                final WifiP2pDevice device = peers.get(position);

                // 2. 调用连接方法
                connectToDevice(device);
            }
        });
    }



    private void initP2P() {
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // 关键修复：从 Service 中拿到 manager 和 channel 来初始化 receiver
        if (wifiService != null) {
            receiver = new WiFiDirectBroadcastReceiver(
                    wifiService.getManager(),
                    wifiService.getChannel(),
                    this
            );
            // 立即注册，确保能收到状态
            registerReceiver(receiver, intentFilter);
        }
    }

    // 点击搜索按钮触发（在布局文件中添加 android:onClick="onDiscoverClick"）
    public void onDiscoverClick(View view) {
        if (isBound) {
            if (wifiService != null) {
            wifiService.discoverPeers(new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() { Toast.makeText(MainActivity.this, "正在搜索设备...", Toast.LENGTH_SHORT).show(); }
                @Override
                public void onFailure(int reason) { Toast.makeText(MainActivity.this, "搜索失败 code:" + reason, Toast.LENGTH_SHORT).show(); }
            });
            } else {
                Toast.makeText(this, "服务正在初始化...", Toast.LENGTH_SHORT).show();
            }
        }
    }



    // 当发现设备列表变化时的回调
    @Override
    public void onPeersAvailable(WifiP2pDeviceList peerList) {
        peers.clear();
        deviceNames.clear();
        peers.addAll(peerList.getDeviceList());
        for (WifiP2pDevice device : peerList.getDeviceList()) {
            deviceNames.add(device.deviceName + "\n" + device.deviceAddress);
        }
        adapter.notifyDataSetChanged();
    }

    // 连接信息回调 (用于获取 IP)
    public WifiP2pManager.ConnectionInfoListener connectionInfoListener = info -> {
        if (info.groupFormed) {
            String ownerIP = info.groupOwnerAddress.getHostAddress();
            if (info.isGroupOwner) {
                Toast.makeText(this, "我是服务端，准备接收数据", Toast.LENGTH_LONG).show();
                textView_addr.setText("本机是服务端IP:一般是192.168.49.1");
            } else {
                Toast.makeText(this, "连接成功，服务端IP: " + ownerIP, Toast.LENGTH_LONG).show();
                textView_addr.setText("本机是客户端，服务端IP:"+ownerIP);
            }
        }
    };

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只有当 receiver 已经被初始化过了，才在这里重新注册
        if (receiver != null && intentFilter != null) {
            registerReceiver(receiver, intentFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 只有注册过才注销
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        // 显式停止服务，这会触发 WiFiDirectService 的 onDestroy
        Intent stopIntent = new Intent(this, WiFiDirectService.class);
        stopService(stopIntent);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {

    }


    private void connectToDevice(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress; // 目标的 MAC 地址
        // config.wps.setup = WpsInfo.PBC; // 某些旧版本 Android 需要这行，新版可选

        if (wifiService != null) {
            wifiService.getManager().connect(wifiService.getChannel(), config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    // 这里只代表“连接请求”发送成功，并不代表已经连上了
                    Toast.makeText(MainActivity.this, "正在发起连接：" + device.deviceName, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(int reason) {
                    Toast.makeText(MainActivity.this, "连接失败，错误代码：" + reason, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

}