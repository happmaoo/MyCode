# MyMQTT —— 一个 APK，两种角色（控制端 / 执行端）

同一个包 `com.myapp.mymqtt`（应用名 **MyMQTT**），在设置页里切换角色：

- **Control（控制端）**：发命令，收结果
- **Executor（执行端）**：收命令，执行 shell，把结果发回去

两台机器各装这一个 APK，分别设成两种角色即可（以前分开的 `MyMQTTExec` 已废弃，请卸载，避免同一台设备上出现两个执行端）。

## 一、主题

| | Control | Executor |
|---|---|---|
| 发布 | `control/command` | `executor/result` |
| 订阅 | `executor/result` | `control/command` |

切换角色时主题会自动重置成该角色的默认值，并断开旧连接（需要回主界面重新 Connect）。

## 二、消息格式

命令就是纯文本 shell 命令，不再套 JSON：

```
ls /
uptime
df -h
```

- 控制端点的 SEND 把输入框内容原样发到发布主题（多行也行）。
- 执行端**不解析 JSON**：收到的整条 payload 原样交给 `sh -c` 执行（`screenshot` 是特殊命令，走 screencap，先试 APP 外部私有目录，失败再 `su -c`）。
- 执行端回结果仍是 JSON，方便控制端显示：

```json
{"cmd":"uptime","code":0,"ms":152,"ts":1795661912930,"output":"12:58:32 up 18:35, 0 users, load average: ..."}
```

`code` 是退出码（`-1` 起不来进程，`-2` 超时被终止），`output` 超过 4000 字符截断，单条命令超时 15 秒。

## 三、设置项

公共：

- **Role**：Control / Executor（立即生效，主题重置）
- Broker host / port
- **Use TLS / SSL**：勾上用 `ssl://`，走系统信任库；**没有证书路径输入框了**，自签 CA 的场景请把 CA 装进系统信任（或保留旧的 `files/ca.crt`，程序会自动用它）
- Username / Password（用户名留空 = 匿名）
- Keepalive（5~3600 秒）
- QoS（0/1/2，发布订阅共用）
- 连接超时（1~120 秒，0 = 一直等）
- Client ID（每台设备必须不同）
- 发布主题
- **Debug**（默认**不勾**）：勾上才在日志区显示时间戳、收发报文、订阅/连接细节
- **记录日志**（默认**不勾**）：勾上把运行日志写文件，一天一个文件，位置 `Android/data/com.myapp.mymqtt/files/logs/mqtt-YYYYMMDD.log`（外部私有目录，不需要权限；拿不到外部目录时退到内部 `files/logs`）。设置页里会直接显示实际路径。记录内容包括状态变化、收到的报文、发出的报文、执行端执行的每条命令
- 开机自动连接、断线自动重连（退避 1→30 秒）

仅 Control：**Quick commands**（每行一条，⌄CMD 面板里点一条直接发送）

仅 Executor：**Startup command**（连接成功后自动执行一次的命令，空 = 不执行；结果同样发到 `executor/result`，控制端能看到）

订阅主题在右上角三点菜单 **Subscribe topics** 里增删，连上后自动全部订阅（支持 `executor/#` 通配符）。

## 四、日志区显示

- **Debug 不勾（默认）**：只显示命令输出，每条输出前加一行时间标记：

```
──── 12:23:34 ────
whoami: Max 0 arguments (see "whoami --help")

──── 12:24:02 ────
12:24:02 up 20:01, 0 users, load average: 3.35, ...
```

- **Debug 勾上**：完整调试视图（带时间戳），收发报文、订阅结果、断线原因都显示。

发布失败/断线这类错误在两种模式下都会显示（不带时间戳）。

## 五、连接状态

状态以 HiveMQ 客户端的真实状态为准（`client.getState()`），不再出现「界面显示已连接、发消息却报 `MqttClientStateException`」：

- 发布时客户端已断 → 立刻把状态改成「已断开」并自动重连
- 每 3 秒巡检一次，断线触发重连，重连成功后重新订阅全部主题

## 六、执行端省电

执行端收到命令时申请一个 `PARTIAL_WAKE_LOCK`（带 60 秒超时兜底），执行 shell + 把结果**同步发出去**之后再释放，锁屏/后台时不会因为 CPU 休眠把命令丢掉。

