package com.myapp.mybleserver;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;


// 用于打开蓝牙共享网络的类


public class BluetoothPanServer {
    private Object mBluetoothPan;
    private BluetoothAdapter mBluetoothAdapter;

    public void enableBtPan(Context context) {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // 1. 先确保蓝牙是打开的
        if (!mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.enable();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 还可以打开数据开关 (Root)
        try {
            Runtime.getRuntime().exec("svc data enable");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 2. 获取 PAN Profile 代理
        mBluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == 5) { // 5 是 PAN 的隐藏常量
                    mBluetoothPan = proxy;
                    setTetheringOn(true); // 调用开启逻辑
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                mBluetoothPan = null;
            }
        }, 5);
    }

    private void setTetheringOn(boolean enabled) {
        try {
            // 3. 反射调用 setBluetoothTethering
            Method setTetheringMethod = mBluetoothPan.getClass().getDeclaredMethod("setBluetoothTethering", boolean.class);
            setTetheringMethod.invoke(mBluetoothPan, enabled);
            Log.d("BT_SERVER", "蓝牙网络共享已尝试设置为: " + enabled);
        } catch (Exception e) {
            Log.e("BT_SERVER", "开启失败，可能是系统权限限制", e);
        }
    }
}