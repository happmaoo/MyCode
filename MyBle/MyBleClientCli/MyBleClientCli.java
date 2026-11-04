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
import android.os.HandlerThread;

/**
 * MyBleClientCli —— 命令行 BLE 客户端（app_process 直跑，root 身份）。
 *
 * 用法:
 *   MyBleClientCli --server <名字|MAC> --psk <密钥> --cmd <命令> [--timeout 秒]
 *
 * 流程: 连接 → 请求 MTU → 发现服务 → 订阅 RES 通知 → 静默 1s（丢弃 --on-connect
 * 输出）→ 写 AUTH 密钥 → 写 CMD（长命令自动 '>'/'<' 分片）→ 收 RES 分片拼装 →
 * 末片一次性 UTF-8 解码打印到 stdout，退出。
 *
 * 输出约定: 结果走 stdout（供脚本 $() 取用），日志走 stderr。
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

    static Context ctx;
    static Handler h;
    static BluetoothGatt gatt;
    static BluetoothGattCharacteristic cCmd, cRes, cAuth;
    static int mtu = 23;
    static boolean cmdSent = false;
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

    static void stopScan() {
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Throwable ignored) { }
        scanner = null;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--server".equals(a) && i + 1 < args.length) serverArg = args[++i];
            else if ("--psk".equals(a) && i + 1 < args.length) psk = args[++i];
            else if ("--cmd".equals(a) && i + 1 < args.length) cmd = args[++i];
            else if ("--timeout".equals(a) && i + 1 < args.length) timeoutSec = Integer.parseInt(args[++i]);
        }
        if (serverArg == null || serverArg.trim().isEmpty() || cmd == null || cmd.trim().isEmpty()) {
            System.err.println("用法: MyBleClientCli --server <名字|MAC> --cmd <命令> [--psk 密钥] [--timeout 秒]");
            System.exit(1);
        }
        serverArg = serverArg.trim();
        if (cmd.length() > 4096) die(1, "命令超长（>4096 字节）");

        HandlerThread ht = new HandlerThread("mbcli");
        ht.start();
        h = new Handler(ht.getLooper());

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
        try { ht.join(); } catch (InterruptedException ignored) { }
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
        try {
            scanner = ad.getBluetoothLeScanner();
            scanner.startScan(scanCb);
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (gatt == null && !done) {
                        stopScan();
                        die(1, "扫描超时：没找到 \"" + serverArg
                                + "\"。也可用 MAC 直连：--server 04:D1:3A:56:0E:5F");
                    }
                }
            }, 12000);
        } catch (SecurityException se) {
            die(3, "扫描被拒（" + se + "）。改用 MAC 直连：--server <MAC>");
        } catch (Throwable t) {
            die(3, "扫描启动失败: " + t);
        }
    }

    static final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, final ScanResult r) {
            try {
                String name = r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
                if (name == null) name = r.getDevice().getName();
                if (name == null || !name.equals(serverArg)) return;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (gatt != null || done) return;
                        stopScan();
                        log("发现目标 " + r.getDevice().getAddress() + " RSSI " + r.getRssi() + "dBm");
                        connect(r.getDevice());
                    }
                });
            } catch (Throwable ignored) { }
        }
    };

    static void connect(BluetoothDevice d) {
        try {
            gatt = d.connectGatt(ctx, false, gcb);
        } catch (Throwable t) { die(3, "connectGatt 失败: " + t); }
        if (gatt == null) die(3, "connectGatt 返回 null");
    }

    static final BluetoothGattCallback gcb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            final int st = status, ns = newState;
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        log("已连接，请求 MTU 517…");
                        if (!g.requestMtu(517)) { g.discoverServices(); }
                    } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                        die(3, "连接断开/失败 status=" + st);
                    }
                }
            });
        }

        @Override public void onMtuChanged(final BluetoothGatt g, final int m, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
                    mtu = m;
                    log("MTU " + m + "（单片可用 " + (m - 3) + " 字节）");
                    g.discoverServices();
                }
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (done) return;
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
                                cAuth.setValue(psk);
                                cAuth.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                                if (!g.writeCharacteristic(cAuth)) die(3, "AUTH 写入提交失败");
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
                    if (isAuth) { log("AUTH 已发送"); writeCmd(g); }
                    // CMD 分片链：writeCmd 里逐片提交，onCharacteristicWrite 驱动下一片
                    else if (!cmdSent) { /* 不可能到这 */ }
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

    /** CMD 写入：短命令单帧直发；长命令按 '>' 续片 / '<' 末片 分片（与服务端 cmdAccum 协议一致） */
    static void writeCmd(BluetoothGatt g) {
        try {
            byte[] b = cmd.getBytes("UTF-8");
            int per = Math.max(1, mtu - 3 - 1);   // 首字节留给 '>'/'<' 标记
            if (b.length <= per) {
                cCmd.setValue(b);
                cCmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (!g.writeCharacteristic(cCmd)) die(3, "CMD 提交失败");
            } else {
                for (int off = 0; off < b.length; off += per) {
                    int len = Math.min(per, b.length - off);
                    byte[] piece = new byte[len + 1];
                    piece[0] = (byte) ((off + len >= b.length) ? '<' : '>');
                    System.arraycopy(b, off, piece, 1, len);
                    pending.addLast(piece);
                }
                writeNext(g);
            }
        } catch (Throwable t) { die(3, "CMD 编码失败: " + t); }
    }

    static final java.util.ArrayDeque<byte[]> pending = new java.util.ArrayDeque<byte[]>();

    static void writeNext(final BluetoothGatt g) {
        byte[] p = pending.pollFirst();
        if (p == null) {                       // 全部提交完成
            cmdSent = true;
            log("CMD 已发送，等待结果…");
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!done) die(1, "命令已发但 " + timeoutSec + "s 内没收到结果");
                }
            }, timeoutSec * 1000L);
            return;
        }
        cCmd.setValue(p);
        cCmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!g.writeCharacteristic(cCmd)) die(3, "CMD 分片提交失败");
        // 下一片在 onCharacteristicWrite 回调里驱动
    }

    /** 服务端分帧协议：'+' 续片 / '-' 末片 / '!' 错误帧；cmdSent 之前的完整消息一律丢弃 */
    static void handleChunk(byte[] v) {
        if (v == null || v.length == 0) return;
        char mark = (char) (v[0] & 0xFF);
        if (mark == '+') {
            if (!cmdSent) return;
            try { rx.write(v, 1, v.length - 1); } catch (Throwable ignored) { }
        } else if (mark == '-') {
            try { rx.write(v, 1, v.length - 1); } catch (Throwable ignored) { }
            String whole = new String(rx.toByteArray());
            rx.reset();
            if (!cmdSent) {
                log("丢弃连接期输出（" + whole.length() + " 字符，--on-connect 回显）");
                return;
            }
            if (done) return;
            done = true;
            System.out.print(whole);
            System.out.flush();
            log("接收完成 " + whole.length() + " 字符");
            cleanup();
            System.exit(0);
        } else if (mark == '!') {
            String msg = new String(v, 1, v.length - 1);
            if (cmdSent && !done) {
                done = true;
                System.err.println("[mbcli] ✗ 服务端错误: " + msg);
                cleanup();
                System.exit(2);
            }
        }
    }

    static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object inst = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(inst);
        } catch (Throwable t) {
            die(3, "取 system Context 失败: " + t);
            return null;
        }
    }
}
