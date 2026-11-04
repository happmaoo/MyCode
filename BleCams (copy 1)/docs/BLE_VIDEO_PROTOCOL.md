# BleCam BLE 视频传输协议 v2

服务端（Android BLE Peripheral / GATT Server）把视频码流经 **GATT notify** 推给客户端（BLE Central）。
本文是两端实现与第三方客户端对接的唯一依据。

**v1 → v2 的变化**：包头 5 字节 → **2 字节**，并取消每包的 chunkIndex，改为**每帧尾 1 字节 CRC8**。
MTU 247 时每包可用载荷从 239 → 242 字节（+1.3%），而丢包检测能力基本没损失（漏检概率 1/256，
换来的是每帧 1 字节而不是每包 2 字节）。

## 1. GATT 布局

| 项目 | UUID | 属性 | 说明 |
|---|---|---|---|
| 视频服务 | `c8a2f101-1c3e-4f5a-9b7d-6e0a1b2c3d40` | primary | 广播只带这一个 128bit UUID，设备名 `BleCam` 放 scan response |
| STREAM | `c8a2f102-1c3e-4f5a-9b7d-6e0a1b2c3d40` | `NOTIFY` + CCCD | 视频分包下行通道 |
| CONTROL | `c8a2f103-1c3e-4f5a-9b7d-6e0a1b2c3d40` | `WRITE` / `WRITE_NO_RESPONSE` | 控制命令上行通道 |
| STATUS | `c8a2f104-1c3e-4f5a-9b7d-6e0a1b2c3d40` | `READ` | 一行 `key=value` 状态文本 |

客户端时序（**顺序很重要**）：

1. 按 service UUID 扫描并连接，同时 `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` 把连接间隔压到 7.5ms。
2. `requestMtu(247)`，读到自己协商到的 MTU。
3. `setCharacteristicNotification(true)` + 写 STREAM 的 CCCD = `ENABLE_NOTIFICATION`。
4. **写 CONTROL：`08 <mtu 大端 uint16>`**，把自己的 ATT 上限告诉服务端。
5. 开始收 notify。

> 第 4 步不是可选的。Android 的 GATT **服务端**在 API 33 之前没有公开的协商 MTU 回调，
> 服务端只能按客户端上报值决定分包大小；没收到上报就按 `MTU=23`（每包 18 字节）工作。
> 用 nRF Connect 联调时必须手工写 `08 00 F7`。
>
> **v2 与 v1 不兼容**：包头 version 位不同，旧包会被 `Header.parse` 直接拒收。两端要一起升级。

## 2. 分包格式（STREAM notify 载荷）

```
普通包:  [0] version|flags   [1] frameSeq(uint8)   [2..] 帧数据
最后一包: [0] version|flags(含 LAST)  [1] frameSeq  [2..] 帧数据尾段  [末尾] CRC8(整帧)
```

byte0 高 4 位 = version（当前 `2`），低 4 位 = flags：

| bit | 名称 | 含义 |
|---|---|---|
| 0x08 | KEY | 帧内关键帧（JPEG 恒为 1；AVC 表示含 IDR） |
| 0x04 | CONFIG | 帧首带解码配置（JPEG=APPn/量化表；AVC=SPS/PPS） |
| 0x02 | FIRST | 本帧第 1 包 |
| 0x01 | LAST | 本帧最后 1 包，**载荷末尾多 1 字节 CRC8** |

`frameSeq` 是 uint8，逐帧 +1 回绕；每包帧数据长度 = `min(MTU - 3 - 2, 245)`，其中 `MTU-3` 是 ATT 载荷上限（opcode 1 + handle 2）。CRC8 用 poly `0x07`、init `0x00`，覆盖**整帧原始字节**。

### 接收端组帧规则

- 见 `FIRST` → 开新缓冲（上一帧若没收尾就作废），记住 `frameSeq`/KEY/CONFIG。
- 非 `FIRST` 且（没有进行中缓冲 或 `frameSeq` 不一致）→ 本帧作废，等下一个带 `FIRST` 的帧。
- 见 `LAST` → 去掉尾部 CRC 字节后校验 CRC8：
  - **CRC 不匹配** → 帧内丢包，整帧作废（JPEG 下一帧立即可用；AVC 需 flush 解码器 + 请求 IDR）；
  - JPEG 还额外校验 `SOI(FFD8)` 开头、`EOI(FFD9)` 结尾。
- 完整帧的 `frameSeq` 相比上一个完整帧不是 +1 → 判定丢帧，回调 `onStreamGap`。

`StreamProtocol.seqDelta(a,b)` 处理 uint8 回绕（结果范围 [-128,127]）。

## 3. 控制命令（CONTROL）

