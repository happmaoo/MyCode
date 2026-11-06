package com.myapp.mybluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;
import java.util.Set;
import java.lang.reflect.Method;
import android.bluetooth.BluetoothProfile;
import android.content.Context;


public class BluetoothManager {
    private static final String TAG = "BT_PAN_DEBUG";
    private BluetoothAdapter mBluetoothAdapter;
    private Object mBluetoothPan;

    public BluetoothManager() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void connectToPanDevice(final Context context, final String targetDeviceName) {
        if (mBluetoothAdapter == null) return;

        if (!mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.enable(); // 发起开启指令

            // 延时 5 秒后执行连接逻辑
            new android.os.Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mBluetoothAdapter.getState() == BluetoothAdapter.STATE_ON) {
                        setupPanProfile(context, targetDeviceName);
                    } else {
                        Log.e("BT_PAN", "蓝牙开启超时，请手动开启后重试");
                    }
                }
            }, 1000); // 5000 毫秒 = 5 秒
        } else {
            // 蓝牙已开启，直接连接
            setupPanProfile(context, targetDeviceName);
        }
    }

    private void setupPanProfile(final Context context, final String targetDeviceName) {
        // 5 代表 BluetoothProfile.PAN
        mBluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == 5) {
                    mBluetoothPan = proxy;
                    findAndConnect(targetDeviceName);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                mBluetoothPan = null;
            }
        }, 5);
    }

    private void findAndConnect(String deviceName) {
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            if (device.getName().equalsIgnoreCase(deviceName)) {
                invokeConnect(device);
                return;
            }
        }
        Log.e(TAG, "未在配对列表中找到设备: " + deviceName);
    }

    private void invokeConnect(BluetoothDevice device) {
        try {
            Method connectMethod = mBluetoothPan.getClass().getDeclaredMethod("connect", BluetoothDevice.class);
            connectMethod.setAccessible(true);
            connectMethod.invoke(mBluetoothPan, device);
            Log.d(TAG, "已发起对 " + device.getName() + " 的 PAN 连接指令");
        } catch (Exception e) {
            Log.e(TAG, "反射调用失败", e);
        }
    }
}