## 七、界面

照参考 APP 的 sh 窗口风格（纯系统控件、`Theme.Material.Light`、无 AndroidX）：

- 标题 `MyMQTT · Control` / `MyMQTT · Executor`
- 第一行 `Server:` + 地址 + **CONNECT/DISCONNECT**
- 状态行：连接状态 / ↑发送 ↓接收 / 最近收到 / 当前订阅
- `Pub:` 发布主题
- 工具行：自动换行、A- / A+、COPY、CLEAR
- 等宽日志区（不换行时可横向滚动，自动贴底）
- 底部：多行输入 + ▲▼ 历史 + ⌄CMD 面板（Quick / History）+ SEND

## 八、构建

```bash
export JAVA_HOME=/home/user/jdk17
export ANDROID_HOME=/home/user/android-sdk
/home/user/gradle-8.9/bin/gradle --no-daemon :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

- AGP 8.5.2 / compileSdk 34 / minSdk 21 / targetSdk 30
- HiveMQ `com.hivemq:hivemq-mqtt-client:1.3.3`，core library desugaring 已开
- `control` / `executor` 两个旧模块仍留在工程里便于回退，新包只依赖 `common`

## 九、版本与验证

- 1.7：合并成单包，设置页切换角色；去掉证书路径，改 TLS 开关；执行端不解析 JSON；状态以客户端真实状态为准
- 1.8：执行端命令执行期间加 wakelock，收发完毕释放
- 1.9：控制端加 Debug 开关，默认不勾时日志只显示输出
- 2.0：不勾 Debug 时每条输出前加 `──── 时间 ────` 标记；执行端加「Startup command」
- 2.1：两端设置页加「记录日志」复选框（写文件，默认不勾）
- 2.2：执行端改成**持久 shell 会话**：整下 app 只起一个 sh 进程，后面的命令都写进它的 stdin，所以 `cd /etc` 之后再发 `pwd` 就是 `/etc`，`export`/`alias` 也一直留着。多条命令按顺序排队执行；单条命令 20 秒没结束就当超时，自动重置会话（下一条命令重新起 shell）；发 `!reset` 可手动重置会话。

真机验证（红米 M2007J22C / Android 12 作 Control，工业网关 rolex / Android 10 作 Executor，同一个私有 EMQX 8883/TLS）：

- 两台都连上，Control 发 `ls /`、`uptime`、`whoami`，Executor 执行并回传，Control 日志按 `──── 12:23:34 ────` + 输出的格式显示
- Executor 设 `startup_cmd=uptime` 后重启，连接成功即自动执行并把 `uptime` 输出回到 Control（12:24:02 那条）
- 断线时不再显示「已连接」：发布失败会立刻变成「已断开」，并自动重连
- 2.3：传输层自动 gzip 压缩（控制端、执行端都生效）

## 长文本自动压缩（2.3 起）

两端发送前检查 payload：原始 UTF-8 字节 **≥ 256 字节**才尝试 gzip，并且**压完必须比原文至少小 16 字节**才真的发压缩版，否则照原样发。接收端不需要约定标记：gzip 数据的固定头是 `1F 8B`，而 UTF-8 文本永远不会以这两个字节开头，所以看到前两字节是 `1F 8B` 就 gunzip，否则直接按文本处理 —— 纯文本消息格式完全不变，和旧版本/别家客户端也能互通（发出去的短消息仍是纯文本）。

阈值怎么定的（本地实测，gzip level 6）：

| 原始字节 | 说明 | 结论 |
|---|---|---|
| < 100 | 如 `whoami` 的 60 B 输出 | 压了反而更大（gzip 头尾就 18 B），不压 |
| 100–200 | 一两行短输出 | 基本打平，不值得 |
| ≥ 256 | 约 250 英文字符 / 85 个汉字 | 开始明显省，实测 1596 B 的输出压到 478 B（省 70%） |

实测（真机 rolex）：
- `whoami` 结果 65 B → 明文发送
- `ls -l /system/etc | head -25` 结果 1596 B → gzip 478 B 发送，对端自动解压
- 325 B 的长命令（发往执行端）→ gzip 29 B；执行端解压后执行，回传 705 B → gzip 91 B

Debug 勾上时，收到压缩消息会多显示一行 `[压缩] ← topic 478B → 1596B`；日志文件里发送行前面带 `[gzip 1596B→478B]`，方便核对。

边界处理：压缩失败直接发原文；解压失败退回原字节按文本显示（不会丢消息）；单条消息解压上限 8 MB 防炸内存。
- 2.4：修「日志窗口有时候滚不到最底部」

## 日志滚动（2.4 修正）

原因：旧写法是 `svOut.post(...)` 里读 `child.getHeight()`。`View.post` 的 Runnable 跑在**主线程队列**，而 setText 引起的重新测量/布局要等下一帧的 traversal 才做，所以拿到的还是**上一次布局的高度** —— 滚动量按旧内容算，新加的那段就压在下面够不着。每来一小段差一点，来一大段（比如几十行命令输出）差一屏，这就是“有时候滚不到底”。

参考 MyBleClient 的 shell 窗口做法（120 ms 合并渲染 + `scrollTo(0, child.getHeight() - svOut.getHeight())`，不用 `fullScroll` 以免焦点被日志 TextView 抢走），本版在此基础上补齐：
- 用 `ViewTreeObserver.OnGlobalLayoutListener`：等测量/布局真正完成后再算最大滚动量并 `scrollTo`，另留一个 `post` 兜底（内容没变不触发 layout 时也能滚）。
- 手指按住日志区时不抢滚动，松开后继续跟随到底。
- 不换行模式只在内容宽度真的变宽时才重设 LayoutParams，避免每次渲染都 requestLayout 把滚动位置冲掉。
- Debug 勾上时，日志文件里会记 `[滚屏] y=… target=… vh=… ch=…`，可直接核对是否真的到底。

真机核对（红米，一次 120 行长输出）：日志里 25 次滚动全部 `y == target`。
- 2.5：主界面 CLEAR 后加 `FULL` 按钮

## FULL 全屏阅读（2.5）

- 按钮位置：主界面工具栏 `A-  A+  …  COPY  CLEAR  FULL`，点一下进入全屏。
- 全屏后收起：Server 行（地址 + Connect）、状态行、Pub 行、`自动换行 / A- / A+ / COPY / CLEAR`，按钮本身变成 `EXIT`，日志区往上顶满，能多看四五行的内容。
- 退出：再点 `EXIT`，或按返回键（全屏时返回键只退出全屏，不会退出 app）。
- 顺带把每秒刷新改成「文本没变就不重设」：以前每秒都 setText 一次，会让日志区每秒重排一遍（也会干扰滚动到底），现在只在 ↑↓ 计数/状态真的变化时才更新。
- 2.6：主界面精简（地址/主题只读显示、状态压成一行、Connect 用默认色、FULL 挪到 COPY 左边）

## 主界面（2.6 起）

- 第一行：`Server: <host>:<port>` 纯文本（改地址去 Settings），右边 Connect / Disconnect / Cancel 按钮用系统默认按钮色，不再按状态变红/橙/蓝。
- 第二行只有一行字：`已连接. ↑ 4 ↓ 5  订阅:executor/result pub:control/command`（状态后面不再重复地址端口，订阅多个用逗号分隔，太长会省略号）。
- Pub 输入框删掉了，发布主题固定用设置里的值，只在这一行显示。
- 工具栏顺序：`自动换行  A-  A+  …  FULL  COPY  CLEAR`。
- 2.9：按「TextView 日志自动滚动底部实现」的做法重做滚动跟随（2.4 的 OnGlobalLayout 方案保留）

## 滚动到底 / 跳顶（2.9，按参考文档实现）

1. **只 append，不全量 setText**：日志内容和已渲染内容做前缀比较，正常增长时用 `TextView.append(delta)` 追加；这样 TextView 不会整段重排，滚动位置也不会被冲掉。
2. **等布局完成再算最大滚动量**：`child.getHeight() - svOut.getHeight()`，通过 `OnGlobalLayoutListener` + `post` 双保险，等价于文档里的 `scrollView.post { child.height - scrollView.height }`；不使用 `fullScroll`（它会做焦点搜索，焦点跑到别的控件上就会跳）。
3. **距底部 24px 内才算“在底部”**：用 `OnScrollChangedListener` 判断 `child.height - (sv.height + scrollY) <= 24`，只有本来就在底部时才自动跟随；往上翻看历史时不会被强行拉回去。
4. **去掉 `textIsSelectable`**，TextView 设成不可聚焦/不可点击，避免点日志区时选区重建把滚动带回 0。
5. **整段重建后强制滚一次**：清屏、超长截断（20 万字）、界面重建这三种情况必须全量 setText，之后 `forcePin` 强制到底一次。
6. **界面重建不丢日志**：`onSaveInstanceState` 保存最近 2 万字，重建后恢复并滚到底；MainActivity 加了 `configChanges`，键盘弹出/转屏不再重建界面（这是“日志突然清空 + 从头开始”的一大来源）。
- 3.0：日志文本恢复可选中复制；执行端输出截断长度可在设置页自定义
- 3.1：vi/vim 控制端外部编辑器打开、编辑完自动回写执行端
- 3.2：编辑器设置合并成一个输入框，格式 `包名/类名`
- 3.3：日志里的体积自动换算 B / KB / MB（`[压缩]`、`[文件]`、`已写入`、`本地副本`）
- 3.4：vi 只从编辑器返回时校验一次；内容没改不再刷“跳过回写”
- 3.5：startup 里的 `cd` 行会被记住，shell 超时重置后新会话自动补回目录
- 3.6：startup 支持 `!shell` 行——用指定命令启动持久 shell（可换 shell / 换用户）
- 3.7：Startup command 说明里直接给出 `!shell` 示例
- 3.8：通知栏改用 App 自己的单色小图标（`ic_stat_mqtt`），不再借用系统蓝牙图标
- 3.9/3.10：两端互相感知在线/断开（retained 遗嘱 presence）
- ~~3.11：控制端「定时监控」~~（3.15 已按要求移除）
- 3.12：vi/!put 支持相对路径（按持久 shell 的 cwd 解析，`~` 也展开）
- 3.13：su 改用绝对路径探测（termux 的 PATH 里没有 `su`）
- 3.14：vi 先判断 App 能否直接读，读不到再走 su；能区分“文件不存在”和“需要 root 授权”
- 3.15：移除定时监控；改成执行端主动推电量，控制端显示在「已连接.」下面一行
- 3.16：只在控制端在线（presence）时推电量；控制端上线瞬间补发一次当前电量
- 3.17：设置里加 wakelock 复选框
- 3.18：复选框改为「连接期间一直持 wakelock」，并加「忽略电池优化」入口（Doze 下保心跳）

## 输出截断（3.0）

执行端每条命令的输出默认最多保留 **4000 字符**，超出就在末尾加一行 `…(输出已截断)`。这个值现在可以在执行端 Settings 的 `Output limit (chars)` 里改，范围 100–500000，改完立即生效（存在 prefs 的 `out_limit`）。截断只影响回传的 output 文本，命令本身照常执行完。

## 消息格式

- 控制端 → 执行端（topic `control/command`）：**纯文本 shell 命令**，不套 JSON，例如 `ls -l /system/etc`。
- 执行端 → 控制端（topic `executor/result`）：JSON：
`{"cmd":"uptime","code":0,"ms":44,"ts":1795670299549,"output":" 13:18:19 up 20:55,  0 users,  load average: 2.51, 2.79, 2.57\n"}`
  - `code`：退出码，`-2` = 超时被杀，`-1` = 异常/空命令
  - `ms`：执行耗时（毫秒）；`ts`：完成时间戳（epoch ms）
  - `output`：stdout+stderr 合并，超过 `out_limit` 截断
  - 异常时只有一个字段：`{"error":"IOException"}`
- 两个方向只要 payload ≥ 256 字节就自动 gzip（MQTT payload 以 `1F 8B` 开头），对端收到后自动解压，解压出来就是上面的纯文本 / JSON，格式不变。

## vi / vim 编辑回传（3.1）

1. 控制端发 `vi <路径>` / `vim <路径>`（例 `vi /data/local/tmp/a.txt`）。执行端不执行编辑器，而是把文件读出来带回：
   `{"cmd":"vi /data/local/tmp/a.txt","code":0,"ms":3,"output":"","file":{"path":"/data/local/tmp/a.txt","name":"a.txt","mode":"644","size":25,"sha":"…","data":"<base64>"}}`
   读不到/太大时 `code=1`，`output` 里是原因。
2. 控制端把内容写到自己的 `files/edit/<文件名>`，用 `ACTION_EDIT` + `content://com.myapp.mymqtt.editfile/<文件名>` 打开编辑器。编辑器取设置页 `vi/vim 外部编辑器` 输入框，一行写成 `包名/类名`（例 `in.mfile/com.nmmedit.nmm.TextEditorActivity`）；没填就弹系统选择框。
3. 编辑完回到 App，`onActivityResult` 和 `onResume` 各延迟 0.5 秒比对 sha256：**内容没变不发**，变了才回写。回写走命令通道：`!put <路径> <权限>` 换行接 base64，执行端写文件、`chmod` 恢复原权限，回一条普通结果 JSON（`已写入 … 字节，权限 644`）。
4. 上限与权限：默认 256 KB（prefs `edit_max_kb`，32–4096，暂无界面）。读写都先按普通 App 权限尝试，失败自动走一次 su：读是 `su -c cp 目标 → 私有目录` 再读，写是写私有暂存再 `su -c cp + chmod` 回目标。所以 `/etc`、`/system/build.prop`、`/sdcard` 都能编辑，前提是 root 管理器里给过 MyMQTT su 授权；没授权会回报「无写权限，su 也失败」。

