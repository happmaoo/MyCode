package com.qw.pancli;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * PAN 干活的公共部分，被 PanRcv（显式广播，推荐）和 PanSvc（服务，前台时可用）共用。
 *
 * 为什么不用 startService 触发：Android 8+ 禁止后台 app startService，
 * 而 am start 拉起的透明 Activity 一 finish，进程立刻变成 cached，
 * logcat 里就是「Background start not allowed: service Intent { cmp=com.qw.pancli/.PanSvc }」。
 * 显式广播没这条限制，bindService（getProfileProxy 内部用的）也没有。
 */
public class Pan {

    static final int PAN = panId();

    static int panId() {
        try { return BluetoothProfile.class.getField("PAN").getInt(null); }
        catch (Throwable t) { return 5; }
    }

    /** 同步执行，调用方要放在工作线程里跑。返回一句结论，也写进 pan.status */
    public static String run(final Context ctx, String devArg, final String act) {
        unseal();
        log(ctx, "开始 act=" + act + " dev=" + devArg);

        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) return status(ctx, "FAILED bt-off");

        final BluetoothDevice dev = resolve(ad, devArg == null ? "" : devArg.trim());
        if (dev == null) return status(ctx, "FAILED no-device " + devArg);
        log(ctx, "目标 " + dev.getAddress() + " bond=" + dev.getBondState());

        final Object[] got = new Object[1];
        boolean req;
        try {
            req = ad.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    synchronized (got) { got[0] = proxy; got.notifyAll(); }
                    log(ctx, "profile 代理已连接 profile=" + profile);
                }
                @Override public void onServiceDisconnected(int profile) {
                    log(ctx, "profile 代理断开 profile=" + profile);
                }
            }, PAN);
        } catch (Throwable t) {
            return status(ctx, "FAILED getProfileProxy threw " + t);
        }
        if (!req) return status(ctx, "FAILED getProfileProxy=false（PAN 未启用或权限不足）");

        BluetoothProfile proxy = waitProxy(got);
        if (proxy == null) return status(ctx, "FAILED no-proxy-callback");

        String method = "disconnect".equals(act) ? "disconnect" : "connect";
        boolean ok;
        try {
            Method m = proxy.getClass().getMethod(method, BluetoothDevice.class);
            ok = Boolean.TRUE.equals(m.invoke(proxy, dev));
            log(ctx, method + "() -> " + ok);
        } catch (Throwable t) {
            release(ad, proxy);
            return status(ctx, "FAILED reflect-" + method + " " + t);
        }
        if (!ok) {
            // 已经连着/已经断开时框架就返回 false，不能当成「对端没开 NAP」。
            // 先看真实状态，保证 --pan / --pan-disconnect 可以重复执行（看门狗要靠它）
            int st = state(proxy, dev);
            release(ad, proxy);
            if ("disconnect".equals(act) && st != BluetoothProfile.STATE_CONNECTED) {
                return status(ctx, "DISCONNECTED");
            }
            if (!"disconnect".equals(act) && st == BluetoothProfile.STATE_CONNECTED) {
                return status(ctx, "CONNECTED " + dev.getAddress());
            }
            return status(ctx, "FAILED " + method + "=false state=" + st
                    + "（对端没开 NAP、配对失效，或对端 PAN 已连满）");
        }
        if ("disconnect".equals(act)) { release(ad, proxy); return status(ctx, "DISCONNECTED"); }

        for (int i = 0; i < 20; i++) {
            sleep(500);
            int st = state(proxy, dev);
            if (st == BluetoothProfile.STATE_CONNECTED) {
                release(ad, proxy);
                return status(ctx, "CONNECTED " + dev.getAddress());
            }
            if (st == BluetoothProfile.STATE_DISCONNECTED && i > 4) {
                release(ad, proxy);
                return status(ctx, "FAILED link-dropped");
            }
        }
        release(ad, proxy);
        return status(ctx, "FAILED timeout state=" + state(proxy, dev));
    }

    static BluetoothProfile waitProxy(Object[] got) {
        synchronized (got) {
            long end = System.currentTimeMillis() + 5000;
            while (got[0] == null && System.currentTimeMillis() < end) {
                try { got.wait(300); } catch (InterruptedException e) { return null; }
            }
            return (BluetoothProfile) got[0];
        }
    }

    static int state(BluetoothProfile p, BluetoothDevice d) {
        try { return p.getConnectionState(d); } catch (Throwable t) { return -1; }
    }

    static void release(BluetoothAdapter ad, BluetoothProfile proxy) {
        try { ad.closeProfileProxy(PAN, proxy); } catch (Throwable ignored) { }
    }

    static BluetoothDevice resolve(BluetoothAdapter ad, String arg) {
        try {
            if (arg.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return ad.getRemoteDevice(arg.toUpperCase());
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (arg.equalsIgnoreCase(d.getName())) return d;
                }
            }
        } catch (Throwable t) { log(null, "解析目标失败 " + t); }
        return null;
    }

    /** Android 9-13 通用 hidden API 解封 */
    static void unseal() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            Method g = Class.class.getMethod("getDeclaredMethod", String.class, Class[].class);
            Method exempt = (Method) g.invoke(vm, "setHiddenApiExemptions", new Class[]{String[].class});
            exempt.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) { }
    }

    // ---------- 状态与日志落盘（root 侧 CLI 直接 cat 这些文件） ----------

    static File dir(Context ctx) { return ctx == null ? null : ctx.getFilesDir(); }

    static String status(Context ctx, String s) {
        log(ctx, "status: " + s);
        File d = dir(ctx);
        if (d != null) write(new File(d, "pan.status"), s + "\n", false);
        return s;
    }

    static void log(Context ctx, String s) {
        File d = dir(ctx);
        if (d != null) write(new File(d, "pan.log"), "[" + System.currentTimeMillis() + "] " + s + "\n", true);
    }

    static void write(File f, String s, boolean append) {
        try {
            if (!append && f.length() > 200000) { try { f.delete(); } catch (Throwable ignored) { } }
            FileOutputStream o = new FileOutputStream(f, append);
            try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
        } catch (Throwable ignored) { }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static String extra(Intent it, String k, String def) {
        try {
            String v = it == null ? null : it.getStringExtra(k);
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Throwable t) { return def; }
    }
}
