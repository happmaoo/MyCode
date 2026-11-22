package com.myapp.mybleclient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;

/**
 * 前台服务 = 唯一的 BLE 连接引擎。
 *
 * 原先把整套 GATT 状态机放在 MainActivity 里，界面被销毁重建（切后台被回收、
 * 从通知栏重进、旋转屏幕）时连接随之丢失，还可能出现"第二个实例再连一条"的问题。
 * 现在所有连接状态都住在 Service 里：
 *   - Activity 无论重建多少次，都只是 bind 上来"订阅状态"，不碰连接；
 *   - Activity 销毁不再断链、不再停服务；
 *   - 重建后 setUi() 会把当前状态（状态行/电量信号/按钮/日志）整包推回去，
 *     界面文字自动恢复，不会闪成"Not connected"再等回调。
 * 引擎全部事件都收敛到主线程 Handler 上串行执行，与原来 runOnUiThread 的单线程语义一致。
 */
public class BleService extends Service {

    static final int NOTIF_ID = 0x42;
    static final String CH_ID = "blecmd_conn";

    static final UUID SVC_CMD = UUID.fromString("8e7a1f01-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final UUID CH_CMD  = UUID.fromString("8e7a1f03-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final UUID CH_RES  = UUID.fromString("8e7a1f04-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final UUID CH_AUTH = UUID.fromString("8e7a1f05-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final UUID CH_FILE = UUID.fromString("8e7a1f06-2b3c-4d5e-6f70-8192a3b4c5d6");  // v3 文件上传（二进制直写）
    static final UUID SVC_BAS = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID CH_BATT = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final Charset UTF8 = Charset.forName("UTF-8");

    /** 界面回调：Activity 绑定后注册；重建后重新注册并收到全量快照 */
    public interface Ui {
        void onInfo(String s);        // 状态行 tvInfo
        void onBatt(String s);        // 电量/信号行 tvBatt
        void onLog(String s);         // 追加一条事件/结果块（已含时间戳格式）
        void onLogReset(String s);    // 整体重设日志区（重绑定时回放 logBuf / 清空）
        void onBtn(String text, boolean enabled); // Connect 按钮状态
        void onDisconnectBtn(boolean enabled);    // 断开按钮：未连接时禁用
        void onToast(String s);
        /** v3 上传进度：0~100（在状态行展示，不上弹窗） */
        void onProgress(String line);   // 上传进度行（状态行展示，不上弹窗）
        /** 上传开始/结束：用于把 up 按钮切换成 cancel（结束记得复位成 up） */
        void onUploadState(boolean uploading);
        /** 下载开始/结束：用于把 down 按钮切换成 cancel（结束记得复位成 down） */
        void onDownloadState(boolean downloading);
        /** v3 目录列表回传：每行形如 "d 0 <name>" / "f <size> <name>" */
        void onFileList(java.util.ArrayList<String> items);
        /** vi 编辑：服务端按需下载文件到本地后的回调（path=本地绝对路径, remote=服务端绝对路径, mode=八进制权限, sha=原始校验） */
        void onEditFileReady(String path, String remote, String mode, String sha);
        /** 编辑回写完成回调 */
        void onEditUploaded(boolean ok, String msg);
    }

    public class LocalBinder extends Binder {
        public BleService getService() { return BleService.this; }
    }
    private final LocalBinder binder = new LocalBinder();
    @Override public IBinder onBind(Intent intent) { return binder; }

    volatile Ui uiCb;                                   // 当前界面（唯一）
    final Handler th = new Handler(Looper.getMainLooper()); // 引擎单线程

    // ---- 全量状态快照：重建的界面靠它一次性恢复文字 ----
    String curInfo = "Not connected";
    String curBatt = "";   // 未连接时不显示电量信号行
    String btnText = "Connect";
    boolean btnEnabled = true;
    boolean disconnectEnabled = false;   // 断开按钮是否可用（未连接时禁用）
    final ArrayDeque<String> logBuf = new ArrayDeque<String>(); // 日志环形缓冲，重进回放
    static final int LOG_MAX = 400;

    // ------------------------------------------------------------- 引擎状态
    BluetoothAdapter adapter;
    BluetoothManager btManager;
    BluetoothGatt gatt;
    BluetoothDevice curDevice;
    BluetoothGattCharacteristic cBatt, cCmd, cRes, cAuth, cFile;

    final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss", Locale.US);
    final Deque<W> wq = new ArrayDeque<W>();
    int inflight = 0;                  // 在途写请求数（含 NO_RESPONSE 流水线）
    static final int WINDOW = 6;        // 无应答写窗口：1M PHY 每连接事件约可塞 5~6 个包；2M 更高
    long upSent = 0, upTotal = 0;      // 上传进度：已提交字节 / 总字节
    String upName = "";                // 当前上传文件名
    long upStartMs = 0;                // 上传开始时刻（用于计时）
    // ---- 下载进度（与上传对称，共用状态行 onProgress）----
    boolean downBusy = false;          // 是否有文件正在下载（用于 T 帧接收时判定进度归属）
    String downName = "";              // 当前下载文件名（FILD 头解析出后回填）
    long downTotal = 0;                // 下载文件总字节（FILD 头里的 size）
    long downStartMs = 0;              // 下载开始时刻（计时用）
    final java.util.ArrayList<Uri> upQueue = new java.util.ArrayList<Uri>();  // 待上传队列（多选）
    final java.util.ArrayList<Uri> pendingShare = new java.util.ArrayList<Uri>();  // 系统分享进来、尚未就绪时暂存，连上后自动上传
    boolean upBusy = false;            // 当前是否有文件正在传
    boolean upCancel = false;          // 上传是否已被用户取消（用于丢弃剩余待发分片）
    boolean highPrioLocked = false;    // 传输期 HIGH 优先级锁：上传/下载期间屏蔽其它 LOW_POWER/BALANCED 切换，让 HIGH 独占
    int upTotalFiles = 0;              // 本轮总文件数（用于进度里显示 n/total）
    final java.util.ArrayList<String> deferredCmds = new java.util.ArrayList<String>();  // 上传期间被暂停、待全部上传完成后提交的命令
    /** 结果累积用字节缓冲：多字节 UTF-8 字符（如汉字 3 字节）可跨帧，末片到齐后一次性解码 */
    final java.io.ByteArrayOutputStream rx = new java.io.ByteArrayOutputStream();

    // ---- 可靠传输 v2 接收状态（T 帧：'T'+tid+seq+total+payload+CRC16）----
    int rvTid = -1;            // 当前传输号（-1 = 空闲）
    int rvDoneTid = -1;        // 已结束（完成/放弃/超时）的传输号：忽略其残帧，防止同一 tid 重复收/重复输出
    String serverVer = "";      // 服务端下发的版本号（连接后由 blegatt-ver: 帧填充，状态栏展示）
    // App 版本号直接读取 APK 自身的 versionName（构建时由脚本注入编译时刻）。
    // 这样“界面显示的版本”与“安卓 APP 属性里的版本”永远一致，且为编译时固定值（不再是运行时当前时间）。
    static String appVersion(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), 0);
            String vn = pi.versionName;
            return (vn != null && !vn.isEmpty()) ? vn : "2.0.dev";
        } catch (Throwable t) {
            return "2.0.dev";
        }
    }
    int rvTotal = 0;           // 本传输总片数
    int rvNext = 0;            // 期望的下一个 seq（已连续收到 0..rvNext-1）
    int rvNacks = 0;           // 本传输已发重传请求次数（上限 5，防止无限循环）
    byte[] rvDigest = null;    // 期望的 SHA-256（total>1 时服务端会发校验帧）
    boolean rvDigestGot = false;
    // 乱序重排：晚到的高 seq 先缓存，只请求真正缺的那个序号；避免"收到 11 却期望 0 → 从头 NACK"的重传活锁
    final java.util.TreeMap<Integer, byte[]> rvPend = new java.util.TreeMap<Integer, byte[]>();
    int rvNackGuardSeq = -1;   // 最近一次断点 NACK 的 fromSeq（去重节流）
    long rvNackGuardAt = 0;    // 最近一次断点 NACK 的时刻
    int rvTimeouts = 0;        // 连续超时次数（超时改为断点续传，超过阈值才放弃）
    boolean streaming = false;  // 命令输出流进行中（回声已打头，正文连续追加，不再每片加头/脚注）
    int streamChars = 0;       // 本次命令已接收的字符数（仅用于结束时的汇总行）
    String serverCwd = "";     // 持久 shell 每次命令后回报的当前目录（供 vi 解析相对路径）
    // vi 编辑回写：当前待回写的目标信息（PUTE 头用），null 表示不是编辑回写
    String editRemotePath = null;   // 服务端绝对路径
    String editMode = "644";        // 原权限八进制

    int step = 0;              // 0 未开始 1 读电量 2 订阅电量 3 订阅RES 4 鉴权 9 就绪
    int lastBattery = -1;
    int lastRssi = 0;
    int rssiBusy = 0;
    int hbTicks = 0;
    boolean linkUp = false;
    boolean authAwaiting = false;   // AUTH 挑战响应进行中（已发 HMAC / 等待 nonce）
    final Runnable authWatchdog = new Runnable() {
        @Override public void run() {
            if (authAwaiting) logEvent("⚠ 未收到服务端 nonce / 认证未通过：服务端可能未配置 --psk，或链路异常");
        }
    };
    boolean connecting = false;
    boolean scanning = false;
    boolean waitingResult = false;
    // ---- wakeupserver 探活：零片超时后向 CMD 写特殊命令 wakeupserver 确认服务端存活，不重发业务命令 ----
    boolean probing = false;       // 探活进行中
    int probeFails = 0;            // 连续失败次数（达 3 即判定链路失效）
    int probeRounds = 0;           // 已发探活轮次（达 3 即停止，避免无限探测）
    boolean probePending = false;  // 本轮 wake 命令已发出、等待服务端回包 wakeup-ok
    boolean resSubscribed = false;   // RES 的 CCCD 是否订阅成功（没订阅就别发命令）
    boolean autoCmdDone = false;     // 本次连接是否已执行过"连接后自动命令"
    PowerManager.WakeLock wake;      // 可选：熄屏保活（设置 keep_wake 开启才持有）
    String want = "";
    String notifTitle = "MyBleClient";   // 通知标题：未连接用默认值，连上后改为服务器名
    Set<String> seenDev = new HashSet<String>();   // 扫描期已记录过的设备地址（去重，避免日志刷屏）
    List<String> scanDevLines = new ArrayList<String>();  // 扫描期发现设备的日志行（保序，连上时重新汇入）
    String mtuText = "";
    int curMtu = 23;               // 协商后的 MTU；命令超长分片写入时按 MTU-3 切

    /** 一条待写的 (特征, 内容, 写入类型)：AUTH 与 CMD 共用写队列但目标不同 */
    static final class W {
        final BluetoothGattCharacteristic ch;
        final byte[] v;
        final int writeType;
        final int upLen;        // 上传数据片字节数（0 表示非上传片）
        boolean counted;        // 已计入上传进度（重试重发不重复计）
        W(BluetoothGattCharacteristic ch, byte[] v) { this(ch, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, 0); }
        W(BluetoothGattCharacteristic ch, byte[] v, int wt) { this(ch, v, wt, 0); }
        W(BluetoothGattCharacteristic ch, byte[] v, int wt, int upLen) { this.ch = ch; this.v = v; this.writeType = wt; this.upLen = upLen; }
    }

    // ------------------------------------------------------------------ 定时器

    final Runnable scanTimeout = new Runnable() {
        @Override public void run() {
            if (connecting || linkUp) return;
            stopScan();
            info("No match: " + want + "(confirm the server is running and the advertised name matches)");
            setBtn("Connect", true);
        }
    };

    /** 初始化看门狗：15 秒走不到就绪就释放连接（外设被重启时客户端会卡在发现服务） */
    final Runnable readyTimeout = new Runnable() {
        @Override public void run() {
            if (step >= 9 || gatt == null) return;
            info("Init timeout (stuck at step " + step + ")  -  connection released; retrying…");
            logEvent("Init timeout step=" + step + ": an ATT request got no callback, or the peripheral process was restarted");
            hardClose();
        }
    };

    /** 半片兜底：4 秒没等到后续片。
     *  数据片全到但校验帧未到 → 只请求补发校验帧（fromSeq==total）；
     *  数据片没到齐 → 保留已收前缀，请求从断点续传（绝不 abortRx 清零，否则迟到片被当新传输从头再来）；
     *  连续 3 次仍不完整才放弃，并标记该 tid 的迟到残帧一律吞掉。 */
    final Runnable chunkTimeout = new Runnable() {
        @Override public void run() {
            if (rvTid < 0) return;
            if (rvTotal > 1 && rvNext >= rvTotal && !rvDigestGot) {
                rvTimeouts = 0;
                sendNack(rvTid, rvTotal, "digest frame missing");
                return;
            }
            int timeoutLimit = (rvTotal > 50) ? 15 : 3;   // 大文件：允许更多次连续无进展超时，别轻易放弃
            if (rvNext < rvTotal && rvTimeouts < timeoutLimit) {
                rvTimeouts++;
                sendNack(rvTid, rvNext, "timeout, resume from seq " + rvNext);
                th.postDelayed(chunkTimeout, 4000);
                return;
            }
            int done = rvTid;
            int doneNext = rvNext, doneTotal = rvTotal;
            boolean wasDownload = downBusy;
            abortRx(done);
            if (wasDownload) {
                // 文件下载超时放弃：绝不 dump 二进制（满屏乱码），给友好提示
                toast("下载失败：传输超时，请重试或调大收发节奏");
                logEvent("下载放弃: 连续超时 rvNext=" + doneNext + "/" + doneTotal + " tid=" + done);
                notifyDownloadState(false);
                unlockPriority();
            } else {
                String half = rx.size() > 0 ? new String(rx.toByteArray(), UTF8) : "";
                if (!half.isEmpty())
                    appendResult(half + "\n! Fragment incomplete (no follow-up; partial output shown)");
            }
            info(linkUp ? readyText() + "(previous result incomplete)" : "Disconnected");
        }
    };

    /** 零片兜底：Run 之后超时没收到任何片就明确提示（时长可在设置页调，0=关闭）。
     *  只提示、不自动重连——要不要重连由用户决定；真断链由心跳检测并按"Auto-connect"开关处理。 */
    final Runnable noChunkTimeout = new Runnable() {
        @Override public void run() {
            if (!waitingResult || rx.size() > 0) return;
            waitingResult = false;
            long ms = P.zeroChunkMs(BleService.this);
            appendResult("! " + (ms / 1000.0) + "s: no notification chunk received: server didn't send, or subscribe/link state invalid"
                    + " (server log will show why PUMP was skipped; duration adjustable in Settings)");
            info(linkUp ? readyText() + " (no result returned)" : "Disconnected");
            if (linkUp) startProbe();   // 连接还在：发 wakeupserver 探活确认服务端是否存活（不重发业务命令）
        }
    };

    /** wakeupserver 探活：零片超时后每 5s 向 CMD 写特殊命令 wakeupserver，连续 3 次没收到服务端回包（wakeup-ok）即判定链路失效，
     *  并按「自动连接」开关重连。该命令不进服务端命令队列、服务端收到后直接回 wakeup-ok 且绝不执行，
     *  能区分「命令卡死（服务端 GATT 栈仍活）」与「链路/设备死」。
     *  探测期间不打屏幕日志；只有「全部超时（3 次均无 wakeup-ok）」或「3 轮无真实回复」时才出一条结论。 */
    final Runnable wakeProbe = new Runnable() {
        @Override public void run() {
            // 上传进行中：探活命令会被文件包挤掉而连续失败，直接停探活，等上传结束由下次心跳/异常重新触发
            if (!probing || gatt == null || cCmd == null || !linkUp
                    || upBusy || !upQueue.isEmpty()) { stopProbe(); return; }
            if (probePending) {                 // 上一轮 wake 写完 5s 内没回 wakeup-ok = 本轮失败（服务端无响应）
                onProbeFail();
                if (!probing) return;           // 连败 3 次已判定链路失效并重连
            }
            if (probeRounds >= 3) {             // 已探测 3 次仍无真实回复（服务端多半卡死），停手
                stopProbe();
                appendResult("! Probed 3 times with no real reply: server alive, command may be stuck; waiting for watchdog to reclaim");
                return;
            }
            // 通过 CMD 通道发探活命令（短于 MTU、不分片），服务端识别后直接回 wakeup-ok、不执行
            probePending = true;
            probeRounds++;
            wq.add(new W(cCmd, "wakeupserver".getBytes(UTF8)));
            drain();
            th.postDelayed(wakeProbe, 5000);     // 5s 后下一轮（或等待本轮回包超时）
        }
    };

    void startProbe() {
        // 上传进行中不上探活：wakeupserver 走命令通道会排在文件包后面，长上传里可能连续失败误判"Server unresponsive"
        if (probing || gatt == null || cCmd == null || !linkUp
                || upBusy || !upQueue.isEmpty()) return;
        probing = true; probeFails = 0; probeRounds = 0; probePending = false;
        th.removeCallbacks(wakeProbe);
        th.postDelayed(wakeProbe, 0);            // 立即发起第一次（探测期间不显示日志）
    }

    void stopProbe() {
        probing = false; probeFails = 0; probeRounds = 0; probePending = false;
        th.removeCallbacks(wakeProbe);
    }

    void onProbeFail() {
        probeFails++;
        probePending = false;
        if (probeFails >= 3) {
            // 3 次全部超时（均无 wakeup-ok）→ 判定链路失效，按「自动连接」重连
            stopProbe();
            appendResult("! Server unresponsive: wakeupserver probe failed 3 times consecutively; link considered dead");
            boolean auto = P.autoConnect(BleService.this);
            hardClose();
            if (auto) th.postDelayed(autoConnectTask, 3000);
        }
    }

    /**
     * 单一心跳：链路存在期间任何分支都在 finally 里自我续期。
     * 职责：按需 readRemoteRssi、刷新电量/信号行、链路新鲜度看门狗。
     * gatt 已彻底释放时心跳自动终止——否则会每轮空报 linkAlive=false 刷屏。
     */
    final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (gatt == null) return;   // 链路已完全释放：心跳到此为止，不再空转
            int iv = Math.max(1, P.rssiSec(BleService.this));
            try {
                hbTicks++;
                if (!linkAlive()) {
                    boolean auto = P.autoConnect(BleService.this);
                    info(auto ? "Link disconnected (stack reported no GATT connection) - reconnecting"
                              : "Link disconnected (stack reported no GATT connection) - please reconnect manually");
                    logEvent("Heartbeat #" + hbTicks + ": linkAlive=false → released and reset button");
                    hardClose();
                    if (auto) th.postDelayed(autoConnectTask, 3000);
                    return;
                }
                boolean ok = false;
                try { ok = gatt.readRemoteRssi(); } catch (Throwable ignored) { }
                if (!ok) {
                    rssiBusy++;
                    if (rssiBusy == 1 || rssiBusy % 10 == 0) {
                        logEvent("readRemoteRssi rejected " + rssiBusy + " times (another op in flight on the link)");
                    }
                }
                refreshBattLine();
            } catch (Throwable t) {
                logEvent("Heartbeat error: " + t);
            } finally {
                th.postDelayed(this, iv * 1000L);      // 唯一续期点，永不漏
            }
        }
    };

    final Runnable autoConnectTask = new Runnable() {
        @Override public void run() { if (gatt == null && !linkUp) beginConnect(P.server(BleService.this)); }
    };

    final Runnable retryDrain = new Runnable() {
        @Override public void run() { drain(); }
    };

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        logEvent("MyBleClient " + appVersion(this) + " started (foreground service established)");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "BLE Connection",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = (btManager == null) ? null : btManager.getAdapter();
        if (adapter == null) info("This device has no Bluetooth, or Bluetooth service unavailable");
    }

    // 注意：onDestroy 只在服务真正被销毁时执行（用户"Disconnect & Release"、系统杀进程）。
    // Activity 销毁/重建不会走到这里 —— 连接与通知因此得以存活。

    @Override
    public void onDestroy() {
        super.onDestroy();
        uiCb = null;
        releaseWake();
        removeAllTimers();
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Throwable ignored) { }
            gatt = null;
        }
        stopForeground(true);
    }

    void removeAllTimers() {
        th.removeCallbacks(scanTimeout);
        th.removeCallbacks(readyTimeout);
        th.removeCallbacks(chunkTimeout);
        th.removeCallbacks(noChunkTimeout);
        th.removeCallbacks(heartbeat);
        th.removeCallbacks(autoConnectTask);
        th.removeCallbacks(retryDrain);
    }

    // ------------------------------------------------------------- 对 UI 的接口

    /** 绑定（或重建后重绑）时调用：注册回调并整包回放当前状态，界面文字立即恢复 */
    public void setUi(final Ui u) {
        uiCb = u;
        if (u == null) return;
        th.post(new Runnable() {
            @Override public void run() {
                if (uiCb != u) return;
                try {
                    u.onInfo(curInfo);
                    u.onBatt(curBatt);
                    u.onBtn(btnText, btnEnabled);
                    u.onDisconnectBtn(disconnectEnabled);
                    StringBuilder sb = new StringBuilder();
                    for (String s : logBuf) sb.append(s);
                    u.onLogReset(sb.toString());
                } catch (Throwable ignored) { }
            }
        });
    }

    public void clearUi(Ui u) {
        if (uiCb == u) uiCb = null;
    }

    /** 由 Activity 在权限检查通过后调用；引擎串到主线程执行 */
    public void connect(final String name) {
        th.post(new Runnable() {
            @Override public void run() { beginConnect(name); }
        });
    }

    /** 自动连接入口（设置里开了自动连接时，界面 onResume / 重绑定时触发一次） */
    public void ensureAutoConnect() {
        th.post(new Runnable() {
            @Override public void run() {
                if (!P.autoConnect(BleService.this)) return;
                if (gatt != null || linkUp || connecting || scanning) return;
                logEvent("Auto-connect: " + P.server(BleService.this));
                beginConnect(P.server(BleService.this));
            }
        });
    }

    /**
     * 连接就绪后自动执行设置里配置的那条命令。
     * 触发时机：链路就绪（step=9 且 RES 已订阅）且鉴权已完成；有 PSK 时必须等 auth:ok 之后，
     * 否则服务端会以"未认证"拒掉。每次连接只执行一次。
     */
    void maybeRunOnConnectCmd() {
        if (autoCmdDone) return;
        String c = P.onConnectCmd(this);
        if (c == null || c.trim().length() == 0) { autoCmdDone = true; return; }
        if (step < 9 || !resSubscribed) return;   // 还没就绪
        if (authAwaiting) return;                 // 还在等服务端 nonce / 认证
        autoCmdDone = true;
        logEvent("On-connect command: " + c.trim());
        run(c.trim());                            // run() 内部会再校验一遍门槛并刷新界面
    }

    /** 发命令；就绪门槛在引擎里统一把关，拒绝原因通过 toast 回给界面 */
    public void run(final String cmd) {
        th.post(new Runnable() {
            @Override public void run() {
                String c = cmd == null ? "" : cmd.trim();
                if (c.length() == 0) { toast("Command is empty"); return; }
                if (gatt == null || cCmd == null) { toast("Not connected / not ready yet"); return; }
                // 关键门槛：鉴权与订阅都没完成就发命令，必然被服务端拒绝且结果回不来
                if (step < 9) { toast("Init not finished (step " + step + "); wait and retry"); return; }
                if (!resSubscribed) { toast("RES notify not subscribed; command output cannot be received"); return; }
                // 上传进行中：暂停命令，全部上传完成后再提交到服务端（避免命令与文件包混排/抢通道）
                if (upBusy || !upQueue.isEmpty()) {
                    deferredCmds.add(c);
                    toast("命令已暂停，全部上传完成后执行");
                    logEvent("命令已暂停（上传中），待全部上传完成后提交: " + c);
                    if (linkUp) info(readyText());
                    return;
                }
                submitCmd(c);
            }
        });
    }

    /** 真正提交一条命令到服务端（分片 + 入队 + 发送）。run() 与上传完成后补交共用。 */
    void submitCmd(String c) {
        setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED); // 交互期提速
        addLog("──── " + ts.format(new Date()) + " ────\n$ " + c + "\n\n");
        streaming = true; streamChars = 0;   // 命令回声即输出流起点：后续片段连续追加，不再每片加头/脚注
        waitingResult = true;
        th.removeCallbacks(noChunkTimeout);
        int zc = P.zeroChunkMs(BleService.this);
        if (zc > 0) th.postDelayed(noChunkTimeout, zc);   // 0=关闭零片提示
        // BLE 单次 write 载荷上限 = MTU-3。超长命令按 (MTU-4)/片 分片：
        // 首字节 '>' = 还有后续，'<' = 末片；单帧命令保持原样（兼容旧服务端）
        byte[] raw = c.getBytes(UTF8);
        int maxW = Math.max(20, curMtu - 3);
        if (raw.length <= maxW) {
            wq.add(new W(cCmd, raw));
        } else {
            int per = maxW - 1;                    // 每片首字节留给 > / < 标记
            int frags = (raw.length + per - 1) / per;
            logEvent("Command " + raw.length + " bytes exceed MTU (" + maxW + "), split into " + frags + " chunks to send");
            for (int off = 0; off < raw.length; off += per) {
                int len = Math.min(per, raw.length - off);
                byte[] piece = new byte[len + 1];
                piece[0] = (byte) ((off + len >= raw.length) ? '<' : '>');
                System.arraycopy(raw, off, piece, 1, len);
                wq.add(new W(cCmd, piece));
            }
        }
        drain();
    }

    /** 上传全部完成后：把暂停期间累计的命令依次提交到服务端 */
    void flushDeferredCmds() {
        if (deferredCmds.isEmpty()) return;
        java.util.ArrayList<String> pending = new java.util.ArrayList<String>(deferredCmds);
        deferredCmds.clear();
        logEvent("全部上传完成，提交 " + pending.size() + " 条暂停命令");
        for (String c : pending) submitCmd(c);
    }

    /** 手动断开/释放：关链路、撤定时器、撤通知、停服务（Activity 销毁不走这里） */
    public void userRelease() {
        th.post(new Runnable() {
            @Override public void run() {
                removeAllTimers();
                stopScan();
                connecting = false;
                if (gatt != null) {
                    try { gatt.disconnect(); gatt.close(); } catch (Throwable ignored) { }
                    gatt = null;
                }
                linkUp = false;
                curDevice = null;
                resetState();
                setBattIdle();
                info("Disconnected (manual)");
                setBtn("Connect", true);
                updateNotif("Not connected");
                notifTitle = "MyBleClient";  // 通知标题复位为默认值
                stopForeground(true);
                stopSelf();
            }
        });
    }

    /** 界面点"Clear"：视图和缓冲一起清，否则重进后又被回放出来 */
    public void clearLog() {
        th.post(new Runnable() {
            @Override public void run() {
                logBuf.clear();
                Ui u = uiCb;
                if (u != null) { try { u.onLogReset(""); } catch (Throwable ignored) { } }
            }
        });
    }

    // ---------------------------------------------------------------- 扫描连接

    void beginConnect(String name) {
        String nm = (name == null) ? "" : name.trim();
        if (nm.length() == 0) {
            toast("Please enter the advertised name");
            return;
        }
        want = nm;
        if (adapter == null || !adapter.isEnabled()) { info("Bluetooth is off; please turn it on first"); setBtn("Connect", true); return; }

        // 按广播名称扫描（名称不随服务端地址变化）
        startScan();
    }

    void startScan() {
        setBtn("Scanning…", false);
        connecting = false;
        goForeground("Scan/Connect " + want + " …");
        final BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) { info("Cannot get BLE scanner"); setBtn("Connect", true); return; }
        // 不按设备名过滤，所有广播都进 onScanResult，从而在日志里列出全部设备
        ScanSettings st = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        seenDev.clear();
        scanDevLines.clear();
        info("Scanning for all devices (looking for name '" + want + "') …");
        scanning = true;
        try {
            sc.startScan(null, st, scanCb);
            th.postDelayed(scanTimeout, 12000);
        } catch (SecurityException e) {
            scanning = false;
            info("Missing permission, cannot scan: " + e.getMessage());
            setBtn("Connect", true);
        }
    }

    void stopScan() {
        scanning = false;
        try {
            if (adapter == null) return;
            BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
            if (sc != null) sc.stopScan(scanCb);
        } catch (Throwable ignored) { }
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, final ScanResult result) {
            th.post(new Runnable() {
                @Override public void run() {
                    BluetoothDevice d = result.getDevice();
                    String name = safeName(d, result);
                    String addr = (d != null) ? d.getAddress() : "?";
                    int rssi = result.getRssi();
                    // —— 扫描期：把每个出现的设备都记到日志（按地址去重，避免重复刷屏）——
                    if (seenDev.add(addr)) {
                        String line;
                        if (name != null && !name.isEmpty()) {
                            line = addr + "  " + name + "  " + rssi + "dBm";
                        } else {
                            line = addr + "  " + rssi + "dBm";
                        }
                        scanDevLines.add(line);
                        logEvent(line);
                    }
                    // —— 名称命中目标才自动连接 ——
                    if (name == null || !name.equalsIgnoreCase(want)) {
                        return;
                    }
                    connecting = true;
                    scanning = false;
                    th.removeCallbacks(scanTimeout);
                    stopScan();
                    // 原始广播数据（ADV payload，hex）：裁掉末尾补零，附有效字节数
                    byte[] adv = null;
                    try {
                        if (result.getScanRecord() != null) adv = result.getScanRecord().getBytes();
                    } catch (Throwable ignored) { }
                    String advHex = "(empty)";
                    if (adv != null && adv.length > 0) {
                        int end = adv.length;                       // 裁掉末尾连续的 00 填充
                        while (end > 0 && adv[end - 1] == 0) end--;
                        if (end == 0) end = adv.length;             // 全 0 时原样显示
                        advHex = "0x" + hex(java.util.Arrays.copyOf(adv, end))
                                + " (" + end + " Byte)";
                    }
                    // 发现目标：新会话起点——清空扫描期间记录的全部设备日志，再写发现行
                    logBuf.clear();
                    Ui u = uiCb;
                    if (u != null) { try { u.onLogReset(""); } catch (Throwable ignored) { } }
                    logEvent("Target found " + d.getAddress() + " RSSI " + result.getRssi()
                            + "dBm, advertising data " + advHex);
                    connectDevice(d, result.getRssi());
                }
            });
        }

        @Override public void onScanFailed(final int errorCode) {
            th.post(new Runnable() {
                @Override public void run() {
                    scanning = false;
                    info("Scan failed code=" + errorCode + "(usually Bluetooth/location not ready)");
                    th.removeCallbacks(scanTimeout);
                    setBtn("Connect", true);
                }
            });
        }
    };

    String safeName(BluetoothDevice d, ScanResult r) {
        try {
            String n = d.getName();
            if (n != null) return n;
        } catch (Throwable ignored) { }
        try {
            if (r.getScanRecord() != null) return r.getScanRecord().getDeviceName();
        } catch (Throwable ignored) { }
        return null;
    }

    /** 字节数组 → 大写十六进制串 */
    static String hex(byte[] b) {
        if (b == null || b.length == 0) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    /** 就绪状态文案：显示已连接对端的 MAC 地址 */
    String readyText() {
        String s = "Connected " + ((curDevice != null) ? curDevice.getAddress() : want);
        s += " · App " + appVersion(this);
        if (serverVer != null && !serverVer.isEmpty()) s += " · server " + serverVer;
        return s;
    }

    void connectDevice(BluetoothDevice d, final int rssi) {
        if (gatt != null) { try { gatt.close(); } catch (Throwable ignored) { } gatt = null; }
        curDevice = d;
        resetState();
        lastRssi = rssi;
        refreshBattLine();
        setBtn("Connecting…", false);
        info("Connecting " + d.getAddress() + "(RSSI " + rssi + ")…");
        goForeground("Connecting " + want + " …");
        th.postDelayed(readyTimeout, 15000);
        if (Build.VERSION.SDK_INT >= 23) {
            gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = d.connectGatt(this, false, gattCb);
        }
    }

    /** 强制释放：撤定时器、清状态、复位按钮（不断开链路以外的资源由调用方决定） */
    /** 强制释放：撤定时器、关链路、清状态、复位按钮。
     *  必须真正 disconnect+close，否则留下"半清理"状态：
     *  gatt 悬着、curDevice 已空 → linkAlive() 永远 false → 心跳每轮空报刷屏。 */
    void hardClose() {
        linkUp = false;
        connecting = false;
        th.removeCallbacks(readyTimeout);
        th.removeCallbacks(chunkTimeout);
        th.removeCallbacks(noChunkTimeout);
        th.removeCallbacks(heartbeat);
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Throwable ignored) { }
            gatt = null;
        }
        curDevice = null;
        resetState();
        setBattIdle();
        info("Not connected");
        updateNotif("Not connected");
        setBtn("Connect", true);
        releaseWake();
    }

    void resetState() {
        stopProbe();                          // 复位时停掉任何进行中的 wakeupserver 探活
        cBatt = null; cCmd = null; cRes = null; cAuth = null;
        wq.clear();
        inflight = 0;
        abortRx();
        step = 0;
        mtuText = "";
        curMtu = 23;
        lastBattery = -1;
        waitingResult = false;
        resSubscribed = false;
        serverVer = "";
        serverCwd = "";
        editRemotePath = null;
        highPrioLocked = false;   // 断链复位：清除可能残留的 HIGH 锁
        autoCmdDone = false;      // 下次连接可以再执行一次"连接后自动命令"
    }

    /** 链路存活只问协议栈，不信业务 flag，也不受迟到事件摆布 */
    boolean linkAlive() {
        if (gatt == null) return false;
        try {
            if (btManager != null && curDevice != null) {
                return btManager.getConnectionState(curDevice, BluetoothProfile.GATT)
                        == BluetoothProfile.STATE_CONNECTED;
            }
        } catch (Throwable ignored) { }
        return linkUp;
    }

    // ------------------------------------------------------------- GATT 客户端

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            th.post(new Runnable() {
                @Override public void run() { onConnState(g, status, newState); }
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            th.post(new Runnable() {
                @Override public void run() { onSvcDiscovered(g, status); }
            });
        }

        @Override public void onMtuChanged(final BluetoothGatt g, final int mtu, final int status) {
            th.post(new Runnable() {
                @Override public void run() { onMtu(g, mtu, status); }
            });
        }

        @Override public void onCharacteristicRead(final BluetoothGatt g,
                                                   final BluetoothGattCharacteristic ch, final int status) {
            th.post(new Runnable() {
                @Override public void run() { onCharRead(g, ch, status); }
            });
        }

        @Override public void onCharacteristicWrite(final BluetoothGatt g,
                                                    final BluetoothGattCharacteristic ch, final int status) {
            th.post(new Runnable() {
                @Override public void run() { onCharWrite(g, ch, status); }
            });
        }

        @Override public void onDescriptorWrite(final BluetoothGatt g, final BluetoothGattDescriptor d, final int status) {
            th.post(new Runnable() {
                @Override public void run() { onDescWrite(g, d, status); }
            });
        }

        @Override public void onReadRemoteRssi(final BluetoothGatt g, final int rssi, final int status) {
            th.post(new Runnable() {
                @Override public void run() { onRssi(g, rssi, status); }
            });
        }

        @Override public void onCharacteristicChanged(final BluetoothGatt g,
                                                      final BluetoothGattCharacteristic ch) {
            th.post(new Runnable() {
                @Override public void run() { onNotify(g, ch); }
            });
        }

        // 2M PHY 协商结果：不支持的机型协议栈会回退 1M（status 非成功），属正常兼容路径，不报错。
        @Override public void onPhyUpdate(final BluetoothGatt g, final int txPhy, final int rxPhy, final int status) {
            th.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) return;
                    logEvent("PHY 更新 tx=" + phyName(txPhy) + " rx=" + phyName(rxPhy)
                            + " status=" + status + (status == BluetoothGatt.GATT_SUCCESS ? "" : "（已回退 1M，兼容）"));
                }
            });
        }

        @Override public void onPhyRead(final BluetoothGatt g, final int txPhy, final int rxPhy, final int status) {
            th.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) return;
                    logEvent("PHY 读取 tx=" + phyName(txPhy) + " rx=" + phyName(rxPhy) + " status=" + status);
                }
            });
        }

        // API 26+：链路层实际协商出的连接参数。interval 单位 1.25ms，timeout 单位 10ms。
        // 这是判断 requestConnectionPriority(HIGH) 是否真正生效的唯一可靠依据
        // （dumpsys 看到的只是“请求档位”，不是链路层真实间隔）。低版本无此回调，静默跳过。
        // 注意：编译用的裁剪 android.jar 无此方法的声明，故不加 @Override；
        // 但签名与 framework 一致，API 26+ 设备上仍会被正常回调（Java 虚方法按签名派发）。
        // 低版本设备上 framework 无此方法，就是一个永不被调用的普通方法，无害。
        public void onConnectionUpdated(final BluetoothGatt g, final int interval,
                                                  final int latency, final int timeout, final int status) {
            th.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) return;
                    double intervalMs = interval * 1.25;
                    int timeoutMs = timeout * 10;
                    dbgOnly("ConnParams: interval=" + intervalMs + "ms latency=" + latency
                            + " timeout=" + timeoutMs + "ms status=" + status
                            + " (请求=" + (highPrioLocked ? "HIGH/传输" : "省电/空闲") + ")");
                }
            });
        }
    };

    void onConnState(BluetoothGatt g, int status, int newState) {
        if (g != gatt) { logEvent("Ignore stale gatt object connect event state=" + newState); return; }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            linkUp = true;
            String addr = g.getDevice().getAddress();
            logEvent("Connected " + addr);  // 新会话起点标记
            notifTitle = want;   // 通知标题改为已连接的服务器名
            goForeground("Connected " + want + ", initializing…");
            acquireWake();
            setBtn("Connected", false);
            info("Connected, discovering services…");
            g.discoverServices();
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (status != BluetoothGatt.GATT_SUCCESS && status != 19) {
                info("Connection interrupted status=" + status + "(peripheral may have restarted)");
            } else {
                info("Disconnected");
            }
            th.removeCallbacks(readyTimeout);
            th.removeCallbacks(heartbeat);
            updateNotif("Disconnected");
            notifTitle = "MyBleClient";  // 通知标题复位为默认值
            setBtn("Connect", true);     // 无条件复位
            try { g.close(); } catch (Throwable ignored) { }
            gatt = null;
            curDevice = null;
            linkUp = false;
            connecting = false;
            resetState();
            setBattIdle();
            releaseWake();
            stopForeground(true);        // 链路没了，通知也撤下
            if (P.autoConnect(this)) th.postDelayed(autoConnectTask, 4000);
        }
    }

    void onSvcDiscovered(BluetoothGatt g, int status) {
        if (g != gatt) return;
        BluetoothGattService cmd = null, bas = null;
        try {
            cmd = g.getService(SVC_CMD);
            bas = g.getService(SVC_BAS);
        } catch (Throwable t) { }
        if (cmd != null) {
            cCmd  = cmd.getCharacteristic(CH_CMD);
            cRes  = cmd.getCharacteristic(CH_RES);
            cAuth = cmd.getCharacteristic(CH_AUTH);
            cFile = cmd.getCharacteristic(CH_FILE);
        }
        if (bas != null) cBatt = bas.getCharacteristic(CH_BATT);
        logEvent("Service discovery: BAS=" + (bas != null) + " 2A19=" + (cBatt != null)
                + " cmd=" + (cmd != null) + " CMD=" + (cCmd != null)
                + " RES=" + (cRes != null) + " AUTH=" + (cAuth != null) + " FILE=" + (cFile != null));
        if (cCmd == null && cBatt == null) {
            th.removeCallbacks(readyTimeout);
            info("Expected characteristic not found - confirm this is Rolex and the server has registered the service");
            return;
        }
        g.requestMtu(517);
    }

    void onMtu(BluetoothGatt g, int mtu, int status) {
        if (g != gatt) return;
        curMtu = mtu;
        mtuText = "MTU " + mtu;
        logEvent("MTU negotiated " + mtu + "(usable per chunk " + Math.max(1, mtu - 4) + " bytes)");
        step = 1;
        runStep();
        request2MPhy();   // 协商 2M PHY（不支持的机型自动回退 1M，不影响连接）
    }

    /** 请求 2M PHY 以拉满吞吐；反射调用避免低版本编译/运行问题。失败则留在 1M（兼容）。 */
    void request2MPhy() {
        if (gatt == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            logEvent("2M PHY 不受支持（Android < 8），保持 1M");
            return;
        }
        try {
            int PHY_2M = (Integer) BluetoothDevice.class.getField("PHY_LE_2M").get(null);
            int OPT_NONE = (Integer) BluetoothDevice.class.getField("PHY_OPTION_NO_PREFERRED").get(null);
            java.lang.reflect.Method m = gatt.getClass().getMethod("setPreferredPhy", int.class, int.class, int.class);
            m.invoke(gatt, PHY_2M, PHY_2M, OPT_NONE);
            logEvent("已请求 2M PHY（机型不支持则自动回退 1M）");
        } catch (Throwable t) {
            logEvent("2M PHY 请求跳过（" + t.getClass().getSimpleName() + "），保持 1M");
        }
    }

    static String phyName(int phy) {
        try {
            if (phy == (Integer) BluetoothDevice.class.getField("PHY_LE_2M").get(null)) return "2M";
            if (phy == (Integer) BluetoothDevice.class.getField("PHY_LE_1M").get(null)) return "1M";
        } catch (Throwable ignored) { }
        return "?" + phy;
    }

    void onCharRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
        if (g != gatt) return;
        if (CH_BATT.equals(ch.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
            showBattery(ch.getValue());
        }
        step = 2;
        runStep();
    }

    void onCharWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
        if (g != gatt) return;
        if (inflight > 0) inflight--;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            info("Write failed status=" + status);
            logEvent("Write " + shortOf(ch.getUuid()) + " failed status=" + status);
        }
        drain();
    }

    void onDescWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
        if (g != gatt) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            info("Subscribe failed status=" + status);
            logEvent("Subscribe CCCD failed status=" + status + "(server may not have declared NOTIFY)");
        } else {
            if (CH_RES.equals(uuidOf(d))) resSubscribed = true;
            logEvent("Subscribed: " + shortOf(uuidOf(d))
                    + (CH_RES.equals(uuidOf(d)) ? "(result channel ready)" : ""));
        }
        step = step + 1;
        runStep();
    }

    void onRssi(BluetoothGatt g, int rssi, int status) {
        if (g != gatt) return;
        if (status == BluetoothGatt.GATT_SUCCESS) {
            lastRssi = rssi;
            refreshBattLine();
        } else {
            logEvent("readRemoteRssi Back status=" + status);
        }
    }

    void onNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        if (g != gatt) return;
        byte[] v = ch.getValue();
        if (v == null || v.length == 0) return;
        if (CH_BATT.equals(ch.getUuid())) {
            showBattery(v);
        } else if (CH_RES.equals(ch.getUuid())) {
            handleChunk(v);
        }
    }

    static UUID uuidOf(BluetoothGattDescriptor d) {
        try {
            BluetoothGattCharacteristic c = d.getCharacteristic();
            return c == null ? CCCD : c.getUuid();
        } catch (Throwable t) { return CCCD; }
    }

    /** 串起来：读电量 -> 订阅电量 -> 订阅 RES -> 写 AUTH */
    void runStep() {
        BluetoothGatt g = gatt;
        if (g == null) return;
        if (step == 1) { if (cBatt != null) { g.readCharacteristic(cBatt); return; } step = 2; }
        if (step == 2) { if (cBatt != null && enableNotify(cBatt)) return; step = 3; }
        if (step == 3) { if (cRes != null && enableNotify(cRes)) return; step = 4; }
        if (step == 4) {
            String psk = P.psk(this).trim();
            if (cAuth != null && psk.length() > 0) {
                // 不配对、不传明文：等服务端下发 nonce，再回 HMAC(psk, nonce)（挑战-响应，防嗅探/重放）
                authAwaiting = true;
                th.postDelayed(authWatchdog, 8000);
                logEvent("AUTH: 等待服务端 nonce（HMAC 挑战响应）…");
            }
            step = 9;
        }
        if (step == 9) {
            th.removeCallbacks(readyTimeout);
            th.removeCallbacks(heartbeat);
            th.postDelayed(heartbeat, 1000);
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER); // 空闲省电
            info(readyText());
            updateNotif(readyText());
            logEvent("Ready(" + readyText() + "): " + (cBatt != null ? "Battery Level readable/subscribable; " : "no battery service; ")
                    + (cCmd != null ? "commandchannel available" : "no command channel"));
            flushPendingShare();   // 系统分享进来的文件：链路就绪后自动开始上传
            maybeRunOnConnectCmd(); // 设置里的"连接后自动命令"
        }
    }

    /**
     * 连接参数：就绪/空闲请求 LOW_POWER 省电，交互用 BALANCED，传输用 HIGH。
     * 服务端是外设端，无法自己请求连接参数，只能靠客户端这头提。
     *
     * 关键：传输期（highPrioLocked=true）只允许 HIGH，屏蔽一切 LOW_POWER/BALANCED 请求——
     * 否则上传途中任何命令回执（#rc=、auth:ok、!错误帧等）都会把 HIGH 切回 LOW_POWER，
     * 连接间隔被拉到 150ms+，这是“nRF Connect 能提速、我们 App 不行”的根因之一。
     */
    void setConnPriority(int mode) {
        if (highPrioLocked && mode != BluetoothGatt.CONNECTION_PRIORITY_HIGH) return;  // 传输期锁 HIGH
        try {
            if (gatt != null) gatt.requestConnectionPriority(mode);
        } catch (Throwable ignored) { }
    }

    /** 传输开始：锁住 HIGH，之后其它档位的请求一律被 setConnPriority 屏蔽 */
    void lockHighPriority() {
        highPrioLocked = true;
        setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
    }

    /** 传输结束：解锁并降回 LOW_POWER 省电档 */
    void unlockPriority() {
        highPrioLocked = false;
        setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
    }

    // ------------------------------------------------------------ 熄屏保活（可选）

    /** 设置开启时，连接期间持有 CPU 唤醒锁：防止熄屏后 AP 休眠导致 ATT 停摆/回调丢失 */
    void acquireWake() {
        if (!P.keepWake(this) || wake != null) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blecmd:conn");
            wake.setReferenceCounted(false);
            wake.acquire();
            logEvent("Holding CPU wake-lock (screen-off wake-lock, trade power for stability)");
        } catch (Throwable t) {
            logEvent("Wake-lock acquire failed: " + t);
        }
    }

    void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) { }
        wake = null;
    }

    boolean enableNotify(BluetoothGattCharacteristic ch) {
        if (ch == null || gatt == null) return false;
        if (!gatt.setCharacteristicNotification(ch, true)) {
            logEvent("setCharacteristicNotification failed: " + shortOf(ch.getUuid()));
            return false;
        }
        BluetoothGattDescriptor cd = ch.getDescriptor(CCCD);
        if (cd == null) {
            logEvent(shortOf(ch.getUuid()) + " has no CCCD, read-only, cannot notify");
            return false;
        }
        // RES 结果通道用 INDICATION：服务端 confirm=true 可靠流控，一次只一片在途，
        // 根治 NOTIFY 背靠背冲掉缓冲区导致的丢片（参考 Nordic GATT Server 的可靠下行做法）。
        // 电量通道仍用 NOTIFICATION（单字节偶丢无碍，且省一次 ATT 确认往返）。
        if (CH_RES.equals(ch.getUuid())) {
            cd.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            logEvent("Subscribe " + shortOf(ch.getUuid()) + ": INDICATION(reliable flow control)");
        } else {
            cd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        }
        return gatt.writeDescriptor(cd);
    }

    // ------------------------------------------------------ 电量 / 信号 / 结果

    void showBattery(byte[] v) {
        if (v == null || v.length == 0) { logEvent("Battery value empty"); return; }
        lastBattery = v[0] & 0xFF;
        refreshBattLine();
    }

    void refreshBattLine() {
        final String line;
        if (!linkUp) {
            line = "";          // 未连接/已断开/连接中：电量信号行整体不显示
        } else {
            line = "Battery: " + (lastBattery < 0 ? " - " : lastBattery + "%")
                    + "   Signal: " + (lastRssi == 0 ? " - " : lastRssi + " dBm")
                    + "   " + ts.format(new Date());
        }
        curBatt = line;
        Ui u = uiCb;
        if (u != null) { try { u.onBatt(line); } catch (Throwable ignored) { } }
        updateNotif((lastBattery < 0 ? "" : lastBattery + "%  ")
                + (lastRssi == 0 ? "" : lastRssi + " dBm"));
    }

    void setBattIdle() {
        lastBattery = -1;
        lastRssi = 0;
        refreshBattLine();
    }

    /** 服务端 v2 可靠传输协议：
     *  T 帧: 'T' + tid(2) + seq(2) + total(2) + payload + CRC16(2)
     *  D 帧: 'D' + tid(2) + SHA-256(32) + CRC16(2)
     *  '!'  : 错误帧（不入序列，收到即中止当前传输）
     *  接收侧三重校验：逐帧 CRC16、序号连续性、整包 SHA-256。
     *  缺号/CRC 坏帧 → 请求断点重传（保留已收前缀）；SHA 不符 → 清缓冲全量重传；
     *  校验不过绝不输出——避免拼出"看似完整实则有空洞"的坏结果。 */
    void handleChunk(byte[] v) {
        waitingResult = false;
        th.removeCallbacks(noChunkTimeout);
        if (v == null || v.length == 0) return;
        char mark = (char) (v[0] & 0xFF);
        if (mark == 'T') {
            if (v.length < 9) return;
            int crc = ((v[v.length - 2] & 0xFF) << 8) | (v[v.length - 1] & 0xFF);
            int tid = ((v[1] & 0xFF) << 8) | (v[2] & 0xFF);
            int seq = ((v[3] & 0xFF) << 8) | (v[4] & 0xFF);
            if (tid == rvDoneTid) return;                     // 已放弃传输的迟到残帧：吞掉，防止被误当新传输从头再来
            if (crc16(v, 0, v.length - 2) != crc) {          // 单帧 CRC 失败：按缺片请求该帧
                if (tid == rvTid) requestMissing(tid, seq, "CRC16 check failed");
                return;
            }
            int total = ((v[5] & 0xFF) << 8) | (v[6] & 0xFF);
            if (tid != rvTid) {                              // 新传输：重置接收状态
                rx.reset(); rvPend.clear(); rvTimeouts = 0;
                rvTid = tid; rvTotal = total; rvNext = 0; rvNacks = 0;
                rvDigest = null; rvDigestGot = false;
                rvNackGuardSeq = -1; rvNackGuardAt = 0;
            }
            if (seq < rvNext) return;                        // 重复片（重传窗口内已收过），忽略
            if (seq == rvNext) {
                // 顺序片：写入后把缓冲区里已到齐的后续连续片一并冲刷进 rx
                try {
                    rx.write(v, 7, v.length - 9);
                    rvNext++;
                    while (rvPend.containsKey(rvNext)) {
                        byte[] b = rvPend.remove(rvNext);
                        rx.write(b, 0, b.length);
                        rvNext++;
                    }
                    rvTimeouts = 0;   // 有进展：清零连续超时计数（大文件传输偶尔慢一拍不该被误判为连续超时）
                } catch (Throwable ignored) { }
            } else {
                // 乱序片：缓存，不丢弃；只请求真正缺的 rvNext（带节流，杜绝每来一帧都从头 NACK）
                if (!rvPend.containsKey(seq)) {
                    byte[] b = new byte[v.length - 9];
                    System.arraycopy(v, 7, b, 0, b.length);
                    rvPend.put(seq, b);
                }
                requestMissing(tid, rvNext, "missing seq " + rvNext + "(received " + seq + ")");
            }
            if (rvTotal > 1) {
                // 收到任意一片就重置超时：chunkTimeout 表示"连续 4 秒无任何新片"，
                // 而非旧逻辑那样每片排一个定时器、导致数据正常流动时每 4 秒仍误报一次 timeout NACK。
                th.removeCallbacks(chunkTimeout);
                th.postDelayed(chunkTimeout, 4000);
            }
            if (downBusy) {
                // 下载中：进度走状态行（与上传同款格式），不刷日志区
                int dpct = rvTotal > 0 ? (int) ((long) rvNext * 100 / rvTotal) : 0;
                if (dpct > 100) dpct = 100;
                Ui du = uiCb;
                if (du != null) { try { du.onProgress(downStatus(false, dpct, rx.size())); } catch (Throwable ignored) { } }
            } else {
                info("receiving... chunk " + rvNext + "/" + rvTotal + ", got " + rx.size()
                        + " bytes, buffered=" + rvPend.size());
            }
            if (rvNext >= rvTotal && rvDigestGot) verifyDigest();   // 校验帧先到、数据后齐：此刻才比对
            else if (rvTotal == 1) completeRx();                     // 单片传输：CRC 已保证完整
            return;
        }
        if (mark == 'D') {
            if (v.length < 37) return;
            int crc = ((v[v.length - 2] & 0xFF) << 8) | (v[v.length - 1] & 0xFF);
            if (crc16(v, 0, v.length - 2) != crc) return;    // 坏校验帧：等 chunkTimeout 补请求
            int tid = ((v[1] & 0xFF) << 8) | (v[2] & 0xFF);
            if (tid == rvDoneTid) return;                     // 已放弃传输的迟到校验帧：吞掉
            if (tid == rvTid && !rvDigestGot && rvTotal > 1) {
                rvDigest = new byte[32];
                System.arraycopy(v, 3, rvDigest, 0, 32);
                rvDigestGot = true;
                if (rvNext >= rvTotal) verifyDigest();       // 数据已齐才比对；否则等最后一片到齐再校验
            }
            return;
        }
        if (mark == '!') {
            int done = rvTid;
            abortRx(done);
            appendResult("✗ " + new String(v, 1, v.length - 1, UTF8));
        }
        // 其他帧型不认识：忽略
    }

    /** HMAC-SHA256(key, data) → hex；AUTH 挑战-响应用，密钥永不明文出现在链路 */
    static String hmacHex(String key, byte[] data) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            return toHex(m.doFinal(data));
        } catch (Throwable t) { return ""; }
    }
    static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }
    static byte[] parseHex(String s) {
        s = s.replace(":", "").replace("-", "").replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** 单帧 CRC16-CCITT（与服务端同算法，poly 0x1021 init 0xFFFF） */
    static int crc16(byte[] b, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    /** 校验帧到齐：比对整包 SHA-256，不符则丢弃全量重传 */
    void verifyDigest() {
        try {
            byte[] actual = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rx.toByteArray());
            if (java.util.Arrays.equals(actual, rvDigest)) { completeRx(); return; }
        } catch (Throwable ignored) { }
        logEvent("SHA-256 mismatch: discarding " + rx.size() + " bytes, requesting full retransmit");
        rx.reset(); rvPend.clear();
        rvNext = 0; rvTimeouts = 0; rvNackGuardSeq = -1;
        rvDigestGot = false;
        rvDigest = null;
        sendNack(rvTid, 0, "SHA-256 mismatch, full retransmit");
    }

    /** 传输完成：一次性 UTF-8 解码输出（跨帧字符不坏） */
    void completeRx() {
        th.removeCallbacks(chunkTimeout);
        byte[] data = rx.toByteArray();
        abortRx(rvTid);   // 标记完成 tid：其迟到残帧一律吞掉，不被当成新传输重复输出
        // v3 文件传输：FILD: 头=下载数据，FLST: 头=目录列表，FILM: 头=vi 编辑用的带元数据下载
        // 走专用处理（字节安全，不经 UTF-8 解码）
        if (data.length >= 5 && data[0] == 'F' && data[1] == 'I' && data[2] == 'L') {
            if (data.length > 5 && data[3] == 'D' && data[4] == ':') { handleFileDownload(data); return; }
            if (data.length > 6 && data[3] == 'S' && data[4] == 'T' && data[5] == ':') { handleFileList(data); return; }
            if (data.length > 5 && data[3] == 'M' && data[4] == ':') { handleEditDownload(data); return; }
        }
        String whole = new String(data, UTF8);
        String wholeTrim = whole.trim();
        // wakeup-ok 探活回包：服务端对 wakeupserver 直接回 wakeup-ok（不执行），纯协议标记、不是命令输出。
        // 无论是否处于探测中都直接吞掉、绝不显示——否则会出现"command先产出真实输出→探测已停→迟到的 wakeup-ok 被当成结果打印"的情况。
        if ("wakeup-ok".equals(wholeTrim)) {
            probePending = false;             // 探测进行中则只清挂起标记，继续探测直到真实回包
            return;
        }
        stopProbe();                          // 其它结果（真实命令回包）回来了 → 服务端存活，停止探活
        if (wholeTrim.startsWith("nonce:")) {     // AUTH 挑战：收到服务端一次性 nonce，回 HMAC(psk, nonce)
            th.removeCallbacks(authWatchdog);
            try {
                byte[] nonce = parseHex(wholeTrim.substring("nonce:".length()).trim());
                String mac = hmacHex(P.psk(this).trim(), nonce);
                if (cAuth != null) {
                    logEvent("Write AUTH(HMAC challenge-response)…");
                    wq.add(new W(cAuth, mac.getBytes(UTF8)));
                    drain();
                }
            } catch (Throwable t) { logEvent("nonce 解析失败: " + t); }
            return;
        }
        if (wholeTrim.startsWith("blegatt-ver:")) {   // 服务端版本帧：提取后不计入结果
            serverVer = wholeTrim.substring("blegatt-ver:".length()).trim();
            logEvent("Received server version: " + serverVer);
            if (linkUp) info(readyText());
            return;
        }
        if (wholeTrim.startsWith("FILEDONE:")) {   // v3 上传完成回执：name:sha:size
            // 不在单文件完成时降档：多文件队列还要继续传。解锁统一由 pumpUpload() 在队列空时处理。
            String body = wholeTrim.substring("FILEDONE:".length()).trim();
            String[] p = body.split(":");
            String name = p.length > 0 ? p[0] : "?";
            String size = p.length > 2 ? p[2] : "?";
            upName = name;
            // 编辑回写（PUTE）完成：与普通上传分开通知，避免误报 Uploaded: 与误触发队列
            if (editRemotePath != null) {
                String rp = editRemotePath;
                editRemotePath = null;
                unlockPriority();
                logEvent("编辑已回写: " + rp + " (" + size + " 字节)");
                Ui eu = uiCb;
                if (eu != null) { try { eu.onEditUploaded(true, "已回写 " + rp); } catch (Throwable ignored) { } }
                return;
            }
            Ui u = uiCb;
            if (u != null) { try { u.onProgress(upStatus(true, 100)); } catch (Throwable ignored) { } }  // 完成行：Uploaded: ...
            toast("上传完成: " + name + " (" + size + " 字节)");   // 每个文件传完弹一条，多文件队列会逐条提示
            logEvent("上传完成: " + name + " (" + size + " 字节)");
            upBusy = false;                                  // 本文件传完，启动队列里的下一个
            th.post(new Runnable() { public void run() { pumpUpload(); } });
            return;
        }
        if (wholeTrim.startsWith("FILEABORT:")) {   // v3 上传中止回执：服务端已关闭流并删除部分文件
            String nm = wholeTrim.substring("FILEABORT:".length()).trim();
            upCancel = false; upBusy = false; upQueue.clear();
            unlockPriority();   // 传输彻底中止：解锁并降回省电档
            Ui u = uiCb;
            if (u != null) {
                try { u.onProgress((nm.isEmpty() ? "upload" : "upload " + nm) + " cancelled."); u.onUploadState(false); }
                catch (Throwable ignored) { }
            }
            logEvent("上传已取消（服务端确认）: " + (nm.isEmpty() ? "(进行中文件)" : nm));
            return;
        }
        if (wholeTrim.startsWith("!")) {          // 服务端文本错误（!auth:denied / !timeout / !exec-failed / !cmd-too-long / !denied）
            finishStream();                        // 先收尾当前输出流（若正在流式显示）
            appendResult("✗ " + whole.trim().substring(1));
            logEvent("Server: " + whole.trim());
            // fs edget/fs get 等传输请求若返回文本错误（如 !fs: not a file），
            // 之前为保证速度锁了 HIGH，这里没有 FILD 回包来解锁，需主动释放，避免永久卡在 HIGH
            if (highPrioLocked && !upBusy && upQueue.isEmpty() && editRemotePath == null) unlockPriority();
            else setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
            if (linkUp) info(readyText());
            return;
        }
        if ("auth:ok".equals(wholeTrim)) { rememberKey(); authAwaiting = false; th.removeCallbacks(authWatchdog); setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER); if (linkUp) info(readyText()); maybeRunOnConnectCmd(); return; }
        if (wholeTrim.startsWith("#cwd=")) {        // 持久 shell 回报当前目录：静默更新，不进终端
            serverCwd = wholeTrim.substring(5).trim();
            dbgOnly("server cwd = " + serverCwd);   // 仅调试日志，不进界面
            return;
        }
        if (wholeTrim.startsWith("#rc=")) {        // 命令正常结束帧：打印一条汇总，替代原先每片一条的「接收完成」脚注
            finishStream();
            int code = -1;
            try { code = Integer.parseInt(wholeTrim.substring(4).trim()); } catch (Throwable ignored) { }
            dbgOnly("Command finished (exit code " + code + "), received " + streamChars + " chars (CRC/SHA verified)");   // 仅调试日志，不进界面
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER); // 回到省电档
            if (linkUp) info(readyText());
            return;
        }
        // 普通输出片段：连续追加正文，不打断、不加每片头/脚注（流式边读边发的可读性关键）
        if (!streaming) { addLog("──── " + ts.format(new Date()) + " ────\n"); streaming = true; streamChars = 0; }
        addLog(whole);
        streamChars += whole.length();
        if (linkUp) info(readyText());
    }

    // ================= 文件收发（v3）=================
    /** 下载：FILD:name:size:sha\n + 原始文件字节 → 写入 App 私有 Download 目录，SHA 校验 */
    void handleFileDownload(byte[] data) {
        try {
            int nl = -1;
            for (int i = 5; i < data.length; i++) { if (data[i] == '\n') { nl = i; break; } }
            if (nl < 0) { logEvent("下载失败：FILD 头无换行"); downBusy = false; notifyDownloadState(false); unlockPriority(); return; }
            String hdr = new String(data, 0, nl, UTF8);
            String[] h = hdr.split(":");                // FILD name size sha
            if (h.length < 4) { logEvent("下载失败：FILD 头格式错"); downBusy = false; notifyDownloadState(false); unlockPriority(); return; }
            String name = h[1];
            long size = Long.parseLong(h[2].trim());
            String sha = h[3].trim();
            downName = name; downTotal = size;   // 用 FILD 头里的真实文件名/大小回填进度状态
            byte[] fileBytes = java.util.Arrays.copyOfRange(data, nl + 1, data.length);
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            java.io.File out = new java.io.File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(fileBytes);
            fos.close();
            unlockPriority();   // 下载完成：解锁并降回省电档
            downBusy = false;
            notifyDownloadState(false);   // down 按钮 → down
            String got = sha256hex(fileBytes);
            boolean ok = got.equalsIgnoreCase(sha);
            Ui du = uiCb;
            if (ok) {
                // 完成行走状态行（与上传 "Uploaded:" 对称），不被 readyText 覆盖
                if (du != null) { try { du.onProgress(downStatus(true, 100, fileBytes.length)); } catch (Throwable ignored) { } }
                toast("下载完成: " + name + " (" + size + " 字节)");
                logEvent("下载完成 " + name + " -> " + out.getAbsolutePath() + " 校验OK");
            } else {
                if (du != null) { try { du.onProgress("download failed: " + name); } catch (Throwable ignored) { } }
                toast("下载校验失败: " + name);
                logEvent("下载校验失败 " + name + " 期望 " + sha + " 实得 " + got);
            }
        } catch (Throwable t) {
            logEvent("下载失败: " + t);
            downBusy = false;
            notifyDownloadState(false);
            unlockPriority();   // 下载异常：解锁并降回省电档
        }
    }

    /** vi 编辑下载：FILM:<base64绝对路径>:<mode八进制>:<size>:<sha>\n<原始字节>
     *  落到 App 私有 edit 目录（文件名用远端 basename），回调界面交给外部编辑器。 */
    void handleEditDownload(byte[] data) {
        try {
            int nl = -1;
            for (int i = 5; i < data.length; i++) { if (data[i] == '\n') { nl = i; break; } }
            if (nl < 0) { logEvent("编辑下载失败：FILM 头无换行"); unlockPriority(); return; }
            String hdr = new String(data, 0, nl, UTF8);
            String[] h = hdr.split(":");   // FILM b64Path mode size sha
            if (h.length < 5) { logEvent("编辑下载失败：FILM 头格式错"); unlockPriority(); return; }
            String remote = new String(Base64.decode(h[1], Base64.NO_WRAP), UTF8);
            String mode = h[2].trim();
            long size = Long.parseLong(h[3].trim());
            String sha = h[4].trim();
            byte[] fileBytes = java.util.Arrays.copyOfRange(data, nl + 1, data.length);
            String got = sha256hex(fileBytes);
            if (!got.equalsIgnoreCase(sha)) {
                toast("编辑下载校验失败");
                logEvent("编辑下载校验失败 " + remote + " 期望 " + sha + " 实得 " + got);
                unlockPriority();
                return;
            }
            java.io.File dir = new java.io.File(getFilesDir(), "edit");
            if (!dir.exists()) dir.mkdirs();
            String base = remote.substring(remote.lastIndexOf('/') + 1);
            if (base.length() == 0) base = "edit.txt";
            String localName = base + "." + System.currentTimeMillis();
            java.io.File out = new java.io.File(dir, localName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(fileBytes);
            fos.close();
            unlockPriority();
            logEvent("编辑下载OK " + remote + " -> " + out.getAbsolutePath() + " mode=" + mode);
            Ui u = uiCb;
            if (u != null) { try { u.onEditFileReady(out.getAbsolutePath(), remote, mode, sha); } catch (Throwable ignored) { } }
        } catch (Throwable t) {
            logEvent("编辑下载失败: " + t);
            unlockPriority();
        }
    }

    /** vi 拦截入口：请求下载服务端指定绝对路径的文件并在本地打开编辑器 */
    public void editRemoteFile(final String absPath) {
        th.post(new Runnable() { public void run() {
            if (gatt == null || step < 9) { toast("未就绪"); return; }
            if (absPath == null || absPath.trim().length() == 0) { toast("路径为空"); return; }
            if (upBusy || !upQueue.isEmpty() || editRemotePath != null) { toast("传输中，请稍后"); return; }
            lockHighPriority();
            logEvent("请求编辑: " + absPath.trim());
            BleService.this.run("fs edget " + absPath.trim());
            th.post(new Runnable() { public void run() { lockHighPriority(); } });
        }});
    }

    /** 把本地编辑后的文件写回服务端原路径，并让服务端恢复原权限 */
    public void uploadEditedFile(final String localPath, final String remote, final String mode) {
        th.post(new Runnable() { public void run() {
            if (gatt == null || cFile == null || step < 9) { toast("未就绪，无法回写"); return; }
            if (upBusy || !upQueue.isEmpty() || editRemotePath != null) { toast("传输中，请稍后重试回写"); return; }
            try {
                byte[] all = readAll(new java.io.FileInputStream(localPath));
                if (all.length == 0) { toast("文件为空，未回写"); return; }
                String b64 = Base64.encodeToString(remote.getBytes(UTF8), Base64.NO_WRAP);
                byte[] hdr = ("PUTE:" + b64 + ":" + all.length + ":" + (mode == null ? "644" : mode) + "\n").getBytes(UTF8);
                editRemotePath = remote;
                editMode = (mode == null ? "644" : mode);
                upSent = 0; upTotal = all.length; upStartMs = System.currentTimeMillis();
                upName = remote.substring(remote.lastIndexOf('/') + 1);
                lockHighPriority();
                wq.add(new W(cFile, hdr, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT));
                int maxW = Math.max(20, curMtu - 3);
                for (int off = 0; off < all.length; off += maxW) {
                    int len = Math.min(maxW, all.length - off);
                    byte[] piece = new byte[len];
                    System.arraycopy(all, off, piece, 0, len);
                    wq.add(new W(cFile, piece, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, len));
                }
                drain();
                logEvent("编辑回写 " + remote + " (" + all.length + " 字节) 已入队");
            } catch (Throwable t) {
                editRemotePath = null;
                unlockPriority();
                toast("回写失败: " + t);
                logEvent("回写失败: " + t);
                Ui u = uiCb;
                if (u != null) { try { u.onEditUploaded(false, "回写失败: " + t); } catch (Throwable ignored) { } }
            }
        }});
    }

    /** 目录列表：FLST:\n + 每行 "d/f <size> <name" → 回调界面填充列表 */
    void handleFileList(byte[] data) {
        try {
            int nl = -1;
            for (int i = 5; i < data.length; i++) { if (data[i] == '\n') { nl = i; break; } }
            if (nl < 0) { logEvent("列表失败：FLST 头无换行"); return; }
            String body = new String(data, nl + 1, data.length - nl - 1, UTF8);
            java.util.ArrayList<String> items = new java.util.ArrayList<String>();
            for (String line : body.split("\n")) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                items.add(line);   // 形如 "d 0 name" / "f 123 name"
            }
            Ui u = uiCb;
            if (u != null) { try { u.onFileList(items); } catch (Throwable ignored) { } }
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
            logEvent("目录列表 " + items.size() + " 项");
        } catch (Throwable t) {
            logEvent("列表失败: " + t);
        }
    }

    static String sha256hex(byte[] d) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(d);
            StringBuilder s = new StringBuilder();
            for (byte x : h) s.append(String.format("%02x", x & 0xFF));
            return s.toString();
        } catch (Throwable t) { return ""; }
    }

    /** 远程列目录 */
    public void listRemote(final String path) {
        th.post(new Runnable() { public void run() {
            if (gatt == null || step < 9) { toast("未就绪"); return; }
            BleService.this.run("fs ls " + (path == null || path.trim().length() == 0 ? "." : path.trim()));
        }});
    }

    /** 远程下载文件（服务端 FILE_BASE 基准路径） */
    public void downloadFromServer(final String path) {
        th.post(new Runnable() { public void run() {
            if (gatt == null || step < 9) { toast("未就绪"); return; }
            if (path == null || path.trim().length() == 0) { toast("路径为空"); return; }
            if (upBusy) { toast("上传中，请稍后再下载"); return; }   // 上传/下载共用同一把 HIGH 锁，串行执行
            if (downBusy) { toast("下载中，请稍后"); return; }
            lockHighPriority();           // 传输期提速
            // 初始化下载进度状态（与上传对称：状态行显示 Downloading: ...）
            downBusy = true;
            notifyDownloadState(true);   // down 按钮 → cancel
            downTotal = 0;
            downStartMs = System.currentTimeMillis();
            String base = path.trim();
            int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            downName = slash >= 0 ? base.substring(slash + 1) : base;   // 先用请求路径的 basename，FILD 头到后再用真实名回填
            Ui du = uiCb;
            if (du != null) { try { du.onProgress(downStatus(false, 0, 0)); } catch (Throwable ignored) { } }
            logEvent("请求下载: " + path.trim());
            // 路径解析（与 vi 一致）：
            //  绝对路径 → getabs 直读；
            //  相对路径 → 拼当前 shell 工作目录(serverCwd)成绝对路径再 getabs，实现「当前路径+文件名」下载；
            //  serverCwd 未知 → 回退 fs get（相对 FILE_BASE）。
            String dl = path.trim();
            String fsOp;
            if (dl.startsWith("/")) {
                fsOp = "fs getabs ";
            } else {
                String cwd = serverCwd;
                if (cwd == null || cwd.length() == 0) {
                    fsOp = "fs get ";
                } else {
                    dl = (cwd.endsWith("/") ? cwd : cwd + "/") + dl;
                    fsOp = "fs getabs ";
                }
            }
            BleService.this.run(fsOp + dl);
            th.post(new Runnable() { public void run() { lockHighPriority(); } });
        }});
    }

    /** 系统「分享到」入口：文件已由 ShareActivity 复制到私有缓存（file:// Uri）。
     *  已就绪则直接入上传队列；未连接则暂存并自动连接，就绪后自动上传。 */
    public void shareFiles(final java.util.ArrayList<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        th.post(new Runnable() { public void run() {
            if (gatt != null && cFile != null && step >= 9) {
                logEvent("分享 " + uris.size() + " 个文件，链路就绪，开始上传");
                uploadUris(uris);
            } else {
                pendingShare.addAll(uris);
                logEvent("分享 " + uris.size() + " 个文件已暂存，等待连接就绪后自动上传");
                toast("已保存 " + uris.size() + " 个文件，连接就绪后自动上传");
                // 未连接则触发自动连接（按服务器名）
                if (gatt == null && !linkUp && !connecting && !scanning) {
                    beginConnect(P.server(BleService.this));
                }
            }
        }});
    }

    /** 连接就绪后，把分享暂存的文件送入上传队列 */
    void flushPendingShare() {
        if (pendingShare.isEmpty()) return;
        java.util.ArrayList<Uri> copy = new java.util.ArrayList<Uri>(pendingShare);
        pendingShare.clear();
        logEvent("连接就绪，自动上传分享的 " + copy.size() + " 个文件");
        uploadUris(copy);
    }

    /** 多选上传入口：把多个 Uri 入队，逐个（串行）上传 */
    public void uploadUris(final java.util.ArrayList<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        th.post(new Runnable() { public void run() {
            for (Uri u : uris) if (u != null) upQueue.add(u);
            upTotalFiles = upQueue.size();
            pumpUpload();
        }});
    }

    /** 单文件上传（入队后走同一串行路径） */
    public void uploadUri(final Uri uri) {
        java.util.ArrayList<Uri> l = new java.util.ArrayList<Uri>();
        l.add(uri);
        uploadUris(l);
    }

    /** 串行泵：同一时刻只传一个文件，上一个收到 FILEDONE 后再传下一个 */
    void pumpUpload() {
        if (upBusy) return;
        if (gatt == null || cFile == null || step < 9) {
            if (!upQueue.isEmpty()) toast("未就绪/未连接，取消剩余上传");
            upQueue.clear();
            upBusy = false;
            unlockPriority();   // 上传链路中断：解锁并降回省电档
            notifyUploadState(false);
            flushDeferredCmds();   // 即便上传被取消，也让暂停的命令跑起来
            return;
        }
        if (upQueue.isEmpty()) {
            upBusy = false;
            unlockPriority();   // 全部文件上传完成：解锁并降回省电档
            notifyUploadState(false);
            flushDeferredCmds();   // 全部上传完成，提交上传期间被暂停的命令
            return;
        }
        Uri uri = upQueue.remove(0);
        upBusy = true;
        notifyUploadState(true);
        startUpload(uri);
    }

    /** 把文件上传按钮切换为 Cancel / 还原为 Files（仅通知当前界面） */
    void notifyUploadState(boolean uploading) {
        Ui u = uiCb;
        if (u != null) { try { u.onUploadState(uploading); } catch (Throwable ignored) { } }
    }

    /** 把 down 按钮切换为 cancel / 还原为 down（下载开始/结束/取消/断开时调用） */
    void notifyDownloadState(boolean downloading) {
        Ui u = uiCb;
        if (u != null) { try { u.onDownloadState(downloading); } catch (Throwable ignored) { } }
    }

    /** 取消上传：清空待传队列、丢弃当前文件剩余待发分片、通知服务端中止、复位状态。
     *  由 UI 的 Cancel 按钮触发；上传全部完成后 Files 按钮会自动还原。 */
    public void cancelUpload() {
        th.post(new Runnable() { public void run() {
            if (!upBusy && upQueue.isEmpty()) return;
            upQueue.clear();
            upCancel = true;   // 让 issue() 丢弃尚未发出的 cFile 分片
            // 丢弃 wq 中尚未发出的 cFile 分片（已发出的在途片由 upCancel 把关，不再重发）
            if (cFile != null) {
                java.util.Iterator<W> it = wq.iterator();
                while (it.hasNext()) { if (it.next().ch == cFile) it.remove(); }
            }
            // 通知服务端关闭当前落盘流、删除部分文件（放在 cCmd 应答写通道，严格单飞保证送达）
            if (gatt != null && cCmd != null) {
                wq.add(new W(cCmd, "blegatt-upload-abort\n".getBytes(UTF8),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT));
            }
            drain();
            upBusy = false;
            upCancel = false;
            unlockPriority();   // 用户取消上传：解锁并降回省电档
            flushDeferredCmds();   // 上传已结束，让暂停的命令跑起来
            String nm = upName.isEmpty() ? "" : upName + " ";
            Ui u = uiCb;
            if (u != null) {
                try { u.onProgress(nm + "upload cancelled."); u.onUploadState(false); } catch (Throwable ignored) { }
            }
            logEvent("上传已取消: " + (upName.isEmpty() ? "(进行中文件)" : upName));
        }});
    }

    /** 取消下载：中断当前接收、吞掉该 tid 迟到残帧、复位状态并解锁 HIGH。
     *  由 UI 的 down→cancel 按钮触发。服务端随后发来的该 tid 残帧会被 rvDoneTid 吞掉，
     *  不会被误当新传输。 */
    public void cancelDownload() {
        th.post(new Runnable() { public void run() {
            if (!downBusy) return;
            int doneTid = rvTid;
            abortRx(doneTid >= 0 ? doneTid : -1);   // 清接收状态；标记 tid 已结束，迟到残帧吞掉
            downBusy = false;
            unlockPriority();   // 用户取消下载：解锁并降回省电档
            flushDeferredCmds();
            Ui u = uiCb;
            if (u != null) {
                try { u.onProgress("download cancelled."); u.onDownloadState(false); } catch (Throwable ignored) { }
            }
            toast("下载已取消");
            logEvent("下载已取消 tid=" + doneTid);
        }});
    }

    /** 真正发起单个文件上传（假设已连接就绪） */
    void startUpload(final Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) { toast("无法打开文件"); upBusy = false; pumpUpload(); return; }
            byte[] all = readAll(in);   // 读全量以取得精确大小（小文件场景足够）
            in.close();
            if (all.length == 0) { toast("空文件"); upBusy = false; pumpUpload(); return; }
            String name = nameFromUri(uri);
            if (name == null || name.length() == 0) name = "upload.bin";
            lockHighPriority();   // 传输期提速：锁住 HIGH，屏蔽途中任何回执把它切回省电档
            upSent = 0;
            upTotal = all.length;
            upName = name;
            upStartMs = System.currentTimeMillis();
            byte[] hdr = ("PUT:" + name + ":" + all.length + "\n").getBytes(UTF8);
            wq.add(new W(cFile, hdr, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT));
            int maxW = Math.max(20, curMtu - 3);
            for (int off = 0; off < all.length; off += maxW) {
                int len = Math.min(maxW, all.length - off);
                byte[] piece = new byte[len];
                System.arraycopy(all, off, piece, 0, len);
                wq.add(new W(cFile, piece, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, len));
            }
            drain();
            logEvent("上传 " + name + " (" + all.length + " 字节) 已入队，等待 FILEDONE 回执");
        } catch (Throwable t) {
            toast("上传失败: " + t); logEvent("上传失败: " + t);
            upBusy = false; pumpUpload();
        }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    String nameFromUri(Uri uri) {
        String n = null;
        try {
            android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) { try { if (c.moveToFirst()) n = c.getString(0); } finally { c.close(); } }
        } catch (Throwable ignored) { }
        if (n == null) n = uri.getLastPathSegment();
        return n;
    }

    /** 收尾当前命令输出流：与正文空一行，复位 streaming 标记 */
    void finishStream() {
        if (streaming) { addLog("\n"); streaming = false; }
    }

    /** 断点 NACK 去重节流：同一 fromSeq 在 900ms 内只请求一次，
     *  杜绝"迟到帧每来一片就从头 NACK"与服务端清队列互相喂养形成活锁 */
    void requestMissing(int tid, int fromSeq, String why) {
        long now = System.currentTimeMillis();
        if (fromSeq == rvNackGuardSeq && now - rvNackGuardAt < 900) return;
        rvNackGuardSeq = fromSeq; rvNackGuardAt = now;
        sendNack(tid, fromSeq, why);
    }

    /** 请求重传：#retrans <tid> <fromSeq>（fromSeq==total 表示只补校验帧）。
     *  普通文本上限 5 次；大文件按片数放宽（至少 50 + total/50），避免丢几片就放弃。 */
    void sendNack(int tid, int fromSeq, String why) {
        th.removeCallbacks(chunkTimeout);
        int nackLimit = 5;
        if (rvTotal > 50) nackLimit = Math.max(50, rvTotal / 50 + 20);
        if (rvNacks >= nackLimit) {
            boolean wasDownload = downBusy;
            abortRx(tid);
            if (wasDownload) {
                // 文件下载失败：绝不把已收二进制当文本 dump（满屏乱码），给友好提示即可
                toast("下载失败：重传次数超限，请重试或调大收发节奏");
                logEvent("下载放弃: 重传 " + rvNacks + " 次仍未完成 tid=" + tid);
                notifyDownloadState(false);
            } else {
                appendResult("! Retransmit requested " + rvNacks + " times without completion (tid=" + tid + "), giving up this round");
            }
            unlockPriority();   // 接收放弃：解锁并降回省电档
            if (linkUp) info(readyText() + "(last transfer failed)");
            return;
        }
        rvNacks++;
        String msg = "#retrans " + tid + " " + fromSeq;
        logEvent("Retransmit requested: " + msg + "(" + why + ")");
        if (gatt != null && cCmd != null) {
            wq.add(new W(cCmd, msg.getBytes(UTF8)));
            drain();
        }
        th.postDelayed(chunkTimeout, 4000);
    }

    /** 中止/重置当前传输的接收状态；doneTid>=0 时标记该 tid 已结束，其迟到残帧一律吞掉 */
    void abortRx() { abortRx(-1); }
    void abortRx(int doneTid) {
        th.removeCallbacks(chunkTimeout);
        rx.reset(); rvPend.clear();
        rvTid = -1; rvTotal = 0; rvNext = 0; rvNacks = 0;
        rvDigest = null; rvDigestGot = false;
        rvNackGuardSeq = -1; rvNackGuardAt = 0; rvTimeouts = 0;
        rvDoneTid = doneTid;
        downBusy = false;   // 接收放弃/断链复位：下载状态一并清掉
    }

    /** 上传流水线：允许最多 UP_PIPELINE_MAX 个 WRITE_NO_RESPONSE 数据片同时在途，
     *  让控制器在一个连接事件里连续 flush 多片，显著提速（实测 10KB/s → 30~50KB/s 量级）。
     *  可在设置里关闭：关闭后退回严格单飞（一次只发一片，最稳）。
     *  安卓 BluetoothGatt 底层有 mDeviceBusy 串行锁：连发若被栈拒绝，issue() 会 addFirst + 25ms
     *  重试自动退回单飞，不丢片。应答写（命令/鉴权/文件头）始终严格单飞，不受此开关影响。 */
    static final int UP_PIPELINE_MAX = 4;

    void drain() {
        if (gatt == null || wq.isEmpty()) return;
        boolean pipeline = P.uploadPipeline(this);
        while (!wq.isEmpty()) {
            W w = wq.peek();
            // 取消上传中：尚未发出的 cFile 分片直接丢弃（不再入队重发）
            if (upCancel && w.ch == cFile) { wq.poll(); issue(w); continue; }
            // 应答写（命令/鉴权/PUT 头）必须等所有在途写落定，保持严格单飞、避免重排
            if (w.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                if (inflight > 0) return;
                wq.poll();
                issue(w);
                return;                       // 应答写一次一条，回到原节奏
            }
            // 上传数据片（WRITE_NO_RESPONSE）：关流水线→1 片在途；开流水线→补到 UP_PIPELINE_MAX
            int cap = pipeline ? UP_PIPELINE_MAX : 1;
            if (inflight >= cap) return;      // 在途已满：等 onCharWrite 回调再补
            wq.poll();
            if (!issue(w)) return;            // 栈忙：issue 已 addFirst 并安排 25ms 重试
            if (!pipeline) return;            // 单飞：发一片就停
            // 流水线：继续 while，把在途补到上限
        }
    }

    boolean issue(W w) {
        // 取消上传中：cFile 分片直接丢弃（已落盘的由服务端 abort 清理）
        if (upCancel && w.ch == cFile) { w.counted = true; return true; }
        w.ch.setWriteType(w.writeType);
        w.ch.setValue(w.v);
        if (!gatt.writeCharacteristic(w.ch)) {
            wq.addFirst(w);
            // 协议栈忙（上一包还在飞）：稍后由 onCharWrite 或本重试补发，属正常流控，不打错误日志
            th.postDelayed(retryDrain, 25);
            return false;
        }
        inflight++;
        if (w.upLen > 0 && !w.counted) {   // 上传数据片：累加已提交字节并回调进度
            w.counted = true;
            upSent += w.upLen;
            Ui u = uiCb;
            if (u != null) {
                int pct = upTotal > 0 ? (int) (upSent * 100 / upTotal) : 0;
                if (pct > 100) pct = 100;
                try { u.onProgress(upStatus(false, pct)); } catch (Throwable ignored) { }
            }
        }
        return true;
    }

    /** 上传进度行格式化：file.txt [2/5] Uploading: xxKB 80% (00:25) / Uploaded: file.txt [2/5] xxKB 00:59. */
    String upStatus(boolean done, int pct) {
        long kb = upSent / 1024;
        long sec = (System.currentTimeMillis() - upStartMs) / 1000;
        String idx = upTotalFiles > 1 ? " [" + (upTotalFiles - upQueue.size()) + "/" + upTotalFiles + "]" : "";
        if (done) {
            long tk = upTotal / 1024;
            return "uploaded: " + upName + idx + " " + tk + "KB " + mmss(sec) + ".";
        }
        return upName + idx + " uploading: " + kb + "KB " + pct + "% (" + mmss(sec) + ")";
    }

    /** 下载进度行格式化（与上传对称）：xxx Downloading: xxKB 80% (00:25) / Downloaded: xxx xxKB 00:59. */
    String downStatus(boolean done, int pct, long gotBytes) {
        long sec = (System.currentTimeMillis() - downStartMs) / 1000;
        if (done) {
            long tk = downTotal / 1024;
            return "downloaded: " + downName + " " + tk + "KB " + mmss(sec) + ".";
        }
        long kb = gotBytes / 1024;
        return downName + " downloading: " + kb + "KB " + pct + "% (" + mmss(sec) + ")";
    }

    static String mmss(long sec) {
        long m = sec / 60, s = sec % 60;
        if (m > 99) m = 99;
        return String.format("%02d:%02d", m, s);
    }

    void rememberKey() {
        String k = P.psk(this).trim();
        if (k.length() == 0) return;
        P.savePsk(this, k);
        logEvent("Key remembered (auto-filled on next launch; no key is built into the APK)");
    }

    // ------------------------------------------------------------------ 显示

    void info(String s) {
        curInfo = s;
        DbgLog.write(this, s);   // 调试日志落盘（开关关闭时为空操作）
        Ui u = uiCb;
        if (u != null) { try { u.onInfo(s); } catch (Throwable ignored) { } }
    }

    void appendResult(String text) {
        addLog("──── " + ts.format(new Date()) + " ────\n" + text + "\n\n");
    }

    void logEvent(String s) {
        addLog("· " + ts.format(new Date()) + "  " + s + "\n");
    }

    // 只写调试日志落盘文件（受 Debug 开关控制），不进界面日志缓冲、不回调 UI。
    // 用于 ConnParams 这类高频/诊断性信息，避免刷屏界面日志。
    void dbgOnly(String s) {
        DbgLog.write(this, "· " + ts.format(new Date()) + "  " + s + "\n");
    }

    void addLog(String s) {
        logBuf.addLast(s);
        while (logBuf.size() > LOG_MAX) logBuf.pollFirst();
        DbgLog.write(this, s);   // 调试日志落盘（开关关闭时为空操作）
        Ui u = uiCb;
        if (u != null) { try { u.onLog(s); } catch (Throwable ignored) { } }
    }

    void setBtn(String text, boolean enabled) {
        btnText = text;
        btnEnabled = enabled;
        // 断开按钮只在「真正连上」时可用：未连接 / 断开后 / 扫描中 / 连接中一律禁用。
        // 所有 8 处「断开后复位」都走 setBtn("Connect", true)，在此统一推导，不会遗漏。
        disconnectEnabled = !enabled && "Connected".equals(text);
        Ui u = uiCb;
        if (u != null) {
            try { u.onBtn(text, enabled); } catch (Throwable ignored) { }
            try { u.onDisconnectBtn(disconnectEnabled); } catch (Throwable ignored) { }
            // 断开/未连接态：传输按钮复位为 up/down，避免 Cancel 状态卡住
            if ("Connect".equals(text) && enabled) {
                upBusy = false; upCancel = false;
                downBusy = false;
                try { u.onUploadState(false); } catch (Throwable ignored) { }
                try { u.onDownloadState(false); } catch (Throwable ignored) { }
            }
        }
        // 所有复位到「未连接」的路径（扫描超时/失败/权限错/蓝牙关/主动断开）统一刷新通知栏，
        // 避免按钮已回到 Connect、通知栏却仍停在 "Scan/Connect …" 的不同步。
        // 这里只更新文字、不 stopForeground：扫描超时后服务仍需留在前台保活（供重试/自动重连）。
        if ("Connect".equals(text) && enabled) {
            notifTitle = "MyBleClient";
            updateNotif("Not connected");
        }
    }

    void toast(String s) {
        Ui u = uiCb;
        if (u != null) { try { u.onToast(s); } catch (Throwable ignored) { } }
        else logEvent(s);
    }

    // ------------------------------------------------------------ 前台通知

    /** 连接开始就进入前台：比原来"连上才挂通知"更稳，后台扫描期间进程也不会被降级 */
    void goForeground(String text) {
        try {
            startForeground(NOTIF_ID, build(text));
        } catch (Throwable t) {
            logEvent("Foreground notification failed: " + t);
        }
    }

    void updateNotif(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, build(text));
        } catch (Throwable ignored) { }
    }

    Notification build(String text) {
        Intent open = new Intent(this, MainActivity.class);
        // 点通知是"回到已有实例"（singleTop + CLEAR_TOP|SINGLE_TOP）：
        // 不新建 Activity；就算 Task 已被回收导致重建，重建后也只是重新 bind，
        // 从本服务拿全量状态，不会发起第二条连接。
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23
                        ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle(notifTitle)
         .setContentText(text == null || text.length() == 0 ? "Not connected" : text)
         .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
         .setContentIntent(pi)
         .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 24) b.setShowWhen(false);
        return b.build();
    }

    static String shortOf(UUID u) {
        String s = u.toString();
        return s.length() >= 8 ? s.substring(4, 8) : s;
    }
}
