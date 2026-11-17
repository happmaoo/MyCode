package com.myapp.mywifidirect;

import android.app.Application;

import com.topjohnwu.superuser.Shell;

/** 全局初始化：libsu 的默认 Builder 必须在任何 Shell 创建之前设置。 */
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 首次申请 root 时 Magisk / KernelSU 会弹授权对话框，需要用户手动点
        // 「允许」。把建壳超时放宽到 30 秒，否则 libsu 会在用户响应前就超时、
        // 退化成非 root shell，导致授权窗口白弹。
        Shell.enableVerboseLogging = false;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30));
    }
}
