package com.myapp.mywifidirect.util;

import android.os.Handler;
import android.os.Looper;

import com.topjohnwu.superuser.Shell;

/**
 * libsu Root 通道。
 *
 * 关键点：不要用 {@code Shell.isAppGrantedRoot()} 判断是否「有 root」。libsu 内部
 * 该方法的语义是「应用是否已被确认授予 root」——设备已 root 但本应用从未建立过
 * root shell 时会返回 {@code null}，此时它既不会启动 {@code su}，也不会触发
 * Magisk / KernelSU 的授权对话框。把 null 当成「没有 root」就会永远卡在未授权状态。
 *
 * 主动申请的正确做法是 {@link Shell#getShell()}：它真正启动 {@code su} 进程，
 * 第一次调用会弹出授权对话框；随后用 {@code shell.isRoot()} 判断结果。
 */
public final class RootWifi {

    public interface Callback {
        void onResult(boolean ok, String detail);
    }

    /** 已缓存的 root shell 状态，仅用于 UI 展示，不会触发授权弹窗。 */
    public static final int ROOT_UNKNOWN = 0;
    public static final int ROOT_GRANTED = 1;
    public static final int ROOT_DENIED = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RootWifi() {}

    /** @return ROOT_UNKNOWN / ROOT_GRANTED / ROOT_DENIED */
    public static int cachedStatus() {
        try {
            Shell s = Shell.getCachedShell();
            if (s == null || !s.isAlive()) return ROOT_UNKNOWN;
            return s.isRoot() ? ROOT_GRANTED : ROOT_DENIED;
        } catch (Throwable t) {
            return ROOT_UNKNOWN;
        }
    }

    /**
     * 主动建立 root shell（会触发授权弹窗）。
     * 若之前缓存的是非 root shell（例如用户曾拒绝授权），先关闭它，
     * 否则 {@code getShell()} 会一直复用该 shell、不再弹窗。
     */
    private static Acquire acquireRoot() {
        try {
            Shell cached = Shell.getCachedShell();
            if (cached != null && !cached.isRoot()) {
                try {
                    cached.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        Shell shell;
        try {
            shell = Shell.getShell();   // 阻塞，直到用户响应授权或超时
        } catch (Throwable t) {
            return new Acquire(false, "无法启动 su：" + t.getClass().getSimpleName());
        }
        if (shell == null || !shell.isRoot()) {
            return new Acquire(false,
                    "未获得 Root 权限：请在弹出的授权对话框中点「允许」，并确认设备已 root");
        }
        Shell.Result id = Shell.cmd("id").exec();
        String who = (id.isSuccess() && !id.getOut().isEmpty())
                ? id.getOut().get(0) : "uid=0(root)";
        return new Acquire(true, "已获得 Root 权限 · " + who);
    }

    /** 只申请 Root 权限，不执行开 Wi-Fi。供设置页「申请 Root 权限」按钮使用。 */
    public static void requestRootAsync(Callback cb) {
        run(cb, () -> {
            Acquire a = acquireRoot();
            return new Result(a.ok, a.detail);
        });
    }

    /** 主动申请 Root 并执行 svc wifi enable（失败时 settings 全局开关兜底）。 */
    public static void enableAsync(Callback cb) {
        run(cb, () -> {
            Acquire a = acquireRoot();
            if (!a.ok) return new Result(false, a.detail);

            Shell.Result r = Shell.cmd("svc wifi enable").exec();
            if (r.isSuccess()) {
                return new Result(true, "svc wifi enable 成功 · " + a.detail);
            }
            Shell.Result r2 = Shell.cmd("settings put global wifi_on 1").exec();
            if (r2.isSuccess()) {
                return new Result(true, "settings global wifi_on=1 成功（svc 兜底）· " + a.detail);
            }
            return new Result(false, "命令失败 svc=" + r.getCode()
                    + " / settings=" + r2.getCode() + " · " + firstErr(r, r2));
        });
    }

    /**
     * 关闭系统 Wi-Fi（退出清理用）。
     *
     * @param aggressive true  = 主动申请 Root（可能弹出授权对话框），供已开启
     *                          「Root 自动开启 Wi-Fi」选项的场景使用；
     *                  false = 仅复用已缓存的 root shell，不弹窗、不等待授权，
     *                          没有权限就直接返回失败。
     */
    public static void disableAsync(boolean aggressive, Callback cb) {
        run(cb, () -> {
            if (aggressive) {
                Acquire a = acquireRoot();
                if (!a.ok) return new Result(false, a.detail);
            }
            Shell shell = Shell.getCachedShell();
            if (shell == null || !shell.isAlive() || !shell.isRoot()) {
                return new Result(false, "无可用 Root 权限，跳过关闭 Wi-Fi");
            }
            Shell.Result r = Shell.cmd("svc wifi disable").exec();
            if (r.isSuccess()) {
                return new Result(true, "svc wifi disable 成功");
            }
            Shell.Result r2 = Shell.cmd("settings put global wifi_on 0").exec();
            if (r2.isSuccess()) {
                return new Result(true, "settings global wifi_on=0 成功（svc 兜底）");
            }
            return new Result(false, "命令失败 svc=" + r.getCode()
                    + " / settings=" + r2.getCode() + " · " + firstErr(r, r2));
        });
    }

    private static String firstErr(Shell.Result... rs) {
        for (Shell.Result r : rs) {
            if (r != null && r.getErr() != null && !r.getErr().isEmpty()) {
                String s = r.getErr().get(0);
                if (s != null && !s.trim().isEmpty()) {
                    return s.length() > 120 ? s.substring(0, 120) : s;
                }
            }
        }
        return "无错误输出";
    }

    private static final class Acquire {
        final boolean ok;
        final String detail;
        Acquire(boolean ok, String detail) { this.ok = ok; this.detail = detail; }
    }

    private static final class Result {
        final boolean ok;
        final String detail;
        Result(boolean ok, String detail) { this.ok = ok; this.detail = detail; }
    }

    private interface Work {
        Result run() throws Throwable;
    }

    private static void run(Callback cb, Work work) {
        new Thread(() -> {
            Result res;
            try {
                res = work.run();
            } catch (Throwable t) {
                res = new Result(false, "Root 执行异常：" + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : " · " + t.getMessage()));
            }
            final Result f = res;
            MAIN.post(() -> {
                if (cb != null) cb.onResult(f.ok, f.detail);
            });
        }, "root-wifi").start();
    }
}