| 命令 | 长度 | 参数 | 作用 |
|---|---|---|---|
| `01` | 1 | — | 请求立即输出 IDR（AVC）/ 立即出帧 |
| `02` | 1 | — | 开始推流 |
| `03` | 1 | — | 停止推流 |
| `04` | 2 | u8 profileId | 切档位；跨分辨率时服务端重建编码器 |
| `05` | 5 | u32 bps | 设码率 |
| `06` | 2 | u8 fps | 设相机目标帧率（`CONTROL_AE_TARGET_FPS_RANGE`） |
| `07` | 1 | — | 心跳 |
| `08` | 3 | u16 mtu | **上报客户端协商 MTU**（必需） |
| `09` | 5 | u32 B/s | 上报客户端实测接收吞吐，供服务端自适应 |
| `0A` | 2 | u8 codec | 切编码格式：0=H.264/AVC，1=JPEG（预留给 JPEG 模式） |

## 4. STATUS（READ）

```
st=streaming;prof=2;res=320x240;fps=10;br=60000;mtu=247;cks=242;seq=173;thr=7300;drop=3;q=512;pr=77;cb=1
```

`pr` = 实际码流 `profile_idc`（66=Baseline / 77=Main / 100=High），`cb` = 实际熵编码（1=CABAC / 0=CAVLC）。
这两个值由接收端从 SPS/PPS 解析后回报，**用来确认 `KEY_PROFILE` 有没有真被 Vendor 编码器采纳**——
不少机型会静默忽略它，继续输出 Baseline/CAVLC。

## 5. 码流约定（AVC 模式）

- 先尝试 **Main + CABAC**（低码率下通常比 Baseline/CAVLC 省 10~15%），configure 失败逐级回退到
  Constrained Baseline、再回退到不设 profile。
- IDR 间隔 10s。低码率下一个 IDR 可能等于整整 1 秒的字节预算，所以只在“链路确实失同步”时由
  客户端用 `01` 请求即时 IDR，服务端侧的丢帧重同步也有 2s 冷却。
- 每个 IDR 前自动拼 SPS/PPS，客户端任意时刻接入都能靠一个关键帧起解。
- Android 解码侧写法：SPS/PPS 以**裸 NAL（无起始码）+ `BUFFER_FLAG_CODEC_CONFIG`** 各喂一次，
  AU 本体按**带起始码的 Annex-B 整段**作为一个输入 buffer 送入。

## 6. 带宽预算

按最保守估计（7.5ms 连接间隔、每个连接事件只发 1 包 ≈ 133 包/s）：

| MTU | 每包帧数据 | 133 包/s | 结论 |
|---|---|---|---|
| 23 | 18 B | ≈ 2.4 kB/s ≈ **19 kbps** | 只够 `ULTRA_LOW` |
| 65 | 60 B | ≈ 8.0 kB/s ≈ **64 kbps** | 60kbps 卡线，抖动即丢帧 |
| 185 | 180 B | ≈ 24 kB/s ≈ 190 kbps | 舒适 |
| 247 | 242 B | ≈ 32 kB/s ≈ 258 kbps | 可跑 `HIGH` |

实际机型在支持 DLE（Data Length Extension）时一个事件能发多包，所以上表是**下限**；真要以实测为准，
看 STATUS 的 `thr` 字段。

**单位比特成本（bpp）比编码格式更决定画质**：`bpp = bitrate / (w × h × fps)`。

| 配置 | bpp | 直观效果 |
|---|---|---|
| 320x240 @10fps / 60kbps | 0.078 | 马赛糊，勉强认人 |
| 320x240 @5fps / 60kbps（`HQ_5FPS`） | 0.156 | 同字节数，每帧比特翻倍 |
| 160x120 @10fps / 60kbps（`LOW` 提高码率） | 0.313 | 每像素比特 4 倍 |
| 换 HEVC（省 ~20%） | 0.094 | 相当于只把帧率提到 8fps |

也就是说：**在半分辨率/半帧率面前，换编码格式带来的 20~30% 收益是二阶的。**

服务端策略（`StreamPump`）：

1. **令牌桶限速**：注入空口的字节速率 ≈ `bitrate/8 × 1.2`。不限速时 Android 协议栈会照单全收，
   队列越堆越长，表现为“延迟无限增长”。
2. **拥塞时整帧丢到下一个 IDR**（AVC）：队列超 6kB 或 6 帧、或单帧滞留超 700ms 就清空待发队列。
3. **码率/帧率自适应**：500ms 窗口测速，连续 2 个窗口跟不上就把码率压到实测 90%（下限 12kbps）；
   连续 6 个窗口空闲才每次 +10% 涨回档位上限；持续拥塞则帧率 -1（下限 3fps）。

## 7. 编码格式怎么选

| 方案 | 同画质码率 | 上机代价 | 建议 |
|---|---|---|---|
| H.264/AVC（硬编，当前默认） | 基准 | 无 | **首选**，Main+CABAC |
| HEVC/H.265 硬编 | −30~40% | 你的手机没有 | 有就白赚，没有别指望 |
| HEVC 软编（NDK + x265） | −15~25%（小分辨率下收益缩水） | 自己维护 NDK/GOP/码控，功耗高，还要专利授权 | **不划算** |
| MJPEG | +300~500%（更费） | CPU 换码率，无参考帧、抗丢包好 | 只在“必须逐帧独立”或极低帧率抓拍时用 |

