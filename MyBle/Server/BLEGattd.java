import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.ParcelUuid;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * blegatt —— 不打包 APK 的 Android BLE 外围设备命令行工具。
 *
 * 以 app_process 直接加载 dex 运行（root 身份），提供：
 *   1) LE 可连接广播（可配 name / 厂商数据 / service UUID / tx power）
 *   2) GATT server：读特征、写特征（可当命令通道）、通知特征
 *   3) 命令执行通道（写 CMD 特征远程执行 shell，结果走 RES 通知，支持 PSK 鉴权）
 *
 * 用法见 --help。
 */
public class BLEGattd {

    static final String TAG = "blegatt";

    // ---- 版本号（BUILD_DATE 由构建脚本在编译时注入；未注入则运行时按当前日期生成）----
    static final String BUILD_DATE = "__BUILD_DATE__";
    static String serverVersion() {
        // 优先读 jar 内 /VERSION 资源：该资源由构建脚本写入，运行时直接从 jar 文件读取，
        // 不经过 ART 对 dex 的缓存，因此替换 jar 后版本一定是最新的（根治"替换 jar 仍显示旧版本"的问题）。
        String v = readVersionResource();
        if (v != null && !v.isEmpty()) return v;
        // 兜底：BUILD_DATE 由构建脚本在编译时注入；未注入则运行时按当前日期生成
        String d = BUILD_DATE.equals("__BUILD_DATE__")
                ? new java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.US).format(new java.util.Date())
                : BUILD_DATE;
        return "2.2." + d;
    }
    static String readVersionResource() {
        try {
            java.io.InputStream in = BLEGattd.class.getClassLoader().getResourceAsStream("VERSION");
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8").trim();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 默认值（全部可用命令行覆盖）----
    static String advName = "r4a";
    static boolean nameExplicit = false;      // --name 显式给出才改 adapter 名
    static boolean echoOnWrite = false;       // 写特征内容回弹成通知（便于验证通知链路）
    static String svcUuidStr = "8e7a1f01-2b3c-4d5e-6f70-8192a3b4c5d6";
    // 标准 Battery Service：0x180F / Battery Level 0x2A19（uint8 0~100）
    static String battSvcUuidStr = "0000180f-0000-1000-8000-00805f9b34fb";
    static String battLvlUuidStr = "00002a19-0000-1000-8000-00805f9b34fb";
    static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";
    static int batteryNotifySec = 60;
    static String advSvcUuid = "0000180f-0000-1000-8000-00805f9b34fb"; // 默认放进 AD：Battery 服务 180f
    static String writeCharUuidStr = "8e7a1f03-2b3c-4d5e-6f70-8192a3b4c5d6";
    static String notifyCharUuidStr = "8e7a1f04-2b3c-4d5e-6f70-8192a3b4c5d6";
    static String fileCharUuidStr = "8e7a1f06-2b3c-4d5e-6f70-8192a3b4c5d6";   // v3 文件收发（字节安全，二进制直写）
    static int mfgId = 0xABCD;
    static byte[] mfgData = new byte[0];    // 默认不放厂商数据（要放用 --mfg / --adv-text）
    static boolean connectable = true;
    static boolean includeNameInAdv = true;
    static boolean includeTxPower = true;
    static boolean includeServiceUuidInAdv = true; // 默认把 180f 放进广播
    static boolean advertiseOnly = false;   // 只广播，不起 GATT
    static boolean execOnWrite = false;     // 写特征当命令执行（危险，默认关闭）
    static String psk = null;               // 预共享密钥：非空则命令通道强制先 AUTH
    static int maxOutBytes = 10240;         // 命令输出上限（10KB），超出截断；可用 --max-out 调整
    static int cmdTimeoutSec = 10;          // 单条命令最长执行时间
    static int streamFlushBytes = 256;      // 流式下发：累计该字节数冲刷一次；首段立即冲刷以取消客户端 5s 守卫
    // 通知（confirm=false）在很多 Android 协议栈上 onNotificationSent 不可靠/偏慢，
    // 背靠背快发会被控制器通知缓冲区冲掉中间片。故默认"匀速节奏发送"：
    // 每片之间固定留 paceMs 间隔，不依赖回调，最坏也只是走重传，链路更稳。
    static int sendAckTimeoutMs = 2000;
    static int paceMs = 50;                 // 默认片间隔（匀速节奏模式），放宽以稳链路
    static int txPaceMs = 40;               // 传输帧最小间隔下限：片间至少 40ms，连发易被协议栈丢包
    static final int MAX_QUEUE_ITEMS = 200; // 每设备待发片上限
    static final int MAX_CMD_BYTES = 65536; // 分片命令累计上限，超限丢弃防堆积
    /** 分片命令拼接缓冲：客户端 MTU 小于命令长度时按 '>'/（'<' 末片）分片写入 */
    static final Map<String, java.io.ByteArrayOutputStream> cmdAccum = new HashMap<>();
    // ---- 文件收发（v3）----
    static final String FILE_BASE = "/sdcard/Download/blegatt_upload";   // 上传落地目录 & fs ls/get 基准目录
    static final Map<String, java.io.OutputStream> fileOut = new HashMap<>();   // addr -> 上传落盘流
    static final Map<String, Long> fileRecvLen = new HashMap<>();
    static final Map<String, Long> fileRecvTarget = new HashMap<>();
    static final Map<String, String> fileRecvName = new HashMap<>();
    // ---- 可靠传输（v2 T 帧）：断点/全量重传所需的服务端侧状态 ----
    static final Map<String, byte[][]> txChunks = new HashMap<>(); // 当前传输的全部分片
    static final Map<String, byte[]> txDigest = new HashMap<>();   // 当前传输 SHA-256（total>1 才有）
    static final Map<String, Integer> txId = new HashMap<>();      // 传输号，每次传输 +1
    static String onConnectCmd = null;      // 客户端一连上就自动执行的命令
    static int logOutBytes = 1024;          // 设备日志里打印 exec 输出的上限，0=不打印
    static String authCharUuidStr = "8e7a1f05-2b3c-4d5e-6f70-8192a3b4c5d6";
    static String advText = null;           // 广播包载荷模板（渲染后写入厂商数据）
    static String srText = null;            // scan response 载荷模板（独立的第二个 31 字节）
    static int refreshSec = 0;              // >0 时按模板自动刷新广播内容
    // 省电默认：低占空比广播（约 1s 间隔）+ 中低发射功率。
    // 发现速度会慢零点几秒，但射频常开的功耗大头直接砍掉；要快可用 --adv-mode/--tx 调回
    static int advMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
    static int advTx = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
    static boolean keepAdv = true;    // 默认连接后继续广播：反复 stop/start 会让栈进入"假活"（扫得到但连不上），常开最稳；如需省电可改回 false
    static boolean subDump = false;   // true=pushBattery 时打印订阅表诊断（诊断用，平时关）
    static int advTimeoutSec = 0;
    // 广播保活：强制重开周期(秒)，0=关闭。>0 时每 N 秒 stop+start 一次广播，兜
    // "framework 认为在发、控制器实际没发"的长待机假活；每次重开会换一次 RPA。
    // 命令行 --adv-keepalive-force <小时> 可覆盖（单位是小时，内部 ×3600 转秒；建议 1~2，别低于 0.25）
    static int advForceSec = 0;

    // ---- 运行态 ----
    static BluetoothAdapter adapter;
    static volatile boolean btMonitorStop = false;   // 置 true 可停掉蓝牙监控线程
    static BluetoothLeAdvertiser advertiser;
    static BluetoothGattServer server;
    static BluetoothGattServer serverBatt;      // --split-servers：Battery 独立 server 实例
    static boolean battFirst = false;           // --batt-first：先注册 Battery 服务
    static boolean splitServers = false;        // --split-servers：Battery 用独立 server
    static GattCb gattCb;
    static android.os.Handler mainHandler;
    static BluetoothGattCharacteristic notifyChar;
    static BluetoothGattCharacteristic battChar;
    static BluetoothGattCharacteristic writeChar;
    static BluetoothGattCharacteristic authChar;
    static BluetoothGattCharacteristic fileChar;   // v3 文件上传专用（二进制直写，不用 '>'/'<' 分片）
    static PowerManager.WakeLock cmdWake = null;   // 命令执行期 Partial-WakeLock：防浅休眠拖慢/误杀，执行完释放
    // 每连接的鉴权/MTU/待发通知队列（全部只在主线程访问，除了 executor 线程通过 post 投递）
    static final java.util.Set<String> authed = new java.util.HashSet<String>();
    static final Map<String, byte[]> nonceByAddr = new HashMap<>();   // AUTH 挑战：每连接一次性 nonce（挑战-响应，防明文/重放）
    static final java.util.Set<String> connHandled = new java.util.HashSet<String>();
    static final Map<String, Integer> mtuOf = new HashMap<>();
    static final Map<String, Deque<NotifyItem>> queues = new HashMap<>();
    static final Map<String, Boolean> sending = new HashMap<>();
    // 发送闸门加固：序号对账（防"回调先到、后置位"把标志永久卡成在途）+ 超时自愈
    static final Map<String, Integer> sendSeq = new HashMap<>();    // 已提交的最大序号
    static final Map<String, Integer> ackSeq = new HashMap<>();     // 已回调到的序号
    static final Map<String, Long> lastSendAt = new HashMap<>();    // 本次发送时刻
    static final Map<String, Integer> lostAck = new HashMap<>();    // 连续丢失回调次数
    static final Map<String, Boolean> trustSentCb = new HashMap<>();// false=该设备不再等回调，改节流
    static final Map<String, Long> stallLogAt = new HashMap<>();    // "仍在途"日志节流
    // 泵送全局锁：onNotificationSent 在 binder 线程回调，与主线程的补泵并发时，
    // 非线程安全的 ArrayDeque 可能被并发取出——同一片发两次（客户端看到内容重复）
    // 或迭代器损坏跳片（客户端丢 513 字节）。所有入队/出队/泵送都必须持锁。
    static final Object PUMP_LOCK = new Object();
    static java.util.concurrent.ExecutorService cmdExec;
    static final Object VALUE_LOCK = new Object();
    static byte[] notifyValue = "hello".getBytes();
    static byte[] lastWrite = new byte[0];
    static final Map<String, Boolean> cccdEnabled = new HashMap<>();
    // 订阅类型：true=客户端按 INDICATION(confirm=true) 订阅；false/缺省=NOTIFY(无确认)。
    // INDICATION 下 Android GATT 栈会等客户端回 ATT 确认后才触发 onNotificationSent，
    // 由协议栈保证"一次只一片在途"，根治 NOTIFY 背靠背冲掉控制器缓冲区导致的丢片。
    static final Map<String, Boolean> cccdIndicate = new HashMap<>();
    static final Map<String, BluetoothDevice> connected = new HashMap<>();
    static final Deque<String> events = new ArrayDeque<>();
    static AdvertiseCallback advCallback;
    static volatile boolean advActive = false;
    // --debug：把 log() 同步追加到 Download/blegatt.log（app_process 直跑有 sdcard 权限）。
    static boolean debugLog = false;
    static java.io.Writer debugWriter = null;
    static final Object DEBUG_LOCK = new Object();
    static volatile int lastAdvError = 0;

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "-h") || hasFlag(args, "--help")) { usage(); return; }
        parseInto(args);

        log("启动 pid=" + Process.myPid() + " uid=" + android.system.Os.getuid());
        log("========== " + serverVersion() + " ==========");
        log("协议 v2 可靠传输已启用（T 帧 + CRC16 + SHA-256 + 断点重传，片间 "
                + txPaceMs + "ms）——若客户端收不到结果，先确认两端都是 v2");

        // 框架回调都要投递到当前线程的 Looper，因此这里必须在主线程准备并开启消息循环
        Looper.prepareMainLooper();
        mainHandler = new android.os.Handler(Looper.myLooper());

        // 主线程负责消息循环；初始化与交互放到 worker 线程，避免自己等自己
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                final CountDownLatch ready = new CountDownLatch(1);
                final CountDownLatch advDone = new CountDownLatch(1);
                // 先取 adapter 并启动蓝牙监控（蓝牙关闭时它会每 5 秒自动尝试打开，程序运行期无限重试）
                adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) { log("FATAL getDefaultAdapter()==null，bluetooth_manager 不可达"); System.exit(1); }
                startBtMonitor();
                // 等待蓝牙被监控线程打开（最多等约 2 分钟，监控每 5 秒重试一次）
                int w = 0;
                while (!adapter.isEnabled() && w < 120) { sleep(1000); w++; }
                if (!adapter.isEnabled()) { log("FATAL 蓝牙仍未开启（监控已持续重试）"); System.exit(1); }
                log("蓝牙已就绪，开始初始化 GATT…");
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { setupGatt(); } catch (Throwable t) {
                            log("FATAL setup: " + t); t.printStackTrace(); System.exit(1);
                        }
                        ready.countDown();
                    }
                });
                try {
                    if (!ready.await(30, TimeUnit.SECONDS)) { log("setup 超时"); System.exit(1); }
                } catch (InterruptedException ignored) { }

                mainHandler.post(new Runnable() {
                    @Override public void run() { startAdvertising(advDone); }
                });
                try {
                    if (!advDone.await(8, TimeUnit.SECONDS)) log("广播回调超时（可能仍已起广播）");
                } catch (InterruptedException ignored) { }

                if (!advActive) {
                    log("!! 广播未成功，lastAdvError=" + lastAdvError
                            + " (" + advErrName(lastAdvError) + ")");
                }
                startRefresh();
                startAdvKeepalive();   // 广播保活：空闲时定期强制重开，兜长待机假活
                startBatteryPoller();
                startDrainTicker();
            }
        }, "worker");
        worker.setDaemon(true);
        worker.start();

        Looper.loop();   // 阻塞主线程，派发所有框架回调
    }

    // ------------------------------------------------------------------ GATT

    /** 单实例自检：两个实例会各自收到同一连接的回调，还会互抢广播实例 */
    static void checkSingleInstance() {
        java.io.File f = new java.io.File("/data/local/tmp/blegatt.pid");
        try {
            if (f.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[32];
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    int pid = Integer.parseInt(new String(buf, 0, n).trim());
                    if (pid != Process.myPid() && new java.io.File("/proc/" + pid).exists()) {
                        log("!! 已有另一个实例在跑 pid=" + pid
                                + "，请先 kill -9 " + pid + " 再起，否则回调会重复、广播会互抢");
                    }
                }
            }
        } catch (Throwable t) { /* 忽略：仅提示用 */ }
        try {
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(Integer.toString(Process.myPid()));
            w.close();
        } catch (Throwable t) { }
    }

    /**
     * 蓝牙监控线程：只要程序还在跑，就持续保证蓝牙是开的。
     * 检测到蓝牙关闭时，等 5 秒后尝试 adapter.enable()，失败就无限重试（不再限制次数）。
     * 蓝牙从「关→开」恢复后，整体重建 GATT server（旧实例已被框架清掉，只重启广播会让
     * 客户端写特征报 status=1），重建失败每约 2 秒自动重试，成功后重启广播（恢复可被发现）。
     */
    static void startBtMonitor() {
        Thread t = new Thread(new Runnable() {
            int prev = -1;   // 上一次观测到的蓝牙状态
            @Override public void run() {
                while (!btMonitorStop) {
                    try {
                        if (adapter == null) { prev = -1; sleep(2000); continue; }
                        int st = adapter.getState();
                        boolean on = adapter.isEnabled();
                        if (on) {
                            // 刚从关闭/关闭中恢复 → 旧 GATT server 已被框架清掉，必须整体重建；
                            // gattBroken=上次重建失败，同样自动重试
                            if (prev == BluetoothAdapter.STATE_OFF
                                    || prev == BluetoothAdapter.STATE_TURNING_OFF
                                    || gattBroken) {
                                if (server != null || gattBroken) {
                                    log("蓝牙已恢复，重建 GATT server 并重启广播…");
                                    mainHandler.post(new Runnable() {
                                        @Override public void run() {
                                            try {
                                                if (rebuildGatt()) startAdvertising(null);
                                            } catch (Throwable t) { log("重建异常: " + t); }
                                        }
                                    });
                                } else {
                                    log("蓝牙已恢复（GATT 尚未就绪，等待初始化完成）");
                                }
                            }
                            prev = st;
                            sleep(2000);
                            continue;
                        }
                        // 关闭或正在关闭：等 5 秒后尝试打开，失败就下一轮继续（无限重试）
                        prev = st;
                        log("蓝牙未开启，5 秒后将尝试自动打开…");
                        sleep(5000);
                        try { adapter.enable(); }
                        catch (Throwable e) { log("adapter.enable() 异常: " + e); }
                    } catch (Throwable e) {
                        log("btMonitor 异常: " + e);
                    }
                }
            }
        }, "bt-monitor");
        t.setDaemon(true);
        t.start();
    }

    static void setupGatt() {
        checkSingleInstance();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { fail("getDefaultAdapter()==null，bluetooth_manager 不可达"); }
        if (!adapter.isEnabled()) {
            log("setup 时蓝牙仍未就绪，等待监控线程打开…");
            int g = 0;
            while (!adapter.isEnabled() && g < 60) { sleep(1000); g++; }
            if (!adapter.isEnabled()) fail("蓝牙未开启：监控线程也无法打开，请手动 adb shell svc bluetooth enable");
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) { fail("getBluetoothLeAdvertiser()==null"); }

        log("adapter=" + adapter.getName() + " " + adapter.getAddress()
                + " state=" + adapter.getState()
                + " le=" + adapter.isEnabled());

        if (advertiseOnly) { log("只广播模式：跳过 GATT server"); return; }

        Context ctx = systemContext();
        if (ctx == null) { fail("无 Context，无法 openGattServer（ActivityThread.systemMain 不可用）"); }
        // 命令执行期唤醒锁：系统进程默认具备 WAKE_LOCK 权限；拿不到就降级（不持锁，功能仍正常）
        try {
            cmdWake = ((PowerManager) ctx.getSystemService(Context.POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blegatt:cmd");
            cmdWake.setReferenceCounted(false);   // 单线程串行执行，非引用计数更稳妥
            log("命令唤醒锁(PARTIAL_WAKE_LOCK) 已就绪");
        } catch (Throwable t) {
            cmdWake = null;
            log("命令唤醒锁初始化失败（" + t + "），降级为不持锁");
        }

        rebuildGatt();
    }

    static volatile boolean gattBroken = false;   // GATT 重建失败标记：监控线程据此自动重试
    static volatile boolean rebuilding = false;   // 防重叠重建（mainHandler 串行，双保险）

    /**
     * 打开/重建 GATT server 并注册服务。蓝牙「关→开」后框架会清掉旧 server 的服务注册
     * （旧 BluetoothGattServer/advertiser 句柄全部失效，客户端写特征会得到 status=1），
     * 因此恢复时必须整体重建，而不是只重启广播。须在主 Looper 线程调用。
     */
    static boolean rebuildGatt() {
        if (rebuilding) return false;
        rebuilding = true;
        try {
            Context ctx = systemContext();
            if (ctx == null) { log("重建失败：无 Context"); gattBroken = true; return false; }
            BluetoothManager mgr = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mgr == null) { log("重建失败：BLUETOOTH_SERVICE 不可达"); gattBroken = true; return false; }

            // 蓝牙重启后旧实例与连接态全部失效，先整体清理
            try { if (cmdExec != null) cmdExec.shutdownNow(); } catch (Throwable ignored) { }
            cmdExec = null;
            try { if (server != null) server.close(); } catch (Throwable ignored) { }
            try { if (serverBatt != null) serverBatt.close(); } catch (Throwable ignored) { }
            server = null; serverBatt = null;
            connected.clear(); authed.clear(); nonceByAddr.clear(); connHandled.clear();
            cccdEnabled.clear(); cccdIndicate.clear(); mtuOf.clear();
            queues.clear(); sending.clear(); cmdAccum.clear();
            txChunks.clear(); txDigest.clear(); txId.clear();
            advActive = false;

            gattCb = new GattCb();
            server = mgr.openGattServer(ctx, gattCb);
            if (server == null) {
                log("重建失败：openGattServer 返回 null（蓝牙可能尚未就绪，稍后自动重试）");
                gattBroken = true; return false;
            }
            if (splitServers) {
                serverBatt = mgr.openGattServer(ctx, gattCb);
                if (serverBatt == null) {
                    log("重建失败：Battery 的 openGattServer 返回 null（稍后自动重试）");
                    gattBroken = true; return false;
                }
                log("--split-servers：Battery 挂在独立 GATT server 实例上");
            }

            BluetoothGattService cmdSvc = buildCmdService();
            BluetoothGattService basSvc = buildBatteryService();
            BluetoothGattServer basHost = (serverBatt != null) ? serverBatt : server;

            if (battFirst && serverBatt == null) {
                log("先注册 Battery，再注册 cmd（验证只映射第一个服务的缺陷）");
                log("addService(Battery 0x180F)=" + basHost.addService(basSvc)
                        + " 初始 Battery Level=" + (battChar.getValue()[0] & 0xFF) + "%");
                log("addService(cmd 服务)=" + server.addService(cmdSvc));
            } else {
                log("addService(cmd 服务 " + ParcelUuid.fromString(svcUuidStr) + ")="
                        + server.addService(cmdSvc));
                log("addService(Battery 0x180F)=" + basHost.addService(basSvc)
                        + " 初始 Battery Level=" + (battChar.getValue()[0] & 0xFF) + "%"
                        + (serverBatt != null ? " [独立 server]" : ""));
            }
            log("cmd 服务特征: CMD=" + writeCharUuidStr + " AUTH=" + authCharUuidStr
                    + " RES(notify)=" + notifyCharUuidStr);
            cmdExec = java.util.concurrent.Executors.newSingleThreadExecutor();
            if (psk != null) {
                log("命令通道：预共享密钥门控已启用（订阅 RES 后会下发 nonce，写 CMD 前须向 AUTH 回 HMAC(psk,nonce)）");
            } else if (execOnWrite) {
                log("!! 写特征直执行模式（无鉴权）：蓝牙范围内任何客户端都能执行 root 命令");
            }

            // 蓝牙重启后旧 advertiser 句柄同样失效，必须重新获取
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                log("重建后 getBluetoothLeAdvertiser()==null（稍后自动重试）");
                gattBroken = true; return false;
            }

            gattBroken = false;
            log("GATT server 已重建");
            return true;
        } finally {
            rebuilding = false;
        }
    }

    static BluetoothGattService buildCmdService() {
        BluetoothGattService svc = new BluetoothGattService(
                UUID.fromString(svcUuidStr), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        writeChar = new BluetoothGattCharacteristic(UUID.fromString(writeCharUuidStr),
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        authChar = new BluetoothGattCharacteristic(UUID.fromString(authCharUuidStr),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        notifyChar = new BluetoothGattCharacteristic(UUID.fromString(notifyCharUuidStr),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        notifyChar.addDescriptor(new BluetoothGattDescriptor(UUID.fromString(CCCD_UUID),
                BluetoothGattDescriptor.PERMISSION_WRITE
                        | BluetoothGattDescriptor.PERMISSION_READ));
        // v3 文件上传特征：二进制直写（不用 '>'/'<' 分片），首包为 PUT:name:size\n 头
        fileChar = new BluetoothGattCharacteristic(UUID.fromString(fileCharUuidStr),
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        svc.addCharacteristic(writeChar);
        svc.addCharacteristic(authChar);
        svc.addCharacteristic(notifyChar);
        svc.addCharacteristic(fileChar);
        return svc;
    }

    /** 标准 Battery Service 0x180F -> Battery Level 0x2A19，纯 1 字节 */
    static BluetoothGattService buildBatteryService() {
        BluetoothGattService bas = new BluetoothGattService(
                UUID.fromString(battSvcUuidStr), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        battChar = new BluetoothGattCharacteristic(UUID.fromString(battLvlUuidStr),
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        battChar.addDescriptor(new BluetoothGattDescriptor(UUID.fromString(CCCD_UUID),
                BluetoothGattDescriptor.PERMISSION_WRITE
                        | BluetoothGattDescriptor.PERMISSION_READ));
        battChar.setValue(new byte[]{(byte) batteryLevelInt()});
        bas.addCharacteristic(battChar);
        return bas;
    }

    static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object inst = at.getMethod("systemMain").invoke(null);
            Object ctx = at.getMethod("getSystemContext").invoke(inst);
            log("system Context 取得：" + ctx);
            return (Context) ctx;
        } catch (Throwable t) {
            log("取 system Context 失败（" + t + "），改用 null");
            return null;
        }
    }

    static final class GattCb extends BluetoothGattServerCallback {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            final String k = device.getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 无条件重置会话：双实例/迟到回调多清几次完全无害，
                // 但因 connHandled 竞态被 return 跳过重置才致命（快速重连卡 step=0 的根因之一）。
                resetSession(k);
                connected.put(k, device);
                authed.remove(k);
                nonceByAddr.remove(k);
                if (!connHandled.add(k)) {
                    log("重复 CONNECTED " + k + "（split-servers 下两个 server 实例各收一次；会话已确保重置）");
                    return;
                }
                event("connect " + k);
                log("CONNECTED " + k + " status=" + status + "（会话状态已重置）");
                // 省电大头：连接建立后广播已无意义（一个外设一次只服务一个主设备），
                // 停掉广播让射频进入低占空比；断开时会自动重启广播等重连
                if (!keepAdv && advertiser != null && advActive) {
                    try {
                        advertiser.stopAdvertising(advCallback);
                        advActive = false;
                        log("已连接，停广播省电");
                    } catch (Throwable t) {
                        log("停广播失败: " + t);
                    }
                }
                if (onConnectCmd != null) {
                    log("--on-connect 触发: " + onConnectCmd);
                    execCommand(device, onConnectCmd);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!connHandled.remove(k)) {
                    log("重复 DISCONNECTED " + k + "（忽略）");
                    return;
                }
                // 延迟清理会话：快速断开重连时，旧连接迟到的第二次 DISCONNECTED 与新连接的
                // CONNECTED 会交错。若此处立即 resetSession，可能落在新连接初始化窗口里，
                // 把刚订阅的 CCCD/鉴权状态清掉，导致客户端卡 step=0。延迟 1.2s 后再复查：
                // 期间若该地址重新 CONNECTED（connHandled 又含 k），说明是新会话，取消清理。
                final int finStatus = status;
                mainHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (connHandled.contains(k)) {
                            log("DISCONNECTED 延迟清理取消：" + k + " 已重新连接（新会话）");
                            return;
                        }
                        connected.remove(k);
                        authed.remove(k);
                        nonceByAddr.remove(k);
                        resetSession(k);   // 队列/在途/序号/CCCD 全部归零
                        event("disconnect " + k);
                        log("DISCONNECTED " + k + " status=" + finStatus + "（延迟清理已完成）");
                        // 断开后自动重启广播，等客户端重连；载荷用当前最新值
                        if (!keepAdv && advertiser != null && !advertiseOnly) {
                            log("对端断开，重启广播等待重连");
                            try { startAdvertising(null); }
                            catch (Throwable t) { log("重启广播失败: " + t); }
                        }
                    }
                }, 1200);
            }
        }

        /**
         * 一次连接 = 一个会话：MTU、订阅、队列、"在途"标志、回调序号都属于上一次连接。
         * 不清就会把"上一片仍在途"的僵尸标志带进新连接，导致新连接一上来就被跳过。
         */
        static void resetSession(String k) {
            // 断连回调也在 binder 线程：清队列要持泵锁，防止清的同时另一条线程正在出队
            synchronized (PUMP_LOCK) { queues.remove(k); }
            mtuOf.remove(k);
            sending.remove(k);
            cmdAccum.remove(k);
            txChunks.remove(k);
            txDigest.remove(k);
            txId.remove(k);
            fileOut.remove(k);
            fileRecvName.remove(k);
            fileRecvTarget.remove(k);
            fileRecvLen.remove(k);
            sendSeq.remove(k);
            ackSeq.remove(k);
            lastSendAt.remove(k);
            lostAck.remove(k);
            // 默认"不信任 onNotificationSent"=匀速节奏发送：从连接第一片起就按 paceMs 间隔发，
            // 不背靠背快发，避免中间片被通知缓冲区冲掉（这正是一直丢第 2 片的元凶）。
            trustSentCb.put(k, Boolean.FALSE);
            stallLogAt.remove(k);
            for (Iterator<String> it = cccdEnabled.keySet().iterator(); it.hasNext(); ) {
                if (it.next().startsWith(k + "|")) it.remove();
            }
            for (Iterator<String> it = cccdIndicate.keySet().iterator(); it.hasNext(); ) {
                if (it.next().startsWith(k + "|")) it.remove();
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            mtuOf.put(device.getAddress(), mtu);
            log("MTU " + device.getAddress() + " -> " + mtu + "（单包通知可用 "
                    + (mtu - 3) + " 字节）");
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                int offset, BluetoothGattCharacteristic ch) {
            byte[] v = currentValueFor(ch);
            byte[] out = offset <= v.length ? Arrays.copyOfRange(v, offset, v.length) : new byte[0];
            boolean sr = srvFor(ch.getUuid()).sendResponse(device, requestId,
                    BluetoothGatt.GATT_SUCCESS, offset, out);
            log("READ  " + shortOf(ch.getUuid()) + " from " + device.getAddress()
                    + " len=" + out.length + " sendResponse=" + sr
                    + (serverBatt != null ? " [走 " + (srvFor(ch.getUuid()) == serverBatt ? "Battery server" : "cmd server") + "]" : ""));
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic ch, boolean prepared,
                                                 boolean responseNeeded, int offset, byte[] value) {
            String addr = device.getAddress();
            String text = value == null ? "" : new String(value);
            if (responseNeeded) {
                srvFor(ch.getUuid()).sendResponse(device, requestId,
                        BluetoothGatt.GATT_SUCCESS, offset, null);
            }

            if (authChar != null && ch.getUuid().equals(authChar.getUuid())) {
                // 挑战-响应：客户端发来的是 HMAC(psk, nonce) 的 hex，绝不明文比对、绝不打印密钥
                if (psk == null) {
                    authed.remove(addr);
                    enqueueNotify(device, "!auth:denied:server has no --psk");
                    log("AUTH " + addr + " -> 拒绝（服务端未配置 --psk）");
                    return;
                }
                byte[] nonce = nonceByAddr.get(addr);
                if (nonce == null) {
                    authed.remove(addr);
                    enqueueNotify(device, "!auth:denied:no-nonce (subscribe RES first)");
                    log("AUTH " + addr + " -> 拒绝（无 nonce：请先订阅 RES 触发挑战）");
                    return;
                }
                boolean pass = text.trim().equalsIgnoreCase(hmacHex(psk, nonce));
                if (pass) { authed.add(addr); } else { authed.remove(addr); }
                nonceByAddr.remove(addr);   // 一次性：用后即废，防重放
                log("AUTH(HMAC) " + addr + " -> " + (pass ? "OK" : "FAIL"));
                enqueueNotify(device, pass ? "auth:ok" : "!auth:denied");
                return;
            }

            // v3 文件上传通道：二进制直写，首包 PUT: 头，不走命令分片协议
            if (fileChar != null && ch.getUuid().equals(fileChar.getUuid())) {
                handleFileUpload(device, value);   // 仅在 handleFileUpload 内部打 UPLOAD start/done，不在每片刷日志
                return;
            }

            log("WRITE " + ch.getUuid() + " from " + addr + ": \"" + text + "\"");
            event("write " + text);

            if (writeChar != null && ch.getUuid().equals(writeChar.getUuid())) {
                lastWrite = value == null ? new byte[0] : value.clone();
                // 可靠传输控制帧：重传请求（ATT 写带响应，天然可靠，不需要分片）
                if (text.startsWith("#retrans ")) {
                    handleRetrans(device, text);
                    return;
                }
                // 分片命令协议：首字节 '>' = 还有后续，'<' = 末片；无标记 = 单帧整条（兼容旧客户端）
                String cmdText = text;
                byte[] pv = value == null ? new byte[0] : value;
                char m0 = pv.length > 0 ? (char) (pv[0] & 0xFF) : 0;
                if (m0 == '>' || m0 == '<') {
                    java.io.ByteArrayOutputStream acc = cmdAccum.get(addr);
                    if (acc == null) {
                        acc = new java.io.ByteArrayOutputStream();
                        cmdAccum.put(addr, acc);
                    }
                    if (acc.size() + pv.length - 1 > MAX_CMD_BYTES) {
                        acc.reset();
                        log("!! CMD 分片累计超限（>" + MAX_CMD_BYTES + " 字节），已丢弃 " + addr);
                        if (m0 == '<') enqueueNotify(device, "!cmd-too-long: 命令超长被丢弃");
                        return;
                    }
                    acc.write(pv, 1, pv.length - 1);
                    if (m0 == '>') {
                        log("CMD 分片 " + addr + "：已攒 " + acc.size() + " 字节，等待后续片");
                        return;
                    }
                    cmdText = new String(acc.toByteArray());   // '<' 末片到齐，拼接执行
                    acc.reset();
                    log("CMD 分片完成 " + addr + "：共 "
                            + cmdText.getBytes().length + " 字节，执行");
                }
                // 特殊探活命令：服务端收到后不进命令队列、绝不执行，立即回一条 wakeup-ok 标记帧，
                // 让客户端确认 GATT 栈仍活（能区分「命令卡死」与「链路/设备死」）。
                // 拦截点放在鉴权判断之前：无论是否已鉴权都直接回应，保证探活随时可用。
                if ("wakeupserver".equals(cmdText.trim())) {
                    log("WAKEUP " + addr + " <- 探活命令，不执行，直接回应 wakeup-ok");
                    final BluetoothDevice d = device;
                    if (mainHandler != null) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() { enqueueNotify(d, "wakeup-ok"); }
                        });
                    } else {
                        enqueueNotify(device, "wakeup-ok");
                    }
                    return;
                }
                // 上传中止指令：客户端在取消上传时发出，服务端立即关闭当前落盘流、丢弃部分文件，
                // 不再等待 FILEDONE，也不把已收到的碎片当有效数据。放在鉴权判断之前，随时可用。
                if ("blegatt-upload-abort".equals(cmdText.trim())) {
                    abortUpload(device);
                    return;
                }
                if (psk != null) {
                    if (authed.contains(addr)) {
                        dispatchCommand(device, cmdText.trim());
                    } else {
                        log("CMD 被拒（未鉴权）" + addr);
                        enqueueNotify(device, "!denied:AUTH_REQUIRED 先向 AUTH 特征写密钥");
                    }
                } else if (execOnWrite) {
                    dispatchCommand(device, cmdText);
                } else if (echoOnWrite) {
                    notifyValue = lastWrite.clone();
                    doNotify();
                }
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor desc, boolean prepared,
                                             boolean responseNeeded, int offset, byte[] value) {
            boolean enable = value != null && value.length > 0 && value[0] != 0;
            // CCCD 低字节：0x01=NOTIFY 0x02=INDICATION 0x03=两者。indication 位=0x02。
            boolean indication = value != null && value.length >= 1 && (value[0] & 0x02) != 0;
            cccdEnabled.put(device.getAddress() + "|" + desc.getCharacteristic().getUuid(), enable);
            cccdIndicate.put(device.getAddress() + "|" + desc.getCharacteristic().getUuid(), indication);
            if (responseNeeded) {
                srvFor(desc.getCharacteristic().getUuid()).sendResponse(device, requestId,
                        BluetoothGatt.GATT_SUCCESS, offset, null);
            }
            log("CCCD " + shortOf(desc.getCharacteristic().getUuid()) + " " + device.getAddress()
                    + " notify=" + enable + " indicate=" + indication + " [" + hex(value) + "]");
            dumpSubState("CCCD后");   // 只读诊断：看 put 进去的键到底长什么样
            if (enable) {
                Deque<NotifyItem> q = queues.get(device.getAddress());
                log("订阅生效，待发队列=" + (q == null ? 0 : q.size()) + " 片，开始冲刷");
                // RES 结果通道按 INDICATION 订阅 => 服务端以 confirm=true 可靠流控发送，
                // 协议栈保证一次只一片在途，根治丢片；故切换为"信任 onNotificationSent"推进。
                if (indication && notifyChar != null
                        && desc.getCharacteristic().getUuid().equals(notifyChar.getUuid())) {
                    trustSentCb.put(device.getAddress(), Boolean.TRUE);
                    log("RES 按 INDICATION 订阅：服务端启用 confirm=true 可靠流控（一次一片，等对端确认）");
                }
                pumpQueue(device.getAddress());   // 把 --on-connect 攒下的输出冲出去
                // 客户端一订阅 RES 就把服务端版本号推过去（无论是否设了 --psk 都能收到）
                if (notifyChar != null
                        && desc.getCharacteristic().getUuid().equals(notifyChar.getUuid())) {
                    enqueueNotify(device, "blegatt-ver:" + serverVersion());
                    // 启用 PSK 时下发一次性 nonce：客户端须回 HMAC(psk, nonce)，实现不配对也安全的挑战-响应
                    if (psk != null) {
                        byte[] nonce = new byte[16];
                        new java.security.SecureRandom().nextBytes(nonce);
                        nonceByAddr.put(device.getAddress(), nonce);
                        enqueueNotify(device, "nonce:" + hex(nonce));
                        log("下发 AUTH nonce → " + device.getAddress());
                    }
                }
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor desc) {
            Boolean en = cccdEnabled.get(device.getAddress() + "|" + desc.getCharacteristic().getUuid());
            Boolean ind = cccdIndicate.get(device.getAddress() + "|" + desc.getCharacteristic().getUuid());
            byte[] v = (en == null || !en)
                    ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    : (Boolean.TRUE.equals(ind)
                        ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            srvFor(desc.getCharacteristic().getUuid()).sendResponse(device, requestId,
                    BluetoothGatt.GATT_SUCCESS, offset, v);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            // 该回调发生在 binder 线程：绝不能在这里直接泵送（会和主线程并发碰队列）。
            // 只做一件事——post 回主线程，由主线程统一记账 + 泵送。
            final String addr = device.getAddress();
            final int st = status;
            if (mainHandler == null) return;
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    int seq = seqOf(addr);
                    if (seq > ackOf(addr)) ackSeq.put(addr, seq);   // 记下"已回调到的序号"
                    sending.put(addr, Boolean.FALSE);
                    if (st != BluetoothGatt.GATT_SUCCESS) {
                        log("NOTIFY_SENT 异常 " + addr + " status=" + st + " seq=" + seq);
                    }
                    pumpQueue(addr);   // 这片发完了才能发下一片
                }
            });
        }
    }

    static byte[] currentValueFor(BluetoothGattCharacteristic ch) {
        if (battChar != null && ch.getUuid().equals(battChar.getUuid())) {
            return new byte[]{(byte) batteryLevelInt()};   // 纯 1 字节，标准 BAS 语义
        }
        if (notifyChar != null && ch.getUuid().equals(notifyChar.getUuid())) {
            return notifyValue;
        }
        return new byte[0];
    }

    static String readUptime() {
        try {
            String s = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream("/proc/uptime"))).readLine();
            return s == null ? "?" : s.split(" ")[0];
        } catch (Throwable t) { return "?"; }
    }

    // ------------------------------------------------------------- advertising

    /** legacy advertising 的 AD 载荷上限（不含 HCI 头） */
    static final int MAX_AD = 31;

    /** AD 里真正会出现的名字：优先用协议栈当前的 adapter 名，取不到时回退默认名 */
    static String effectiveAdvName() {
        try {
            String cur = adapter == null ? null : adapter.getName();
            if (cur != null && cur.length() > 0) return cur;   // 空口用的是栈里的实际名
        } catch (Throwable ignored) { }
        return advName;
    }

    /** 估算 AD 占用：返回 {总计, flags, name, tx, uuid, mfg} */
    static int[] estimateAd(boolean name, boolean tx, boolean uuid, byte[] mfg) {
        int flags = 3;                                        // 协议栈自动插 AD Flags
        int n = name ? 2 + effectiveAdvName().getBytes().length : 0;
        int t = tx ? 3 : 0;   // len+type+1字节功率
        // Android 的 AD 写手不会把 UUID 压缩成 16 位，一律按 128 位 = 2+16 计
        int u = uuid ? 18 : 0;
        int m = (mfg == null || mfg.length == 0) ? 0 : 4 + mfg.length;
        return new int[]{flags + n + t + u + m, flags, n, t, u, m};
    }

    static AdvertiseData buildData(boolean name, boolean tx, boolean uuid, byte[] mfg) {
        AdvertiseData.Builder db = new AdvertiseData.Builder()
                .setIncludeDeviceName(name)
                .setIncludeTxPowerLevel(tx);
        if (uuid && advSvcUuid != null) db.addServiceUuid(ParcelUuid.fromString(advSvcUuid));
        if (mfg != null && mfg.length > 0) db.addManufacturerData(mfgId, mfg);
        return db.build();
    }

    /** 一次 AD 预算分配结果 */
    static final class AdPlan {
        boolean name, tx, uuid;
        byte[] mfg;
        int total;
    }

    /** 逐级降级塞进 31 字节：先剔 UUID，再 TX，再名字，最后截断载荷 */
    static AdPlan planAd(boolean name, boolean tx, boolean uuid, byte[] mfg, String label) {
        AdPlan p = new AdPlan();
        p.name = name; p.tx = tx; p.uuid = uuid;
        p.mfg = mfg == null ? new byte[0] : mfg;
        int[] est = estimateAd(p.name, p.tx, p.uuid, p.mfg);
        if (est[0] > MAX_AD && p.uuid) {
            p.uuid = false; log(label + " 超限(" + est[0] + ">31)，剔除 service UUID");
            est = estimateAd(p.name, p.tx, p.uuid, p.mfg);
        }
        if (est[0] > MAX_AD && p.tx) {
            p.tx = false; log(label + " 仍超限(" + est[0] + ">31)，剔除 TX Power");
            est = estimateAd(p.name, p.tx, p.uuid, p.mfg);
        }
        if (est[0] > MAX_AD && p.name) {
            p.name = false; log(label + " 仍超限(" + est[0] + ">31)，剔除设备名");
            est = estimateAd(p.name, p.tx, p.uuid, p.mfg);
        }
        if (est[0] > MAX_AD && p.mfg.length > 0) {
            int keep = MAX_AD - (est[0] - est[5]) - 4;
            if (keep < 0) keep = 0;
            log(label + " 仍超限，载荷截断 " + p.mfg.length + " -> " + keep + " 字节");
            p.mfg = Arrays.copyOf(p.mfg, keep);
            est = estimateAd(p.name, p.tx, p.uuid, p.mfg);
        }
        p.total = est[0];
        log(String.format(Locale.US,
                "%s 载荷 %d/%d = flags %d + name %d + tx %d + uuid %d + 厂商数据 %d [%s]%s",
                label, est[0], MAX_AD, est[1], est[2], est[3], est[4], est[5],
                new String(p.mfg), est[0] > MAX_AD ? "  <== 仍超限，会被拒" : ""));
        return p;
    }

    /** 模板渲染成 ASCII 载荷；没有模板则沿用原始 --mfg 字节 */
    static byte[] payloadFor(String tpl, byte[] fallback) {
        if (tpl == null) return fallback == null ? new byte[0] : fallback;
        return renderTokens(tpl).getBytes();
    }

    /** 占位符：{b}电量% {v}电压V {t}温度℃ {c}已连接数 {u}运行秒 {n}设备名 */
    static String renderTokens(String tpl) {
        String s = tpl;
        s = s.replace("{b}", batteryPct());
        s = s.replace("{v}", voltageV());
        s = s.replace("{t}", tempC());
        s = s.replace("{c}", Integer.toString(connected.size()));
        s = s.replace("{u}", uptimeSec());
        s = s.replace("{n}", effectiveAdvName());
        return s;
    }

    static String currentPayload() {
        return (advText == null ? "" : renderTokens(advText))
                + "|" + (srText == null ? "" : renderTokens(srText));
    }

    static String sysfs(String path) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            try {
                byte[] buf = new byte[64];
                int n = in.read(buf);
                return n <= 0 ? null : new String(buf, 0, n).trim();
            } finally { in.close(); }
        } catch (Throwable t) { return null; }
    }

    /** 0~100 的整数电量，读不到返回 0 */
    static int batteryLevelInt() {
        String c = sysfs("/sys/class/power_supply/battery/capacity");
        if (c == null) return 0;
        try {
            int v = Integer.parseInt(c.trim());
            return (v < 0 || v > 100) ? 0 : v;
        } catch (Throwable t) { return 0; }
    }

    /** 标准 Battery Service 的轮询：变化才 notify，且发的是纯 1 字节（无前缀、不切片） */
    static void startBatteryPoller() {
        if (batteryNotifySec <= 0 || battChar == null) { log("--battery-notify 0：关闭电量轮询"); return; }
        log("Battery Level 轮询 " + batteryNotifySec + "s（变化才通知；不连接时无处可推）");
        Thread t = new Thread(new Runnable() {
            int last = batteryLevelInt();
            @Override public void run() {
                while (true) {
                    sleep(batteryNotifySec * 1000L);
                    // 省电：没有客户端连接时读 sysfs + 推送纯属空转，直接跳过本轮
                    if (connected.isEmpty()) continue;
                    int v = batteryLevelInt();
                    if (v == last) continue;
                    log("电量变化 " + last + "% -> " + v + "%");
                    last = v;
                    final int lvl = v;
                    mainHandler.post(new Runnable() {
                        @Override public void run() { pushBattery(lvl); }
                    });
                }
            }
        }, "batt");
        t.setDaemon(true);
        t.start();
    }

    /** 只读诊断：打印订阅表/连接表/期望键，验证 isSubscribed 为什么对 2a19 失配 */
    static void dumpSubState(String where) {
        StringBuilder sb = new StringBuilder();
        sb.append("SUBDUMP[").append(where).append("]");
        sb.append(" connected=").append(connected.keySet());
        sb.append(" cccd(").append(cccdEnabled.size()).append(")={");
        for (Map.Entry<String, Boolean> e : cccdEnabled.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(" ");
        }
        sb.append("}");
        if (battChar != null) {
            sb.append(" 期望键=<addr>|").append(battChar.getUuid());
            sb.append(" battChar身份=").append(System.identityHashCode(battChar));
            sb.append(" 描述符数=").append(battChar.getDescriptors().size());
            for (BluetoothGattDescriptor d : battChar.getDescriptors()) {
                sb.append(" [desc=").append(d.getUuid()).append(" 父特征=");
                try {
                    sb.append(d.getCharacteristic() == null ? "null"
                            : String.valueOf(d.getCharacteristic().getUuid()));
                } catch (Throwable t) {
                    sb.append("异常=").append(t);
                }
                sb.append("]");
            }
        }
        sb.append(" server=").append(server == null ? "null" : System.identityHashCode(server));
        sb.append(" serverBatt=")
          .append(serverBatt == null ? "null" : System.identityHashCode(serverBatt));
        sb.append(" MTU=").append(mtuOf).append(" sending=").append(sending);
        log(sb.toString());
    }

    static void pushBattery(int level) {
        if (subDump) dumpSubState("pushBattery");   // 诊断日志默认关：每次推送打一大串也耗电/耗 IO
        byte[] one = new byte[]{(byte) level};
        battChar.setValue(one);                       // 让后续 READ 也拿到最新值
        int n = 0;
        for (BluetoothDevice d : linkedDevices()) {
            if (isSubscribed(d.getAddress(), battChar.getUuid())) {
                enqueueRaw(d, (serverBatt != null) ? serverBatt : server, battChar, one);
                n++;
            }
        }
        log("Battery Level=" + level + "% 已推给 " + n + " 个已订阅客户端");
    }

    static String batteryPct() {
        String c = sysfs("/sys/class/power_supply/battery/capacity");
        return c == null ? "-" : c;
    }

    /** µV -> V；能一位小数说清就不留两位（4.2 而非 4.20），省字节 */
    static String voltageV() {
        String raw = sysfs("/sys/class/power_supply/battery/voltage_now");
        if (raw == null) return "-";
        try {
            double v = Double.parseDouble(raw) / 1000000.0;
            double one = Math.round(v * 10) / 10.0;
            if (Math.abs(v - one) < 0.05) return String.format(Locale.US, "%.1f", one);
            return String.format(Locale.US, "%.2f", v);
        } catch (Throwable t) { return "-"; }
    }

    /** 0.1℃ 单位 -> 整数℃ */
    static String tempC() {
        String raw = sysfs("/sys/class/power_supply/battery/temp");
        if (raw == null) return "-";
        try { return Integer.toString(Integer.parseInt(raw) / 10); } catch (Throwable t) { return "-"; }
    }

    static String uptimeSec() {
        String up = sysfs("/proc/uptime");
        if (up == null) return "0";
        try { return Integer.toString((int) Double.parseDouble(up.split(" ")[0])); }
        catch (Throwable t) { return "0"; }
    }

    /** 电量电压会变，按周期重算，内容变了才重开广播（不影响已建立的 GATT 连接） */
    static void startRefresh() {
        if (refreshSec <= 0 || (advText == null && srText == null)) return;
        log("载荷变化时重开广播（已连接客户端不受影响）");
        Thread t = new Thread(new Runnable() {
            // 必须以「当前实际载荷」为初值，否则首轮无条件重开广播 = 白换一次 RPA
            String last = currentPayload();
            @Override public void run() {
                while (true) {
                    sleep(refreshSec * 1000L);
                    String cur = currentPayload();
                    if (cur.equals(last)) continue;
                    log("载荷变化 [" + last + "] -> [" + cur + "]");
                    last = cur;
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            // 省电：广播没在发（已连接停广播/被手动停）就不重开——
                            // 重开=换一次 RPA+射频重启，纯浪费；断开自动重启广播时自然用新载荷
                            if (!advActive) {
                                log("广播未在发，跳过重开（断开重启广播时自动生效新载荷）");
                                return;
                            }
                            startAdvertising(null);
                        }
                    });
                }
            }
        }, "refresh");
        t.setDaemon(true);
        t.start();
    }

    static void startAdvertising(final CountDownLatch done) {
        // --name 显式给出才改系统蓝牙名（否则保持系统当前名）
        if (adapter != null && nameExplicit) {
            try {
                if (!advName.equals(adapter.getName())) {
                    log("setName(" + advName + ") 提交，等待生效...");
                    adapter.setName(advName);
                    for (int i = 0; i < 30; i++) {
                        sleep(500);
                        if (advName.equals(adapter.getName())) break;
                    }
                    log("当前 adapter 名 = [" + adapter.getName() + "]");
                }
            } catch (Throwable t) { log("setName 失败: " + t); }
        }

        AdPlan adv = planAd(includeNameInAdv, includeTxPower,
                includeServiceUuidInAdv && advSvcUuid != null,
                payloadFor(advText, mfgData), "AD");
        final AdPlan sr = (srText == null) ? null
                : planAd(false, false, false, payloadFor(srText, null), "SCAN-RESP");

        byte[] mfg = adv.mfg;
        boolean useName = adv.name, useTx = adv.tx, useUuid = adv.uuid;

        log("广播参数: mode=" + advMode + " connectable=" + connectable + " tx=" + advTx
                + " timeout=" + advTimeoutSec + "s advName=\"" + effectiveAdvName() + "\"");

        final AdvertiseData data = buildData(useName, useTx, useUuid, mfg);
        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(advMode)
                .setConnectable(connectable)
                .setTxPowerLevel(advTx)
                .setTimeout(advTimeoutSec)
                .build();

        if (advCallback != null && advActive) {
            advertiser.stopAdvertising(advCallback);
            advActive = false;
            sleep(300);
        }

        advCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) {
                advActive = true;
                log("ADVERTISE_ON mode=" + s.getMode() + " tx=" + s.getTxPowerLevel()
                        + " timeout=" + s.getTimeout());
                if (done != null) done.countDown();
            }
            @Override public void onStartFailure(int errorCode) {
                advActive = false;
                lastAdvError = errorCode;
                log("ADVERTISE_FAIL code=" + errorCode + " (" + advErrName(errorCode) + ")");
                if (done != null) done.countDown();
            }
        };
        if (sr == null) {
            advertiser.startAdvertising(settings, data, advCallback);
        } else {
            advertiser.startAdvertising(settings, data,
                    buildData(false, false, false, sr.mfg), advCallback);
        }
    }

    /**
     * 广播强制重开看门狗：每 advForceSec 秒 stop+start 一次，兜长待机后的"假活"
     * （framework 认为广播在发，但控制器实际没发，表现为扫得到却连不上/扫不到）。
     *
     * 只在没有客户端连接时执行：连接中 force 会换 RPA，破坏客户端"记住 MAC 直连"，
     * 而且 keepAdv=true 下广播本来就常开、无假活风险，没必要打扰。
     * 空窗期（断开后）才是需要保活的场景。
     */
    static void startAdvKeepalive() {
        if (advForceSec <= 0) {
            log("广播保活看门狗关闭（--adv-keepalive-force 未设或为 0）");
            return;
        }
        log("广播保活看门狗启动: 每 " + (advForceSec / 3600.0) + " 小时(" + advForceSec
                + "s) 强制重开一次（仅空闲时）");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    sleep(advForceSec * 1000L);
                    try {
                        // 连接中跳过：既不打扰链路，也不换 RPA 破坏客户端 MAC 直连
                        if (!connected.isEmpty()) {
                            log("keepalive：有客户端连接，跳过本轮强制重开");
                            continue;
                        }
                        if (advertiseOnly) continue;   // 纯广播模式本就常开，无需重开
                        log("keepalive：强制重开广播（stop + start，长待机假活自愈）");
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    // 主线程再确认一次：跨线程读集合有延迟，宁可多查一次
                                    if (!connected.isEmpty()) return;
                                    startAdvertising(null);   // 内部已含 stop 旧广播
                                } catch (Throwable e) {
                                    log("keepalive force 异常（已捕获）: " + e);
                                }
                            }
                        });
                    } catch (Throwable e) {
                        log("keepalive 线程异常（已捕获）: " + e);
                    }
                }
            }
        }, "adv-keepalive");
        t.setDaemon(true);
        t.start();
    }

    static String advErrName(int c) {
        switch (c) {
            case 1: return "DATA_TOO_LARGE";
            case 2: return "TOO_MANY_ADVERTISERS";
            case 3: return "ALREADY_STARTED";
            case 4: return "INTERNAL_ERROR";
            case 5: return "FEATURE_NOT_SUPPORTED";
            case 6: return "NO_RESOURCES";
            case 7: return "INTERNAL_ERROR_START_FAILED";
            default: return "code" + c;
        }
    }

    // -------------------------------------------------------------- stdin 命令（已移除）
    // 不再读 stdin：前台运行时 Ctrl+C（SIGINT）可直接结束进程，
    // 后台运行用 kill <pid> 结束。echo 模式的 doNotify/notifyValue 保留。

    static void doNotify() {
        if (server == null || notifyChar == null) { log("无 GATT server"); return; }
        String text = new String(notifyValue);
        int n = 0;
        for (BluetoothDevice d : linkedDevices()) { enqueueNotify(d, text); n++; }
        if (n == 0) log("没有已连接客户端，通知无处可发");
    }

    /** 命令在独立线程跑——绝不能占用主 Looper，否则 GATT 回调全被卡死 */
    /** 流式下发一段命令输出：每调用一次 = 一个独立 tid，客户端收到即打印，不必等进程结束 */
    static void streamChunk(final BluetoothDevice device, final String text) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (logOutBytes > 0) {
                    String shown = text.length() > logOutBytes
                            ? text.substring(0, logOutBytes) + "\n…[日志截断，共 "
                              + text.length() + " 字符，超出日志打印上限]"
                            : text;
                    String[] lines = shown.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        log((i == 0 ? "┌ " : "│ ") + lines[i]);
                    }
                    log("└ " + lines.length + " 行 / " + text.length() + " 字节 (流式)");
                }
                enqueueNotify(device, text);
            }
        });
    }

    static void execCommand(final BluetoothDevice device, final String cmd) {
        if (cmdExec == null) { log("命令通道未初始化"); return; }
        cmdExec.execute(new Runnable() {
            @Override public void run() {
                final String addr = device.getAddress();
                log("EXEC " + addr + " <- " + cmd);
                // 执行前持唤醒锁：防设备浅休眠拖慢 shell / 触发看门狗误杀（无参 acquire，finally 释放）
                if (cmdWake != null) {
                    try { cmdWake.acquire(); }
                    catch (Throwable ignored) { }
                }
                java.lang.Process p = null;
                try {
                    p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                            .redirectErrorStream(true).start();
                    try { p.getOutputStream().close(); } catch (Throwable ignored) { } // 关 stdin，避免 ffmpeg 等卡在交互提示
                    final java.lang.Process fp = p;
                    final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
                    final int[] total = {0};
                    final boolean[] overflow = {false};
                    final boolean[] sentFirst = {false};
                    // 读线程：阻塞读 stdout 并边读边发；进程被强杀或自然退出（EOF）时 read 抛异常/返回 -1，自然退出
                    Thread reader = new Thread(new Runnable() {
                        @Override public void run() {
                            byte[] buf = new byte[2048];
                            int n;
                            try {
                                while ((n = fp.getInputStream().read(buf)) > 0) {
                                    total[0] += n;
                                    if (total[0] > maxOutBytes) {            // 输出超限：立即杀进程并截断
                                        overflow[0] = true;
                                        try { fp.destroy(); } catch (Throwable ignored) { }
                                        break;
                                    }
                                    pending.write(buf, 0, n);
                                    // 边读边发：首段立即发（取消客户端 5s 守卫），其后累计到阈值再冲刷
                                    if (!sentFirst[0] || pending.size() >= streamFlushBytes) {
                                        streamChunk(device, new String(pending.toByteArray()));
                                        pending.reset();
                                        sentFirst[0] = true;
                                    }
                                }
                            } catch (Throwable ignored) { } // 进程被强杀时 read 抛异常，属正常退出路径
                            if (pending.size() > 0) streamChunk(device, new String(pending.toByteArray()));
                        }
                    });
                    reader.start();
                    // 看门狗（真正生效的超时）：主线程等进程在 cmdTimeoutSec 内结束，否则强杀
                    boolean finished = p.waitFor(cmdTimeoutSec, TimeUnit.SECONDS);
                    if (!finished) {
                        log("EXEC 超时 " + addr + " > " + cmdTimeoutSec + "s，强杀进程");
                        try { p.destroyForcibly(); } catch (Throwable ignored) { }
                    }
                    try { reader.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    if (p.isAlive()) { try { p.destroyForcibly(); } catch (Throwable ignored) { } } // 兜底：仍存活再补一刀
                    boolean cleanEnd = finished && !overflow[0];
                    // 结尾帧：正常结束给 #rc=；超限给 !killed；超时给 !timeout
                    StringBuilder foot = new StringBuilder();
                    if (overflow[0]) foot.append("!cmd-too-long: 输出超过 ").append(maxOutBytes).append(" 字节已截断\n");
                    if (cleanEnd) foot.append("#rc=").append(p.exitValue()).append("\n");
                    else if (overflow[0]) foot.append("!killed 输出过大已被终止\n");
                    else foot.append("!timeout " + cmdTimeoutSec + "s\n");
                    streamChunk(device, foot.toString());
                    log("EXEC 结束 " + addr + " cleanEnd=" + cleanEnd + " 共 " + total[0]
                            + " 字节" + (overflow[0] ? "（截断）" : "") + (!cleanEnd ? "（超时/超限强杀）" : ""));
                } catch (Throwable t) {
                    streamChunk(device, "!exec-failed " + t + "\n");
                } finally {
                    if (p != null) { try { p.destroyForcibly(); } catch (Throwable ignored) { } }
                    // 命令结束（正常/超时强杀/异常）一律释放唤醒锁，让设备回到可休眠状态
                    if (cmdWake != null) {
                        try { if (cmdWake.isHeld()) cmdWake.release(); } catch (Throwable ignored) { }
                    }
                }
            }
        });
    }

    /** 一次待发通知：特征 + 原始字节。Battery Level 必须纯 1 字节，RES 才带 +/- 前缀 */
    static final class NotifyItem {
        final BluetoothGattServer srv;
        final BluetoothGattCharacteristic ch;
        final byte[] value;
        NotifyItem(BluetoothGattServer srv, BluetoothGattCharacteristic ch, byte[] value) {
            this.srv = srv; this.ch = ch; this.value = value;
        }
    }

    static Deque<NotifyItem> queueOf(String addr) {
        Deque<NotifyItem> q = queues.get(addr);
        if (q == null) { q = new ArrayDeque<>(); queues.put(addr, q); }
        return q;
    }

    // ================= 可靠传输 v2（T 帧）=================
    // 数据帧: 'T' + tid(2) + seq(2) + total(2) + payload + CRC16(2)
    // 校验帧: 'D' + tid(2) + SHA-256(32) + CRC16(2)   （total>1 时随末片之后发出）
    // 重传:   客户端向 CMD 写 "#retrans <tid> <fromSeq>"（fromSeq==total 表示只补校验帧）
    static final int FRAME_OVERHEAD = 9;   // 'T' + 3 个 short + CRC16

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

    static byte[] makeDataFrame(int tid, int seq, int total, byte[] payload) {
        byte[] f = new byte[FRAME_OVERHEAD + payload.length];
        f[0] = 'T';
        f[1] = (byte) (tid >> 8); f[2] = (byte) tid;
        f[3] = (byte) (seq >> 8); f[4] = (byte) seq;
        f[5] = (byte) (total >> 8); f[6] = (byte) total;
        System.arraycopy(payload, 0, f, 7, payload.length);
        int crc = crc16(f, 0, f.length - 2);
        f[f.length - 2] = (byte) (crc >> 8); f[f.length - 1] = (byte) crc;
        return f;
    }

    static byte[] makeDigestFrame(int tid, byte[] sha256) {
        byte[] f = new byte[3 + sha256.length + 2];
        f[0] = 'D';
        f[1] = (byte) (tid >> 8); f[2] = (byte) tid;
        System.arraycopy(sha256, 0, f, 3, sha256.length);
        int crc = crc16(f, 0, f.length - 2);
        f[f.length - 2] = (byte) (crc >> 8); f[f.length - 1] = (byte) crc;
        return f;
    }

    /** RES 通道（文本）：UTF-8 编码后交给字节版入队 */
    static void enqueueNotify(BluetoothDevice device, String text) {
        if (notifyChar == null) return;
        enqueueBytes(device, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** RES 通道字节版：按 MTU 切片入队（T 帧 + CRC16 + SHA-256），二进制文件直接传、字节安全。
     *  客户端还没订阅就先攒着 */
    static void enqueueBytes(BluetoothDevice device, byte[] raw) {
        if (notifyChar == null) return;
        String addr = device.getAddress();
        int per = Math.max(1, chunkSize(addr) - FRAME_OVERHEAD);  // 9 字节帧开销
        // 写特征回调也在 binder 线程：入队与泵送必须持同一把锁，避免和出队并发
        synchronized (PUMP_LOCK) {
            int tid = (((txId.get(addr) == null ? 0 : txId.get(addr)) + 1) & 0xFFFF);
            txId.put(addr, tid);
            java.util.List<byte[]> list = new java.util.ArrayList<>();
            if (raw.length == 0) {
                list.add(makeDataFrame(tid, 0, 1, new byte[0]));
            } else {
                int total = (raw.length + per - 1) / per;
                for (int i = 0; i < total; i++) {
                    int off = i * per, len = Math.min(per, raw.length - off);
                    byte[] pl = new byte[len];
                    System.arraycopy(raw, off, pl, 0, len);
                    list.add(makeDataFrame(tid, i, total, pl));
                }
            }
            txChunks.put(addr, list.toArray(new byte[0][]));
            txDigest.remove(addr);
            if (list.size() > 1) {
                try {
                    byte[] sha = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
                    txDigest.put(addr, sha);
                } catch (Throwable t) {
                    log("SHA-256 异常（降级为无校验帧）: " + t);
                }
            }
            Deque<NotifyItem> q = queueOf(addr);
            for (byte[] f : list) q.addLast(new NotifyItem(server, notifyChar, f));
            byte[] dg = txDigest.get(addr);
            if (dg != null) q.addLast(new NotifyItem(server, notifyChar, makeDigestFrame(tid, dg)));
            log("TX " + addr + " tid=" + tid + " " + raw.length + " 字节 -> " + list.size()
                    + " 片" + (dg != null ? " +校验帧" : ""));
            trimQueue(addr);
            pumpQueueLocked(addr);
        }
    }

    /** 客户端重传请求：#retrans <tid> <fromSeq>。tid 不匹配视为过期请求，忽略 */
    static void handleRetrans(BluetoothDevice device, String msg) {
        String addr = device.getAddress();
        String[] p = msg.trim().split(" ");
        if (p.length < 3) return;
        int tid, from;
        try { tid = Integer.parseInt(p[1]); from = Integer.parseInt(p[2]); }
        catch (Throwable t) { return; }
        synchronized (PUMP_LOCK) {
            Integer cur = txId.get(addr);
            byte[][] chunks = txChunks.get(addr);
            if (cur == null || chunks == null || tid != cur.intValue()) {
                log("RETRANS " + addr + " tid=" + tid + " 已过期（当前 "
                        + cur + "），忽略");
                return;
            }
            Deque<NotifyItem> q = queueOf(addr);
            q.clear();          // 清掉排队中的重复片，按断点重排
            int fromClamped = Math.max(0, Math.min(from, chunks.length));
            if (fromClamped < chunks.length) {
                for (int i = fromClamped; i < chunks.length; i++) {
                    q.addLast(new NotifyItem(server, notifyChar, chunks[i]));
                }
            }
            byte[] dg = txDigest.get(addr);
            if (dg != null) q.addLast(new NotifyItem(server, notifyChar, makeDigestFrame(tid, dg)));
            log("RETRANS " + addr + " tid=" + tid + " 从 seq=" + fromClamped
                    + " 重发 " + q.size() + " 帧");
            pumpQueueLocked(addr);
        }
    }

    // ================= 文件收发（v3）=================
    /** 判断 f 是否位于 base 目录内（含 base 自身），防 ".." 逃逸 */
    static boolean isUnder(java.io.File f, java.io.File base) {
        try {
            String fp = f.getCanonicalPath();
            String bp = base.getCanonicalPath();
            return fp.equals(bp) || fp.startsWith(bp + java.io.File.separator);
        } catch (Throwable t) { return false; }
    }

    static byte[] readFile(java.io.File f) throws java.io.IOException {
        long len = f.length();
        if (len > 30L * 1024 * 1024) throw new java.io.IOException("file too large (>30MB)");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.min(len, 8 * 1024 * 1024));
        byte[] buf = new byte[8192];
        java.io.InputStream in = new java.io.FileInputStream(f);
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    static String sha256hex(byte[] d) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(d);
            StringBuilder s = new StringBuilder();
            for (byte x : h) s.append(String.format("%02x", x & 0xFF));
            return s.toString();
        } catch (Throwable t) { return ""; }
    }

    /** 上传文件名白名单：禁 '/''\\' 与 ".."，防越界写入 */
    static String sanitizeName(String n) {
        if (n == null) return null;
        n = n.trim();
        if (n.length() == 0 || n.contains("/") || n.contains("\\")
                || n.equals("..") || n.startsWith("../") || n.endsWith("/..")
                || n.contains("/../")) return null;
        return n;
    }

    /** 文件命令：fs ls [path] / fs get <path>；相对路径以 FILE_BASE 为基准且不可逃逸 */
    static void handleFs(final BluetoothDevice device, final String cmd) {
        String[] sp = cmd.split(" ", 3);
        String op = sp.length > 1 ? sp[1] : "";
        String path = sp.length > 2 ? sp[2] : ".";
        java.io.File base = new java.io.File(FILE_BASE);
        try { base.mkdirs(); } catch (Throwable ignored) { }
        java.io.File target;
        try {
            target = new java.io.File(base, path).getCanonicalFile();
            if (!isUnder(target, base)) { enqueueNotify(device, "!fs: path escapes base dir"); return; }
        } catch (Throwable t) { enqueueNotify(device, "!fs: bad path " + t); return; }
        if ("ls".equals(op)) {
            StringBuilder sb = new StringBuilder("FLST:\n");
            java.io.File[] list = target.isDirectory() ? target.listFiles() : null;
            if (list == null) {
                if (target.isFile()) sb.append("f ").append(target.length()).append(" ").append(target.getName()).append("\n");
                else sb.append("# (empty or not found)\n");
            } else {
                java.util.Arrays.sort(list);
                for (java.io.File f : list) {
                    sb.append(f.isDirectory() ? "d " : "f ").append(f.length()).append(" ").append(f.getName()).append("\n");
                }
            }
            enqueueNotify(device, sb.toString());
        } else if ("get".equals(op)) {
            if (!target.isFile()) { enqueueNotify(device, "!fs: not a file: " + path); return; }
            long size = target.length();
            if (size <= 0) { enqueueNotify(device, "!fs: empty file"); return; }
            if (size > 25L * 1024 * 1024) { enqueueNotify(device, "!fs: file too large (>25MB): " + size); return; }
            try {
                byte[] data = readFile(target);
                String sha = sha256hex(data);
                byte[] hdr = ("FILD:" + target.getName() + ":" + size + ":" + sha + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] payload = new byte[hdr.length + data.length];
                System.arraycopy(hdr, 0, payload, 0, hdr.length);
                System.arraycopy(data, 0, payload, hdr.length, data.length);
                enqueueBytes(device, payload);
                log("FS GET " + target + " " + size + "B sha=" + sha);
            } catch (Throwable t) { enqueueNotify(device, "!fs: read failed " + t); }
        } else {
            enqueueNotify(device, "!fs: unknown op '" + op + "' (use 'fs ls [path]' or 'fs get <path>')");
        }
    }

    /** 文件上传：首包 PUT:name:size\n 头，其后为原始二进制分片（WRITE_NO_RESPONSE） */
    static void handleFileUpload(BluetoothDevice device, byte[] value) {
        String addr = device.getAddress();
        try {
            java.io.OutputStream out = fileOut.get(addr);
            if (out == null) {
                // 文件名可能含中文等多字节 UTF-8 字符，必须以 UTF_8 解析，用 US_ASCII 会把多字节拆成替换符(乱码)
                String hdr = new String(value, 0, value.length, java.nio.charset.StandardCharsets.UTF_8);
                if (!hdr.startsWith("PUT:")) { enqueueNotify(device, "!upload: bad header (expect PUT:name:size)"); return; }
                String rest = hdr.substring(4).trim();
                int nl = rest.indexOf('\n'); if (nl >= 0) rest = rest.substring(0, nl);
                int c = rest.lastIndexOf(':');
                if (c < 0) { enqueueNotify(device, "!upload: bad header format"); return; }
                String name = sanitizeName(rest.substring(0, c));
                if (name == null) { enqueueNotify(device, "!upload: illegal filename"); return; }
                long size = Long.parseLong(rest.substring(c + 1).trim());
                java.io.File base = new java.io.File(FILE_BASE);
                try { base.mkdirs(); } catch (Throwable ignored) { }
                java.io.File f = new java.io.File(base, name);
                out = new java.io.FileOutputStream(f);
                fileOut.put(addr, out);
                fileRecvName.put(addr, name);
                fileRecvTarget.put(addr, size);
                fileRecvLen.put(addr, 0L);
                log("UPLOAD start " + addr + " " + name + " size=" + size);
            } else {
                out.write(value);
                long len = fileRecvLen.get(addr) + value.length;
                fileRecvLen.put(addr, len);
                if (len >= fileRecvTarget.get(addr)) {
                    out.close();
                    fileOut.remove(addr);
                    String name = fileRecvName.remove(addr);
                    long got = fileRecvTarget.remove(addr);
                    fileRecvLen.remove(addr);
                    java.io.File f = new java.io.File(FILE_BASE, name);
                    String sha = sha256hex(readFile(f));
                    enqueueNotify(device, "FILEDONE:" + name + ":" + sha + ":" + got);
                    log("UPLOAD done " + name + " " + got + "B sha=" + sha);
                }
            }
        } catch (Throwable t) {
            log("UPLOAD error " + addr + ": " + t);
            enqueueNotify(device, "!upload: " + t);
            try { java.io.OutputStream o = fileOut.remove(addr); if (o != null) o.close(); } catch (Throwable ignored) { }
            fileRecvName.remove(addr); fileRecvTarget.remove(addr); fileRecvLen.remove(addr);
        }
    }

    /**
     * 中止上传：客户端取消上传时调用。关闭当前落盘流、清掉该连接的接收状态、
     * 删除已落盘的部分文件，并回一条 FILEABORT:name 让客户端复位 UI。
     * 这样服务端不会卡在"等长"状态，也不会留下永远收不到 FILEDONE 的半成品。
     */
    static void abortUpload(BluetoothDevice device) {
        String addr = device.getAddress();
        String name = fileRecvName.remove(addr);
        fileRecvTarget.remove(addr);
        fileRecvLen.remove(addr);
        try {
            java.io.OutputStream o = fileOut.remove(addr);
            if (o != null) o.close();
        } catch (Throwable ignored) { }
        if (name != null) {
            try {
                java.io.File f = new java.io.File(FILE_BASE, name);
                if (f.exists() && !f.delete()) log("UPLOAD abort: 部分文件删除失败 " + name);
            } catch (Throwable t) {
                log("UPLOAD abort: 删除部分文件异常 " + name + ": " + t);
            }
        }
        log("UPLOAD aborted " + addr + (name != null ? " (" + name + ")" : " (无进行中上传)"));
        enqueueNotify(device, "FILEABORT:" + (name == null ? "" : name));
    }

    /** 命令分发：fs 文件命令走专用逻辑，其余照旧当 shell 执行 */
    static void dispatchCommand(final BluetoothDevice device, final String cmd) {
        if (cmd.startsWith("fs ")) { handleFs(device, cmd); return; }
        execCommand(device, cmd);
    }

    /**
     * 队列超限处理：不能只丢最老的一片 —— 那会打断 '+' / '-' 分帧，
     * 客户端永远等不到末片（表现为"⚠ 分片不完整"）。
     * 做法：整队丢弃 + 补发一个 '!' 错误帧，让客户端立刻知道这一轮作废。
     */
    static void trimQueue(String addr) {
        Deque<NotifyItem> q = queues.get(addr);
        if (q == null || q.size() <= MAX_QUEUE_ITEMS) return;
        int n = q.size();
        q.clear();
        log("!! " + addr + " 待发队列超限（" + n + " > " + MAX_QUEUE_ITEMS
                + "）：丢弃全部旧片并补发错误帧，避免客户端卡在半包");
        if (notifyChar != null) {
            q.addLast(new NotifyItem(server, notifyChar,
                    "!queue-overflow: 输出过多，已丢弃".getBytes()));
        }
    }

    /** 原样一帧（不切片、不加前缀），用于标准 Battery Level */
    static void enqueueRaw(BluetoothDevice device, BluetoothGattServer srv,
                           BluetoothGattCharacteristic ch, byte[] value) {
        synchronized (PUMP_LOCK) {
            Deque<NotifyItem> q = queueOf(device.getAddress());
            q.addLast(new NotifyItem(srv, ch, value));
            trimQueue(device.getAddress());
            pumpQueueLocked(device.getAddress());
        }
    }

    /**
     * 兜底排空：每 3 秒扫一次"有队列但没在发"的设备。
     * 任何路径漏掉一次 pump（回调丢失、提交失败、订阅迟到）都不会让队列烂在那里。
     */
    static void startDrainTicker() {
        if (mainHandler == null) return;
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    for (BluetoothDevice d : linkedDevices()) {
                        String a = d.getAddress();
                        Deque<NotifyItem> q = queues.get(a);
                        if (q != null && !q.isEmpty() && !Boolean.TRUE.equals(sending.get(a))) {
                            log("DRAIN 兜底排空 " + a + "：队列 " + q.size() + " 片，未在途");
                            pumpQueue(a);
                        }
                    }
                } catch (Throwable t) {
                    log("DRAIN 异常: " + t);
                }
                mainHandler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    static int parseAdvMode(String s) {
        if (s == null) return AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
        s = s.toLowerCase(Locale.US).replace('_', '-');   // low_power / low-power 两写法等价
        if (s.startsWith("low-p") || s.equals("0")) return AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
        if (s.startsWith("bal") || s.equals("1")) return AdvertiseSettings.ADVERTISE_MODE_BALANCED;
        if (s.startsWith("low-l") || s.equals("2")) return AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;
        return AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
    }

    static int parseAdvTx(String s) {
        if (s == null) return AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
        s = s.toLowerCase(Locale.US);
        if (s.startsWith("ultra")) return AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW;
        if (s.equals("low")) return AdvertiseSettings.ADVERTISE_TX_POWER_LOW;
        if (s.startsWith("med")) return AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
        if (s.equals("high")) return AdvertiseSettings.ADVERTISE_TX_POWER_HIGH;
        return AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
    }

    static int chunkSize(String addr) {
        Integer m = mtuOf.get(addr);
        int mtu = (m == null) ? 23 : m.intValue();
        int n = mtu - 3;                                   // ATT notification 头 3 字节
        return n > 0 ? n : 20;
    }

    static boolean isSubscribed(String addr, UUID chUuid) {
        return Boolean.TRUE.equals(cccdEnabled.get(addr + "|" + chUuid));
    }

    /** 关键：split-servers 时两个 GATT server 共用回调，应答必须按特征归属路由回去 */
    static BluetoothGattServer srvFor(UUID charUuid) {
        if (serverBatt != null && battChar != null && charUuid != null
                && charUuid.equals(battChar.getUuid())) {
            return serverBatt;
        }
        return server;
    }

    static String shortOf(UUID u) {
        String s = u.toString();
        return s.length() >= 8 ? s.substring(4, 8) : s;
    }

    /** 只信协议栈的连接状态：从两个 server 实例各自的视图里找这台设备 */
    static BluetoothDevice linkDevice(String addr) {
        BluetoothGattServer[] srvs = new BluetoothGattServer[]{server, serverBatt};
        for (BluetoothGattServer srv : srvs) {
            if (srv == null) continue;
            try {
                java.util.List<BluetoothDevice> ds = srv.getDevicesMatchingConnectionStates(
                        new int[]{BluetoothProfile.STATE_CONNECTED});
                if (ds == null) continue;
                for (BluetoothDevice d : ds) {
                    if (addr != null && addr.equalsIgnoreCase(d.getAddress())) return d;
                }
            } catch (Throwable t) { }
        }
        return connected.get(addr);            // 兜底：协议栈查询不可用时退回事件表
    }

    /** 当前真正处于已连接状态的设备（两个 server 视图合并去重） */
    static java.util.List<BluetoothDevice> linkedDevices() {
        java.util.List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        BluetoothGattServer[] srvs = new BluetoothGattServer[]{server, serverBatt};
        for (BluetoothGattServer srv : srvs) {
            if (srv == null) continue;
            try {
                java.util.List<BluetoothDevice> ds = srv.getDevicesMatchingConnectionStates(
                        new int[]{BluetoothProfile.STATE_CONNECTED});
                if (ds == null) continue;
                for (BluetoothDevice d : ds) {
                    if (d != null && seen.add(d.getAddress())) out.add(d);
                }
            } catch (Throwable t) { }
        }
        if (out.isEmpty()) out.addAll(connected.values());     // 兜底
        return out;
    }

    /**
     * 一次只发一件，等 onNotificationSent 回来再发下一件；连发会被 Fluoride 丢掉。
     *
     * 三个加固点（原实现会永久卡死在"上一片仍在途"）：
     *  1) 序号对账：有些栈在 notifyCharacteristicChanged() 内部就同步回调 onNotificationSent，
     *     若回调先到、再把 sending 置 TRUE，就会变成"没片在途却标志在途" —— 永久冻结。
     *     现在先发号再发送，返回后用 ack 序号判断是否已被回调，是则不再置位。
     *  2) 超时自愈：回调超时未到（对端消失/栈不上报），清标志继续排空，并挂下一次兜底泵。
     *  3) 自适应降级：连续 3 次收不到回调，判定该栈不上报 onNotificationSent，
     *     改用固定节流（paceMs）继续发，不再干等，吞吐得以保住。
     */
    static void pumpQueue(String addr) {
        // 全部泵送串行化：回调(binder线程)与主线程补泵不再并发碰队列
        synchronized (PUMP_LOCK) {
            pumpQueueLocked(addr);
        }
    }

    static void pumpQueueLocked(String addr) {
        BluetoothDevice d = linkDevice(addr);
        if (d == null) {
            log("PUMP 跳过 " + addr + "：协议栈未报告它处于已连接状态（链路可能已失效）");
            return;
        }
        Deque<NotifyItem> q = queues.get(addr);
        if (q == null || q.isEmpty()) return;                    // 没东西可发，不打扰日志

        long now = System.currentTimeMillis();

        // 片间节奏：连续快速提交会被底层丢包，两次发送之间至少隔 txPaceMs
        Long last = lastSendAt.get(addr);
        if (last != null) {
            long gap = now - last.longValue();
            if (gap >= 0 && gap < txPaceMs) {
                postPump(addr, txPaceMs - gap);
                return;
            }
        }

        if (Boolean.TRUE.equals(sending.get(addr))) {
            long inflight = now - lastSendAtOf(addr);
            if (inflight < sendAckTimeoutMs) {
                logStallLimited(addr, inflight);                 // 正常背压，只做节流日志
                return;
            }
            int lost = lostAckOf(addr) + 1;
            lostAck.put(addr, lost);
            sending.put(addr, Boolean.FALSE);
            log("PUMP 超时自愈 " + addr + "：等 onNotificationSent " + inflight
                    + "ms 未果（连续第 " + lost + " 次）——判定回调丢失，继续排空");
            if (lost >= 3 && !Boolean.FALSE.equals(trustSentCb.get(addr))) {
                trustSentCb.put(addr, Boolean.FALSE);
                log("!! " + addr + " 连续 " + lost + " 次没收到 onNotificationSent："
                        + "该协议栈不可信，改为节流发送（每 " + paceMs + "ms 一片），不再等待回调");
            }
        }

        NotifyItem item = null;
        for (Iterator<NotifyItem> it = q.iterator(); it.hasNext(); ) {
            NotifyItem cand = it.next();
            if (isSubscribed(addr, cand.ch.getUuid())) { item = cand; it.remove(); break; }
        }
        if (item == null) {
            log("PUMP 跳过 " + addr + "：队列 " + q.size()
                    + " 项，但对应特征的 CCCD 都未订阅");
            return;
        }

        int seq = seqOf(addr) + 1;
        sendSeq.put(addr, seq);
        lastSendAt.put(addr, now);
        item.ch.setValue(item.value);
        // 订阅类型决定 confirm：INDICATION => confirm=true（等客户端确认，可靠流控）；
        // NOTIFY => confirm=false（无确认，靠节奏节流，兼容旧 CLI 客户端）。
        boolean confirm = Boolean.TRUE.equals(cccdIndicate.get(addr + "|" + item.ch.getUuid()));
        boolean ok;
        try {
            ok = item.srv.notifyCharacteristicChanged(d, item.ch, confirm);
        } catch (Throwable t) {
            ok = false;
            log("NOTIFY 异常 " + addr + ": " + t);
        }
        log("NOTIFY " + addr + " ch=" + shortOf(item.ch.getUuid()) + " seq=" + seq + " "
                + item.value.length + " 字节 confirm=" + confirm + " 提交=" + ok + " 队列剩=" + q.size());

        if (!ok) {
            q.addFirst(item);
            sending.put(addr, Boolean.FALSE);
            postPump(addr, 80);      // 同一设备的发出通道被占时提交会失败，稍后重试
            return;
        }
        if (Boolean.FALSE.equals(trustSentCb.get(addr))) {
            sending.put(addr, Boolean.FALSE);   // 不信任回调：按节流节奏继续
            postPump(addr, paceMs);
            return;
        }
        if (ackOf(addr) >= seq) {
            // 回调已在 notify 内部同步抵达：此时绝不能再置"在途"，否则永久卡死
            sending.put(addr, Boolean.FALSE);
            mainHandler.post(new Runnable() {
                @Override public void run() { pumpQueue(addr); }
            });
        } else {
            sending.put(addr, Boolean.TRUE);
            postPump(addr, sendAckTimeoutMs + 50);   // 兜底：回调不来也会自愈
        }
    }

    /** 延迟再泵一次：既当重试，也当"回调丢失"的看门狗 */
    static void postPump(final String addr, long ms) {
        if (mainHandler == null) return;
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try { pumpQueue(addr); } catch (Throwable t) { log("PUMP 异常 " + addr + ": " + t); }
            }
        }, ms);
    }

    /** "仍在途"是正常背压，刷屏没意义：同设备 5 秒最多报一次 */
    static void logStallLimited(String addr, long inflight) {
        Long last = stallLogAt.get(addr);
        long now = System.currentTimeMillis();
        if (last != null && now - last.longValue() < 5000) return;
        stallLogAt.put(addr, now);
        log("PUMP 等待 " + addr + "：上一片仍在途 " + inflight
                + "ms（正常背压；若长期停留说明 onNotificationSent 没回来）");
    }

    static int seqOf(String addr) {
        Integer v = sendSeq.get(addr);
        return v == null ? 0 : v.intValue();
    }
    static int ackOf(String addr) {
        Integer v = ackSeq.get(addr);
        return v == null ? -1 : v.intValue();
    }
    static int lostAckOf(String addr) {
        Integer v = lostAck.get(addr);
        return v == null ? 0 : v.intValue();
    }
    static long lastSendAtOf(String addr) {
        Long v = lastSendAt.get(addr);
        return v == null ? 0L : v.longValue();
    }

    static void event(String e) {
        synchronized (events) { events.addLast(e); if (events.size() > 50) events.removeFirst(); }
    }

    // ------------------------------------------------------------------- utils

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static void fail(String m) { log("FATAL " + m); System.exit(1); }

    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }

    static byte[] parseHex(String s) {
        s = s.replace(":", "").replace("-", "").replace(" ", "");
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("hex 长度必须为偶数: " + s);
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** HMAC-SHA256(key, data) 的 hex 串；用于 AUTH 挑战-响应（密钥永不明文出现在链路） */
    static String hmacHex(String key, byte[] data) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            return hex(m.doFinal(data));
        } catch (Throwable t) { return ""; }
    }

    static boolean hasFlag(String[] a, String f) {
        for (String x : a) if (x.equals(f)) return true;
        return false;
    }

    static String nextArg(String[] a, String f) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(f)) return a[i + 1];
        return null;
    }

    static void parseInto(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--debug": debugLog = true; break;
                case "--name": advName = args[++i]; nameExplicit = true; break;
                case "--adv-mode": advMode = parseAdvMode(args[++i]); break;
                case "--tx": advTx = parseAdvTx(args[++i]); break;
                case "--battery-notify": batteryNotifySec = Integer.parseInt(args[++i]); break;
                case "--adv-keepalive-force": advForceSec = (int) (Double.parseDouble(args[++i]) * 3600); break;
                case "--split-servers": splitServers = true; break;
                case "--psk": psk = args[++i]; break;
                case "--on-connect": onConnectCmd = args[++i]; break;
                case "--max-out": maxOutBytes = Integer.parseInt(args[++i]); break;
                case "--cmd-timeout": cmdTimeoutSec = Integer.parseInt(args[++i]); break;
                case "--tx-pace": txPaceMs = Integer.parseInt(args[++i]); break;
                default: break;
            }
        }
    }

    static String normUuid(String s) {
        if (s.length() == 4) return "0000" + s.toLowerCase(Locale.US) + "-0000-1000-8000-00805f9b34fb";
        return s.toLowerCase(Locale.US);
    }

    static String nowHms() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    static void log(String m) {
        String line = "[" + nowHms() + "] " + m;
        System.out.println(line); System.out.flush();
        if (!debugLog) return;
        // 调试日志落盘：任何异常都静默，绝不影响 BLE 主流程。
        synchronized (DEBUG_LOCK) {
            try {
                if (debugWriter == null) {
                    java.io.File dir = new java.io.File("/sdcard/Download");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File f = new java.io.File(dir, "blegatt.log");
                    boolean fresh = !f.exists() || f.length() == 0;
                    debugWriter = new java.io.BufferedWriter(new java.io.FileWriter(f, true));
                    if (fresh) debugWriter.write("=== blegatt debug log " + nowHms() + " ===\n");
                }
                debugWriter.write(line); debugWriter.write("\n"); debugWriter.flush();
            } catch (Throwable t) {
                // 写日志失败不影响运行；下次再尝试（例如权限/目录临时不可用）
                try { if (debugWriter != null) { debugWriter.close(); } } catch (Throwable ignored) { }
                debugWriter = null;
            }
        }
    }

    static void usage() {
        System.out.println("blegatt — 非 APK 的 Android BLE 广播 + GATT server（app_process 直跑）\n"
                + "用法: CLASSPATH=/data/local/tmp/blegatt.jar app_process /system/bin BLEGattd [选项]\n"
                + "  --name <str>         广播/设备名（不传则用系统当前蓝牙名）\n"
                + "  --adv-mode <m>       广播模式 low_power(≈1s)|balanced(≈250ms)|low_latency(≈100ms)，默认 low_power 省电\n"
                + "  --tx <t>             发射功率 ultra_low|low|medium|high（默认 medium）\n"
                + "  --battery-notify <s> Battery Level 轮询秒数，变化才通知（默认 60，0=关闭）\n"
                + "  --adv-keepalive-force <小时> 广播保活：每 N 小时强制 stop+start 一次，兜长待机\"假活\"\n"
                + "                       支持小数，如 1=1小时、0.5=半小时；0=关闭(默认)；建议 1~2，仅空闲时执行；每次重开换一次 RPA\n"
                + "  --split-servers      Battery 用独立 GATT server 实例（排查用）\n"
                + "  --psk <key>          预共享密钥：命令通道强制挑战-响应（订阅 RES 后回 HMAC(psk,nonce) 才能写 CMD）\n"
                + "  --on-connect <cmd>   客户端一连上就自动执行该命令，结果排队等它订阅通知\n"
                + "  --max-out <bytes>    命令输出上限（默认 10240=10KB，超出截断）\n"
                + "  --cmd-timeout <sec>  单条命令最长执行时间（默认 10）\n"
                + "  --tx-pace <ms>       传输帧最小间隔下限（默认 40，连发易被协议栈丢包）\n"
                + "  --debug              把 log() 同步写入 Download/blegatt.log（排查用，默认关）\n"
                + "\n其余参数均使用内置默认值（服务 UUID 8e7a1f01…，广播自带 180f 电池服务 UUID）。\n"
                + "\n命令通道用法（nRF Connect）：\n"
                + "  1) 连上后先订阅 RES(notify) 特征，否则结果发不出来；\n"
                + "  2) 订阅 RES 后会收到 nonce 帧，向 AUTH 写 HMAC(psk, nonce) -> 收到 \"auth:ok\"；\n"
                + "  3) 向 CMD 写命令 -> 结果分片回推，每片首字节 '+' 表示后续还有、'-' 表示最后一片、\n"
                + "     '!' 表示错误/拒绝；片长 = 协商 MTU - 3 - 1（默认 23 MTU 时每片 19 字节）；\n"
                + "     在 nRF 里按顺序拼接各片（去掉首字节）即完整输出，片尾带 #rc=N 行。\n");
    }
}
