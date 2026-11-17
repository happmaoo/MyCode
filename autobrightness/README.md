# autobrightness（修复版源码）

红米 note9 5G / 安卓 10 上用的“自动亮度”小工具：读光线传感器，按配置把屏幕亮度平滑调到目标值。

本包是**修复版源码**，已修正“勾选启用后按返回，复选框自动变成未勾选”以及另外二十来处问题，详见 `docs/自动亮度App-Bug排查报告.md`，逐行改动见 `docs/修复对比.patch`。

## 构建要求

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9（自带 wrapper，`./gradlew` 会自动下载） |
| AGP | 8.5.2 |
| compileSdk | 34（需在 SDK Manager 里装 Android 14 / API 34） |
| minSdk / targetSdk | 25 / 29（targetSdk 保持 29，前台服务和通知行为与原版一致） |
| 依赖 | `androidx.appcompat:appcompat:1.3.1`、`constraintlayout:2.1.0`、`activity:1.2.4`（首次构建需联网拉取） |

> 原工程用的 AGP 3.6.2 + Gradle 5.6.4 在 JDK 17 下无法运行，因此构建链升级到了上面这套；源码本身没有依赖任何新库。若你想退回旧构建链，把 `build.gradle` 里的 AGP 版本改回 3.6.2、`app/build.gradle` 里的 `namespace` 换回 manifest 的 `package` 即可。

## 构建

```bash
# 先配置 SDK 路径（Android Studio 会自动生成，命令行需手动建）
echo "sdk.dir=/你的/Android/Sdk" > local.properties

./gradlew assembleRelease     # 产物: app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug       # 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 签名

工程里没有原始 keystore，`app/build.gradle` 里 release 目前复用本机的调试证书（`signingConfig signingConfigs.debug`），只保证“能直接安装”。

要自己长期发布，请生成 keystore 后替换：

```bash
keytool -genkeypair -v -keystore autobrightness.jks -alias autobrightness \
        -keyalg RSA -keysize 2048 -validity 36500
```

```groovy
android {
    signingConfigs {
        release {
            storeFile file("autobrightness.jks")
            storePassword "..."
            keyAlias "autobrightness"
            keyPassword "..."
        }
    }
    buildTypes {
        release { signingConfig signingConfigs.release }
    }
}
```

## 配置格式

```
lmd:灵敏度/过渡秒数
光照阈值 目标亮度
```

示例（也是首次运行的默认值）：

```
lmd:3/2
0 50
20 250
```

含义：环境光 ≥ 0 时亮度 50，≥ 20 时亮度 250；灵敏度 3 表示同一档位连续出现 3 次才调整；过渡 2 秒。亮度范围通常是 0-255，部分机型上限不同，按自己设备调。

## 使用前提

1. 授予“修改系统设置”权限（`WRITE_SETTINGS`），否则写亮度会失败（修复版会在日志里明确报出来，不再静默失败）；
2. MIUI 里把省电策略设为“无限制”，并加入后台清理白名单；
3. 开机自启需要 `RECEIVE_BOOT_COMPLETED`，部分 ROM 还要在“自启动”列表里手动允许。

## 目录结构

```
app/src/main/java/com/myapp/autobrightness/
    MainActivity.java      # 界面、开关、配置编辑、权限引导
    autobrightness.java    # 前台服务：光感监听 + 配置解析 + 亮度平滑过渡
    BootReceiver.java      # 开机 / 覆盖安装后恢复服务
app/src/main/res/          # 布局、字符串、图标
docs/                      # 排查报告与修复补丁
```
