# 自动亮度 App 代码排查报告

> 工程：`autobrightness`（com.myapp.autobrightness，compileSdk 30 / minSdk 25 / targetSdk 29）
> 已核对：`app/release/autobrightness.apk` 里的 dex 与源码一致（没有 `onBackPressed` 重写，`setChecked` 只在 `onCreate` 出现两次），因此下面的结论是对着你实际在跑的这份代码得出的。
> 修复版代码已直接改动到工程里，另附 `修复对比.patch` 可逐行对照。

---

## 一、你反馈的问题：勾选后按返回，复选框自动变成未勾选

### 根因：开关状态有"两个来源、三个写入点"，却没有一条约束

这份代码里，"启用"这件事同时存在两份状态：

| 状态在哪 | 谁写它 |
| --- | --- |
| `CheckBox` 的勾选状态（界面） | ① `onCreate` 里 `setChecked()` ② 系统恢复界面状态时的 `onRestoreInstanceState` ③ 用户点击 |
| `SharedPreferences` 的 `enable`（真实状态） | 只有 `OnCheckedChangeListener` 会写 |

而 `setOnCheckedChangeListener` 是在 `onCreate` 里就挂上去的，之后**任何** `setChecked()` 调用（包括系统自动回填界面状态）都会走一遍"启用/停用"的完整业务逻辑：

```java
// MainActivity.java 原第 138-161 行
checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
    if (isChecked) { ... 写 enable=true，启动服务 ... }
    else {
        editor.putBoolean("enable", false);   // ← 把真实状态改成"关闭"
        editor.apply();
        stopService(serviceIntent);           // ← 顺手把服务也停了
        unregisterReceiver(lightLevelReceiver);
    }
});
```

具体链路（按返回时最容易被踩中）：

1. 按返回 / 从"修改系统设置"权限页返回 / App 在后台被 MIUI 回收后从桌面重进 → `MainActivity` 被销毁重建，或者原来的界面状态被系统回填；
2. `onCreate` 先按 prefs 设了一次勾选，随后系统调用 `onRestoreInstanceState`，把**上一次保存的勾选状态**再 `setChecked()` 回去；
3. 这一次"回填"发生在监听器已生效之后，被当成用户点击 → 走到 `else` 分支；
4. 于是 `enable` 被写成 `false`，`stopService()` 把前台服务也杀掉，页面上勾选消失；
5. 而 `onResume()` 从不重新读 prefs 同步界面（它只用 `onCreate` 时读进 `enableapp` 的那个**旧值**），所以这个错误状态会一直留在界面上——观感就是"按返回就把勾选取消了，而且再也回不来"。

同时还有两个放大器，让这个现象显得"经常"而不是"必现"：

- **`apply()` 是异步落盘**：勾选后立刻按返回、进程紧接着被回收，`enable=true` 有可能还没写进磁盘，下次打开读到 `false` → 显示未勾选（这类"偶发丢失"和上面的链路叠加，就成了"经常"）。
- **`onResume` 用的是过期变量**：`enableapp` 只在 `onCreate` 赋值一次，之后永不刷新；`onResume` 里那个 `boolean isEnabled` 甚至算了却没用。开关改过之后经过一次 pause/resume，服务与广播的注册状态就和界面彻底脱节。

### 修复（已改）

```java
// 1) 界面状态只认 SharedPreferences，禁止系统回填再反过来覆盖真实值
checkbox.setSaveEnabled(false);

// 2) 代码里设置勾选状态必须"静默"，不能触发业务逻辑
private void setCheckBoxSilently(boolean checked) {
    if (checkbox.isChecked() == checked) return;
    settingCheckedSilently = true;
    checkbox.setChecked(checked);
    settingCheckedSilently = false;
}

// 3) 监听器再加一道值比较，回填/重复回调一律忽略
checkbox.setOnCheckedChangeListener((v, isChecked) -> {
    if (settingCheckedSilently) return;
    if (isChecked == sharedPref.getBoolean(KEY_ENABLE, false)) return;
    applyEnabled(isChecked);
});

// 4) 每次回到前台都以持久化状态为准（onStart 里重新同步）
```

