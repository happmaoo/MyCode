# MyBtCam：经典蓝牙 SPP 摄像头传视频（服务端 + 客户端）

两个独立 APK，通过经典蓝牙 SPP（RFCOMM）传 H.264 实时画面：

| APP | 包名 | 角色 |
|---|---|---|
| MyBtCamS | `com.myapp.btcams` | 服务端：被拍的那台手机，开摄像头推流 |
| MyBtCamC | `com.myapp.btcamc` | 客户端：看画面的那台手机，负责解码显示 |

- 最低兼容 Android 7.1（minSdk 25）
- 主题：Material Light + 亮色 ActionBar（浅灰底 / 深色标题 / 深色三点菜单，菜单弹出无动画）
- 设置页为独立 Activity，设置即时保存
- 已编译好的 APK：`apk/MyBtCamS-debug.apk`、`apk/MyBtCamC-debug.apk`

---

## 一、你问的「客户端怎么让服务端开始推流」

结论：**不需要 root，也不需要额外广播，服务端跑一个前台 Service 就够了。**

原因：跨设备没法发 Android 广播，两台手机之间唯一的通道就是蓝牙 socket。所以方案是：

1. 服务端 APP 启动一个**常驻前台 Service**（`SppService`），里面 `listenUsingRfcommWithServiceRecord` 监听 SPP。
2. 客户端 `connect()` 成功后立刻发一条 `HELLO`（里面带分辨率/码率）。
3. 服务端在**Service 进程内**直接打开 Camera2 + MediaCodec 开始编码，**完全不依赖 Activity 是否在前台**。这就是「自动运行命令开摄像头」的等价实现，而且比 `am start` 稳。
4. 如果希望把服务端主界面也点亮（设置里可关），服务端收到 HELLO 后尝试 `startActivity`（Android 7.1 允许 Service 后台启动 Activity）。
5. 只有一种情况才需要 root：某些 ROM 会「调用成功但界面不显示」。代码在 1.2 秒后用 `MainActivity.sVisible` 检查一次，没显示就退回
   `su -c am start -n com.myapp.btcams/.MainActivity`。

所以 root 在这里只是**兜底点亮界面**，推流链路本身不依赖它。
另外 root 还能帮你把服务端 APP 拉起（如果被系统杀掉），但那是保活问题，见「保活」一节。

---

## 二、目录结构

```
MyBtCam/
├── settings.gradle / build.gradle / gradle.properties
├── common/            协议库（两个 APP 共用）
│   └── BtCamProtocol.java
├── server/            MyBtCamS
│   └── SppService.java          前台监听服务 + 会话
│   └── stream/StreamEngine.java Camera2 → GL → MediaCodec
│   └── stream/SppWriter.java    单写线程 + 丢帧策略
│   └── stream/EglCore.java      最小 EGL 封装
│   └── MainActivity / SettingsActivity / BootReceiver / Prefs / RootShell
├── client/            MyBtCamC
│   └── ClientSession.java       连接 + 解码 + 丢帧重同步
│   └── MainActivity / SettingsActivity / Prefs / AspectSurfaceView
└── apk/               编译产物
```

## 三、编译

用 Android Studio 直接打开 `MyBtCam` 根目录，Sync 后：

```bash
./gradlew assembleDebug
```

产物：

```
server/build/outputs/apk/debug/server-debug.apk
client/build/outputs/apk/debug/client-debug.apk
```

工程使用 AGP 8.5.2 / Gradle 8.9 / compileSdk 34 / JDK 17，Android Studio Koala 及以上可直接打开。
换成本机 SDK 路径时，Studio 会自动生成 `local.properties`。

---

## 四、使用步骤

1. 两台手机先在**系统蓝牙设置里手动配对**（服务端可被发现即可，配对后不需要保持可发现）。
2. 服务端装 MyBtCamS，打开 APP → 授予相机权限 → 点「启动监听」。
   - 需要开机自启就在设置里打开「开机自动运行」（首次仍需手动启动一次 APP）。
3. 客户端装 MyBtCamC → 设置页 → 「从已配对设备选择」挑服务端 → 保存；
   顺便设好默认分辨率和码率，需要的话打开「打开 APP 自动连接」。
4. 回主界面点「连接」——按钮会变成「取消连接」（连接中）/「断开」（已连接，红色）；
   连上后服务端会自动开摄像头，画面出现在客户端；
   主界面可随时切 640x480 / 320x240，码率用下拉框选 1 Mbps / 500 / 300 / 200 / 100 Kbps，服务端会重建编码器（不用重连）。
5. 右上角三点菜单里可以进设置、切全屏；全屏时点一下画面或按返回键退出全屏。

---

## 五、协议（9 字节头 + payload）

