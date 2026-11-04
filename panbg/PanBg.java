import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.system.Os;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 后台开启/关闭蓝牙 PAN 网络共享（不打开设置界面）。
 *
 * 原理:
 *  1. root 启动 app_process, 临时 setuid(1000) 冒充 system/Settings 身份
 *     (Settings 是 uid 1000 且持有 TETHER_PRIVILEGED 特权权限, 共享用户聚合后 uid 1000 整体持有)
 *  2. 直接向系统服务 "connectivity" 发 binder 调用 IConnectivityManager.startTethering(TETHERING_BLUETOOTH)
 *     系统内部会转交给 Tethering 模块, 由它去绑定 PanService 并 setBluetoothTethering(true)
 *  3. 全程无任何 UI
 *
 * 用法: app_process / PanBg [on|off|info] [uid]
 */
public class PanBg {

    static final String PKG = "com.android.settings";

    static Object getService(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object b = sm.getMethod("getService", String.class).invoke(null, name);
        if (b == null) throw new IllegalStateException("Service not found: " + name);
        return b;
    }

    static Object asInterface(Object binder, String stubName) throws Exception {
        Class<?> stub = Class.forName(stubName);
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    /** 伪造 IIntResultListener 回调: 一个纯 Binder, 收到回调直接吞掉 */
    static Object makeResultListener() throws Exception {
        final Class<?> intf = Class.forName("android.net.IIntResultListener");
        final Binder raw = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                return true;
            }
        };
        return Proxy.newProxyInstance(PanBg.class.getClassLoader(), new Class[]{intf},
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if (m.getName().equals("asBinder")) return raw;
                        return null;
                    }
                });
    }

    static int tetheringType() {
        try {
            return Class.forName("android.net.TetheringManager")
                    .getField("TETHERING_BLUETOOTH").getInt(null);
        } catch (Throwable t) {
            try {
                return Class.forName("android.net.ConnectivityManager")
                        .getField("TETHERING_BLUETOOTH").getInt(null);
            } catch (Throwable t2) {
                return 3;
            }
        }
    }

    static Object callTarget(String methodName) throws Throwable {
        // 优先直连 Tethering 模块: service "tethering" [android.net.ITetheringConnector]
        // (Android 12 的 IConnectivityManager 已无 startTethering)
        Class<?> iface = Class.forName("android.net.ITetheringConnector");
        Object api = asInterface(getService("tethering"), "android.net.ITetheringConnector$Stub");
        Method target = null;
        for (Method m : iface.getMethods()) {
            if (m.getName().equals(methodName)) { target = m; break; }
        }
        if (target == null) {
            throw new NoSuchMethodException(methodName + " not found on ITetheringConnector");
        }
        return invokeGeneric(api, target, methodName);
    }

    static Object invokeGeneric(Object api, Method target, String name) throws Throwable {
        Class<?>[] ps = target.getParameterTypes();
        Object[] argv = new Object[ps.length];
        int stringIdx = 0;
        for (int i = 0; i < ps.length; i++) {
            String tn = ps[i].getName();
            if (tn.endsWith("TetheringRequestParcel")) {
                argv[i] = buildRequestParcel(ps[i]);
            } else if (tn.equals("int")) {
                argv[i] = tetheringType();
            } else if (tn.equals("boolean")) {
                argv[i] = Boolean.FALSE;
            } else if (tn.equals("java.lang.String")) {
                argv[i] = (stringIdx++ == 0) ? PKG : null; // callingPkg, attributionTag
            } else {
                argv[i] = makeResultListener();
            }
        }
        System.out.println("call " + name + " sig=" + target);
        try {
            return target.invoke(api, argv);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (e.getCause() != null) ? e.getCause() : e;
        }
    }

    /** 构造 TetheringRequestParcel{tetheringType=BLUETOOTH, ...} */
    static Object buildRequestParcel(Class<?> cls) throws Throwable {
        Object req = cls.newInstance();
        System.out.println("TetheringRequestParcel fields:");
        for (java.lang.reflect.Field f : cls.getFields()) {
            System.out.println("  " + f.getType().getName() + " " + f.getName()
                    + " = " + f.get(req));
        }
        setIfPresent(cls, req, "tetheringType", tetheringType());
        setIfPresent(cls, req, "exemptFromEntitlementCheck", Boolean.TRUE);
        setIfPresent(cls, req, "showProvisioningUi", Boolean.FALSE);
        return req;
    }

    static void setIfPresent(Class<?> cls, Object obj, String field, Object val) {
        try {
            java.lang.reflect.Field f = cls.getField(field);
            f.set(obj, val);
            System.out.println("set " + field + " = " + val);
        } catch (Throwable t) {
            System.out.println("skip field " + field + ": " + t);
        }
    }

    static void info() throws Throwable {
        System.out.println("uid=" + Os.getuid());
        System.out.println("TetheringManager.TETHERING_BLUETOOTH="
                + Class.forName("android.net.TetheringManager").getField("TETHERING_BLUETOOTH").getInt(null));
        try {
            System.out.println("ConnectivityManager.TETHERING_BLUETOOTH="
                    + Class.forName("android.net.ConnectivityManager").getField("TETHERING_BLUETOOTH").getInt(null));
        } catch (Throwable ignore) { }
        Class<?> iface = Class.forName("android.net.ITetheringConnector");
        for (Method m : iface.getMethods()) {
            System.out.println("  ITetheringConnector." + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
        }
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "on";
        if (mode.equals("info")) {
            try { info(); } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
            return;
        }
        boolean start = !mode.equals("off");
        int forcedUid = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int[] uids = (forcedUid >= 0) ? new int[]{forcedUid} : new int[]{1000, 0};
        Throwable last = null;
        for (int uid : uids) {
            try {
                if (Os.getuid() != uid) Os.setuid(uid);
                System.out.println("[uid " + Os.getuid() + "] trying "
                        + (start ? "start" : "stop") + "Tethering(BT)...");
                callTarget(start ? "startTethering" : "stopTethering");
                System.out.println("OK as uid " + Os.getuid());
                return;
            } catch (Throwable t) {
                last = t;
                System.out.println("FAILED as uid " + uid + ": " + t);
            }
        }
        if (last != null) last.printStackTrace();
        System.exit(2);
    }
}
