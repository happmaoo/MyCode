package com.example.gattclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class MyBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {


        // --运行ble服务
        if (intent.getStringExtra("DEVICE_ADDRESS") != null) {
            String DEVICE_ADDRESS = intent.getStringExtra("DEVICE_ADDRESS");
            String message = intent.getStringExtra("message");
            String SERVICE_UUID = intent.getStringExtra("SERVICE_UUID");
            String CHARACTERISTIC_UUID = intent.getStringExtra("CHARACTERISTIC_UUID");


            Intent serviceIntent = new Intent(context, myservice.class);
            //serviceIntent.putExtra("DEVICE_ADDRESS", "3C:28:6D:DD:D3:0D");
            serviceIntent.putExtra("DEVICE_ADDRESS", DEVICE_ADDRESS);
            serviceIntent.putExtra("SERVICE_UUID", SERVICE_UUID);
            serviceIntent.putExtra("CHARACTERISTIC_UUID", CHARACTERISTIC_UUID);
            serviceIntent.putExtra("message", message);
            context.startService(serviceIntent);
        }

        //  这个功能是接收ble那边读取到的数据
        if (intent.getStringExtra("msg") != null) {
            Toast.makeText(context, intent.getStringExtra("msg"), Toast.LENGTH_SHORT).show();
        }

    }
}
