package com.myapp.mybleserver;


import java.util.UUID;

/**
 * 蓝牙服务和特征的 UUID 常量。
 */
public class Constants {

    // 客户端特征配置描述符 (CCCD) UUID
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // --- 自定义服务 UUID (用于读、写、通知演示) ---
    public static final UUID CUSTOM_SERVICE_UUID = UUID.fromString("0000A000-0000-1000-8000-00805F9B34FB");

    // 特征 A: 支持读写操作 (Read, Write)
    public static final UUID CUSTOM_READ_WRITE_CHAR_UUID = UUID.fromString("0000A001-0000-1000-8000-00805F9B34FB");

    // 特征 B: 支持通知操作 (Read, Notify)
    public static final UUID CUSTOM_NOTIFY_CHAR_UUID = UUID.fromString("0000A002-0000-1000-8000-00805F9B34FB");

    // --- 标准电池服务 UUID (用于广播) ---
    public static final UUID BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");

    // 电池电量特征 UUID (Battery Level Characteristic)
    public static final UUID BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");
}