小分辨率下 HEVC 收益缩水的原因很具体：HEVC 的 CTU 是 32/64 像素，320x240 按 CTU64 只有 5x4=20 个块，
它赖以取胜的大块划分/预测树根本没有发挥空间。

如果确实需要更高带宽，正确的杠杆是换传输而不是换编码：经典蓝牙 RFCOMM/L2CAP 流式 socket 实测通常能到
100~200kbps 量级（不再是 BLE），或直接上 WiFi/USB。

## 8. 对照 ESPAUTO（github.com/honoz/ESPAUTO）

那个项目是 ESP32-CAM 做 BLE Peripheral、Android 做 Central，源码里的关键参数：

| 项 | ESPAUTO | 说明 |
|---|---|---|
| 图像 | OV2640 硬件 JPEG，`FRAMESIZE_HQVGA`=**240x180**，`jpeg_quality=12` | 每帧约 3–5kB，MJPEG |
| MTU | `NimBLEDevice::setMTU(512)`，实际按 **490 字节/包** 发 | 载荷率 ≈ 96% |
| PHY | `setDefaultPhy(2M, 2M)`，Android 侧还调了 `setPreferredPhy` | **它吞吐高的第一原因** |
| 帧边界 | 每帧一个 4 字节头包 `AB CD len16`，之后**裸数据无包头** | 见下面的算法对比 |
| 节奏 | 每包 `vTaskDelay(3ms)`；采集队列深度 4，队满丢帧 | 粗节奏控制 + 生产侧丢帧 |
| 解码 | `ThreadPoolExecutor + DiscardOldestPolicy` + `BitmapFactory` | 跟不上就丢最老的，绝不堆积 |

**它的帧头比我们省吗？基本没有。** 按 MTU 247、单帧 1200 字节算：

| 方案 | 每帧头部开销 |
|---|---|
| ESPAUTO：4B 头包 + 5 个 242B 裸数据包（共 6 包） | 4 字节 |
| 我们 v2：6 个包各 2 字节头（含 seq/KEY/CONFIG） | 12 字节 |
| 差值 | 8 字节 / 1200 字节 ≈ **0.7%** |

而且它“按期望字节数收帧”只能靠字节数凑巧合检出丢包，我们的每帧 CRC8 是显式校验。
**帧头不是它的优势来源，没必要照抄**（我们的 2 字节头还带 seq/KEY/CONFIG，AVC 模式必须有）。

**能搬过来的三件事，已经搬了：**

1. **Central 主动请求 2M PHY**（`BleStreamClient.request2mPhy`）。PHY 是链路两端取交集，
   Android **peripheral 侧没有公开 API 能发起 PHY 更新**，只有 central 能——所以这一步只能由接收端做。
   你的摄像头发端不支持 2M 的话谈下来仍是 1M，这点搬不动；接收端现在会把实际 PHY 打进日志并显示在
   状态栏（`PHY 2M/1M`），不用猜。
2. **MTU 要就要大的**：请求值从 247 提到 517，按协商结果动态定分包大小。MTU 与 PHY 无关，
   只取决于控制器是否支持 DLE（BLE 4.2+ 都支持）——这是 1M PHY 下唯一还能放大单包载荷的旋钮。
3. **小帧 + 高帧率的操作点**：新增档位 `TINY = 176x144@12fps/60kbps`（bpp≈0.18），对应它 240x180 的思路。
   BLE 图传“看着顺、看得清”靠的是砍像素数，不是把帧头抠到 0。

另外两处它的做法我们已经用更好的方式覆盖了：它的“每包 sleep 3ms”是开环节奏，我们用令牌桶按
`bitrate/8` 闭环限速；它的“队满丢帧”对 MJPEG 成立（帧独立），对 AVC 必须丢到下一个 IDR，否则花屏。

**搬不动的部分**：OV2640 直出 JPEG 是**零 CPU 成本**的；Android 上做 MJPEG 要用
`YuvImage.compressToJpeg` 软压，帧率一高就吃电发热。同样字节下 H.264 硬编画质仍优于 JPEG，
所以本项目默认仍是 AVC；JPEG 模式只在“必须逐帧独立 / 极低帧率抓拍”时才更划算。

## 9. 参考实现

- `:protocol` — `StreamProtocol`（打包/解包 + CRC8）、`AuReassembler`（组帧/校验）、`AnnexB`
  （NAL 切分、SPS 分辨率与 profile、PPS CABAC 标志）、`Jpeg`、`VideoProfile`、`StatusInfo`、`Perms`。
  纯 Java，JVM 单元测试：`./gradlew :protocol:testDebugUnitTest`（16 条）。
- `:app` — 服务端：`Camera2Source` → `AvcEncoder` → `StreamPump` → `BleCamServer`，装在 `ServerService`。
  `AvcEncoder` 启动时会把本机所有 AVC/HEVC 编解码器打到 logcat（`adb logcat -s AvcEncoder`）。
- `:receiver` — 客户端：`BleStreamClient` → `AuReassembler` → `AvcDecoder`。