## 启动命令与工作目录（3.5）

执行端 Settings 里的 `Startup command` 在每次 MQTT 连接成功后执行一次，在持久 shell 会话里跑，所以 `cd /etc` 这类命令会改变后续所有命令的工作目录。

另外 App 会从启动命令里挑出最后一条 `cd ...` 记住（`ExecShell.setPersistCwd`）。持久 shell 因 20 秒超时/异常被重置、或手动 `!reset` 之后，新会话建立时会先自动执行这条 `cd`，再跑你发来的命令——目录不会莫名其妙回到 `/`。没写 `cd` 就不补任何东西（`sh xxx.sh` 这种有副作用的启动命令也不会被重复执行）。

## startup 里的 `!shell`（3.6）

app 是普通 uid，读不到别的 App 私有目录（例如 `/data/data/com.termux` 是 `drwx------ u0_a121`），也没有 `CAP_SETUID`，所以直接在 startup 里写 `sh /data/data/com.termux/files/home/logintermux.sh` 必然 `Permission denied`；就算能跑，用户脚本里的 `exec bash` 也只会替换一个用完就退出的子进程，顶不掉持久会话。

写法：startup 里加一行

```
!shell su -c 'sh /data/data/com.termux/files/home/logintermux.sh'
```

`!shell` 后面的内容会作为一个**前端进程**来启动持久 shell（`sh -c "<内容>"`），也就是 `su` → 你的脚本 → `setpriv` 降权 → `exec bash`，这条 bash 直接接在 MQTT 的 stdin/stdout 上，后续所有命令都在里面执行。实测 `id` 返回 `uid=10121(u0_a121)`、`HOME=/data/data/com.termux/files/home`、`which python` 指向 termux 的 python。

