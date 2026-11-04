# BleCams — 用 BLE notify 传摄像头视频的 Android Demo

服务端把 Camera2 的画面用 MediaCodec 编成 **H.264/AVC**（默认 **320x240 @ 10fps / 60kbps**），
按自定义分包协议切成 GATT notify 包推给 BLE 客户端。兼容 **蓝牙 4.0 + Android 7.1（minSdk 25）**。

```
Camera2 ──Surface──▶ MediaCodec(AVC) ──AU(Annex-B)──▶ StreamPump ──notify──▶ BLE Central
                        ▲                                │
                        └──── setParameters(码率/IDR) ◀───┘  链路带宽自适应 + 拥塞丢帧
```

## 模块

| 模块 | 说明 |
|---|---|
| `:app` | 服务端（BLE Peripheral）。前台服务承载采集/编码/发送，锁屏继续推流 |
| `:receiver` | 接收端 Demo（BLE Central）。扫描连接、组帧、硬解码上屏、链路统计 |
| `:protocol` | 两端共用协议库：分包/组帧、控制命令、Annex-B 工具、档位、状态文本（纯 Java，带 JVM 单元测试） |

协议细节见 **[docs/BLE_VIDEO_PROTOCOL.md](docs/BLE_VIDEO_PROTOCOL.md)**。

## 构建

```bash
./gradlew :app:assembleDebug :receiver:assembleDebug     # 两个 APK
./gradlew :protocol:testDebugUnitTest                    # 协议往返测试（10 条）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`、`receiver/build/outputs/apk/debug/receiver-debug.apk`。

**为什么 compileSdk/targetSdk 是 30 而不是 31**：本机 JDK 是 1.8，javac 8 读取 API-31 的
`android.jar` 会直接崩（`annotationType(): unrecognized Attribute name MODULE`）。工程用
`compileSdkVersion 30 / buildTools 30.0.3 / AGP 3.6.2 / Gradle 5.6.4 / Java 8`，运行时兼容仍是
Android 7.1+。若换到 JDK 11+ 与新版 AGP，可把三端 SDK 提到 31/34，同时按
`protocol/Perms.java` 注释切换成 `BLUETOOTH_ADVERTISE/CONNECT/SCAN` 权限。

## 跑起来

1. **服务端**：装 `app-debug.apk` → 授予相机/定位权限 → 选档位（默认 `BALANCED 320x240@10fps 60kbps`）
   → 「开始推流」。界面上是本地预览，下方显示状态：广播中 / 客户端地址 / MTU / 分包大小 /
   实测吞吐 / 队列 / 丢帧数。
2. **接收端**：装 `receiver-debug.apk` → 「连接」→ 自动扫描、连接、`requestMtu(247)`、订阅 CCCD、
   回写 MTU → 出画面。断线自动重连；检测到丢帧会自动 flush 解码器并请求 IDR。
3. 两台实体机距离近一些：BLE 吞吐对 RSSI 非常敏感，隔墙后丢包率上升会直接表现为“帧率被降下来”。

用第三方客户端（如 nRF Connect）联调时，必须手工向 CONTROL 写 `08 00 F7`（上报 MTU=247），
否则服务端按 MTU=23 工作，每包只有 18 字节数据。

## 默认编码参数

| 参数 | 值 | 位置 |
|---|---|---|
| 分辨率 | 320x240（相机不支持时按 4:3 就近选，实际值回报在 STATUS） | `VideoProfile.BALANCED` |
| 码率 | 60 kbps，CBR，可在 12kbps～档位上限间自适应 | `AvcEncoder.configure` |
| 帧率 | 10fps（同时设 `CONTROL_AE_TARGET_FPS_RANGE`） | `Camera2Source.startRepeating` |
| Profile | 先试 **Main+CABAC**（低码率省 10~15%），失败退 Constrained Baseline，再退“不设 profile” | `AvcEncoder.configure` |
| I 帧间隔 | **10s**；只在链路失同步时由客户端 `CMD_REQUEST_IDR` 触发（服务端侧 2s 冷却） | `AvcEncoder` |
| GOP 结构 | 每个 IDR 前自动拼 SPS/PPS，客户端随时可接入 | `AvcEncoder.drainLoop` |

