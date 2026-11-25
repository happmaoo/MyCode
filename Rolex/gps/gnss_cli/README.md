# gnss_cli — rolex / Android 10 上的原生 GNSS 客户端

目标设备实测信息（2026-11-25）：

```
ro.product.device   = rolex
ro.build.version    = 10
abilist             = arm64-v8a,armeabi-v7a,armeabi
HAL 服务            = /vendor/bin/hw/android.hardware.gnss@2.0-service-qti (pid 468, ELF 64-bit)
守护进程            = /vendor/bin/loc_launcher (pid 763)
lshal               = android.hardware.gnss@2.0::IGnss/default
当前 client         = system_server
/vendor/lib64/hw    = android.hardware.gnss@2.0-impl-qti.so, vendor.qti.gnss@3.0-impl.so
/vendor/lib/hw      = 无 gps*
/system/lib*/hw     = 无 gps*
```

结论：**没有 gps.default.so，也没有 hardware/gps.h 那套 legacy HAL**。
Android 10 走的是 HIDL，唯一入口是 `android.hardware.gnss@2.0::IGnss`。

## 为什么不能在 Termux 里直接编

`IGnss.h` / `IGnssCallback.h` / `types.h` 是 hidl-gen 生成的，位置：

```
out/soong/.intermediates/hardware/interfaces/gnss/2.0/android.hardware.gnss@2.0_genc++_headers/gen/
out/soong/.intermediates/hardware/interfaces/gnss/2.0/android.hardware.gnss@2.0_genc++/gen/
```

Termux 里没有这些头，也没有 hidl-gen，所以只能在 AOSP 树里编。

## 安装到设备树

```
device/xiaomi/rolex/gnss_cli/
├── Android.bp
└── gnss_cli.cpp
```

在 `device.mk` 里加：

```make
PRODUCT_PACKAGES += gnss_cli
```

产物安装为 `/vendor/bin/gnss_cli`（`vendor: true`）。

## 编译

```sh
source build/envsetup.sh
lunch lineage_rolex-userdebug        # 用你这台机器对应的 17.1 分支
m gnss_cli
```

必须是 arm64-v8a。编成 32 位会在 `getService()` 直接返回 null。

## 部署与运行

```sh
adb push $OUT/vendor/bin/gnss_cli /data/local/tmp/
adb shell su -c 'chmod 755 /data/local/tmp/gnss_cli'
adb shell su -c '/data/local/tmp/gnss_cli -v -t 90'
adb shell su -c '/data/local/tmp/gnss_cli -n -t 90'      # 连 NMEA 一起打
```

输出示例：

```
callback registered
start ok=1 ret=1
CAPS2 0x...
HW_YEAR 2016
HAL_NAME android.hardware.gnss@2.0-service-qti
ENGINE 2
SV visible=14 used=9
FIX lat=28.93xxxxx lon=118.86xxxxx alt=86.5 hacc=8.0 vacc=12.0 spd=0.00 brg=0.0 ts=...
DONE fixes=1 in 12000ms
```

## 收尾（必须）

`IGnss` 只有一路 callback，你的进程 `setCallback_2_0` 会把 `system_server` 顶掉。
测完必须重启 HAL，让 `GnssLocationProvider` 通过 linkToDeath 重新注册：

```sh
su -c 'stop gnss_service; sleep 1; start gnss_service'
```

不做这一步，系统里的地图/微信会一直拿不到位置，直到重启。

## 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `getService() = null` | 编了 32 位；或 hwservicemanager 找不到 | 确认 arm64-v8a；`lshal list | grep gnss` |
| `setCallback_2_0 failed` | HAL 已持有其他 callback 或实现版本不匹配 | 看 `logcat -s GnssHal` |
| 编译报 `no member named ...` | 2.0 类型名/签名与生成头不符 | 以 `out/.../gen/.../IGnssCallback.h` 里的 `virtual Return<void> gnss...` 行为准，逐行替换 |
| 运行被 SELinux 拒 | 非 su 域运行 vendor 二进制 | 用 `su -c`；或给策略加 `hal_gnss` client 域 |
| 有 ENGINE 状态但没有 FIX | 室内无星 / 冷启动 | 到室外，等 30–120 秒；`-t 300` |

## 与 framework 共存（不抢回调）

`IGnss` 2.0 单 callback，想长期共存只有三条路：

1. 改 framework：把 `GnssLocationProvider` 收到的位置通过 socket/property 分发出来；
2. 在你的 HAL 客户端里做一次抓取（就是本工具），用完退出，不常驻；
3. 走 QMI LOC 直接收 position report（`libqmi` + LOC 服务），绕过 HIDL——工作量大得多。

本工具定位是“验证 + 一次性取数”，不是常驻服务。