```
magic(2)=0xB7C1 | version(1) | type(1) | flags(1) | payloadLength(4)
```

| type | 方向 | 内容 |
|---|---|---|
| 1 HELLO | C→S | 宽(2) 高(2) 码率(4) 摄像头(1) 方向(1) 保留(2) |
| 2 HELLO_ACK | S→C | 结果(1) + 原因文本 |
| 3 CONFIG | S→C | 宽(2) 高(2) spsLen(2) ppsLen(2) + SPS + PPS |
| 4 VIDEO | S→C | seq(4) + H.264 Annex-B 数据，flags bit0 = IDR |
| 5 STOP | C→S | 停止推流但保持连接 |
| 6/7 PING/PONG | 双向 | 3 秒心跳 |
| 8 PARAM | C→S | 运行中改参数（payload 同 HELLO） |
| 9 STATUS | S→C | 状态/错误文本 |

**seq 是丢帧不乱码的关键**：服务端对每一次编码输出分配连续 seq（不管这帧发没发出去）。
客户端发现 seq 断裂 → 说明中间被丢了 → 清空本地队列、等下一个 IDR 再喂解码器。
只丢 P 帧不丢 IDR，才能避免参考链断裂导致的一整段花屏。

---

## 六、服务端实现要点

- **Camera2 + YUV 直通（当前方案，不再走 GL）**：`ImageReader(YUV_420_888)` 直接按目标尺寸出图
  （320x240 档相机就出 320x240，640x480 档就出 640x480，相机自己保证宽高比例），
  Java 侧转成 NV21 → 按传感器方向旋转 → 按 16 对齐补边 → 塞进 `MediaCodec` 的 ByteBuffer 输入。
  - 全程没有「把一帧塞进另一个比例画布」的动作，所以画面不会被拉宽或压扁；
  - 旧的 GL(OES) 方案要把 640x480 画到 320x240/240x320 画布，在这台设备的相机上实测会把画面拉变形，已废弃；
  - 旋转 90/270 时编码宽高互换（320x240 请求 → 实际编码 240x320），客户端按 3:4 窗口显示。
- **关闭降噪 / 锐化**：`NOISE_REDUCTION_MODE_OFF` + `EDGE_MODE_OFF`，机型不支持时自动回退默认参数。
- **编码**：H.264 Baseline 风格的 AVC，15fps，I 帧间隔 1 秒，码率 500K/1M。
- **WakeLock**：`PARTIAL_WAKE_LOCK`，连接时获取、断开释放（服务端熄屏也能持续推流）。
- **前台服务**：常驻通知，`stopWithTask=false`，退出任务列表也继续监听。
- **断线**：客户端 socket 一断，写线程抛异常 → 关编码器/摄像头 → 释放 WakeLock → 回到 accept 循环。

## 七、客户端实现要点

- 连接：`createRfcommSocketToServiceRecord`，12 秒超时；SDP 异常时退回反射 `createRfcommSocket(1)`。
- 解码：MediaCodec 直接渲染到 `SurfaceView`，`setVideoScalingMode(SCALE_TO_FIT)` 等比缩放；
  输入队列满时丢帧并转入等 IDR。
- 队列只有 6 帧，超了直接清空，保证画面永远追最新数据（流畅度优先）。
- 控件 `AspectSurfaceView` 按视频宽高比测量（240x320 视频 → 窗口 3:4），多余区域黑边居中，
  不再调用 `setFixedSize` 固定 Surface 缓冲。

## 八、带宽与丢帧

经典蓝牙 SPP 的实际吞吐在手机上通常只有 **1 Mbps 上下**（受芯片与对端影响）。
所以：

- 推荐 500 / 300 / 200 Kbps；1 Mbps 在部分机型上会频繁丢帧（画面会「跳」，但不会花屏），100 Kbps 适合链路很差时保证不卡。
- 服务端发送队列满了就清空积压，并只从下一个 IDR 开始发；客户端靠 seq 检测断链后同样从 IDR 恢复。
- 帧率固定 15fps，320x240 档位在弱链路下明显更流畅。

## 九、保活建议（Android 7.1 及以上）

1. 设置页点「忽略电池优化」。
2. 国产 ROM 再到「自启动 / 后台管理」里给 MyBtCamS 开白名单。
3. 服务端是前台服务，正常不会被立即回收；被杀了下次客户端连不上时，需要服务端自己重新启动（或用 root 看门狗拉起，属于额外需求，可再加）。

## 十、可改的地方

- SPP UUID：`common/.../BtCamProtocol.java` 里的 `SPP_UUID`（两个 APP 共用，改一处即可）。
- 帧率：`StreamEngine.FRAME_RATE`（默认 15）。
- 支持新分辨率：服务端的 `handleRequest` 白名单 + 客户端单选按钮。