- `!shell` 行不参与普通命令执行，startup 里其它行照常执行；只有一行 `!shell` 时启动后不跑任何命令。
- 改完要 Disconnect→Connect（或重启执行端）生效；`su` 需要在 root 管理器里给 MyMQTT 授权。
- 会话因超时被重置后，会再次用 `!shell` 启动（所以用 `exec` 型脚本最合适；脚本自己跑完就退的那种会导致每条命令都重跑一次）。

## 对端在线 / 断开感知（3.10）

两端各自只和 broker 连着，彼此并不知道对方在不在。现在用 MQTT 的 retained + 遗嘱补上：

- 主题固定两条：控制端发 `control/presence`，执行端发 `executor/presence`，各自订阅对方那条。
- 连上后发一条 retained `{"id":"<client_id>","online":true,"ts":…}`；对端一订阅立刻就能拿到（不发命令也知道对面在不在）。
- 主动断开（Disconnect / 退出）前发 retained `online:false`；**进程被杀、断网**这类异常断开，由 broker 在 keepalive 超时后替我们发遗嘱 `online:false`（keepalive 现在 600 秒，所以这种场景最坏要等 10 分钟才显示断开）。
- 日志区显示 `[控制端] 在线  mqtt-control-b568` / `[执行端] 已断开  mqtt-exec-rolex` 这种一行，不发命令也能看到；这类状态消息不进普通消息流，也不占你的输出截断。

