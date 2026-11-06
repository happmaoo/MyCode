import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.bluetooth.le.ScanSettings;
import android.os.Looper;

/**
 * MyBleClientCli —— 命令行 BLE 客户端（app_process 直跑，root 身份）。
 *
 * 用法:
 *   MyBleClientCli --server <名字|MAC> --psk <密钥> --cmd <命令> [--timeout 秒] [--no-wake] [--strip-rc] [--no-mtu]
 *   MyBleClientCli --server <名字|MAC> --pan [--pan-ip CIDR --pan-gw 网关]   # 连对端蓝牙共享（需装 PanCli.apk）
 *   --pan 必须和 --cmd 一起给，语义是「命令执行成功后，再把 PAN 连起来」；
 *   stdout 始终是命令结果，PAN 的网卡信息只进 stderr 日志；PAN 失败退出码 4。
 *   MyBleClientCli --server <名字|MAC> --pan-disconnect                      # 断开 PAN
 *
 * --pan 的实际 connect() 由同机的 PanCli.apk 执行：裸 app_process 没有 AMS 进程记录，
 * 自己 bindService、或让 system_server 代绑（bindBluetoothProfileService，实测 GATT/A2DP/PAN
 * 全部返回 false）都拿不到 PanService binder，只有 Zygote 起的正常 app 进程能拿到。
 * APK 工程在 ~/MyCode/MyBle/PanApk/。连上后安卓会把 PAN 抬成默认网络，网关上行会切到对端。
 *
 * 按名字扫描时默认先发一次 KEYCODE_WAKEUP 点亮屏幕：Android 的 native ScanManager
 * 在息屏状态下会挂起「不带 filter 的扫描」，日志只有一行
 * 「Cannot start unfiltered scan in screen-off」，回调里则表现为 0 条广播。
 * 已知对端 MAC 时用 --server <MAC> 直连，完全不依赖屏幕状态，适合无人值守网关。
 *
 * 流程: 连接 → 请求 MTU → 发现服务 → 订阅 RES（indication+notification 都开）→
 * 静默 1s → 写 AUTH 明文密钥 → 等 auth:ok 再写 CMD（长命令 '>'/'<' 分片）→ 收结果 → 退出。
 * 结果通道双协议：
 *   v2（服务端 2.0.dev）：T 帧 'T'+tid(2)+seq(2)+total(2)+payload+CRC16(2)，
 *     D 帧 'D'+tid(2)+SHA-256(32)+CRC16(2)；缺号/坏CRC 往 CMD 写 "#retrans <tid> <fromSeq>"
 *     断点重传，SHA 不符 fromSeq=0 全量重传；同一传输最多 NACK 5 次。#rc=N 单独一帧=命令结束。
 *   v1（旧服务端）：'+' 续片 / '-' 末片，--strip-rc 只对 v1 有意义。
 *
 * 输出约定: 结果走 stdout（供脚本 $() 取用），日志走 stderr。
 * 正文首行是服务端加的 #rc=N（对端命令退出码），脚本里想要纯输出就加 --strip-rc。
 * 退出码: 0=成功  1=超时/参数错  2=服务端错误帧  3=连接失败/断开  4=命令 OK 但 PAN 失败
 */
public class MyBleClientCli {