另外把 `enable` 改成 `commit()` 同步落盘，勾完立刻退出也不会丢。

---

## 二、连带发现的其他问题（按严重程度）

### P0 — 会崩溃 / 会明显错乱

| # | 位置 | 问题 | 后果 |
| --- | --- | --- | --- |
| 1 | `MainActivity` 186 行 | `save()` 里的 `unregisterReceiver()` 没有 try/catch，而 `onResume` 又用旧的 `enableapp` 决定要不要注册 | `enableapp=false` 启动 → 勾选开关（注册）→ 切后台（`onPause` 注销）→ 回来（没重新注册）→ 点 Save → `IllegalArgumentException: Receiver not registered` **直接崩溃** |
| 2 | `MainActivity` 194 行 | `save()` 在主线程 `Thread.sleep(500)` | 每次保存卡 0.5 秒，超时边缘就是 ANR；睡眠期间 Activity 可能已暂停/销毁，醒来后又 `registerReceiver`，注册泄漏、状态错乱 |
| 3 | `MainActivity` 130/150 + 219-232 | `onCreate` 注册一次、`onResume` 再注册一次，`onPause` 只注销一次 | 同一条广播被处理多次；`onResume` 判断用过期 `enableapp`，界面可能再也不刷新光线值 |
| 4 | `MainActivity` 182-207 | `save()` 用 `stopService` + 睡 500ms + `startService` 重启服务 | 完全没有必要（`onStartCommand` 本来就会重新解析配置），反而制造上面两条 bug |

### P1 — 功能不正确

| # | 位置 | 问题 | 后果 |
| --- | --- | --- | --- |
| 5 | `autobrightness` 100-119、189 | `START_STICKY` 重启后 `intent == null`，配置不会被解析，`cachedConfigData` 为空；也不回读 SharedPreferences | 进程被系统回收后服务"复活"但什么都不做：**开关显示开着、通知也在，亮度却再也不变**。这是最容易被误判为"开关又自己关了"的一类问题 |
| 6 | `autobrightness` 121-188 | 每次 `onStartCommand` 都新建 listener 并 `registerListener`，旧的从不 `unregister` | 监听器越挂越多：多次回调、重复广播、亮度抖动、耗电。每次打开 App / 每次保存都会 +1 |
| 7 | `BootReceiver` 15-40 | 开机广播**完全不看 `enable` 开关** | 用户明确"停用"后，重启手机又自动生效 |
| 8 | `BootReceiver` 30-40 | 在 `onReceive` 里 `postDelayed(5s)` 再启动服务 | `onReceive` 返回后进程随时可能被回收，那段延迟代码根本不保证执行——源码注释里写的"这个自启动很奇怪，重启前在跑才能自启"，根因就是这个 5 秒延迟 |
| 9 | `autobrightness` 169-181 | 灵敏度计数统计的是"目标亮度 ≠ 当前亮度"的次数，而不是"同一个目标连续出现 N 次" | 光线短时间在 3 个不同档位跳一下，`consecutiveCount` 就连满 3，立刻触发调亮度，`lmd` 参数形同虚设 |
| 10 | `autobrightness` 219-228 | 没有 WRITE_SETTINGS 权限时 `putInt` 抛 `SecurityException`，被 `catch (Exception)` 吞掉只打日志 | 勾选后"看起来启用了"，其实一次都没调到亮度，用户完全无从判断 |
| 11 | `MainActivity` 54-70 | 权限页只在 `onCreate` 跳一次；返回后不复查 `canWrite`；无权限时开关照样能勾选 | 假启用；每次冷启动都被弹去设置页 |
| 12 | `autobrightness` 160 | `lightLevel == 0` 浮点等值比较 | 传感器几乎不会返回精确的 `0.0`，这条"极速响应 0"的分支经常不生效 |
| 13 | 配置语义 | 亮度按 0-255 绝对值写死（默认 `0 50 / 20 250`），但部分机型亮度上限不是 255（如 0-2047） | 换机型后同样的配置可能暗到几乎看不见 |