## 电池（3.15，执行端 → 控制端）

执行端在前台服务里注册 `ACTION_BATTERY_CHANGED`（这个广播不需要任何权限，系统 sticky 广播会直接给当前值），电量或充电状态**真的变化时**才发一条 JSON 到结果 topic：

`{"battery":{"level":57,"charging":false,"ts":…}}`

控制端收到后只更新「已连接.」下面那一行，显示 `bat:57%`，充电时带 `⚡`；debug 模式下额外在日志区打一行 `[电池] 57%`。连接成功时执行端会主动补发一次当前电量，所以刚连上就能看到值。

每条消息约 70 字节、只在变化时发，一天几十条，流量可以忽略。

**3.16 起还看对端状态**：执行端只在 `control/presence` 显示控制端在线时才推电量。控制端下线（主动断开、或 keepalive 超时后由遗嘱标记 offline）期间，电量再变也不发；控制端重新上线的那一刻，执行端立刻补发一次当前电量，不用等下一次变化。实测：控制端 offline 后改电量 → 结果 topic 一条都没有；发 online 后立刻收到 `{"battery":{"level":66,…}}`。原来的「定时监控」（3.11）已按要求整块删除，设置页只剩「vi/vim 外部编辑器」一项。

## vi 的相对路径（3.12）

