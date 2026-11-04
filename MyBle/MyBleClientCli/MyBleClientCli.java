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
import android.os.Looper;

/**
 * MyBleClientCli —— 命令行 BLE 客户端（app_process 直跑，root 身份）。
 *
 * 用法:
 *   MyBleClientCli --server <名字|MAC> --psk <密钥> --cmd <命令> [--timeout 秒] [--no-wake] [--strip-rc] [--no-mtu]
 *
 * 按名字扫描时默认先发一次 KEYCODE_WAKEUP 点亮屏幕：Android 的 native ScanManager
 * 在息屏状态下会挂起「不带 filter 的扫描」，日志只有一行
 * 「Cannot start unfiltered scan in screen-off」，回调里则表现为 0 条广播。
 * 已知对端 MAC 时用 --server <MAC> 直连，完全不依赖屏幕状态，适合无人值守网关。
 *
 * 流程: 连接 → 请求 MTU → 发现服务 → 订阅 RES 通知 → 静默 1s（丢弃 --on-connect
 * 输出）→ 写 AUTH 密钥 → 等到 -auth:ok 应答再写 CMD（长命令自动 '>'/'<' 分片）→
 * 收 RES 分片拼装 → 末片一次性 UTF-8 解码打印到 stdout，退出。
 *
 * 输出约定: 结果走 stdout（供脚本 $() 取用），日志走 stderr。
 * 正文首行是服务端加的 #rc=N（对端命令退出码），脚本里想要纯输出就加 --strip-rc。
 * 退出码: 0=成功  1=超时/参数错  2=服务端错误帧  3=连接失败/断开
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
        if (done) return;
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
        }
        if (serverArg == null || serverArg.trim().isEmpty() || cmd == null || cmd.trim().isEmpty()) {
            System.err.println("用法: MyBleClientCli --server <名字|MAC> --cmd <命令> [--psk 密钥] [--timeout 秒] [--no-wake] [--strip-rc] [--no-mtu]");
            System.exit(1);
        }
        serverArg = serverArg.trim();
        if (cmd.length() > 4096) die(1, "命令超长（>4096 字节）");

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

        // 总超时看门狗
        h.postDelayed(new Runnable() {
            @Override public void run() { die(1, "超时（" + timeoutSec + "s）未完成"); }
        }, timeoutSec * 1000L);

        h.post(new Runnable() {
            @Override public void run() { start(ad); }
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
            scanner.startScan(scanCb);
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

    static void connect(BluetoothDevice d) {
        try {
            gatt = d.connectGatt(ctx, false, gcb);
        } catch (Throwable t) { die(3, "connectGatt 失败: " + t); }
        if (gatt == null) die(3, "connectGatt 返回 null");
        // ATT 层看门狗：链路能连上但 MTU/服务发现一个回调都没有，通常是对端的 GATT 服务
        // 进程被冻结（MIUI 后台查杀 / 设备睡了）或两边配对密钥不一致。早点报出来，别干等总超时。
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (done || servicesDone) return;
                die(3, "BLE 链路已连上，但 12s 内 ATT 层毫无响应（MTU/服务发现都没回调）。"
                        + "常见原因：①对端设备睡了/服务进程被冻结 ②对端 GATT 服务已崩 "
                        + "③配对密钥两边不一致（让对端忘记本机后重新配对，或用 --no-mtu 试探）");
            }
        }, 12000);
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
                    if (done) return;
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        log("已连接，" + (noMtu ? "按 --no-mtu 跳过 MTU 协商" : "请求 MTU 517…"));
                        if (noMtu) { afterMtu(g, 23); return; }
                        if (!g.requestMtu(517)) { afterMtu(g, 23); return; }
                        final BluetoothGatt gg = g;
                        h.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (done || servicesRequested) return;
                                log("5s 没收到 MTU 回调，按默认 MTU 23 继续（片小，但链路能走通）");
                                afterMtu(gg, 23);
                            }
                        }, 5000);
                    } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                        die(3, "连接断开/失败 status=" + st
                                + (st == 133 ? "（133=GATT_ERROR，链路或配对状态有问题）" : ""));
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
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(d)) die(3, "订阅提交失败");
                }
            });
        }

        @Override public void onDescriptorWrite(final BluetoothGatt g,
                                                final BluetoothGattDescriptor d, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
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
        char mark = (char) (v[0] & 0xFF);

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
        done = true;
        if (stripRc) whole = stripRcLine(whole);
        System.out.print(whole);
        System.out.flush();
        log("接收完成 " + whole.length() + " 字符");
        cleanup();
        System.exit(0);
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
