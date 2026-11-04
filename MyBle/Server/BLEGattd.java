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

    // ---- 默认值（全部可用命令行覆盖）----
    static String advName = "r4a";
    static boolean nameExplicit = false;      // --name 显式给出才改 adapter 名
    static boolean echoOnWrite = false;       // 写特征内容回弹成通知（便于验证通知链路）
    static String svcUuidStr = "8e7a1f01-2b3c-4d5e-6f70-8192a3b4c5d6";
    // 标准 Battery Service：0x180F / Battery Level 0x1A19（uint8 0~100）
    static String battSvcUuidStr = "0000180f-0000-1000-8000-00805f9b34fb";
    static String battLvlUuidStr = "00001a19-0000-1000-8000-00805f9b34fb";
    static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";
    static int batteryNotifySec = 60;
    static String advSvcUuid = null;      // 放进 AD 的服务 UUID（标准 16 位，如 180f）
    static String writeCharUuidStr = "8e7a1f03-2b3c-4d5e-6f70-8192a3b4c5d6";
    static String notifyCharUuidStr = "8e7a1f04-2b3c-4d5e-6f70-8192a3b4c5d6";
    static int mfgId = 0xABCD;
    static byte[] mfgData = new byte[0];    // 默认不放厂商数据（要放用 --mfg / --adv-text）
    static boolean connectable = true;
    static boolean includeNameInAdv = true;
    static boolean includeTxPower = true;
    static boolean includeServiceUuidInAdv = false; // 由 --adv-svc 打开
    static boolean advertiseOnly = false;   // 只广播，不起 GATT
    static boolean execOnWrite = false;     // 写特征当命令执行（危险，默认关闭）
    static String psk = null;               // 预共享密钥：非空则命令通道强制先 AUTH
    static int maxOutBytes = 4096;          // 命令输出上限，超出截断
    static int cmdTimeoutSec = 10;          // 单条命令最长执行时间
    // onNotificationSent 迟迟不来就判定"回调丢失"并自愈；连续多次丢失则改节流发送
    static int sendAckTimeoutMs = 2000;
    static int paceMs = 40;                 // 不再信任回调时的片间隔（节流模式）
    static final int MAX_QUEUE_ITEMS = 200; // 每设备待发片上限
    static final int MAX_CMD_BYTES = 65536; // 分片命令累计上限，超限丢弃防堆积
    /** 分片命令拼接缓冲：客户端 MTU 小于命令长度时按 '>'/（'<' 末片）分片写入 */
    static final Map<String, java.io.ByteArrayOutputStream> cmdAccum = new HashMap<>();
    static String onConnectCmd = null;      // 客户端一连上就自动执行的命令
    static int logOutBytes = 1024;          // 设备日志里打印 exec 输出的上限，0=不打印
    static String authCharUuidStr = "8e7a1f05-2b3c-4d5e-6f70-8192a3b4c5d6";
    static String advText = null;           // 广播包载荷模板（渲染后写入厂商数据）
    static String srText = null;            // scan response 载荷模板（独立的第二个 31 字节）
    static int refreshSec = 0;              // >0 时按模板自动刷新广播内容
    // 省电默认：低占空比广播（约 1s 间隔）+ 中低发射功率。
    // 发现速度会慢零点几秒，但射频常开的功耗大头直接砍掉；要快可用 --adv-mode/--adv-tx 调回
    static int advMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
    static int advTx = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
    static boolean keepAdv = false;   // true=连接后继续广播（旧行为）；默认连上即停广播省电
    static boolean subDump = false;   // true=pushBattery 时打印订阅表诊断（诊断用，平时关）
    static int advTimeoutSec = 0;

    // ---- 运行态 ----
    static BluetoothAdapter adapter;
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
    // 每连接的鉴权/MTU/待发通知队列（全部只在主线程访问，除了 executor 线程通过 post 投递）
    static final java.util.Set<String> authed = new java.util.HashSet<String>();
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
    static final Map<String, BluetoothDevice> connected = new HashMap<>();
    static final Deque<String> events = new ArrayDeque<>();
    static AdvertiseCallback advCallback;
    static volatile boolean advActive = false;
    static volatile int lastAdvError = 0;

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "-h") || hasFlag(args, "--help")) { usage(); return; }
        parseInto(args);

        if (hasFlag(args, "--uid")) {
            int uid = Integer.parseInt(nextArg(args, "--uid"));
            android.system.Os.setgid(uid);
            android.system.Os.setuid(uid);
        }

        log("启动 pid=" + Process.myPid() + " uid=" + android.system.Os.getuid());

        // 框架回调都要投递到当前线程的 Looper，因此这里必须在主线程准备并开启消息循环
        Looper.prepareMainLooper();
        mainHandler = new android.os.Handler(Looper.myLooper());

        // 主线程负责消息循环；初始化与交互放到 worker 线程，避免自己等自己
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                final CountDownLatch ready = new CountDownLatch(1);
                final CountDownLatch advDone = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { setupGatt(); } catch (Throwable t) {
                            log("FATAL setup: " + t); t.printStackTrace(); System.exit(1);
                        }
                        ready.countDown();
                    }
                });
                try {
                    if (!ready.await(10, TimeUnit.SECONDS)) { log("setup 超时"); System.exit(1); }
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

    static void setupGatt() {
        checkSingleInstance();
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { fail("getDefaultAdapter()==null，bluetooth_manager 不可达"); }
        if (!adapter.isEnabled()) { fail("蓝牙未开启：先 adb shell svc bluetooth enable"); }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) { fail("getBluetoothLeAdvertiser()==null"); }

        log("adapter=" + adapter.getName() + " " + adapter.getAddress()
                + " state=" + adapter.getState()
                + " le=" + adapter.isEnabled());

        if (advertiseOnly) { log("--adv-only：跳过 GATT server"); return; }

        Context ctx = systemContext();
        if (ctx == null) { fail("无 Context，无法 openGattServer（ActivityThread.systemMain 不可用）"); }
        BluetoothManager mgr = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mgr == null) { fail("getSystemService(BLUETOOTH_SERVICE)==null"); }
        gattCb = new GattCb();
        server = mgr.openGattServer(ctx, gattCb);
        if (server == null) { fail("openGattServer 返回 null"); }
        if (splitServers) {
            serverBatt = mgr.openGattServer(ctx, gattCb);
            if (serverBatt == null) { fail("Battery 的 openGattServer 返回 null"); }
            log("--split-servers：Battery 挂在独立 GATT server 实例上");
        }

        BluetoothGattService cmdSvc = buildCmdService();
        BluetoothGattService basSvc = buildBatteryService();
        BluetoothGattServer basHost = (serverBatt != null) ? serverBatt : server;

        if (battFirst && serverBatt == null) {
            log("--batt-first：先注册 Battery，再注册 cmd（验证只映射第一个服务的缺陷）");
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
            log("命令通道：预共享密钥门控已启用（写 CMD 前必须先向 AUTH 写密钥）");
        } else if (execOnWrite) {
            log("!! --exec 无鉴权模式：蓝牙范围内任何客户端都能执行 root 命令");
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
        svc.addCharacteristic(writeChar);
        svc.addCharacteristic(authChar);
        svc.addCharacteristic(notifyChar);
        return svc;
    }

    /** 标准 Battery Service 0x180F -> Battery Level 0x1A19，纯 1 字节 */
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
            String k = device.getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.put(k, device);
                authed.remove(k);
                if (!connHandled.add(k)) {
                    log("重复 CONNECTED " + k + "（split-servers 下两个 server 实例各收一次，忽略）");
                    return;
                }
                resetSession(k);   // 关键：清掉上一轮遗留的"在途"标志/队列/CCCD
                event("connect " + k);
                log("CONNECTED " + k + " status=" + status + "（会话状态已重置）");
                // 省电大头：连接建立后广播已无意义（一个外设一次只服务一个主设备），
                // 停掉广播让射频进入低占空比；断开时会自动重启广播等重连
                if (!keepAdv && advertiser != null && advActive) {
                    try {
                        advertiser.stopAdvertising(advCallback);
                        advActive = false;
                        log("已连接，停广播省电（--keep-adv 可恢复常开广播）");
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
                connected.remove(k);
                authed.remove(k);
                resetSession(k);   // 队列/在途/序号/CCCD 全部归零
                event("disconnect " + k);
                log("DISCONNECTED " + k + " status=" + status);
                // 断开后自动重启广播，等客户端重连；载荷用当前最新值
                if (!keepAdv && advertiser != null && !advertiseOnly) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            log("对端断开，重启广播等待重连");
                            try { startAdvertising(null); }
                            catch (Throwable t) { log("重启广播失败: " + t); }
                        }
                    });
                }
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
            sendSeq.remove(k);
            ackSeq.remove(k);
            lastSendAt.remove(k);
            lostAck.remove(k);
            trustSentCb.remove(k);
            stallLogAt.remove(k);
            for (Iterator<String> it = cccdEnabled.keySet().iterator(); it.hasNext(); ) {
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
                // 绝不打印密钥本身
                boolean pass = psk != null && text.trim().equals(psk.trim());
                if (pass) { authed.add(addr); } else { authed.remove(addr); }
                log("AUTH " + addr + " -> " + (psk == null ? "服务端未配置 --psk，拒绝"
                        : (pass ? "OK" : "FAIL")) + " (len=" + text.length() + ")");
                enqueueNotify(device, pass ? "-auth:ok" : "!auth:denied");
                return;
            }

            log("WRITE " + ch.getUuid() + " from " + addr + ": \"" + text + "\"");
            event("write " + text);

            if (writeChar != null && ch.getUuid().equals(writeChar.getUuid())) {
                lastWrite = value == null ? new byte[0] : value.clone();
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
                if (psk != null) {
                    if (authed.contains(addr)) {
                        execCommand(device, cmdText.trim());
                    } else {
                        log("CMD 被拒（未鉴权）" + addr);
                        enqueueNotify(device, "!denied:AUTH_REQUIRED 先向 AUTH 特征写密钥");
                    }
                } else if (execOnWrite) {
                    execCommand(device, cmdText);
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
            cccdEnabled.put(device.getAddress() + "|" + desc.getCharacteristic().getUuid(), enable);
            if (responseNeeded) {
                srvFor(desc.getCharacteristic().getUuid()).sendResponse(device, requestId,
                        BluetoothGatt.GATT_SUCCESS, offset, null);
            }
            log("CCCD " + shortOf(desc.getCharacteristic().getUuid()) + " " + device.getAddress()
                    + " notify=" + enable + " [" + hex(value) + "]");
            dumpSubState("CCCD后");   // 只读诊断：看 put 进去的键到底长什么样
            if (enable) {
                Deque<NotifyItem> q = queues.get(device.getAddress());
                log("订阅生效，待发队列=" + (q == null ? 0 : q.size()) + " 片，开始冲刷");
                pumpQueue(device.getAddress());   // 把 --on-connect 攒下的输出冲出去
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor desc) {
            Boolean en = cccdEnabled.get(device.getAddress() + "|" + desc.getCharacteristic().getUuid());
            byte[] v = (en == null || !en)
                    ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
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

    /** AD 里真正会出现的名字：显式 --name 用它，否则是协议栈当前的 adapter 名 */
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

    /** 只读诊断：打印订阅表/连接表/期望键，验证 isSubscribed 为什么对 1a19 失配 */
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

    /** 电量电压会变，按 --refresh 周期重算，内容变了才重开广播（不影响已建立的 GATT 连接） */
    static void startRefresh() {
        if (refreshSec <= 0 || (advText == null && srText == null)) return;
        log("--refresh " + refreshSec + "s：载荷变化时重开广播（已连接客户端不受影响）");
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
        // 改名与「名字是否进 AD」解耦：显式给了 --name 就改
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
    static void execCommand(final BluetoothDevice device, final String cmd) {
        if (cmdExec == null) { log("命令通道未初始化（--adv-only?）"); return; }
        cmdExec.execute(new Runnable() {
            @Override public void run() {
                log("EXEC " + device.getAddress() + " <- " + cmd);
                String out;
                java.lang.Process p = null;
                try {
                    p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                            .redirectErrorStream(true).start();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n, total = 0;
                    boolean overflow = false;
                    while ((n = p.getInputStream().read(buf)) > 0) {
                        bo.write(buf, 0, n);
                        total += n;
                        if (total > maxOutBytes) { overflow = true; break; }
                    }
                    boolean finished = p.waitFor(cmdTimeoutSec, TimeUnit.SECONDS);
                    if (!finished) { p.destroy(); p.waitFor(); }
                    out = new String(bo.toByteArray());
                    if (overflow) {
                        out = out.substring(0, Math.min(out.length(), maxOutBytes))
                                + "\n[截断：输出超过 " + maxOutBytes + " 字节]";
                    }
                    out = (finished ? "#rc=" + p.exitValue() + "\n" : "!timeout " + cmdTimeoutSec + "s\n") + out;
                } catch (Throwable t) {
                    out = "!exec-failed " + t;
                } finally {
                    if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
                }
                final String res = out;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        int bytes = res.getBytes().length;
                        log("EXEC 结果 " + bytes + " 字节 -> " + device.getAddress());
                        if (logOutBytes > 0) {
                            String shown = res.length() > logOutBytes
                                    ? res.substring(0, logOutBytes) + "\n…[日志截断，共 "
                                      + res.length() + " 字符，改 --log-out 放宽]"
                                    : res;
                            String[] lines = shown.split("\n", -1);
                            for (int i = 0; i < lines.length; i++) {
                                log((i == 0 ? "┌ " : "│ ") + lines[i]);
                            }
                            log("└ " + lines.length + " 行 / " + bytes + " 字节");
                        }
                        enqueueNotify(device, res);
                    }
                });
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

    /** RES 通道：按 MTU 切片入队；客户端还没订阅就先攒着，CCCD 打开时再冲出去 */
    static void enqueueNotify(BluetoothDevice device, String text) {
        if (notifyChar == null) return;
        String addr = device.getAddress();
        int per = Math.max(1, chunkSize(addr) - 1);        // 首字节留给 + / - 续传标记
        byte[] raw = text.getBytes();
        // 写特征回调也在 binder 线程：入队与泵送必须持同一把锁，避免和出队并发
        synchronized (PUMP_LOCK) {
            Deque<NotifyItem> q = queueOf(addr);
            if (raw.length == 0) {
                q.addLast(new NotifyItem(server, notifyChar, "-".getBytes()));
            } else {
                for (int off = 0; off < raw.length; off += per) {
                    int len = Math.min(per, raw.length - off);
                    byte[] piece = new byte[len + 1];
                    piece[0] = (byte) ((off + len >= raw.length) ? '-' : '+');
                    System.arraycopy(raw, off, piece, 1, len);
                    q.addLast(new NotifyItem(server, notifyChar, piece));
                }
            }
            trimQueue(addr);
            pumpQueueLocked(addr);
        }
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
        s = s.toLowerCase(Locale.US);
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
        boolean ok;
        try {
            ok = item.srv.notifyCharacteristicChanged(d, item.ch, false);
        } catch (Throwable t) {
            ok = false;
            log("NOTIFY 异常 " + addr + ": " + t);
        }
        log("NOTIFY " + addr + " ch=" + shortOf(item.ch.getUuid()) + " seq=" + seq + " "
                + item.value.length + " 字节 提交=" + ok + " 队列剩=" + q.size());

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
                case "--name": advName = args[++i]; nameExplicit = true; break;
                case "--echo": echoOnWrite = true; break;
                case "--svc": svcUuidStr = normUuid(args[++i]); break;
                case "--adv-svc": advSvcUuid = normUuid(args[++i]); includeServiceUuidInAdv = true; break;
                case "--battery-notify": batteryNotifySec = Integer.parseInt(args[++i]); break;
                case "--batt-first": battFirst = true; break;
                case "--split-servers": splitServers = true; break;
                case "--char-write": writeCharUuidStr = normUuid(args[++i]); break;
                case "--char-notify": notifyCharUuidStr = normUuid(args[++i]); break;
                case "--mfg": {
                    String[] kv = args[++i].split(":", 2);
                    mfgId = (int) Long.parseLong(kv[0].replace("0x", ""), 16);
                    if (kv.length > 1) mfgData = parseHex(kv[1]);
                    break;
                }
                case "--mfg-data": mfgData = parseHex(args[++i]); break;
                case "--adv-text": advText = args[++i]; break;
                case "--sr-text": srText = args[++i]; break;
                case "--refresh": refreshSec = Integer.parseInt(args[++i]); break;
                case "--keep-adv": keepAdv = true; break;
                case "--sub-dump": subDump = true; break;
                case "--adv-mode": advMode = parseAdvMode(args[++i]); break;
                case "--adv-tx": advTx = parseAdvTx(args[++i]); break;
                case "--psk": psk = args[++i]; break;
                case "--on-connect": onConnectCmd = args[++i]; break;
                case "--max-out": maxOutBytes = Integer.parseInt(args[++i]); break;
                case "--cmd-timeout": cmdTimeoutSec = Integer.parseInt(args[++i]); break;
                case "--send-timeout": sendAckTimeoutMs = Integer.parseInt(args[++i]); break;
                case "--pace": paceMs = Integer.parseInt(args[++i]); break;
                case "--log-out": logOutBytes = Integer.parseInt(args[++i]); break;
                case "--char-auth": authCharUuidStr = normUuid(args[++i]); break;
                case "--mode": {
                    String m = args[++i];
                    if (m.equals("low_power")) advMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
                    else if (m.equals("balanced")) advMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED;
                    else advMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;
                    break;
                }
                case "--tx": {
                    String t = args[++i];
                    if (t.equals("ultra_low")) advTx = AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW;
                    else if (t.equals("low")) advTx = AdvertiseSettings.ADVERTISE_TX_POWER_LOW;
                    else if (t.equals("medium")) advTx = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
                    else advTx = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH;
                    break;
                }
                case "--timeout": advTimeoutSec = Integer.parseInt(args[++i]); break;
                case "--non-connectable": connectable = false; break;
                case "--adv-only": advertiseOnly = true; break;
                case "--no-name-in-adv": includeNameInAdv = false; break;
                case "--no-txpower-in-adv": includeTxPower = false; break;
                case "--no-svcuuid-in-adv": includeServiceUuidInAdv = false; break;
                case "--exec": execOnWrite = true; break;
                default: break;
            }
        }
    }

    static String normUuid(String s) {
        if (s.length() == 4) return "0000" + s.toLowerCase(Locale.US) + "-0000-1000-8000-00805f9b34fb";
        return s.toLowerCase(Locale.US);
    }

    static void log(String m) { System.out.println("[" + TAG + "] " + m); System.out.flush(); }

    static void usage() {
        System.out.println("blegatt — 非 APK 的 Android BLE 广播 + GATT server（app_process 直跑）\n"
                + "用法: CLASSPATH=/data/local/tmp/blegatt.jar app_process /system/bin BLEGattd [选项]\n"
                + "  --name <str>        广播/设备名（默认 rolex-gw）\n"
                + "  --svc <uuid>        GATT 主服务 UUID（支持 4 位简写，如 abcd）\n"
                + "  --mfg <id>:<hex>    厂商数据，如 fff0:0102aabb\n"
                + "  --mode <m>          low_power|balanced|low_latency（默认 low_latency）\n"
                + "  --tx <t>            ultra_low|low|medium|high（默认 high）\n"
                + "  --timeout <sec>     广播自动停止时间，0=一直（默认 0）\n"
                + "  --non-connectable   发不可连接广播\n"
                + "  --adv-only          只广播，不启 GATT server\n"
                + "  --adv-text <模板>    广播包厂商数据载荷，占位符 {b}电量 {v}电压 {t}温度 {c}连接数 {u}运行秒 {n}名字\n"
                + "                      例：--adv-text 'b{b} v{v}' -> b25 v3.88\n"
                + "  --sr-text <模板>     scan response 的独立 31 字节，例 't{t} c{c}'\n"
                + "  --refresh <sec>      载荷变化时自动重开广播（不掉已连接客户端）\n"
                + "  --adv-svc <uuid>     把该服务 UUID 放进广播（标准 16 位，如 180f）\n"
                + "  --battery-notify <s> Battery Level 轮询秒数，变化才通知（默认 60，0=关闭）\n"
                + "  --batt-first         先注册 Battery 服务（排查框架只映射第一个服务句柄）\n"
                + "  --split-servers      Battery 用独立 GATT server 实例（同上排查）\n"
                + "  --echo              写特征的内容原样弹回通知（验证通知链路用）\n"
                + "  --exec              写 CMD 特征当 shell 命令执行（无鉴权，危险，仅测试）\n"
                + "  --psk <key>         预共享密钥：命令通道强制先 AUTH 后 CMD（root 权限，谨慎）\n"
                + "  --on-connect <cmd>  客户端一连上就自动执行该命令，结果排队等它订阅通知\n"
                + "  --max-out <bytes>   命令输出上限（默认 4096，超出截断）\n"
                + "  --cmd-timeout <sec> 单条命令最长执行时间（默认 10）\n"
                + "  --send-timeout <ms> 等 onNotificationSent 的超时，超时判定回调丢失并自愈（默认 2000）\n"
                + "  --pace <ms>         判定栈不上报 onNotificationSent 后的节流片间隔（默认 40）\n"
                + "  --keep-adv          连接后继续广播（默认连上即停广播省电，断开自动重启广播）\n"
                + "  --adv-mode <m>      广播模式 low-power|balanced|low-latency（默认 low-power 省电）\n"
                + "  --adv-tx <p>        发射功率 ultra-low|low|medium|high（默认 medium）\n"
                + "  --sub-dump          pushBattery 时打印订阅表诊断（默认关）\n"
                + "  --log-out <bytes>   设备日志打印 exec 结果内容的上限（默认 1024，0=不打印）\n"
                + "  --char-auth <uuid>  AUTH 特征 UUID\n"
                + "\n命令通道用法（nRF Connect）：\n"
                + "  1) 连上后先订阅 RES(notify) 特征，否则结果发不出来；\n"
                + "  2) 向 AUTH 写密钥 -> 收到 \"-auth:ok\"；\n"
                + "  3) 向 CMD 写命令 -> 结果分片回推，每片首字节 '+' 表示后续还有、'-' 表示最后一片、\n"
                + "     '!' 表示错误/拒绝；片长 = 协商 MTU - 3 - 1（默认 23 MTU 时每片 19 字节）；\n"
                + "     在 nRF 里按顺序拼接各片（去掉首字节）即完整输出，片尾带 #rc=N 行。\n"
                + "  --uid <n>           进程内 setuid 后再跑（默认保持 root）\n"
                + "  --no-name-in-adv / --no-txpower-in-adv / --no-svcuuid-in-adv\n");
    }
}