### P2 — 健壮性 / 规范性

| # | 位置 | 问题 |
| --- | --- | --- |
| 14 | `autobrightness` 70-97 | `parseConfig` 一行写错（如多打了个字母）就整份解析失败，且 `clear()` 之后可能留下半截数据 |
| 15 | `MainActivity` 74 + 140/154/171 | 全类复用一个 `SharedPreferences.Editor`，`apply()` 后继续复用是反模式（连续写入有互相覆盖的风险） |
| 16 | `autobrightness` 250-261 | `onDestroy` 没有 `removeCallbacks(brightnessRunnable)`、没有 `stopForeground`，服务退出后渐变任务可能还在写系统亮度 |
| 17 | `MainActivity` 115-116 / 188-190 / 227 | 死代码：`filter2` 只创建不用、`filter` 创建后丢弃、`isEnabled` 算了不用 |
| 18 | `AndroidManifest.xml` | `SCHEDULE_EXACT_ALARM` 声明了但代码根本没用；`POST_NOTIFICATIONS` 声明了但没请求；`MainActivity` 缺 `android:exported`（targetSdk 提到 31 会直接装不上）；`foregroundServiceType="dataSync"` 缺 `FOREGROUND_SERVICE_DATA_SYNC` 权限（targetSdk 提到 34 会崩） |
| 19 | `activity_main.xml` | `ScrollView` 用 `match_parent` 高度放在 `wrap_content` 的 LinearLayout 里 → 说明文字把上半部分挤出屏幕/被裁掉；`textSize="14dp"` 单位应为 sp；`EditText` 无最小行数 |
| 20 | `strings.xml` | 帮助文本的示例（`0 50` / `0 200`，两行阈值都是 0，等于重复）与代码默认配置（`0 50` / `20 250`）不一致，用户照着抄必错 |
| 21 | `autobrightness` 类名 | 类名/文件名全小写，不符合 Java 命名规范（`AutobrightnessService` 更合适，本次未重命名以免影响你的构建配置） |
| 22 | `MainActivity` 27 | `Boolean enableapp` 用装箱类型，`if (enableapp)` 自动拆箱，一旦为 `null` 就是 NPE |

---

## 三、这次改了什么

| 文件 | 改动 |
| --- | --- |
| `MainActivity.java` | 开关状态改为"prefs 唯一来源"：`setSaveEnabled(false)` + 静默设置 + 监听器值比较 + `onStart` 重新同步；`enable` 用 `commit()` 同步落盘；广播改在 `onStart/onStop` 严格配对注册/注销（带 `receiverRegistered` 标志）；`save()` 删掉 `stopService + sleep(500) + startService`，改为直接把新配置推给服务；权限引导去重、返回后不再重复弹；删死代码 |
| `autobrightness.java` | 配置回读 prefs（`intent` 为空时兜底）；返回 `START_REDELIVER_INTENT`；开关已关时被系统拉起则 `stopSelf()`；传感器监听改为"只建一次 + 先注销后注册"（`sensorRegistered` 标志）；灵敏度计数改为"同一目标连续 N 次"；`lightLevel <= 0` 判断；`onDestroy` 取消渐变、`stopForeground`、注销带保护；`parseConfig` 逐行容错，非法行只跳过 |
| `BootReceiver.java` | 先判断 `enable` 再启动；去掉不可靠的 5 秒 `postDelayed`，直接启动并 try/catch；同时兼容 `MY_PACKAGE_REPLACED`（升级后服务能自动恢复） |
| `AndroidManifest.xml` | 补 `FOREGROUND_SERVICE_DATA_SYNC`；`MainActivity` 加 `exported="true"`；移除无用的 `SCHEDULE_EXACT_ALARM`；receiver 增加 `MY_PACKAGE_REPLACED` |
| `activity_main.xml` | ScrollView 改 `0dp + weight=1`；`14dp` → `14sp`；`EditText` 加 `minLines="3"`；文本颜色改用主题属性 |
| `strings.xml` | 帮助文本重写为清晰的"格式 + 示例 + 说明"，示例与代码默认配置一致 |
| `build.gradle`（根 + app 模块） | Gradle 8.9 / AGP 8.5.2，`namespace` 化，compileSdk 34，release 用调试证书签名，关闭 lintVital 的 targetSdk 检查 |

