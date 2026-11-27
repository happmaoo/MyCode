package com.myapp.mqtt.base;

import android.content.Context;
import android.os.PowerManager;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 执行端的指令处理：收到什么就执行什么（整条 payload 当 shell 命令，不解析 JSON），
 * 结果 JSON 发到 P.pubTopic（默认 executor/result）。
 */
public final class Exec {

    /** 命令串行执行（单线程），保证都在同一个持久 shell 会话里排队跑，不会互相插队 */
    private static final ExecutorService CMD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mqtt-exec");
        t.setDaemon(true);
        return t;
    });

    private Exec() {
    }

    public static void handleCommand(final MqttCore core, final Context ctx, final String topic, final String payload) {
        String cmd = payload;
        if ("startup".equals(topic)) {
            ExecShell.setPersistCwd(cwdOf(payload));
            // startup 里可以写一行 `!shell <命令>`：把它作为持久 shell 的启动命令（换 shell/换用户）
            String launch = null;
            StringBuilder rest = new StringBuilder();
            for (String line : (payload == null ? "" : payload).split("\n")) {
                String t = line.trim();
                if (t.startsWith("!shell ")) launch = t.substring(7).trim();
                else if (!t.isEmpty()) {
                    if (rest.length() > 0) rest.append('\n');
                    rest.append(line);
                }
            }
            if (launch != null && !launch.isEmpty()) {
                ExecShell.setLaunchCmd(launch);
                ExecShell.reset();           // 丢掉旧 shell，下次命令用新的启动命令起
                LogFile.write("[启动] 持久 shell 启动命令 = " + launch);
            }
            cmd = rest.toString();
            if (cmd.trim().isEmpty()) return;   // startup 只用来设置 shell，不跑普通命令
        }
        final String runCmd = cmd;
        CMD.execute(() -> {
            // 有命令就先抓一个 partial wakelock：锁屏/后台时 CPU 不休眠，保证能执行完并把结果发出去
            PowerManager.WakeLock wl = P.wakeLock(ctx) ? acquireWake(ctx) : null;
            try {
                runOne(core, ctx, runCmd);
            } finally {
                releaseWake(wl);
            }
        });
    }

    private static void runOne(final MqttCore core, final Context ctx, final String payload) {
            long t0 = System.currentTimeMillis();
            // 执行端不解析 JSON：整条 payload 原样当 shell 命令
            String cmd = payload == null ? "" : payload.trim();
            if (cmd.isEmpty()) return;
            LogFile.write("[执行] " + cmd.replace("\n", " ⏎ "));

            // vi/vim：不真的执行编辑器，而是把远端文件带回去，让控制端用外部编辑器打开
            // vi/vim 编辑，以及 !down 下载（都是把远端文件带回去）
            String wantFile = null;
            if (cmd.equals("vi") || cmd.equals("vim") || cmd.equals("mqttdown")) wantFile = "";
            else if (cmd.startsWith("vi ") || cmd.startsWith("vim ") || cmd.startsWith("mqttdown "))
                wantFile = cmd.substring(cmd.indexOf(' ') + 1).trim();
            else if (cmd.startsWith("mqttdown:")) wantFile = cmd.substring(9).trim();
            if (wantFile != null) {
                viCmd(core, ctx, cmd, wantFile, t0);
                return;
            }
            // !put <路径> <权限>\n<base64>：控制端编辑完回写文件
            if (cmd.startsWith("!put ")) {
                putCmd(core, ctx, cmd, t0);
                return;
            }

            ExecShell.Result r;
            if (cmd.equals("screenshot") || cmd.startsWith("screenshot ")) {
                r = screenshot(ctx);
            } else if (cmd.equals("!reset")) {
                // 手动重置 shell 会话（回到初始目录、清掉改过的环境变量）
                ExecShell.reset();
                r = new ExecShell.Result("shell 会话已重置（工作目录回到初始状态）", 0);
            } else {
                // 持久会话：cd / export 等状态会保留到下一条命令
                r = ExecShell.run(cmd, 20000, P.outputLimit(ctx));
            }

            try {
                JSONObject res = new JSONObject();
                res.put("cmd", cmd);
                res.put("code", r.code);
                res.put("ms", System.currentTimeMillis() - t0);
                res.put("ts", System.currentTimeMillis());
                res.put("output", r.output);
                // 同步发：结果真正送出去后再释放 wakelock
                core.publishSync(P.pubTopic(ctx), res.toString(), core.qosLevel(), 8000);
            } catch (Exception e) {
                core.publishSync(P.pubTopic(ctx),
                        "{\"error\":\"" + e.getClass().getSimpleName() + "\"}", core.qosLevel(), 8000);
            }
    }

    /** PARTIAL_WAKE_LOCK，带 60s 超时兜底，避免异常时一直持有耗电 */
    private static PowerManager.WakeLock acquireWake(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyMQTT:exec");
            wl.setReferenceCounted(false);
            wl.acquire(60 * 1000L);
            return wl;
        } catch (Exception e) {
            return null;
        }
    }

    private static void releaseWake(PowerManager.WakeLock wl) {
        if (wl == null) return;
        try {
            if (wl.isHeld()) wl.release();
        } catch (Exception ignore) {
        }
    }

    /** 截屏：先试 app 自己的外部私有目录，失败再走 su */
    private static ExecShell.Result screenshot(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        File f = new File(dir, "shot_" + System.currentTimeMillis() + ".png");
        ExecShell.Result r = ExecShell.once("screencap -p " + q(f.getAbsolutePath()), 8000, P.outputLimit(ctx));
        if (f.exists() && f.length() > 0) {
            return new ExecShell.Result("path=" + f.getAbsolutePath() + "\nsize=" + f.length(), 0);
        }
        File tmp = new File("/data/local/tmp/mqtt_shot.png");
        ExecShell.once("su -c 'screencap -p " + tmp.getAbsolutePath() + "'", 8000, P.outputLimit(ctx));
        if (tmp.exists() && tmp.length() > 0) {
            return new ExecShell.Result("path=" + tmp.getAbsolutePath() + "\nsize=" + tmp.length(), 0);
        }
        return new ExecShell.Result("screenshot 失败（需要 root 或存储权限）\n" + r.output, -1);
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 把文件内容（base64）带回控制端：{...,"file":{path,name,mode,size,sha,data}} */
    private static void viCmd(MqttCore core, Context ctx, String cmd, String wantPath, long t0) {
        String path = wantPath == null ? "" : wantPath;
        path = path.replace("\"", "").replace("'", "");
        if (!path.isEmpty()) path = absPath(path);   // 相对路径按持久 shell 的当前目录解析
        String err = null;
        byte[] data = null;
        String mode = "644";

        if (path.isEmpty()) {
            err = "用法: vi <文件路径> / mqttdown:<文件路径>";
        } else {
            File f = new File(path);
            long max = P.editMaxKb(ctx) * 1024L;
            if (f.exists() && f.isFile() && f.canRead()) {
                try {
                    data = readAll(f);
                } catch (Throwable t) {
                    data = readViaRoot(ctx, path);
                    if (data == null) err = "读取失败: " + t.getMessage();
                }
            } else {
                // App 权限看不到（termux 私有目录 /sdcard /etc 等）：先走 su 拷到私有目录再读
                data = readViaRoot(ctx, path);
                if (data == null) {
                    err = shellExists(path) ? "读取失败（需要 root 授权才能读这个位置）: " + path
                            : "文件不存在: " + path;
                }
            }
            if (data != null && data.length > max) {
                err = "文件太大(" + Fmt.size(data.length) + ")，上限 " + Fmt.size(max);
                data = null;
            }
            if (data != null) {
                ExecShell.Result m = ExecShell.run("stat -c '%a' " + q(path), 5000, 200);
                String mm = m.output == null ? "" : m.output.trim();
                if (mm.matches("[0-7]{3,4}")) mode = mm;
            }
        }

        try {
            JSONObject res = new JSONObject();
            res.put("cmd", cmd);
            res.put("code", err == null ? 0 : 1);
            res.put("ms", System.currentTimeMillis() - t0);
            res.put("ts", System.currentTimeMillis());
            res.put("output", err == null ? "" : err);
            if (data != null) {
                JSONObject fj = new JSONObject();
                fj.put("path", path);
                fj.put("name", new File(path).getName());
                fj.put("mode", mode);
                fj.put("size", data.length);
                fj.put("sha", sha256(data));
                fj.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
                res.put("file", fj);
            }
            core.publishSync(P.pubTopic(ctx), res.toString(), core.qosLevel(), 15000);
        } catch (Exception e) {
            core.publishSync(P.pubTopic(ctx),
                    "{\"error\":\"" + e.getClass().getSimpleName() + "\"}", core.qosLevel(), 8000);
        }
    }

    /** 回写：!put <路径> <权限> + 换行 + base64 内容 */
    private static void putCmd(MqttCore core, Context ctx, String cmd, long t0) {
        String out;
        String putTarget = null;
        int code = 0;
        try {
            int nl = cmd.indexOf('\n');
            String head = nl > 0 ? cmd.substring(0, nl) : cmd;
            String body = nl > 0 ? cmd.substring(nl + 1) : "";
            String[] parts = head.trim().split("\\s+");
            String path = parts.length > 1 ? parts[1] : "";
            String mode = parts.length > 2 ? parts[2] : "644";
            body = body.replaceAll("\\s+", "");
            if (path.isEmpty() || body.isEmpty()) throw new IllegalArgumentException("参数不全");
            path = absPath(path);
            if (!mode.matches("[0-7]{3,4}")) mode = "644";
            byte[] data = Base64.decode(body, Base64.NO_WRAP);
            File f = new File(path);
            putTarget = f.getAbsolutePath();
            if (!writeFile(f, data, mode)) {
                // 普通 App 写不进去：写私有暂存再 su cp 过去
                File dir = new File(ctx.getFilesDir(), "edit");
                if (!dir.exists()) dir.mkdirs();
                File stage = new File(dir, ".su_out");
                FileOutputStream fo = new FileOutputStream(stage);
                fo.write(data);
                fo.close();
                ExecShell.Result r = ExecShell.run(su() + " -c 'cp \"" + stage.getAbsolutePath() + "\" \""
                        + path + "\" && chmod " + mode + " \"" + path + "\"'", 20000, 600);
                stage.delete();
                if (r.code != 0) throw new java.io.IOException("无写权限，su 也失败: "
                        + (r.output == null ? "" : r.output.trim()));
            }
            out = "已写入 " + path + "（" + Fmt.size(data.length) + "，权限 " + mode + "）";
        } catch (Throwable t) {
            code = 1;
            out = "回写失败: " + t.getMessage();
            if (putTarget != null) out = "回写失败(" + putTarget + "): " + t.getMessage();
        }
        try {
            JSONObject res = new JSONObject();
            res.put("cmd", "!put");
            res.put("code", code);
            res.put("ms", System.currentTimeMillis() - t0);
            res.put("ts", System.currentTimeMillis());
            res.put("output", out);
            core.publishSync(P.pubTopic(ctx), res.toString(), core.qosLevel(), 15000);
        } catch (Exception ignore) {
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignore) { }
        }
    }

    private static String sha256(byte[] d) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(d);
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 普通方式写文件；成功返回 true */
    private static boolean writeFile(File f, byte[] data, String mode) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
            ExecShell.run("chmod " + mode + " " + q(f.getAbsolutePath()), 6000, 200);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用 su 把文件拷到私有目录再读（对付 /sdcard、/etc 这类 App 读不到的路径）*/
    private static byte[] readViaRoot(Context ctx, String path) {
        try {
            File dir = new File(ctx.getFilesDir(), "edit");
            if (!dir.exists()) dir.mkdirs();
            File stage = new File(dir, ".su_in");
            if (stage.exists()) stage.delete();
            String s = stage.getAbsolutePath();
            ExecShell.Result r = ExecShell.run(su() + " -c 'cp \"" + path + "\" \"" + s + "\" && chmod 644 \""
                    + s + "\"'", 20000, 600);
            if (stage.exists() && stage.length() > 0) {
                byte[] d = readAll(stage);
                stage.delete();
                return d;
            }
            LogFile.write("[vi] su 读取失败 code=" + r.code + " " + r.output);
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 从启动命令里挑出最后一条 cd 行（新会话重建时自动补回目录） */
    static String cwdOf(String boot) {
        String last = "";
        if (boot == null) return last;
        for (String line : boot.split("\n")) {
            String t = line.trim();
            if (t.equals("cd") || t.startsWith("cd ")) last = t;
        }
        return last;
    }

    /**
     * 相对路径按「持久 shell 的当前目录」补全：
     * Java 的 File 相对路径是相对 App 进程 cwd（一般 /），和用户在 shell 里 cd 到的目录不是一回事。
     */
    private static String absPath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/")) return path;
        String cwd = "/";
        try {
            ExecShell.Result r = ExecShell.run("pwd", 5000, 200);
            String o = r.output == null ? "" : r.output.trim();
            if (o.startsWith("/")) cwd = o;
        } catch (Exception ignore) {
        }
        String p = path;
        if (p.equals("~")) p = "";
        else if (p.startsWith("~/")) p = p.substring(2);
        else if (p.startsWith("~")) p = p.substring(1);
        if (cwd.endsWith("/")) cwd = cwd.substring(0, cwd.length() - 1);
        return cwd + "/" + p;
    }

    /** 找可用的 su 绝对路径（termux 的 bash 里 PATH 不含 /sbin，直接写 su 会 command not found） */
    private static volatile String SU;

    static String su() {
        if (SU != null) return SU;
        String probe = "for p in /sbin/su /system/xbin/su /system/bin/su /debug_ramdisk/su "
                + "/data/adb/magisk/su /data/adb/ksu/bin/su; do [ -x \"$p\" ] && { echo \"$p\"; break; }; done";
        String out = ExecShell.run(probe, 6000, 300).output;
        String v = out == null ? "" : out.trim();
        if (v.startsWith("/")) v = v.split("\n")[0].trim();
        SU = v.startsWith("/") ? v : "su";
        return SU;
    }

    /** 用持久 shell 判断文件在不在（App 自己 stat 不到的目录也能看）*/
    private static boolean shellExists(String path) {
        ExecShell.Result r = ExecShell.run("[ -f " + q(path) + " ] && echo YES || echo NO", 6000, 200);
        return r.output != null && r.output.contains("YES");
    }
}
