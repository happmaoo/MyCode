package com.myapp.blescan;

import android.content.Context;

import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BleScannerHelper {

    private final BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
    private final Context context;
    private boolean isScanning = false;


    // 假设你的服务 UUID
    //private final ParcelUuid mUuid = new ParcelUuid(UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb"));

    public BleScannerHelper(Context context) {
        this.context = context;
    }

    // 用于存放发现的设备，Key 是 MAC 地址
    private final Map<String, ScanResult> deviceMap = new HashMap<>();



    // 1. 定义回调接口
    public interface OnScanResultListener {
        void onNewDeviceFound(String info);
    }

    private OnScanResultListener mListener;

    public void setOnScanResultListener(OnScanResultListener listener) {
        this.mListener = listener;
    }



    /**
     * 开始扫描周围设备
     */
    public void startScan() {
        if (isScanning) return;

        // 1. 配置扫描设置
        // setReportDelay(5000) 表示每 5 秒回调一次结果列表（Batch），适合节省功耗
        ScanSettings settings = new ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(5000)
                .setUseHardwareBatchingIfSupported(true)
                .build();

        // 2. 配置过滤规则
        //List<ScanFilter> filters = new ArrayList<>();
        //filters.add(new ScanFilter.Builder().setServiceUuid(mUuid).build());


        scanner.startScan(null, settings, scanCallback);
        isScanning = true;

    }

    /**
     * 停止扫描
     */
    public void stopScan() {
        if (!isScanning) return;
        scanner.stopScan(scanCallback);
        isScanning = false;
    }

    /**
     * 扫描回调
     */
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            // 统一交给处理中心
            processResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            // 批量结果也一个个交给处理中心，处理中心会自动去重
            for (ScanResult result : results) {
                processResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            android.util.Log.e("BLE_TEST", "扫描失败，错误码: " + errorCode);
        }
    };

    /**
     * 统一的处理中心：负责去重和日志打印
     */
    private void processResult(ScanResult result) {
        String macAddress = result.getDevice().getAddress();

        // 【核心去重逻辑】
        if (!deviceMap.containsKey(macAddress)) {
            // 只有第一次发现该 MAC 地址时才存入并打印 I 级日志
            deviceMap.put(macAddress, result);

            String name = result.getDevice().getName();
            int rssi = result.getRssi();
            String info = "设备: " + (name != null ? name : "未知") + " MAC: " + macAddress + " RSSI: " + rssi + "\n";

            // 2. 只有发现新设备时才通知 Listener
            if (mListener != null) {
                mListener.onNewDeviceFound(info);
            }

            android.util.Log.i("BLE_TEST", ">>> 发现新设备!! " +
                    (name != null ? name : "未知") + " [" + macAddress + "] RSSI: " + rssi);
        } else {
            // 如果设备已存在，仅更新数据，不再重复打印新设备日志
            deviceMap.put(macAddress, result);
        }
    }

}