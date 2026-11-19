package com.myapp.mybleserver;



import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.os.ParcelUuid;
import android.util.Log;

public class BleAdvertiser {
    private static final String TAG = "ble-advertiser";

    public static class Callback extends AdvertiseCallback {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "LE Advertise Failed: " + errorCode);
        }
    }

    public static AdvertiseSettings settings() {
        return new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .build();
    }

    public static AdvertiseData advertiseData() {
        return new AdvertiseData.Builder()
                //.setIncludeDeviceName(true)
                // 这里的 customName 建议不要超过 8 个字符，否则必失败
                .addManufacturerData(0x0000, buildCustomNameData("Ble"))
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(GattService.MyServiceProfile.MY_SERVICE_UUID))
                .build();
    }

    // 新增：专门用于存放名字的扫描响应包
    public static AdvertiseData scanResponseData() {
        return new AdvertiseData.Builder()
                .setIncludeDeviceName(true) // 这一步会抓取上面 setName("MyBLE_Server") 的内容
                .build();
    }

    private static byte[] buildCustomNameData(String name) {
        try {
            byte[] nameBytes = name.getBytes("UTF-8");
            // 注意：addManufacturerData 会自动帮你加上 [Length] [0xFF] [CompanyID_L] [CompanyID_H]
            // 所以我们这里只返回名字的字节，或者根据需要微调
            return nameBytes;
        } catch (Exception e) {
            return new byte[0];
        }
    }
}