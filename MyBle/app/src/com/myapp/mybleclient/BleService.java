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
        /** 上传开始/结束：用于把 Files 按钮切换成 Cancel（结束记得复位成 Files） */
        void onUploadState(boolean uploading);
        /** v3 目录列表回传：每行形如 "d 0 <name>" / "f <size> <name>" */
        void onFileList(java.util.ArrayList<String> items);
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
    final java.util.ArrayList<Uri> upQueue = new java.util.ArrayList<Uri>();  // 待上传队列（多选）
    final java.util.ArrayList<Uri> pendingShare = new java.util.ArrayList<Uri>();  // 系统分享进来、尚未就绪时暂存，连上后自动上传
    boolean upBusy = false;            // 当前是否有文件正在传
    boolean upCancel = false;          // 上传是否已被用户取消（用于丢弃剩余待发分片）
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
    int rvNacks = 0;           // 本传输已发重传请求次数（上限 10，防止无限循环）
    byte[] rvDigest = null;    // 期望的 SHA-256（total>1 时服务端会发校验帧）
    boolean rvDigestGot = false;
    boolean streaming = false;  // 命令输出流进行中（回声已打头，正文连续追加，不再每片加头/脚注）
    int streamChars = 0;       // 本次命令已接收的字符数（仅用于结束时的汇总行）

    int step = 0;              // 0 未开始 1 读电量 2 订阅电量 3 订阅RES 4 鉴权 9 就绪
    int lastBattery = -1;
    int lastRssi = 0;
    long lastRssiOkAt = 0;
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
    PowerManager.WakeLock wake;      // 可选：熄屏保活（设置 keep_wake 开启才持有）
    String want = "";
    boolean connectingByMac = false;     // 本轮是否按记住的 MAC 直连（失败时回退按 MAC 扫描匹配）
    String scanMatchMac = null;          // 非空=本轮扫描按 MAC 匹配（不依赖名称）；空=按名称匹配
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
            if (scanMatchMac != null) {
                // 按记住的 MAC 扫不到：多半是服务端换了地址，清掉旧记录回退按名称
                String old = scanMatchMac;
                scanMatchMac = null;
                P.clearMac(BleService.this);
                info("No device with saved MAC " + old + "; cleared it, retry by name '" + want + "'");
                th.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (gatt == null && !linkUp && !connecting && !scanning) beginConnect(want);
                    }
                }, 600);
                return;
            }
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
            // 按 MAC 直连卡住：可能地址类型/链路问题，清掉直连标记后按 MAC 扫描匹配重试
            boolean wasByMac = connectingByMac;
            hardClose();
            if (wasByMac && P.savedMac(BleService.this).length() > 0) {
                info("MAC direct stalled; retry by MAC scan");
                th.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (gatt == null && !linkUp && !connecting && !scanning) connectByMacScan();
                    }
                }, 600);
            }
        }
    };

    /** 半片兜底：4 秒没等到后续片。
     *  数据片全到但校验帧未到 → 只请求补发校验帧（fromSeq==total）；
     *  其他情况 → 显示半包并中止本次传输（迟到的重传片会触发从断点继续）。 */
    final Runnable chunkTimeout = new Runnable() {
        @Override public void run() {
            if (rvTid >= 0 && rvTotal > 1 && rvNext >= rvTotal && !rvDigestGot) {
                sendNack(rvTid, rvTotal, "digest frame missing");
                return;
            }
            if (rx.size() == 0) return;
            String half = new String(rx.toByteArray(), UTF8);
            abortRx();
            appendResult(half + "\n! Fragment incomplete (no follow-up within 4s; partial output shown)");
            info(linkUp ? readyText() + " (previous result incomplete)" : "Disconnected");
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
                long silent = System.currentTimeMillis() - lastRssiOkAt;
                long lim = P.rssiSilentSec(BleService.this) * 1000L;
                // 上传进行中：不让 RSSI 静默判死误杀上传（上传会持续占满链路、挤掉 RSSI 读取，
                // 导致 lastRssiOkAt 停滞，若照常判死会把传一半的文件腰斩）
                if (lim > 0 && (lastRssiOkAt == 0 || silent > lim)
                        && !(upBusy || !upQueue.isEmpty())) {
                    boolean auto = P.autoConnect(BleService.this);
                    info(auto ? "Link may be dead (RSSI silent " + (silent / 1000) + "s) - reconnecting"
                              : "Link may be dead (RSSI silent " + (silent / 1000) + "s) - please reconnect manually");
                    logEvent("Heartbeat #" + hbTicks + ": RSSI silent " + (silent / 1000)
                            + "s (threshold " + (lim / 1000) + "s), readRemoteRssi rejected "
                            + rssiBusy + " times -> releasing");
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
        if (name == null || name.trim().length() == 0) { toast("Please enter the advertised name"); return; }
        want = name.trim();
        if (adapter == null || !adapter.isEnabled()) { info("Bluetooth is off; please turn it on first"); setBtn("Connect", true); return; }
        // 优先按已记住的 MAC 直连，跳过扫描（更快、更稳）；无记录则按名称扫描
        String mac = P.savedMac(this);
        if (mac != null && mac.length() > 0) {
            connectByMac(mac);
        } else {
            connectingByMac = false;
            scanMatchMac = null;
            startScan();
        }
    }

    /** 按记住的 MAC 直连：不经扫描，直接向该地址发起 GATT 连接 */
    void connectByMac(String mac) {
        BluetoothDevice d = null;
        try { d = adapter.getRemoteDevice(mac); } catch (Throwable t) { }
        if (d == null) {
            // 地址串非法（理论上不会）：清掉坏记录，回退按名称
            P.clearMac(this);
            connectingByMac = false;
            scanMatchMac = null;
            info("Bad saved MAC, cleared; scan by name instead");
            startScan();
            return;
        }
        connectingByMac = true;
        scanMatchMac = null;
        logBuf.clear();
        Ui u = uiCb;
        if (u != null) { try { u.onLogReset(""); } catch (Throwable ignored) { } }
        logEvent("Connect by saved MAC " + mac + " (skip scan)");
        connectDevice(d, 0);
    }

    /** 直连失败后的兜底：仍然扫描，但按 MAC 匹配广播设备（不依赖名称，地址类型也正确） */
    void connectByMacScan() {
        String mac = P.savedMac(this);
        if (mac == null || mac.length() == 0) {
            connectingByMac = false;
            scanMatchMac = null;
            startScan();
            return;
        }
        connectingByMac = false;
        scanMatchMac = mac;
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
        if (scanMatchMac != null) {
            info("Scanning, matching MAC " + scanMatchMac + " (skip name) …");
        } else {
            info("Scanning for all devices (looking for '" + want + "') …");
        }
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
                    // —— 命中目标才自动连接：有 MAC 记录时按 MAC 匹配（不看名称），否则按名称 ——
                    if (scanMatchMac != null) {
                        if (!addr.equalsIgnoreCase(scanMatchMac)) return;
                    } else if (name == null || !name.equalsIgnoreCase(want)) {
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
    };

    void onConnState(BluetoothGatt g, int status, int newState) {
        if (g != gatt) { logEvent("Ignore stale gatt object connect event state=" + newState); return; }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            linkUp = true;
            String addr = g.getDevice().getAddress();
            logEvent("Connected " + addr);  // 新会话起点标记
            // 首次（或换设备）连上后记住服务端 MAC，供以后按地址直连
            String saved = P.savedMac(this);
            if (addr != null && addr.length() > 0 && !addr.equalsIgnoreCase(saved)) {
                P.saveMac(this, addr);
                logEvent("Remembered server MAC " + addr + " for direct connect");
            }
            connectingByMac = false;   // 连上即完成本轮直连使命
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
            // 按 MAC 直连但压根没连上（服务端刚重启/链路瞬断）：回退按 MAC 扫描匹配
            boolean directFailed = connectingByMac
                    && status != BluetoothGatt.GATT_SUCCESS && status != 19;
            if (directFailed) {
                connectingByMac = false;
                info("MAC direct failed status=" + status + "; retry by MAC scan");
                th.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (gatt == null && !linkUp && !connecting && !scanning) connectByMacScan();
                    }
                }, 600);
            }
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
            lastRssiOkAt = System.currentTimeMillis();
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
            lastRssiOkAt = System.currentTimeMillis();
            th.removeCallbacks(heartbeat);
            th.postDelayed(heartbeat, 1000);
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER); // 空闲省电
            info(readyText());
            updateNotif(readyText());
            logEvent("Ready(" + readyText() + "): " + (cBatt != null ? "Battery Level readable/subscribable; " : "no battery service; ")
                    + (cCmd != null ? "commandchannel available" : "no command channel"));
            flushPendingShare();   // 系统分享进来的文件：链路就绪后自动开始上传
        }
    }

    /**
     * 连接参数省电：就绪/空闲时请求 LOW_POWER（连接间隔拉长到 ~150-225ms，射频少醒），
     * 发命令前临时升 BALANCED（~40ms）保住交互延迟，结果收完再降回去。
     * 服务端是外设端，无法自己请求连接参数，只能靠客户端这头提。
     */
    void setConnPriority(int mode) {
        try {
            if (gatt != null) gatt.requestConnectionPriority(mode);
        } catch (Throwable ignored) { }
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
            if (crc16(v, 0, v.length - 2) != crc) {          // 单帧 CRC 失败：按缺片请求该帧
                if (tid == rvTid) {
                    int seq = ((v[3] & 0xFF) << 8) | (v[4] & 0xFF);
                    sendNack(tid, seq, "CRC16 check failed");
                }
                return;
            }
            int seq = ((v[3] & 0xFF) << 8) | (v[4] & 0xFF);
            int total = ((v[5] & 0xFF) << 8) | (v[6] & 0xFF);
            if (tid != rvTid) {                              // 新传输：重置接收状态
                rx.reset();
                rvTid = tid; rvTotal = total; rvNext = 0; rvNacks = 0;
                rvDigest = null; rvDigestGot = false;
            }
            if (seq < rvNext) return;                        // 重传窗口里的重复片，忽略
            if (seq > rvNext) {                              // 序号断档：缺号立即请求断点重传
                sendNack(tid, rvNext, "missing seq " + rvNext + "(received " + seq + ")");
                return;
            }
            try { rx.write(v, 7, v.length - 9); } catch (Throwable ignored) { }
            rvNext++;
            if (rvTotal > 1) th.postDelayed(chunkTimeout, 4000);
            info("Receiving... chunk " + rvNext + "/" + rvTotal + ", got " + rx.size() + " bytes");
            if (rvTotal == 1) completeRx();                  // 单片传输：CRC 已保证完整
            return;
        }
        if (mark == 'D') {
            if (v.length < 37) return;
            int crc = ((v[v.length - 2] & 0xFF) << 8) | (v[v.length - 1] & 0xFF);
            if (crc16(v, 0, v.length - 2) != crc) return;    // 坏校验帧：等 chunkTimeout 补请求
            int tid = ((v[1] & 0xFF) << 8) | (v[2] & 0xFF);
            if (tid == rvTid && rvNext >= rvTotal && !rvDigestGot && rvTotal > 1) {
                rvDigest = new byte[32];
                System.arraycopy(v, 3, rvDigest, 0, 32);
                rvDigestGot = true;
                verifyDigest();
            }
            return;
        }
        if (mark == '!') {
            abortRx();
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
        rx.reset();
        rvNext = 0;
        rvDigestGot = false;
        rvDigest = null;
        sendNack(rvTid, 0, "SHA-256 mismatch, full retransmit");
    }

    /** 传输完成：一次性 UTF-8 解码输出（跨帧字符不坏） */
    void completeRx() {
        th.removeCallbacks(chunkTimeout);
        byte[] data = rx.toByteArray();
        abortRx();
        // v3 文件传输：FILD: 头=下载数据，FLST: 头=目录列表，走专用处理（字节安全，不经 UTF-8 解码）
        if (data.length >= 5 && data[0] == 'F' && data[1] == 'I' && data[2] == 'L') {
            if (data.length > 5 && data[3] == 'D' && data[4] == ':') { handleFileDownload(data); return; }
            if (data.length > 6 && data[3] == 'S' && data[4] == 'T' && data[5] == ':') { handleFileList(data); return; }
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
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
            String body = wholeTrim.substring("FILEDONE:".length()).trim();
            String[] p = body.split(":");
            String name = p.length > 0 ? p[0] : "?";
            String size = p.length > 2 ? p[2] : "?";
            upName = name;
            Ui u = uiCb;
            if (u != null) { try { u.onProgress(upStatus(true, 100)); } catch (Throwable ignored) { } }  // 完成行：Uploaded: ...，不弹 toast
            logEvent("上传完成: " + name + " (" + size + " 字节)");
            upBusy = false;                                  // 本文件传完，启动队列里的下一个
            th.post(new Runnable() { public void run() { pumpUpload(); } });
            return;
        }
        if (wholeTrim.startsWith("FILEABORT:")) {   // v3 上传中止回执：服务端已关闭流并删除部分文件
            String nm = wholeTrim.substring("FILEABORT:".length()).trim();
            upCancel = false; upBusy = false; upQueue.clear();
            Ui u = uiCb;
            if (u != null) {
                try { u.onProgress((nm.isEmpty() ? "Upload" : "Upload " + nm) + " cancelled."); u.onUploadState(false); }
                catch (Throwable ignored) { }
            }
            logEvent("上传已取消（服务端确认）: " + (nm.isEmpty() ? "(进行中文件)" : nm));
            return;
        }
        if (wholeTrim.startsWith("!")) {          // 服务端文本错误（!auth:denied / !timeout / !exec-failed / !cmd-too-long / !denied）
            finishStream();                        // 先收尾当前输出流（若正在流式显示）
            appendResult("✗ " + whole.trim().substring(1));
            logEvent("Server: " + whole.trim());
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
            if (linkUp) info(readyText());
            return;
        }
        if ("auth:ok".equals(wholeTrim)) { rememberKey(); authAwaiting = false; th.removeCallbacks(authWatchdog); setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER); if (linkUp) info(readyText()); return; }
        if (wholeTrim.startsWith("#rc=")) {        // 命令正常结束帧：打印一条汇总，替代原先每片一条的「接收完成」脚注
            finishStream();
            int code = -1;
            try { code = Integer.parseInt(wholeTrim.substring(4).trim()); } catch (Throwable ignored) { }
            addLog("· " + ts.format(new Date()) + " Command finished (exit code " + code + "), received " + streamChars + " chars (CRC/SHA verified)\n");
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
            if (nl < 0) { logEvent("下载失败：FILD 头无换行"); return; }
            String hdr = new String(data, 0, nl, UTF8);
            String[] h = hdr.split(":");                // FILD name size sha
            if (h.length < 4) { logEvent("下载失败：FILD 头格式错"); return; }
            String name = h[1];
            long size = Long.parseLong(h[2].trim());
            String sha = h[3].trim();
            byte[] fileBytes = java.util.Arrays.copyOfRange(data, nl + 1, data.length);
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            java.io.File out = new java.io.File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(fileBytes);
            fos.close();
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
            String got = sha256hex(fileBytes);
            boolean ok = got.equalsIgnoreCase(sha);
            if (ok) {
                toast("下载完成: " + name + " (" + size + " 字节)");
                logEvent("下载完成 " + name + " -> " + out.getAbsolutePath() + " 校验OK");
            } else {
                toast("下载校验失败: " + name);
                logEvent("下载校验失败 " + name + " 期望 " + sha + " 实得 " + got);
            }
            if (linkUp) info(readyText());
        } catch (Throwable t) {
            logEvent("下载失败: " + t);
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
        }
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
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);   // 传输期提速
            logEvent("请求下载: " + path.trim());
            BleService.this.run("fs get " + path.trim());
            th.post(new Runnable() { public void run() { setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } });
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
                // 未连接则触发自动连接（按记住的 MAC / 服务器名）
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
            notifyUploadState(false);
            flushDeferredCmds();   // 即便上传被取消，也让暂停的命令跑起来
            return;
        }
        if (upQueue.isEmpty()) {
            upBusy = false;
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
        // 上传开始/结束都刷新一次 RSSI 时间戳：开始=避免刚进入上传就被旧静默值误杀；
        // 结束=避免长上传后 lastRssiOkAt 过旧、下一拍心跳立刻误判链路死亡
        lastRssiOkAt = System.currentTimeMillis();
        Ui u = uiCb;
        if (u != null) { try { u.onUploadState(uploading); } catch (Throwable ignored) { } }
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
            flushDeferredCmds();   // 上传已结束，让暂停的命令跑起来
            String nm = upName.isEmpty() ? "" : upName + " ";
            Ui u = uiCb;
            if (u != null) {
                try { u.onProgress(nm + "Upload cancelled."); u.onUploadState(false); } catch (Throwable ignored) { }
            }
            logEvent("上传已取消: " + (upName.isEmpty() ? "(进行中文件)" : upName));
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
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);   // 传输期提速
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

    /** 请求重传：#retrans <tid> <fromSeq>（fromSeq==total 表示只补校验帧）。上限 5 次 */
    void sendNack(int tid, int fromSeq, String why) {
        th.removeCallbacks(chunkTimeout);
        if (rvNacks >= 5) {
            abortRx();
            appendResult("! Retransmit requested 5 times without completion (tid=" + tid + "), giving up this round");
            setConnPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER);
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

    /** 中止/重置当前传输的接收状态 */
    void abortRx() {
        th.removeCallbacks(chunkTimeout);
        rx.reset();
        rvTid = -1; rvTotal = 0; rvNext = 0; rvNacks = 0;
        rvDigest = null; rvDigestGot = false;
        rvDoneTid = -1;   // 任何中止都把"已结束"标记清掉；完成/放弃/超时处会单独置为对应 tid
    }

    /** 单飞发数：安卓 BluetoothGatt 同一时刻只允许 1 个写请求在飞（底层 mDeviceBusy 串行锁），
     *  所以每发一个包、等 onCharWrite 回调再补下一个。上传数据用 WRITE_NO_RESPONSE，
     *  配合 HIGH 优先级连接间隔，控制器会在连接事件里连续 flush，得到稳定吞吐。
     *  应答写（命令/鉴权）同样严格单飞，避免命令通道重排或冲掉应答流控。 */
    void drain() {
        if (gatt == null || wq.isEmpty()) return;
        W w = wq.peek();
        // 取消上传中：尚未发出的 cFile 分片直接丢弃（不再入队重发）
        if (upCancel && w.ch == cFile) { wq.poll(); issue(w); return; }
        // 应答写必须等所有在途写落定，保持严格单飞、避免重排
        if (w.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT && inflight > 0) return;
        wq.poll();
        issue(w);
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
            return "Uploaded: " + upName + idx + " " + tk + "KB " + mmss(sec) + ".";
        }
        return upName + idx + " Uploading: " + kb + "KB " + pct + "% (" + mmss(sec) + ")";
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