    static final java.util.UUID SVC = java.util.UUID.fromString("8e7a1f01-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final java.util.UUID CH_CMD  = java.util.UUID.fromString("8e7a1f03-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final java.util.UUID CH_RES  = java.util.UUID.fromString("8e7a1f04-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final java.util.UUID CH_AUTH = java.util.UUID.fromString("8e7a1f05-2b3c-4d5e-6f70-8192a3b4c5d6");
    static final java.util.UUID CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static String serverArg, psk, cmd;
    static int timeoutSec = 30;
    static boolean wake = true;      // 扫描前自动点亮屏幕（见 start() 里的说明）
    static boolean stripRc = false;  // 剥离服务端加在首行的 #rc=N，让 stdout 只有纯输出
    static boolean noMtu = false;    // 跳过 MTU 协商，按默认 23 走（对端栈不应答 MTU 时用它续命）

    // PAN（蓝牙网络共享）：由 PanCli.apk 执行真正的 connect()，这里负责触发与观察
    static boolean panMode = false, panDisc = false;
    static String panIp = null, panGw = null;
    static long panDeadline = 0;
    static boolean pendingPan = false;   // --pan：等命令跑完后再连
    static boolean cmdDone = false;      // 命令结果已经打出，之后的通知/日志都不再算结果
    static boolean panActive = false;    // 已进入 PAN 阶段（done 已置位，但还要允许 die）
    static long deadlineMs = 0;          // 总超时，进入 PAN 阶段时会往后延

    static Context ctx;
    static Handler h;
    static BluetoothAdapter adRef;
    static BluetoothGatt gatt;
    static BluetoothGattCharacteristic cCmd, cRes, cAuth;
    static int mtu = 23;
    /** 接收侧状态机：连接期回显 → 等鉴权应答 → 等命令结果。
     *  用状态而不是「CMD 写回调」来划线，是因为鉴权应答(-auth:ok)和命令结果都可能比
     *  onCharacteristicWrite 先到，靠时序判定必然出错（实测会把 -auth:ok 当成结果打出去）。 */
    static final int ST_BACKLOG = 0, ST_AWAIT_AUTH = 1, ST_RESULT = 2;
    static int rxState = ST_BACKLOG;
    static boolean authAcked = false, authWritten = false, resultTimerArmed = false;

    static boolean done = false;
    static final java.io.ByteArrayOutputStream rx = new java.io.ByteArrayOutputStream();

    static void log(String m) { System.err.println("[mbcli] " + m); }

    static void die(int code, String m) {
        if (done && !panActive) return;    // PAN 阶段虽然 done 已置位，仍要允许收尾报错
        done = true;
        log(m);
        cleanup();
        System.exit(code);
    }

    static void cleanup() {
        try { if (gatt != null) gatt.disconnect(); } catch (Throwable ignored) { }
        try { if (gatt != null) gatt.close(); } catch (Throwable ignored) { }
        try { if (ctx != null) stopScan(); } catch (Throwable ignored) { }
    }

    static android.bluetooth.le.BluetoothLeScanner scanner;
    static BluetoothAdapter.LeScanCallback leScanCb;
    static int seenTotal = 0;                    // 期间收到的广播总条数（与目标名无关）
    static final java.util.LinkedHashSet<String> seenNames = new java.util.LinkedHashSet<String>();

    /** 唤醒屏幕。Android 的 native 扫描管理器在息屏时会把「不带 filter 的扫描」挂起
     *  （logcat: Cannot start unfiltered scan in screen-off. This scan will be resumed later），
     *  命令行场景没人点亮屏幕，所以扫描前主动 keyevent WAKEUP(224)，扫完系统自己再熄屏。 */
    static void wakeScreen() {
        try {
            Process p = new ProcessBuilder(new String[]{"/system/bin/input", "keyevent", "224"})
                    .redirectErrorStream(true).start();
            p.waitFor();
            log("已点亮屏幕（息屏时控制器会挂起无过滤扫描）");
        } catch (Throwable t) {
            log("点亮屏幕失败（" + t + "）——手动亮屏再试，或用 --server <MAC> 跳过扫描");
        }
    }

    /** 留样一条广播：计数 + 记前 12 个不同名字，扫描超时时打印，便于判断「是环境没广播」还是「名字没对上」 */
    static void noteSeen(String name, String addr, int rssi) {
        seenTotal++;
        String shown = (name == null || name.isEmpty()) ? ("<无名> " + addr) : name;
        if (seenNames.size() < 12) seenNames.add(shown);
        if (seenTotal <= 5) log("广播#" + seenTotal + " " + shown + " " + rssi + "dBm");
    }

    static boolean matches(String name, String addr) {
        if (name != null && name.equalsIgnoreCase(serverArg)) return true;
        return addr != null && addr.equalsIgnoreCase(serverArg);
    }

    /** 旧接口 onLeScan 只给裸 AD 字节（公开 SDK 没有 ScanRecord.parseFromBytes），自己扒 0x09/0x08 名字 */
    static String nameFromBytes(byte[] raw) {
        if (raw == null) return null;
        String shortName = null;
        int i = 0;
        while (i + 1 < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0 || i + 1 + len > raw.length) break;
            int type = raw[i + 1] & 0xFF;
            if (type == 0x09) return new String(raw, i + 2, len - 1);
            if (type == 0x08 && shortName == null) shortName = new String(raw, i + 2, len - 1);
            i += len + 1;
        }
        return shortName;
    }

    static void stopScan() {
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Throwable ignored) { }
        scanner = null;
        try {
            if (leScanCb != null) adRef.stopLeScan(leScanCb);
        } catch (Throwable ignored) { }
        leScanCb = null;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--server".equals(a) && i + 1 < args.length) serverArg = args[++i];
            else if ("--psk".equals(a) && i + 1 < args.length) psk = args[++i];
            else if ("--cmd".equals(a) && i + 1 < args.length) cmd = args[++i];
            else if ("--timeout".equals(a) && i + 1 < args.length) timeoutSec = Integer.parseInt(args[++i]);
            else if ("--no-wake".equals(a)) wake = false;
            else if ("--strip-rc".equals(a)) stripRc = true;
            else if ("--no-mtu".equals(a)) noMtu = true;
            else if ("--pan".equals(a)) panMode = true;
            else if ("--pan-disconnect".equals(a)) panDisc = true;
            else if ("--pan-ip".equals(a) && i + 1 < args.length) panIp = args[++i];
            else if ("--pan-gw".equals(a) && i + 1 < args.length) panGw = args[++i];
        }
        if (serverArg == null || serverArg.trim().isEmpty()) {
            System.err.println("用法: MyBleClientCli --server <名字|MAC> --cmd <命令> [--psk 密钥] [--timeout 秒] [--no-wake] [--strip-rc] [--no-mtu]");
            System.err.println("      ... --cmd <命令> --pan [--pan-ip CIDR --pan-gw 网关]   # 先执行命令，成功后再连对端蓝牙共享（需装 PanCli.apk）");
            System.err.println("      MyBleClientCli --server <名字|MAC> --pan-disconnect    # 只有断开可以单独跑");
            System.exit(1);
        }
        if (panMode && (cmd == null || cmd.trim().isEmpty())) {
            System.err.println("用法: --pan 不能单独跑，必须和 --cmd 一起（先执行命令，成功后才连 PAN）；"
                    + "只断开用 --pan-disconnect");
            System.exit(1);
        }
        if (!panDisc && (cmd == null || cmd.trim().isEmpty())) {
            System.err.println("用法: 必须给 --cmd <命令>");
            System.exit(1);
        }
        serverArg = serverArg.trim();
        if (cmd != null && cmd.length() > 4096) die(1, "命令超长（>4096 字节）");

        // systemMain/框架初始化需要 Looper（与服务端一致），否则 Android 7.x 上
        // ActivityThread.systemMain() 会抛 InvocationTargetException
        Looper.prepareMainLooper();

