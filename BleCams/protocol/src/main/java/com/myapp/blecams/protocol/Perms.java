package com.myapp.blecams.protocol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时权限。
 *
 * <p>本工程 targetSdk=30，因此在 Android 12+ 上仍走“旧蓝牙权限 + 定位权限”的兼容模型：
 * BLUETOOTH / BLUETOOTH_ADMIN 在清单里安装期授予，BLE 扫描与广播需要
 * {@code ACCESS_FINE_LOCATION}（Android 6~15 都是这样，12+ 对 targetSdk&lt;31 的应用保持旧行为）。
 * 若以后把 targetSdk 提到 31+，请改用 BLUETOOTH_SCAN / BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE。
 */
public final class Perms {

    private Perms() {
    }

    /** 服务端（广播 + 摄像头）需要的运行时权限。 */
    public static String[] serverPermissions() {
        List<String> list = new ArrayList<>();
        list.add(Manifest.permission.CAMERA);
        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return list.toArray(new String[0]);
    }

    /** 接收端（扫描 + 连接）需要的运行时权限。 */
    public static String[] clientPermissions() {
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public static boolean hasAll(Context context, String[] perms) {
        for (String p : perms) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static String[] missing(Context context, String[] perms) {
        List<String> list = new ArrayList<>();
        for (String p : perms) {
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                list.add(p);
            }
        }
        return list.toArray(new String[0]);
    }
}
