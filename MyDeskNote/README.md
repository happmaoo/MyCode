MyDeskNote 可以连续写多条项目，每条单独设定日期和时间。
**App 界面里全部项目都看得见**（含还没到时间的）；**桌面小工具只显示已经到时间的那些**，
到点自己在桌面上冒出来。

包名 `com.myapp.mydesknote`、应用名 `MyDeskNote`，和参考 app 完全一致（图标也换成同一套）。


## 功能

- **多条项目**：输入框写内容 → 选日期 / 选时间 → 点「添加这条项目」，可反复添加；
- **时间可选启用**：输入框下方「定时显示」复选框，**默认不勾选**（进页面/回到页面都会恢复成不勾选）——不勾就是一条普通便签，
  添加后立刻显示；勾上才启用日期 / 时间（此时日期按钮打开 `DatePickerDialog`、时间按钮打开
  `TimePickerDialog`（24 小时制），默认「当前时间 + 1 小时」，可分别改）；
- **每条独立的时间**：勾了「定时显示」的项目各自带自己的日期和时间；
- **App 内全部显示**：列表列出所有项目，按时间倒序；未到时间的那条会标明
  「未到时间 · 还有 X · yyyy-MM-dd HH:mm 显示」，已到时间的标明「已于 … 显示」，
  倒计时每秒刷新一次，到点文案自动切换；
- **删除**：点右侧「×」，二次确认后即时删除；
- **清空已过期**：列表上方右侧按钮，一键删掉所有已经到过时间的项目（未到时间的保留，
  有二次确认并显示条数），删完桌面小工具同步刷新；没有过期项目时按钮置灰；
- 数据存在 `SharedPreferences`（JSON 数组），用 `commit()` 同步落盘，加完立刻退出也不丢。

## 桌面小工具

样式和尺寸完全照 MyDeskNote 的 `SimpleWidget` 来：

- **尺寸**：`minWidth=40dp` / `minHeight=40dp`（可横向、纵向拉伸，拉伸后自动按高度多显示几行）；
- **样式**：一整块文字（单个 `TextView`），`padding=5dp`、居中、文字色 `#FF333333`，行距 2dp；
- **透明度 / 底色**：
  - 有已到时间的项目 → 不透明淡黄 `#FFFFE0`；
  - 什么都没有 → 很淡的半透明黄 `#11FFFFE0`（alpha 0x11），**不显示任何文字**（空白状态）；
- **字号可调**：App 主界面右上角「小工具字号」输入框（8–40sp，默认 12sp），改完立刻应用到桌面；
- **全部条目都列出**：所有已经到时间的项目正文按时间倒序、一行一条，不截断条数（组件格子放不下时拖大一些即可）；
- **刷新时机**：
  - 新增 / 删除项目后由界面直接刷新（`NoteWidget.requestUpdate`）；
  - 组件被添加到桌面、被拉伸、尺寸变化时刷新；
  - **到点自动刷新**：`AlarmManager.setExactAndAllowWhileIdle` 定在最近一条的解锁时刻，
    响一次立刻重画，再排下一次；没有待解锁项目、或者桌面上没有这个小组件时取消闹钟
    （`updatePeriodMillis=0`，不靠系统轮询）；
  - **App 没在运行也会到点显示**：闹钟是系统级的，到点由系统拉起本 app 的广播更新桌面，不依赖 app 是否在后台；
  - **重启后自动补**：`BootReceiver` 监听开机 / 改时间 / 换时区 / 覆盖安装，重排闹钟（需要 `RECEIVE_BOOT_COMPLETED`）；
  - **打开 App 立刻补**：`onResume` 强制同步一次桌面并重排闹钟，被"强行停止"或省电策略清掉闹钟后，
    只要再打开一次 App 就恢复（已经到点的条目会马上出现在桌面上）；
  - **兜底轮询**：`updatePeriodMillis=1800000`（30 分钟），即使闹钟被 ROM 限制，最多半小时也会自愈；
- **点击**：点小工具任意位置 → 打开 App。

## 说明 / 边界

- 桌面小工具是「到点才出现」的那部分；App 里不隐藏，方便统一管理、删除。
- 不会主动弹通知、不响铃（想要「到点推送/响铃」需要再加通知渠道 + `AlarmManager`，跟我说一声就能补）。
- 删除是即时生效、不可撤销的（有二次确认）。

## 构建

| 项 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.9（自带 wrapper） |
| AGP | 8.5.2 |
| compileSdk / minSdk / targetSdk | 34 / 24 / 29 |
| 依赖 | `androidx.appcompat:appcompat:1.3.1` |

```bash
echo "sdk.dir=/你的/Android/Sdk" > local.properties
./gradlew assembleRelease
```

release 目前复用调试证书签名（为了能直接装）；要自己发布请按需配 `signingConfigs.release`。

## 目录

```
app/src/main/java/com/myapp/mydesknote/MainActivity.java   # 界面：输入、时间选择、列表、状态刷新
app/src/main/java/com/myapp/mydesknote/NoteStore.java      # 数据层：JSON 读写（界面与小工具共用）
app/src/main/java/com/myapp/mydesknote/NoteWidget.java     # 桌面小工具：渲染 + 到点刷新
app/src/main/res/layout/activity_main.xml                # 输入区 + 列表
app/src/main/res/layout/item_note.xml                    # 列表行：内容 / 状态时间 / 删除
app/src/main/res/layout/widget_note.xml                  # 小工具布局（单块文字）
app/src/main/res/xml/widget_note_info.xml                # 小工具声明：40dp×40dp、可拉伸、不轮询
app/src/main/res/drawable/                               # 卡片、按钮等背景
app/src/main/res/mipmap-*/                               # 图标（含自适应图标前景）
```
