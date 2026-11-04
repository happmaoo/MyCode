package com.qw.pancli;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 触发器：Android 8+ 起后台应用不能 startService，但 shell/root 的 am start（启动 Activity）不受限，
 * 所以从 Activity 里把服务拉起来，然后立刻 finish。
 *
 *   am start -n com.qw.pancli/.Main --es dev Cannon
 *   am start -n com.qw.pancli/.Main --es dev 00:00:DD:0B:EE:F1 --es act disconnect
 * 直接点开图标（不带参数）＝把上次的 pan.status 弹个 Toast 看看。
 */
public class Main extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent in = getIntent();
        String dev = in == null ? null : in.getStringExtra("dev");
        if (dev == null || dev.trim().isEmpty()) {
            String st = read(new File(getFilesDir(), "pan.status"));
            Toast.makeText(this, st == null ? "还没跑过" : st.trim(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String act = in.getStringExtra("act");
        Intent bc = new Intent(this, PanRcv.class)
                .putExtra("dev", dev.trim())
                .putExtra("act", act == null || act.isEmpty() ? "connect" : act);
        try {
            sendBroadcast(bc);              // 后台 startService 会被拦，广播不会
        } catch (Throwable t) {
            Toast.makeText(this, "触发失败: " + t, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    static String read(File f) {
        try {
            FileInputStream i = new FileInputStream(f);
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
                return new String(o.toByteArray(), "UTF-8");
            } finally { i.close(); }
        } catch (Throwable t) { return null; }
    }
}
