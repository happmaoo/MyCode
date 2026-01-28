package com.myapp.mybleserver;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;

public class BluetoothPanServer {
    private Object mBluetoothPan;
    private BluetoothAdapter mBluetoothAdapter;

    // 修改点：添加了 boolean enable 参数
    public void enableBtPan(Context context, final boolean enable) {




        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // 1. 如果是开启操作，先确保蓝牙是打开的
        if (enable && !mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.enable();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 只有开启时才尝试打开数据网络 (Root)
        if (enable) {
            try {
                Runtime.getRuntime().exec("svc data enable");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 安卓12以上开启 btpan 太难
        if (Build.VERSION.SDK_INT < 31) {
            // 2. 获取 PAN Profile 代理
            mBluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == 5) {
                        mBluetoothPan = proxy;
                        setTetheringOn(enable); // 修改点：传入参数
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    mBluetoothPan = null;
                }
            }, 5);
        }
    }

    private void setTetheringOn(boolean enabled) {
        try {
            // 3. 反射调用 setBluetoothTethering
            Method setTetheringMethod = mBluetoothPan.getClass().getDeclaredMethod("setBluetoothTethering", boolean.class);
            setTetheringMethod.invoke(mBluetoothPan, enabled);
            Log.d("BT_SERVER", "蓝牙网络共享已尝试设置为: " + enabled);
        } catch (Exception e) {
            Log.e("BT_SERVER", "操作失败，可能是系统权限限制", e);
        }
    }
}