档位（`VideoProfile.ALL`），`bpp = 码率 / (宽 × 高 × fps)`：

| id | 名称 | 分辨率 | fps | 码率 | bpp | 适用 |
|---|---|---|---|---|---|---|
| 0 | ULTRA_LOW | 128x96 | 5 | 16k | 0.21 | 只有 MTU 23 的裸 BLE 4.0 |
| 1 | LOW | 160x120 | 8 | 40k | 0.26 | 常规 BLE 链路 |
| 3 | **HQ_5FPS** | 320x240 | 5 | 60k | 0.156 | **同字节数画质最好，推荐** |
| 2 | BALANCED | 320x240 | 10 | 60k | 0.078 | 你最初指定的档，流畅度优先 |
| 4 | HIGH | 320x240 | 15 | 120k | 0.078 | 需要 MTU≥160 + DLE/2M PHY |
| 5 | TINY | 176x144 | 12 | 60k | 0.18 | ESPAUTO 式小帧高帧率操作点 |

想在 60kbps 上看到更清楚的画面，优先动分辨率和帧率，其次才是换编码格式——
HEVC 软编只能带来 ~20% 收益，代价（NDK、功耗、专利授权）见协议文档第 7 节。

## 想知道你的设备到底能编什么

`AvcEncoder` 启动时会把本机全部 AVC/HEVC 编解码器实例打进 logcat：

```bash
adb logcat -s AvcEncoder Camera2Source BleCamServer StreamPump
```

推流起来后看服务端面板的「码流 profile / 熵编码」两行（也就是 STATUS 里的 `pr`/`cb`），
就能确认 `KEY_PROFILE` 有没有被 Vendor 编码器真的采纳——很多机型会静默忽略，继续输出 Baseline/CAVLC。

## 实现要点（踩过的坑）

- **MTU 必须靠应用层握手**。Android 的 GATT 服务端在 API 33 前拿不到协商 MTU，分包大小只能由
  客户端通过 `CMD_CLIENT_MTU` 上报，否则永远按 20 字节/包发。
- **不能无脑往协议栈灌数据**。`notifyCharacteristicChanged` 会排队并一直返回 true，不限制注入速率
  就等于把延迟推到几十秒。`StreamPump` 用令牌桶把字节速率锁在 `bitrate/8×1.2`。
- **拥塞时只能丢到下一个 IDR**。丢半帧或丢 P 帧都会花屏，所以队列超阈值就清空待发队列 + 请求关键帧；
  IDR 冷却 2s、间隔 10s，避免“丢帧→IDR→更挤→再丢”的正反馈。
- **帧内丢包用 CRC8 检出**。v2 包头没有分包序号，改为每帧尾 1 字节 CRC8：花 1 字节/帧，
  换回“坏帧不送进解码器”的能力（漏检率 1/256）。
- **Camera2 + 编码器 Surface 要求尺寸是相机支持的尺寸**，因此请求 320x240 时可能在同比例里就近选，
  实际分辨率以 STATUS 为准；接收端从 SPS 解析分辨率，变化时重建解码器。
- **BLE 吞吐是这套方案的真上限**：MTU 23 时约 19kbps、MTU 65 时约 64kbps、MTU 247 时约 258kbps
  （最保守的“每连接事件 1 包”估算，实际支持 DLE 会更高）。60kbps 的 320x240 画质是
  “能认出人和动作”的量级，不是监控级画质。
- 服务端跑在前台服务 + `PARTIAL_WAKE_LOCK`，`foregroundServiceType="camera|connectedDevice"`。

## 已知边界

- 只支持 **1 个客户端**（`BluetoothGattServer` 多连接时后加入的会抢同一 notify 通道）。
- 无加密/无鉴权，明文码流；任何 central 都能连。
- 不做分片重传：BLE notify 本身不可靠（LL 只重传同一事件内的包），可靠性靠“丢帧 + IDR 重同步”保证。
- 相机没有客户端时仍保持编码（预览需要）。要省电可在 `onSubscriptionChanged(false)` 里
  `stopRepeating()` + `encoder.stop()`，这里留作扩展点。