之前 `vi cmd.sh` 报「文件不存在」不是权限问题：vi 读文件走的是 Java 的 `File`，相对路径以 App 进程的 cwd（一般是 `/`）为基准，而不是你在持久 shell 里 `cd` 到的目录（termux 的 `/data/data/com.termux/files/home`）。

现在 `vi` / `!put` 遇到不以 `/` 开头的路径，会先向持久 shell 执行一次 `pwd`，拿当前目录拼成绝对路径；`~/xxx`、`~` 也按 `$HOME` 展开。所以 `cd /data/data/com.termux/files/home` 之后 `vi cmd.sh` 等价于 `vi /data/data/com.termux/files/home/cmd.sh`，返回的 JSON 里 `path` 也是绝对路径，改完回写不会跑偏。

## vi 的权限判断（3.14）

`vi cmd.sh` 在 `cd` 到 termux 目录后，路径会拼成 `/data/data/com.termux/files/home/cmd.sh`。但这个目录是 `drwx------ u0_a121`，执行端 App（u0_a127）`File.exists()` 直接返回 false，于是老版本会误报“文件不存在”。

现在的顺序是：App 能直接 `stat` 且可读 → 直接读；否则先走 su 把文件拷到私有目录读（`readViaRoot`），拿到内容就正常返回；拷不到时再用持久 shell 的 `[ -f ... ]` 判断到底是不存在还是没权限，分别报“文件不存在”和“需要 root 授权才能读这个位置”。

su 的路径也改成自动探测（`/sbin/su`、`/system/xbin/su`、`/debug_ramdisk/su`…），因为 termux 的 bash `PATH` 只有 `.../usr/bin`，直接写 `su` 会 command not found。实测：`vi cmd.sh` → `{"code":0,"file":{"path":"/data/data/com.termux/files/home/cmd.sh","size":3535,"mode":"771",…}}`。

## wakelock 与心跳（3.18）

没有 wakelock 时，屏幕熄灭、进入 Doze/CPU 挂起后，MQTT 客户端的心跳定时器不再按时执行，PINGREQ 发不出去，broker 在 keepalive 到点后就把这条连接判死——表现就是"锁屏一会儿就掉线、重连"。

现在的行为：

- **设置里的复选框（3.17 起，默认勾选）**：连接成功后立刻持 `PARTIAL_WAKE_LOCK`（tag `MyMQTT:conn`，不设超时），断开或退出即释放；命令执行那条短锁保留，但连接期已持锁时不会再额外耗电。
- **忽略电池优化**：新加一个复选框，勾选会跳到系统授权页（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。这一项比 wakelock 更关键——Doze 状态下系统会掐断普通应用的网络，只有白名单应用不受限，心跳才真的发得出去。建议两端都开。

实测（rolex）：`dumpsys power` 里能看到 `PARTIAL_WAKE_LOCK 'MyMQTT:conn' ACQ=-3s427ms (uid=10127 pid=7479)`，连接一建立就持锁。