        // 所有业务回调都跑在主线程 Looper 上：BluetoothLeScanner 的 ScanCallbackWrapper、
        // BluetoothAdapter.LeScanCallback 以及 connectGatt 的 GATT 回调，框架统一 post 到
        // main Looper。只 prepareMainLooper() 而不 loop()，消息就只排队不执行，
        // 表现正是「广播明明在，却一个都扫不到」（结果已进进程，回调永不触发）。
        h = new Handler(Looper.getMainLooper());

        ctx = systemContext();
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) die(1, "蓝牙不可用或未开启");
        adRef = ad;                          // PAN 连完还要接着跑命令流程，这里先存住

        // 总超时看门狗：按 deadlineMs 判定，进入 PAN 阶段会把 deadline 往后推，
        // 免得「命令已经跑完、正在连 PAN」被总超时误杀
        // 全局只是兜底：要比各阶段超时（扫描12s / MTU5s / ATT12s / 结果timeoutSec）之和更晚，
        // 否则会抢先把「命令已发但没收到结果」这类有意义的提示盖掉
        deadlineMs = System.currentTimeMillis() + (timeoutSec + 45) * 1000L;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (done) return;
                long left = deadlineMs - System.currentTimeMillis();
                if (left <= 0) { die(1, "总超时兜底（" + (timeoutSec + 45) + "s）到点，流程没走完"); return; }
                h.postDelayed(this, Math.min(left, 2000));
            }
        }, 2000);

        h.post(new Runnable() {
            @Override public void run() {
                if (panDisc) { startPan(); return; }        // 只断开，不跑命令
                pendingPan = panMode;                       // --pan = 命令成功后再连
                start(ad);
            }
        });
        Looper.loop();   // 阻塞在此派发所有框架回调；退出由 die() / 成功路径的 System.exit 完成
        die(1, "Looper 意外返回");
    }

    static void start(BluetoothAdapter ad) {
        if (serverArg.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            log("按 MAC 直连 " + serverArg);
            try {
                connect(ad.getRemoteDevice(serverArg.toUpperCase()));
            } catch (Throwable t) { die(3, "无效 MAC: " + t); }
            return;
        }
        log("扫描目标 \"" + serverArg + "\" …");
        if (wake) wakeScreen();
        else log("--no-wake：注意息屏时控制器会挂起「无过滤扫描」，多半一条广播都收不到");
        try {
            scanner = ad.getBluetoothLeScanner();
            ScanSettings ss = new ScanSettings.Builder()
       		 .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
       		 .build();
			scanner.startScan(null, ss, scanCb);   // filters=null：仍是全量广播，匹配逻辑不变

            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (gatt == null && !done) {
                        stopScan();
                        die(1, "扫描 12s 超时：目标 \"" + serverArg + "\" 未命中，期间共收到 "
                                + seenTotal + " 条广播"
                                + (seenTotal == 0
                                    ? "（一条都没进回调：屏幕大概率又熄了——息屏时 unfiltered scan 会被控制器挂起；"
                                      + "其次查位置服务是否开启。治本做法是 --server <MAC> 直连，不依赖扫描）"
                                    : "，见过的名字: " + seenNames)
                                + "。也可用 --server <MAC> 跳过扫描直连");
                    }
                }
            }, 12000);
        } catch (Throwable t1) {
            // 老系统（7.x）上扫描接口可能因权限/位置服务被拒，回退到废弃但通用的 startLeScan
            log("新扫描接口不可用（" + t1 + "），回退 startLeScan");
            try {
                adRef = ad;
                leScanCb = new BluetoothAdapter.LeScanCallback() {
                    @Override public void onLeScan(final BluetoothDevice d, final int rssi, final byte[] sr) {
                        try {
                            String name = nameFromBytes(sr);
                            handleAdv(d, name, rssi);
                        } catch (Throwable t) { log("onLeScan 异常: " + t); }
                    }
                };
                if (!ad.startLeScan(leScanCb)) die(3, "扫描启动失败（旧接口）");
            } catch (Throwable t2) {
                die(3, "扫描无法启动（" + t2 + "）。改用 MAC 直连：--server <MAC>");
            }
        }
    }

    static final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, final ScanResult r) {
            try {
                handleAdv(r.getDevice(),
                        r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName(),
                        r.getRssi());
            } catch (Throwable t) { log("onScanResult 异常: " + t); }
        }

        @Override public void onBatchScanResults(java.util.List<ScanResult> rs) {
            try {
                for (ScanResult r : rs) {
                    handleAdv(r.getDevice(),
                            r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName(),
                            r.getRssi());
                }
            } catch (Throwable t) { log("onBatchScanResults 异常: " + t); }
        }

        /** 扫描在控制器层就没跑起来时框架只走这里，不 override 就完全静默 */
        @Override public void onScanFailed(final int errorCode) {
            log("LE 扫描启动失败 errorCode=" + errorCode
                    + "（2=OUT_OF_SPACE 3=TOO_MANY_SCANNERS 4=INTERNAL 5=ALREADY_STARTED）");
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
                    stopScan();
                    die(3, "LE 扫描无法启动 errorCode=" + errorCode);
                }
            });
        }
    };

    /** 一条广播的去向：先计数留样，再判断是否目标；命中才发起连接 */
    static void handleAdv(BluetoothDevice d, String name, int rssi) {
        String addr = null;
        try { addr = d.getAddress(); } catch (Throwable ignored) { }
        if (name == null) { try { name = d.getName(); } catch (Throwable ignored) { } }
        noteSeen(name, addr, rssi);
        if (!matches(name, addr)) return;
        final BluetoothDevice target = d;
        final String shownName = name, shownAddr = addr;
        h.post(new Runnable() {
            @Override public void run() {
                if (gatt != null || done) return;
                stopScan();
                log("发现目标 " + shownAddr + "（" + shownName + "）");
                connect(target);
            }
        });
    }

    static BluetoothDevice lastDev;     // MTU 卡死时原地重连要用
    static int connGen = 0;             // 连接代数：重连后旧看门狗闭嘴
    static boolean mtuRetried = false;

    static void connect(BluetoothDevice d) {
        connGen++;
        lastDev = d;
        try {
            gatt = d.connectGatt(ctx, false, gcb);
        } catch (Throwable t) { die(3, "connectGatt 失败: " + t); }
        if (gatt == null) die(3, "connectGatt 返回 null");
        final int gen = connGen;
        // ATT 层看门狗：链路能连上但 MTU/服务发现一个回调都没有，通常是对端的 GATT 服务
        // 进程被冻结（MIUI 后台查杀 / 设备睡了）或两边配对密钥不一致。早点报出来，别干等总超时。
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != connGen || done || servicesDone) return;
                die(3, "BLE 链路已连上，但 12s 内 ATT 层毫无响应（MTU/服务发现都没回调）。"
                        + "常见原因：①对端设备睡了/服务进程被冻结 ②对端 GATT 服务已崩 "
                        + "③配对密钥两边不一致（双向取消配对后重新配对）");
            }
        }, 12000);
    }

    /** onConnectionStateChange 的 status 是 HCI 错误码，不是 GATT 码，这里翻译成人话 */
    static String hintFor(int st) {
        switch (st) {
            case 133: return "（133=GATT_ERROR，链路或配对状态有问题）";
            case 19:  return "（19=0x13 对端主动断链 Remote User Terminated Connection："
                    + "对端蓝牙栈不接受这次交互直接踢链路，服务端代码里并没有主动断开，属对端状态问题）";
            case 22:  return "（22=0x16 本地主机终止连接：多半是我们自己超时/cleanup）";
            case 5:   return "（5=认证失败：配对密钥不对或加密没起来，双向取消配对重来）";
            case 15:  return "（15=需要加密链路：对端特征声明了加密权限）";
            case 8:   return "（8=连接超时：对端掉线或在睡觉）";
            case 6:   return "（6=命令被拒：参数或状态不对）";
            default:  return "";
        }
    }

    /** 一次连接的接收侧状态清零（MTU 卡死后关链路重连要用） */
    static void resetConn() {
        rxState = ST_BACKLOG;
        authAcked = false; authWritten = false; resultTimerArmed = false;
        servicesRequested = false; servicesDone = false;
        mtu = 23;
        pending.clear();
        rx.reset();
    }

    static boolean servicesRequested = false, servicesDone = false;

    /** MTU 阶段的唯一出口（谈成 / 超时 / --no-mtu 跳过）：只做一次服务发现 */
    static void afterMtu(BluetoothGatt g, int m) {
        mtu = m;
        if (servicesRequested || done) return;
        servicesRequested = true;
        if (!g.discoverServices()) die(3, "discoverServices 提交失败");
    }

    static final BluetoothGattCallback gcb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            final int st = status, ns = newState;
            h.post(new Runnable() {
                @Override public void run() {
                    // 只认当前这条链路：MTU 卡死后我们 close() 重连，旧链路也会回调一次 DISCONNECTED，
                    // 不挡掉就会「刚重连上就被自己判死」（status=22 本地主机终止）
                    if (done || g != gatt) return;
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        log("已连接，" + (noMtu ? "按 --no-mtu 跳过 MTU 协商" : "请求 MTU 517…"));
                        if (noMtu) { afterMtu(g, 23); return; }
                        if (!g.requestMtu(517)) { afterMtu(g, 23); return; }
                        final BluetoothGatt gg = g;
                        h.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (done || servicesRequested) return;
                                if (!mtuRetried && lastDev != null) {
                                    // 没答的 MTU 请求是「在飞行中」状态，会把后面的 CCCD 写堵在
                                    // ATT 队列里（实测就是卡在“订阅结果通道”）。唯一干净的办法是
                                    // 关掉这条链路，跳过 MTU 重连一次。
                                    mtuRetried = true;
                                    noMtu = true;
                                    log("5s 没收到 MTU 回调：未完成的 MTU 请求会堵死 CCCD 写，"
                                            + "关链路重连并跳过 MTU…");
                                    try { gatt.close(); } catch (Throwable ignored) { }
                                    gatt = null;
                                    resetConn();
                                    // 必须等栈把旧链路拆干净再连：紧接着 connectGatt 会让旧 client 的
                                    // 拆除流程把新链路一起 terminate（实测 status=22 本地主机终止）
                                    h.postDelayed(new Runnable() {
                                        @Override public void run() {
                                            if (!done) connect(lastDev);
                                        }
                                    }, 1500);
                                    return;
                                }
                                log("MTU 仍无回音，按默认 MTU 23 继续（片小，但链路能走通）");
                                afterMtu(gg, 23);
                            }
                        }, 5000);
                    } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                        die(3, "连接断开/失败 status=" + st + hintFor(st));
                    }
                }
            });
        }

        @Override public void onMtuChanged(final BluetoothGatt g, final int m, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
                    log("MTU " + m + "（单片可用 " + (m - 3) + " 字节）");
                    afterMtu(g, m);
                }
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
                    servicesDone = true;
                    BluetoothGattService s = g.getService(SVC);
                    if (s == null) die(3, "没找到命令服务（对端不是 blegatt？）");
                    cCmd = s.getCharacteristic(CH_CMD);
                    cRes = s.getCharacteristic(CH_RES);
                    cAuth = s.getCharacteristic(CH_AUTH);
                    if (cCmd == null || cRes == null) die(3, "命令服务特征不全");
                    log("服务发现完成，订阅结果通道…");
                    g.setCharacteristicNotification(cRes, true);
                    BluetoothGattDescriptor d = cRes.getDescriptor(CCCD);
                    if (d == null) die(3, "RES 没有 CCCD");
                    // v2 服务端按「值 == ENABLE_INDICATION_VALUE」判定订阅（confirm=true 可靠流控，
                    // 一次只一片在途）。写 0x0003 会被它的 isSubscribed 当成未订阅然后踢链路，
                    // 所以必须与它的客户端完全一致：只开 INDICATION。
                    d.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    if (!g.writeDescriptor(d)) die(3, "订阅提交失败");
                }
            });
        }

        @Override public void onDescriptorWrite(final BluetoothGatt g,
                                                final BluetoothGattDescriptor d, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (done || g != gatt) return;      // 旧链路的残留回调要忽略
                    log("已订阅 RES。静默 1s（丢弃 --on-connect 输出）…");
                    h.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (done) return;
                            if (psk != null && !psk.isEmpty() && cAuth != null) {
                                rxState = ST_AWAIT_AUTH;      // 先置状态再提交：应答可能比写回调先到
                                cAuth.setValue(psk);
                                cAuth.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                                if (!g.writeCharacteristic(cAuth)) die(3, "AUTH 写入提交失败");
                                final BluetoothGatt gg = g;
                                h.postDelayed(new Runnable() {   // 对端不回 auth:ok 时别干等总看门狗
                                    @Override public void run() {
                                        if (done || rxState != ST_AWAIT_AUTH) return;
                                        log("5s 没收到鉴权应答，按「不回应答」的服务端处理，直接发命令");
                                        rxState = ST_RESULT;
                                        writeCmd(gg);
                                    }
                                }, 5000);
                            } else {
                                log("未配置 --psk，跳过鉴权直接发命令");
                                writeCmd(g);
                            }
                        }
                    }, 1000);
                }
            });
        }

        @Override public void onCharacteristicWrite(final BluetoothGatt g,
                                                    final BluetoothGattCharacteristic c, int status) {
            final boolean isAuth = cAuth != null && c.getUuid().equals(cAuth.getUuid());
            final int st = status;
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
                    if (st != BluetoothGatt.GATT_SUCCESS) die(3, "写入失败 status=" + st);
                    if (isAuth) { log("AUTH 已写入"); authWritten = true; maybeSendCmd(); }
                    // CMD 分片链：每写完一片接着提交下一片；队列已空时 markCmdSent() 是幂等的
                    else writeNext(g);
                }
            });
        }

        @Override public void onCharacteristicChanged(final BluetoothGatt g,
                                                      final BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue();
            if (cRes == null || !c.getUuid().equals(cRes.getUuid())) return;
            h.post(new Runnable() {
                @Override public void run() { handleChunk(v); }
            });
        }
    };

    /** CMD 写入：整条命令一律走 pending 队列。短命令 = 单帧无标记（服务端按「无标记=整条」解析），
     *  长命令 = '>' 续片 + '<' 末片，跟服务端 cmdAccum 协议一致。 */
    static void writeCmd(BluetoothGatt g) {
        try {
            byte[] b = cmd.getBytes("UTF-8");
            int per = Math.max(1, mtu - 3 - 1);   // 首字节留给 '>'/'<' 标记
            pending.clear();
            if (b.length <= per) {
                pending.addLast(b);
            } else {
                for (int off = 0; off < b.length; off += per) {
                    int len = Math.min(per, b.length - off);
                    byte[] piece = new byte[len + 1];
                    piece[0] = (byte) ((off + len >= b.length) ? '<' : '>');
                    System.arraycopy(b, off, piece, 1, len);
                    pending.addLast(piece);
                }
            }
            writeNext(g);
        } catch (Throwable t) { die(3, "CMD 编码失败: " + t); }
    }

    static final java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<byte[]>();

    static void writeNext(final BluetoothGatt g) {
        byte[] p = pending.pollFirst();
        if (p == null) {                       // 队列已排空（末片的写回调会走到这里）
            armResultWindow();
            return;
        }
        cCmd.setValue(p);
        cCmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!g.writeCharacteristic(cCmd)) die(3, "CMD 分片提交失败");
        // 末片（'<' 或无标记单帧）一提交就立刻开收结果窗口：服务端的通知可能比本机的
        // onCharacteristicWrite 先到，等回调再开就晚了。
        if (pending.isEmpty()) armResultWindow();
        // 中间片由 onCharacteristicWrite 驱动下一片
    }

    /** 进入「等命令结果」状态 + 起零片超时；幂等 */
    static void armResultWindow() {
        if (rxState == ST_BACKLOG) rxState = ST_RESULT;   // 无 psk 的直达路径
        if (resultTimerArmed) return;
        resultTimerArmed = true;
        log("CMD 已发送，等待结果…");
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!done) die(1, "命令已发但 " + timeoutSec + "s 内没收到结果");
            }
        }, timeoutSec * 1000L);
    }

    /** 服务端分帧：帧首字节 '+' 续片 / '-' 末片 / '!' 错误帧；另外还有一类错误是把 "!xxx"
     *  当正文经 '-' 末片发来（-auth:ok 的对应拒绝、AUTH_REQUIRED 等），两种形态都要认。
     *  一条消息算不算结果，看 rxState，不看写回调时间戳。 */
    static void handleChunk(byte[] v) {
        if (v == null || v.length == 0) return;
        if (cmdDone) return;          // 命令结果已经取到，之后的任何通知都不再当结果（PAN 阶段照旧跑）
        char mark = (char) (v[0] & 0xFF);

        if (mark == 'T') { v2T(v); return; }   // v2 可靠传输：数据帧
        if (mark == 'D') { v2D(v); return; }   // v2 可靠传输：整包 SHA-256 校验帧
        if (mark == '+') {
            if (rxState != ST_RESULT) return;                 // 结果窗口外的续片（连接期回显）直接丢
            try { rx.write(v, 1, v.length - 1); } catch (Throwable ignored) { }
            return;
        }
        if (mark == '!') {
            String msg = new String(v, 1, v.length - 1);
            rx.reset();
            if (rxState == ST_RESULT) die(2, "服务端错误: " + msg);
            log("连接期错误帧（忽略）: " + msg);               // 例如 backlog 撑爆队列
            return;
        }
        if (mark != '-') return;                              // 未知标记：忽略，绝不当成结果

        try { rx.write(v, 1, v.length - 1); } catch (Throwable ignored) { }
        String whole;
        try { whole = new String(rx.toByteArray(), "UTF-8"); }
        catch (Throwable t) { whole = new String(rx.toByteArray()); }
        rx.reset();
        if (done) return;

        // 鉴权应答永远优先吃掉，绝不允许被当成命令结果打印出去
        if (whole.equals("-auth:ok") || whole.startsWith("auth:ok")) {
            authAcked = true;
            log("鉴权通过");
            maybeSendCmd();
            return;
        }
        if (whole.startsWith("!")) {                          // 正文形态的错误
            if (rxState == ST_RESULT || whole.contains("denied") || whole.startsWith("!auth"))
                die(2, "服务端拒绝: " + whole);
            log("连接期错误（忽略）: " + whole);
            return;
        }
        if (rxState != ST_RESULT) {
            log("丢弃" + (rxState == ST_BACKLOG ? "连接期输出（--on-connect 回显）" : "鉴权阶段的杂讯")
                    + "：" + whole.length() + " 字符");
            return;
        }
        done = true;          // 静音后续 GATT 回调：cleanup() 主动断开也会回调，别让它误判成失败
        cmdDone = true;       // 结果已取到，之后任何通知都不再当结果
        if (stripRc) whole = stripRcLine(whole);
        System.out.print(whole);
        System.out.flush();
        log("接收完成 " + whole.length() + " 字符");
        cleanup();                                     // 关掉 GATT/扫描，把蓝牙让给 PAN
        if (!pendingPan) { System.exit(0); }
        // --pan = 命令成功之后才连。done 保持置位（GATT 回调与残余通知全部静音），
        // 靠 panActive 让 die() 在 PAN 阶段仍能收尾退出。
        deadlineMs = System.currentTimeMillis() + Math.max(20, timeoutSec) * 1000L;
        log("命令完成，接着连接 PAN…");
        h.post(new Runnable() {
            @Override public void run() { startPan(); }
        });
    }

    /** AUTH 的写回调与鉴权应答谁先到不定，两者都到齐才发命令 */
    static void maybeSendCmd() {
        if (rxState != ST_AWAIT_AUTH) return;
        if (!(authAcked && authWritten)) return;
        rxState = ST_RESULT;
        writeCmd(gatt);
    }

    /** 服务端在结果首行加了 #rc=N。--strip-rc 时把它从 stdout 摘掉（退出码转记到 stderr），
     *  这样 R=$(...) 拿到的就是命令本身的纯输出。注意本进程退出码仍表示传输是否成功，
     *  跟对端命令的退出码不是一回事，要按对端 rc 分流就别加这个参数、自己读首行。 */
    static String stripRcLine(String whole) {
        int nl = whole.indexOf('\n');
        String first = nl < 0 ? whole : whole.substring(0, nl);
        if (!first.startsWith("#rc=")) return whole;
        log("对端命令退出码: " + first.substring(4));
        return nl < 0 ? "" : whole.substring(nl + 1);
    }

    // ==================== PAN：连对端的蓝牙共享（NAP），本机当 PANU 客户端 ====================
    //
    // 裸 app_process 拿不到 PanService 的 binder：自己 bindService 会被 AMS 拒
    // （Unable to find app for caller），uid 换成 1000 也一样；让 system_server 代绑
    // （IBluetoothManager.bindBluetoothProfileService，GATT/A2DP/PAN 一律返回 false）也失败，
    // 因为 ActiveServices 是按 Binder.getCallingPid() 找 ProcessRecord 的，我们没有那条记录。
    // 所以真正的 connect() 放在同机的 PanCli.apk 里执行（Zygote 起的正常 app 进程），
    // 本 CLI 只做三件事：触发它 → 轮询它落盘的状态 → 观察网卡并在需要时手工配 IP。
    //   APK 工程：~/MyCode/MyBle/PanApk/  （构建：bash PanApk/build.sh && adb install -r PanApk/PanCli.apk）

    static final String PAN_PKG = "com.qw.pancli";
    static final String PAN_STATUS = "/data/data/" + PAN_PKG + "/files/pan.status";
    static final String PAN_LOG = "/data/data/" + PAN_PKG + "/files/pan.log";

    /** PAN 阶段失败的退出码：命令结果已经拿到就用 4（传输 OK、PAN 没成），否则 1 */
    static void panFail(String m) { die(cmdDone ? 4 : 1, m); }

    static void startPan() {
        panActive = true;
        if (trim(sh("pm path " + PAN_PKG)).isEmpty()) {
            panFail("没装 " + PAN_PKG + "（PanCli.apk）——先 bash PanApk/build.sh 再 adb install -r PanCli.apk");
        }
        sh("rm -f " + PAN_STATUS);                       // 别读到上一轮的旧结果
        String args = "--es dev \"" + serverArg + "\"" + (panDisc ? " --es act disconnect" : "");
        // 用显式广播触发：am start + startService 那条路会被 Android 8+ 的后台限制拦
        String r = sh("am broadcast -n " + PAN_PKG + "/.PanRcv " + args);
        log("am 返回：" + trim(r));
        if (r != null && r.toLowerCase().contains("error")) {
            log("am 报错了：检查包名/组件名，或 app 是否被 force-stop（广播投不到）");
        }
        panDeadline = System.currentTimeMillis() + Math.max(20, timeoutSec) * 1000L;
        h.postDelayed(panPoll, 1000);
    }

    static final Runnable panPoll = new Runnable() {
        @Override public void run() {
            String st = trim(sh("cat " + PAN_STATUS + " 2>/dev/null"));
            log("APK 状态: " + (st.isEmpty() ? "（还没写状态文件）" : st));

            if (st.startsWith("CONNECTED")) { panUp(); return; }
            if (st.startsWith("DISCONNECTED")) {
                if (!cmdDone) { System.out.println("disconnected"); System.out.flush(); }
                System.exit(0);
            }
            if (st.startsWith("FAILED")) panFail("APK 侧失败：" + st);

            if (System.currentTimeMillis() > panDeadline) {
                panFail("等 PAN 结果超时（" + timeoutSec + "s）。排查：①对端「蓝牙网络共享」是否开着"
                        + "②两边是否仍配对（bondState）③APK 有没有被拉起来，看 " + PAN_LOG);
            }
            h.postDelayed(this, 1000);
        }
    };

    /** 连上了：确认网卡和地址。安卓自己会跑 DHCP+DNS，通常不需要 --pan-ip */
    static void panUp() {
        String iface = trim(sh("ls /sys/class/net 2>/dev/null | grep -iE 'bnep|bt-pan' | head -1"));
        if (iface.isEmpty()) { panFail("状态 CONNECTED 但没看到 bnep/bt-pan 网卡"); }
        // BNEP 通了之后 DHCP 还要一两秒才落地址，轮询到为止（否则刚 CONNECTED 就查会报「没有 IPv4」）
        String addr = "";
        for (int i = 0; i < 20 && addr.isEmpty(); i++) {
            addr = trim(sh("ip -o -4 addr show dev " + iface + " scope global 2>/dev/null | awk '{print $4}'"));
            if (addr.isEmpty()) sleep(500);
        }
        log("网卡 " + iface + " 地址 " + (addr.isEmpty() ? "（10s 内没拿到 IPv4）" : addr));
        log("默认路由: " + trim(sh("ip -o route show table all 2>/dev/null | grep 'default.*" + iface + "'")));
        log("提醒：安卓会把这条 PAN 抬成默认网络，网关上行可能从蜂窝切到对端；"
                + "不想要就用 --pan-disconnect 断掉");

        if (addr.isEmpty() && panIp != null) {              // 只有 DHCP 没起来时才手工配
            sh("ip link set " + iface + " up");
            sh("ip addr add " + panIp + " dev " + iface);
            if (panGw != null) sh("ip route add default via " + panGw + " dev " + iface);
            addr = trim(sh("ip -o -4 addr show dev " + iface + " 2>/dev/null | awk '{print $4}'"));
            log("手工配置后地址 " + addr);
        }
        // --pan 一定跟在 --cmd 之后，stdout 早就是命令结果了，网卡信息只进日志
        log("PAN 就绪：" + iface + (addr.isEmpty() ? "（无 IPv4）" : " " + addr));
        System.exit(0);
    }

    static String trim(String s) { return s == null ? "" : s.trim(); }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static String sh(String cmdline) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmdline)
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) bo.write(buf, 0, n);
            p.waitFor();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) { return ""; }
    }
    // ==================== v2 可靠传输（与服务端 2.0.dev 对齐）====================
    // 接收侧三重校验：逐帧 CRC16-CCITT(poly 0x1021, init 0xFFFF)、序号连续、整包 SHA-256。
    // 校验不过绝不输出。实现照抄 Note9 客户端 BleService.handleChunk（未改其源码）。

    static int rvTid = -1, rvTotal = 0, rvNext = 0, rvNacks = 0;
    static boolean rvDigestGot = false;
    static byte[] rvDigest = null;

    static final Runnable chunkTimeout = new Runnable() {
        @Override public void run() {
            if (done || rvTid < 0) return;
            if (rvTotal > 1 && rvNext >= rvTotal && !rvDigestGot) {
                v2Nack(rvTid, rvTotal, "digest frame missing");
                return;
            }
            if (rx.size() == 0) return;
            v2Nack(rvTid, rvNext, "no chunk within 4s");
        }
    };

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

    static void v2T(byte[] v) {
        if (v.length < 9) return;
        int crc = ((v[v.length - 2] & 0xFF) << 8) | (v[v.length - 1] & 0xFF);
        int tid = ((v[1] & 0xFF) << 8) | (v[2] & 0xFF);
        int seq = ((v[3] & 0xFF) << 8) | (v[4] & 0xFF);
        int total = ((v[5] & 0xFF) << 8) | (v[6] & 0xFF);
        if (crc16(v, 0, v.length - 2) != crc) {
            if (tid == rvTid) v2Nack(tid, seq, "CRC16 check failed");
            return;
        }
        if (tid != rvTid) {                       // 新传输：重置接收状态
            rx.reset();
            rvTid = tid; rvTotal = total; rvNext = 0; rvNacks = 0;
            rvDigest = null; rvDigestGot = false;
        }
        if (seq < rvNext) return;                 // 重传窗口里的重复片
        if (seq > rvNext) { v2Nack(tid, rvNext, "missing seq " + rvNext + "(got " + seq + ")"); return; }
        try { rx.write(v, 7, v.length - 9); } catch (Throwable ignored) { }
        rvNext++;
        if (rvTotal > 1) { h.removeCallbacks(chunkTimeout); h.postDelayed(chunkTimeout, 4000); }
        if (rvTotal == 1) v2Complete();           // 单片：CRC 已保证完整
    }

    static void v2D(byte[] v) {
        if (v.length < 37) return;
        int crc = ((v[v.length - 2] & 0xFF) << 8) | (v[v.length - 1] & 0xFF);
        if (crc16(v, 0, v.length - 2) != crc) return;
        int tid = ((v[1] & 0xFF) << 8) | (v[2] & 0xFF);
        if (tid == rvTid && rvNext >= rvTotal && !rvDigestGot && rvTotal > 1) {
            rvDigest = new byte[32];
            System.arraycopy(v, 3, rvDigest, 0, 32);
            rvDigestGot = true;
            v2Verify();
        }
    }

    static void v2Verify() {
        try {
            byte[] actual = java.security.MessageDigest.getInstance("SHA-256").digest(rx.toByteArray());
            if (java.util.Arrays.equals(actual, rvDigest)) { v2Complete(); return; }
        } catch (Throwable ignored) { }
        log("SHA-256 不符：丢弃 " + rx.size() + " 字节，请求全量重传");
        rx.reset(); rvNext = 0; rvDigestGot = false; rvDigest = null;
        v2Nack(rvTid, 0, "SHA-256 mismatch, full retransmit");
    }

    /** 一段传输完成：按内容分流（版本帧/鉴权帧/#rc 结束帧/正文） */
    static void v2Complete() {
        h.removeCallbacks(chunkTimeout);
        byte[] data = rx.toByteArray();
        v2Abort();
        String whole;
        try { whole = new String(data, "UTF-8"); } catch (Throwable t) { whole = new String(data); }
        String t2 = whole.trim();
        if ("auth:ok".equals(t2)) { authAcked = true; log("鉴权通过（v2）"); maybeSendCmd(); return; }
        if (t2.startsWith("blegatt-ver:")) { log("服务端版本: " + t2.substring(11).trim()); return; }
        if ("wakeup-ok".equals(t2)) { return; }
        if (t2.startsWith("!")) {
            if (rxState == ST_RESULT && !cmdDone) die(2, "服务端错误: " + t2.substring(1));
            log("连接期错误（忽略）: " + t2);
            return;
        }
        if (t2.startsWith("#rc=")) {
            // --on-connect 回显也有自己的 #rc=0 收尾帧，它在本命令发出之前到达；
            // 只有 CMD 已发出（ST_RESULT）之后的 #rc 才是本命令的结束帧
            if (rxState != ST_RESULT) {
                log("忽略连接期 #rc 帧（" + t2 + "，属于 --on-connect 回显）");
                return;
            }
            finishAndMaybePan("对端命令退出码: " + t2.substring(4).trim() + "（CRC/SHA 已校验）");
            return;
        }
        if (rxState != ST_RESULT) {               // on-connect 回显等：进日志不进 stdout
            log("丢弃连接期输出（v2）：'" + t2 + "'");
            return;
        }
        System.out.print(whole);
        System.out.flush();
    }

    static void v2Nack(int tid, int fromSeq, String why) {
        h.removeCallbacks(chunkTimeout);
        if (rvNacks >= 5) {
            v2Abort();
            panFail("重传请求已达 5 次仍未收全（tid=" + tid + "），放弃本轮");
            return;
        }
        rvNacks++;
        log("请求重传: #retrans " + tid + " " + fromSeq + "（" + why + "）");
        if (gatt != null && cCmd != null) {
            cCmd.setValue(("#retrans " + tid + " " + fromSeq).getBytes());
            cCmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(cCmd)) log("NACK 提交失败（对端写队列忙）");
        }
        h.postDelayed(chunkTimeout, 4000);
    }

    static void v2Abort() {
        h.removeCallbacks(chunkTimeout);
        rx.reset();
        rvTid = -1; rvTotal = 0; rvNext = 0; rvNacks = 0;
        rvDigest = null; rvDigestGot = false;
    }

    /** 命令收尾：打退出码、收尾 GATT，按需进入 PAN 阶段（v1/v2 共用） */
    static void finishAndMaybePan(String rcLine) {
        done = true;          // 静音后续 GATT 回调：cleanup() 主动断开也会回调，别让它误判成失败
        cmdDone = true;       // 结果已取到，之后任何通知都不再当结果
        log(rcLine);
        cleanup();                                     // 关掉 GATT/扫描，把蓝牙让给 PAN
        if (!pendingPan) { System.exit(0); }
        // --pan = 命令成功之后才连。done 保持置位（GATT 回调与残余通知全部静音），
        // 靠 panActive 让 die() 在 PAN 阶段仍能收尾退出。
        deadlineMs = System.currentTimeMillis() + Math.max(20, timeoutSec) * 1000L;
        log("命令完成，接着连接 PAN…");
        h.post(new Runnable() {
            @Override public void run() { startPan(); }
        });
    }

    static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object inst = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(inst);
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException)
                    ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
            die(3, "取 system Context 失败: " + t
                    + (c != null && c != t ? " / 根因: " + c : ""));
            return null;
        }
    }
}