改动已用 `javac` 对着 android-34 的 `android.jar` 做过类型检查，无编译错误；并且**已经真实编出了可安装的 APK**（见下）。

---

## 三之二、APK 与构建环境

产物：`autobrightness-2.1.20261117-fixed.apk`（1.7 MB，minSdk 25 / targetSdk 29 / compileSdk 34，v2 签名，`apksigner verify` 通过）。

为了能在这台机器上编出来，构建脚本做了升级（原 AGP 3.6.2 + Gradle 5.6.4 在 JDK 17 下已经跑不起来）：

| 项 | 原 | 现 |
| --- | --- | --- |
| Gradle | 5.6.4 | 8.9（需 JDK 17） |
| AGP | 3.6.2 | 8.5.2 |
| compileSdk | 30 | 34（本机只装了 android-34） |
| targetSdk | 29 | 29（保持不变，避免前台服务/通知行为变化） |
| 命名空间 | manifest 里的 `package` | `build.gradle` 的 `namespace`（AGP 8 要求，manifest 已移除 `package`） |
| 依赖 | appcompat 1.3.1 等 | 同版本，从 Google Maven 拉取 |
| 签名 | 无 keystore | release 用自动生成的调试证书签名，保证可直接安装 |
| lint | — | 关闭 `checkReleaseBuilds`（不然 lint 会因 targetSdk 29 中断 release 构建） |

⚠️ 两点安装注意：

1. **这个包的签名和你手上的旧版不同**（工程里没有原始 keystore）。如果手机上已装了旧版，直接覆盖会报“签名不一致/应用未安装”，需要先卸载旧版再装。要长期升级，建议你自己生成一个 keystore 并在 `app/build.gradle` 里配 `signingConfigs.release`。
2. 如果你的 Android Studio 还是老版本（Gradle 5.6.4 / AGP 3.6.2），用这套构建脚本会打不开，需要把 IDE 升级到支持 AGP 8.5 的版本（或用命令行 JDK 17 + Gradle 8.9 构建）。只想要代码改动、不想动构建链的话，`修复对比.patch` 里的 6 个文件可以单独应用。

---

## 四、建议你这样验证

1. 勾选"启用" → 立刻按返回 → 再进 App：开关应保持勾选，通知栏服务仍在；
2. 勾选 → 切后台 → 回来 → 点 Save：不应崩溃，也不应出现保存卡顿；
3. 勾选后手动在系统设置里"强行停止" App → 再打开：开关应仍为勾选且服务自动恢复（`onStart` 自愈）；
4. 关闭开关 → 重启手机：服务不应自动启动；
5. 把配置写成 `lmd:3/2` + 一行乱码 → Save：只有乱码那行被跳过，其余规则照常生效；
6. 遮住/放开光线传感器，观察"光线传感器 / 亮度"两个数值是否持续刷新（验证广播注册不再错乱）。

## 五、还可以继续做（本次未动）

- 通知栏加一个"停止"动作按钮，不用进 App 就能关；
- 亮度配置支持按百分比（0-100）填写，内部再按机型最大值换算，换机不用改参数；
- 服务里对 `SCREEN_BRIGHTNESS_MODE` 的改动，在停用时恢复用户原来的自动亮度设置；
- `autobrightness` 类重命名为 `AutobrightnessService`，并同步 manifest。
