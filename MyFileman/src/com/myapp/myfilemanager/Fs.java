package com.myapp.myfilemanager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 文件系统访问层：普通模式用 java.io；Root 模式复用一个持久的交互式 su 会话。
 *
 * 旧实现每条命令都执行 "su -c"，等于每次都新建一个 su 进程，授权管理器设为
 * 「每次询问」时就会每点一次文件夹弹一次窗。现在改为：需要 Root 时只建立一次
 * 交互式 su 会话（触发一次授权），之后所有命令都通过它的 stdin/stdout 执行，
 * 全程不再新建 su 进程，因此不会重复申请授权。
 */
public final class Fs {
    private Fs() {}

    public static volatile boolean rootMode = false;
    public static volatile boolean showHidden = false;

    /** 批量文件操作（大目录复制/清理）的 Root 会话超时：上万个小文件在 FUSE 上可能耗时数分钟。 */
    public static final long BULK_TIMEOUT_MS = 600_000L;

    // Root 会话的挂载命名空间状态，建立会话时探测一次。
    // Android 11+ 的 per-app 挂载命名空间里 /data/data 只剩本应用数据，
    // 看不到其它应用目录；全局命名空间或 /data_mirror 镜像才是完整视图。
    private static volatile boolean globalMountNs = false;
    private static volatile String mirrorRoot = null;   // /data_mirror 真实视图根目录，null=不可用

    // ---------------- 通用结果 ----------------

    public static class ExecResult {
        public int code = -1;
        public String out = "";
        public String err = "";
        public boolean ok() { return code == 0; }
    }

    /** 逐行回调：命令每产生一行输出就调用一次（不含换行符）。用于结果窗口的实时流式输出。 */
    public interface LineSink {
        void line(String line);
    }

    public static class ListResult {
        public List<FileItem> items = new ArrayList<FileItem>();
        public String error = null;
    }

    // ---------------- 一次性进程（普通 shell） ----------------

    public static ExecResult exec(final String[] argv) {
        Dbg.log("FS.EXEC", "argv=" + Dbg.clip(java.util.Arrays.toString(argv), 800));
        ExecResult r = exec0(argv);
        Dbg.log("FS.EXEC", "结果 code=" + r.code + " out=" + Dbg.clip(r.out, 200)
                + " err=" + Dbg.clip(r.err, 200));
        return r;
    }

    private static ExecResult exec0(final String[] argv) {
        final ExecResult r = new ExecResult();
        try {
            final Process p = Runtime.getRuntime().exec(argv);
            final String[] o = {""}, e = {""};
            Thread t1 = new Thread(new Runnable() {
                public void run() { o[0] = read(p.getInputStream()); }
            });
            Thread t2 = new Thread(new Runnable() {
                public void run() { e[0] = read(p.getErrorStream()); }
            });
            t1.start(); t2.start();
            r.code = p.waitFor();
            t1.join(); t2.join();
            r.out = o[0]; r.err = e[0];
        } catch (Exception ex) {
            r.code = -1;
            r.err = String.valueOf(ex.getMessage());
        }
        return r;
    }

    private static String read(InputStream is) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        } catch (Exception ignored) {
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
        try { return new String(bos.toByteArray(), "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    /**
     * 以 root（优先 mount-master 全局命名空间）读取文件原始字节写入 out。
     * 供 FileProvider 通过管道把「普通进程命名空间看不到的 root 文件」流式提供给外部 App。
     * out 由调用方负责关闭；返回 true 表示 cat 成功结束（code==0）。
     */
    public static boolean streamRootFileTo(String path, java.io.OutputStream out) {
        String mapped = rootPath(path);
        String cmd = "cat " + Util.sq(mapped) + " 2>/dev/null";
        Dbg.log("FS.STREAM", "cat " + mapped);
        if (streamCat(new String[]{"su", "--mount-master", "-c", cmd}, out)) return true;
        return streamCat(new String[]{"su", "-c", cmd}, out);   // 兜底：不支持 --mount-master 的 su
    }

    private static boolean streamCat(String[] argv, java.io.OutputStream out) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(argv);
            java.io.InputStream is = p.getInputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = is.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
            int code = p.waitFor();
            Dbg.log("FS.STREAM", "code=" + code + " bytes=" + total + " argv=" + argv[0]);
            return code == 0;
        } catch (Exception e) {
            Dbg.log("FS.STREAM", "失败 " + e.getMessage());
            return false;
        } finally {
            if (p != null) {
                try { p.getErrorStream().close(); } catch (Exception ignored) {}
                try { p.getInputStream().close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------------- 持久 Root 会话 ----------------

    private static final Object ROOT_LOCK = new Object();
    private static RootSession rootSession = null;
    private static volatile boolean cancelRequested = false;

    /** 请求中断当前 Root 会话命令（供进度对话框「取消」）；下一条命令开始时自动清除。 */
    public static void cancelRootCommand() { cancelRequested = true; }

    // 非 Root 的一次性 sh 进程引用，供「停止」按钮在命令卡死时强制结束，避免窗口挂死。
    private static volatile Process sCurrentShell = null;
    /** 强制结束当前正在执行的一次性（非 Root）shell 进程；无则空操作。 */
    public static void killCurrentShell() {
        Process p = sCurrentShell;
        if (p != null) { try { p.destroy(); } catch (Exception ignored) {} }
    }

    /** 会话是否已建立且仍可用。 */
    public static boolean rootSessionAlive() {
        synchronized (ROOT_LOCK) {
            return rootSession != null && !rootSession.dead;
        }
    }

    /**
     * 建立（或复用）持久 root 会话。幂等且线程安全：并发调用只会启动一个 su 进程，
     * 因此只会弹出一次授权请求。timeoutMs 用于等待用户在授权弹窗上点击。
     *
     * 强制用 "su -mm"（Magisk 的 --mount-master 短选项）把整个会话放进全局挂载命名空间，
     * 这样 /data/data、/data/user/0 能看到所有应用目录，后续命令无需额外处理；
     * 所有走 root 的调用（主界面导航、结果窗口、自定义命令、脚本）都使用同一强制方式。
     * 极少数不支持 -mm 的 Root 方案会快速失败，此时回退普通 "su" 作为兜底。
     */
    /** 挂载命名空间状态：随会话独立保存，主界面与结果窗口各持一份，互不干扰。 */
    private static final class NsInfo {
        boolean globalMountNs = false;
        String mirrorRoot = null;   // /data_mirror 真实视图根目录，null=不可用
    }

    public static boolean ensureRootSession(long timeoutMs) {
        Dbg.log("FS.ROOT", "ensureRootSession timeoutMs=" + timeoutMs);
        synchronized (ROOT_LOCK) {
            if (rootSession != null && !rootSession.dead) return true;
            closeLocked();
            NsInfo ns = new NsInfo();
            RootSession s = tryOpen("su -mm", timeoutMs);
            if (s == null) s = tryOpen("su", timeoutMs);   // 极少数不支持 -mm 的方案兜底
            if (s == null) return false;
            rootSession = s;
            probe(s, ROOT_LOCK, ns);
            globalMountNs = ns.globalMountNs;
            mirrorRoot = ns.mirrorRoot;
            return true;
        }
    }

    /** 启动一个交互式 su 会话并握手确认拿到 uid 0；失败时关闭进程返回 null。 */
    private static RootSession tryOpen(String cmd, long timeoutMs) {
        try {
            RootSession s = RootSession.open(cmd);
            // exec 2>&1 让后续命令的 stderr 并入 stdout；用显式标记输出 uid，
            // 避免某些 su 实现混入 banner/提示符时误判。
            ExecResult hello = s.run(
                    "exec 2>&1\nprintf '__WB_UID__%s\\n' \"$(id -u)\"", timeoutMs);
            if (hello.ok() && hello.out.contains("__WB_UID__0")) return s;
            s.close();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 探测某个 su 会话的挂载命名空间与 /data_mirror 镜像视图，结果写入 out。
     * 调用方须已持有该会话自己的锁（主界面持 ROOT_LOCK，结果窗口持自己的锁），
     * 因为内部直接在该会话上执行 readlink / [ -d ... ] 探测命令。
     */
    private static void probe(RootSession s, Object lock, NsInfo out) {
        // 与 init(PID 1) 的挂载命名空间相同即为全局视图（Magisk su -mm 生效）。
        ExecResult g = s.run("readlink /proc/self/ns/mnt; readlink /proc/1/ns/mnt", 5000);
        String a = "", b = "";
        String[] ls = g.out.split("\n");
        for (String t : ls) {
            t = t.trim();
            if (!t.startsWith("mnt:[")) continue;   // 过滤 readlink 的报错行
            if (a.length() == 0) a = t; else if (b.length() == 0) b = t;
        }
        out.globalMountNs = a.length() > 0 && a.equals(b);
        // Android 11+ 的 /data/data 真实视图；全局命名空间下用不到，但仍探测以备回退。
        if (s.run("[ -d /data_mirror/data_ce/null/0 ]", 5000).ok()) {
            out.mirrorRoot = "/data_mirror/data_ce/null/0";
        } else if (s.run("[ -d /data_mirror/data_ce/0 ]", 5000).ok()) {
            out.mirrorRoot = "/data_mirror/data_ce/0";   // 少数 ROM 的镜像路径层级差异
        }
    }

    /** 关闭持久会话（设置页关闭 Root 时调用）。 */
    public static void closeRootSession() {
        Dbg.log("FS.ROOT", "closeRootSession");
        synchronized (ROOT_LOCK) { closeLocked(); }
    }

    private static void closeLocked() {
        if (rootSession != null) {
            try { rootSession.close(); } catch (Exception ignored) {}
            rootSession = null;
        }
    }

    /**
     * 以 root 或普通 shell 执行一段脚本。
     * root=true 时只走持久会话；会话不存在就直接返回错误，绝不新建 su 进程，
     * 从根本上避免「每次点击文件夹都申请一次 Root」。
     */
    public static ExecResult shell(String script, boolean root) {
        return shell(script, root, 30_000L);
    }

    /** 可指定超时：批量复制/删除等长命令传 BULK_TIMEOUT_MS，避免固定 30 秒误杀。 */
    public static ExecResult shell(String script, boolean root, long timeoutMs) {
        Dbg.log("FS.SHELL", "root=" + root + " timeout=" + timeoutMs
                + "ms 脚本=" + Dbg.clip(script, 1000));
        ExecResult r = shell0(script, root, timeoutMs);
        Dbg.log("FS.SHELL", "结果 code=" + r.code + " out=" + Dbg.clip(r.out, 300)
                + " err=" + Dbg.clip(r.err, 300));
        return r;
    }

    /**
     * 以「su --mount-master -c」直接运行命令（全局挂载命名空间）。用于 Root 环境下由脚本生成的
     * [mycmd:btn:...] 按钮命令：脚本里常通过 touch 停标 / pkill 等方式在全局命名空间里停止后台任务，
     * 走 su --mount-master 才能保证与后台任务处于同一挂载命名空间而命中目标。
     * 极少数不支持 --mount-master 的 su 实现会快速失败，此时自动回退到普通「su -c」。
     */
    public static ExecResult shellMountMaster(String script) {
        Dbg.log("FS.MM", "su --mount-master -c " + Dbg.clip(script, 1000));
        ExecResult r = exec(new String[]{"su", "--mount-master", "-c", script});
        if (r.ok()) return r;
        // 回退：部分 su（如旧版 toybox/非 Magisk）不支持 --mount-master 长选项
        ExecResult r2 = exec(new String[]{"su", "-c", script});
        if (r2.ok()) return r2;
        return r;   // 两次都不成功：返回首次错误信息
    }

    private static ExecResult shell0(String script, boolean root, long timeoutMs) {
        if (!root) return exec(new String[]{"sh", "-c", script});
        synchronized (ROOT_LOCK) {
            RootSession s = rootSession;
            if (s == null || s.dead) {
                // 会话曾因命令超时/取消被销毁：静默重建一次（已永久授权时不会弹窗），
                // 让后续清理命令仍能执行，避免临时目录残留；重建失败才提示重新授权。
                if (!ensureRootSession(15_000L)) {
                    ExecResult r = new ExecResult();
                    r.code = -1;
                    r.err = "Root 会话未就绪：请重启应用，或到「设置 → Root 权限」重新开启以授权";
                    return r;
                }
                s = rootSession;
            }
            // 整段执行都持有 ROOT_LOCK，保证多条命令不会交叉写入同一会话导致输出串台
            return s.run(script, timeoutMs);
        }
    }

    /**
     * 流式执行：命令一开始执行就逐行把输出通过 sink 回传（用于结果窗口「边执行边显示」），
     * 同时支持超时（timeoutSec > 0 时生效，单位秒；<=0 表示不限时）。
     * 返回值与 shell() 一致（out 为完整输出，供超时/失败等兜底场景使用）。
     */
    public static ExecResult shellStream(String script, boolean root, int timeoutSec, LineSink sink) {
        Dbg.log("FS.SHELL", "流式 root=" + root + " timeout=" + timeoutSec
                + "s 脚本=" + Dbg.clip(script, 1000));
        ExecResult r = shellStream0(script, root, timeoutSec, sink);
        Dbg.log("FS.SHELL", "流式结果 code=" + r.code + " err=" + Dbg.clip(r.err, 300));
        return r;
    }

    private static ExecResult shellStream0(String script, boolean root, int timeoutSec, LineSink sink) {
        if (!root) {
            return execStream(new String[]{"sh", "-c", "exec 2>&1; " + script}, timeoutSec, sink);
        }
        synchronized (ROOT_LOCK) {
            RootSession s = rootSession;
            if (s == null || s.dead) {
                if (!ensureRootSession(15_000L)) {
                    ExecResult r = new ExecResult();
                    r.code = -1;
                    r.err = "Root 会话未就绪：请重启应用，或到「设置 → Root 权限」重新开启以授权";
                    return r;
                }
                s = rootSession;
            }
            return s.runStream(script, timeoutSec <= 0 ? 120000 : (long) timeoutSec * 1000, sink);
        }
    }

    /** 一次性进程（普通 shell）的流式版本：逐行回传，并用看门狗线程在超时后销毁进程。 */
    private static ExecResult execStream(final String[] argv, final int timeoutSec, final LineSink sink) {
        final ExecResult r = new ExecResult();
        try {
            final Process p = Runtime.getRuntime().exec(argv);
            sCurrentShell = p;
            final boolean[] finished = { false };
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (sink != null) sink.line(line);
                        }
                    } catch (Exception ignored) {}
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (timeoutSec > 0) {
                Thread watch = new Thread(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep((long) timeoutSec * 1000);
                            if (!finished[0]) p.destroy();   // 超时：强制结束进程
                        } catch (Exception ignored) {}
                    }
                });
                watch.setDaemon(true);
                watch.start();
            }
            r.code = p.waitFor();
            finished[0] = true;
            reader.join();
            if (sCurrentShell == p) sCurrentShell = null;
        } catch (Exception ex) {
            r.code = -1;
            r.err = String.valueOf(ex.getMessage());
        }
        return r;
    }

    /** 单个交互式 su 进程，贯穿 APP 进程生命周期。 */
    private static final class RootSession {
        private static final String EOF = "\u0000__WB_EOF__\u0000";

        Process p;
        BufferedWriter writer;
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        volatile boolean dead = false;
        /** 已被外部强制中断（停止 / 关闭窗口）：run()/runStream() 读循环检测到即立即返回，释放调用方的会话锁。 */
        volatile boolean aborted = false;

        /** cmd 为 su 启动命令，例如 "su" 或 "su -mm"（全局挂载命名空间）。 */
        static RootSession open(String cmd) throws Exception {
            final RootSession s = new RootSession();
            s.p = Runtime.getRuntime().exec(cmd);
            s.writer = new BufferedWriter(new OutputStreamWriter(s.p.getOutputStream(), "UTF-8"));

            // 必须吞掉 su 自身的 stderr，否则管道缓冲写满会阻塞整个会话
            Thread errT = new Thread(new Runnable() {
                public void run() {
                    try {
                        InputStream is = s.p.getErrorStream();
                        byte[] b = new byte[2048];
                        while (is.read(b) > 0) {}
                    } catch (Exception ignored) {}
                }
            });
            errT.setDaemon(true);
            errT.start();

            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.p.getInputStream(), "UTF-8"));
            Thread outT = new Thread(new Runnable() {
                public void run() {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) s.queue.offer(line);
                    } catch (Exception ignored) {}
                    s.dead = true;
                    s.queue.offer(EOF);
                }
            });
            outT.setDaemon(true);
            outT.start();
            return s;
        }

        /** 调用方需持有 ROOT_LOCK。用随机结束标记界定一条命令的输出与退出码。 */
        ExecResult run(String script, long timeoutMs) {
            ExecResult r = new ExecResult();
            if (dead) {
                r.err = "Root 会话已结束，请重新授权";
                return r;
            }
            String token = "__WB_END__" + System.nanoTime();
            queue.clear();
            try {
                writer.write(script);
                if (!script.endsWith("\n")) writer.write("\n");
                // 前置换行：万一 su 输出了不带换行的提示符，也让它单独成行，
                // 不与下面的结束标记粘在一起。空行不改变 $?。
                // echo 的 $? 即 script 最后一条命令的退出码。
                writer.write("\necho " + token + "$?\n");
                writer.flush();
            } catch (Exception ex) {
                dead = true;
                r.err = "写入 Root 会话失败：" + ex.getMessage();
                return r;
            }

            StringBuilder sb = new StringBuilder();
            cancelRequested = false;   // 每条命令开始时清除上一轮的取消请求
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                // abort()（停止 / 关闭窗口）已置位：立即返回，释放调用方持有的会话锁，
                // 避免 onDestroy 在 UI 线程等锁造成卡死；EOF 哨兵同时唤醒 queue.poll。
                if (aborted) { r.code = -1; r.err = "已取消"; return r; }
                if (cancelRequested) {
                    r.code = -1;
                    r.err = "已取消";
                    Dbg.log("FS.ROOT", "会话命令收到取消请求，销毁当前会话");
                    killSelf();
                    return r;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    r.code = -1;
                    r.err = "命令超时（" + (timeoutMs / 1000) + " 秒），Root 会话无响应";
                    // 卡死的 su 进程已不可信：销毁本会话，避免超时命令迟到的结束标记
                    // 与后续命令错位（旧实现只 return，曾导致输出串台、清理也超时）。
                    Dbg.log("FS.ROOT", "会话命令超时，销毁当前会话");
                    killSelf();
                    return r;
                }
                String line;
                try {
                    // 分段等待：便于及时响应取消，又不改变整体超时语义
                    line = queue.poll(Math.min(left, 250L), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    r.err = "已中断";
                    return r;
                }
                if (line == null) continue;   // 尚未到截止时间，继续等（会重新检查取消）
                if (EOF.equals(line)) {
                    dead = true;
                    if (aborted) { r.code = -1; r.err = "已取消"; return r; }
                    // 命令已有正常输出被实时回传，说明命令确实执行过；
                    // 此刻会话结束多为 shell 正常退出，按执行成功处理，避免误报。
                    if (sb.length() > 0) {
                        r.out = sb.toString();
                        r.err = "";
                        r.code = 0;
                        return r;
                    }
                    r.code = -1;
                    r.err = "Root 会话已结束（授权可能被撤销），请重新授权";
                    return r;
                }
                int idx = line.indexOf(token);
                if (idx >= 0) {
                    String tail = line.substring(idx + token.length()).trim();
                    try { r.code = Integer.parseInt(tail); }
                    catch (Exception ignored) { r.code = 0; }
                    r.out = sb.toString();
                    r.err = "";   // 握手时 exec 2>&1 已把 stderr 并入 out
                    return r;
                }
                sb.append(line).append('\n');
            }
        }

        /** run() 的流式版本：每读到一行（非结束标记）就通过 sink 实时回传。 */
        ExecResult runStream(String script, long timeoutMs, LineSink sink) {
            ExecResult r = new ExecResult();
            if (dead) {
                r.err = "Root 会话已结束，请重新授权";
                return r;
            }
            String token = "__WB_END__" + System.nanoTime();
            queue.clear();
            try {
                writer.write(script);
                if (!script.endsWith("\n")) writer.write("\n");
                writer.write("\necho " + token + "$?\n");
                writer.flush();
            } catch (Exception ex) {
                dead = true;
                r.err = "写入 Root 会话失败：" + ex.getMessage();
                return r;
            }

            StringBuilder sb = new StringBuilder();
            cancelRequested = false;   // 每条命令开始时清除上一轮的取消请求
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                // abort()（停止 / 关闭窗口）已置位：立即返回，释放调用方持有的会话锁，
                // 避免 onDestroy 在 UI 线程等锁造成卡死；EOF 哨兵同时唤醒 queue.poll。
                if (aborted) { r.code = -1; r.err = "已取消"; return r; }
                if (cancelRequested) {
                    r.code = -1;
                    r.err = "已取消";
                    Dbg.log("FS.ROOT", "会话命令收到取消请求，销毁当前会话");
                    killSelf();
                    return r;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    r.code = -1;
                    r.err = "命令超时（" + (timeoutMs / 1000) + " 秒），Root 会话无响应";
                    // 卡死的 su 进程已不可信：销毁本会话，避免超时命令迟到的结束标记
                    // 与后续命令错位（旧实现只 return，曾导致输出串台、清理也超时）。
                    Dbg.log("FS.ROOT", "会话命令超时，销毁当前会话");
                    killSelf();
                    return r;
                }
                String line;
                try {
                    // 分段等待：便于及时响应取消，又不改变整体超时语义
                    line = queue.poll(Math.min(left, 250L), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    r.err = "已中断";
                    return r;
                }
                if (line == null) continue;   // 尚未到截止时间，继续等（会重新检查取消）
                if (EOF.equals(line)) {
                    dead = true;
                    if (aborted) { r.code = -1; r.err = "已取消"; return r; }
                    // 命令已有正常输出被实时回传，说明命令确实执行过；
                    // 此刻会话结束多为 shell 正常退出，按执行成功处理，避免误报。
                    if (sb.length() > 0) {
                        r.out = sb.toString();
                        r.err = "";
                        r.code = 0;
                        return r;
                    }
                    r.code = -1;
                    r.err = "Root 会话已结束（授权可能被撤销），请重新授权";
                    return r;
                }
                int idx = line.indexOf(token);
                if (idx >= 0) {
                    String tail = line.substring(idx + token.length()).trim();
                    try { r.code = Integer.parseInt(tail); }
                    catch (Exception ignored) { r.code = 0; }
                    r.out = sb.toString();
                    r.err = "";
                    return r;
                }
                sb.append(line).append('\n');
                if (sink != null) sink.line(line);
            }
        }

        /**
         * 强制中断：不依赖任何会话锁。置 aborted 标志并向队列投递 EOF 哨兵，
         * 让阻塞在 run()/runStream()（它们持有调用方的会话锁）里的读循环立即返回、释放锁；
         * 同时销毁 su 进程。用于「停止」/窗口销毁：若先去抢会话锁，UI 线程会被长期持锁的
         * stream() 卡死（命令未结束锁不释放），「停止」也会失效。
         */
        void abort() {
            aborted = true;
            dead = true;
            Dbg.log("FS.ROOT", "OutputSession.abort：投递 EOF 强制唤醒阻塞中的读循环");
            queue.offer(EOF);   // 先投哨兵唤醒读循环，再销毁进程（顺序保证 poll 立即返回）
            try { writer.close(); } catch (Exception ignored) {}
            try { p.destroy(); } catch (Exception ignored) {}
        }

        /** 销毁当前会话自身：不触碰 Fs.rootSession 引用，避免误伤结果窗口的独立会话。 */
        void killSelf() {
            dead = true;
            try { writer.close(); } catch (Exception ignored) {}
            try { p.destroy(); } catch (Exception ignored) {}
        }

        void close() {
            dead = true;
            try { writer.write("exit\n"); writer.flush(); } catch (Exception ignored) {}
            try { writer.close(); } catch (Exception ignored) {}
            try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    // ---------------- Root 路径映射 ----------------

    /**
     * 把 /data/data、/data/user/0 映射到 /data_mirror/data_ce/null/0。
     * Android 11+ 在 per-app 挂载命名空间里只把本应用的数据挂到 /data/data，
     * 其它应用目录不可见；/data_mirror 是 init 挂载的全局真实视图，root 下始终完整。
     * 会话已处于全局命名空间时无需映射，路径原样返回（用户看到的路径与命令都不变）。
     * 映射后的路径只用于 shell 命令，界面上的路径仍用原始 path 拼接。
     */
    public static String rootPath(String p) {
        return mapRootPath(p, globalMountNs, mirrorRoot);
    }

    /** 按给定命名空间状态做路径映射（主界面与结果窗口各自传入自己的 NsInfo）。 */
    static String mapRootPath(String p, boolean globalMountNs, String M) {
        if (p == null || globalMountNs || M == null) return p;
        // 去掉尾部斜杠，避免 "/data/data/" 映射出带尾斜杠的镜像路径
        String q = p;
        while (q.length() > 1 && q.charAt(q.length() - 1) == '/') {
            q = q.substring(0, q.length() - 1);
        }
        if (q.equals("/data/data") || q.equals("/data/user/0")) return M;
        if (q.startsWith("/data/data/")) return M + q.substring("/data/data".length());
        if (q.startsWith("/data/user/0/")) return M + q.substring("/data/user/0".length());
        return p;   // 非映射路径原样返回，不改变用户看到的路径
    }

    /**
     * 设置页路径回退：当普通方式读不到某个路径、且已开启 Root 时，改用已建立的 Root 会话访问。
     * 这些方法都不会主动触发授权弹窗（仅当 root 会话已存活时才尝试），避免在抽屉打开/设置校验等
     * UI 流程里卡住等待用户点授权。
     */

    /** 普通 File 访问失败时，判断 Root 下该路径是否可读（isDir=true 校验为目录）。 */
    public static boolean rootCanAccess(String path, boolean isDir) {
        if (!rootMode || path == null || path.isEmpty()) return false;
        if (!rootSessionAlive()) return false;
        String p = Util.sq(rootPath(path));
        String test = (isDir ? ("[ -d " + p + " ]") : ("[ -e " + p + " ]")) + " && [ -r " + p + " ]";
        return shell(test, true).ok();
    }

    /** Root 下列出目录下的文件名（去掉 . 与 ..），失败返回空数组。 */
    public static String[] rootList(String dir) {
        if (!rootMode || !rootSessionAlive()) return new String[0];
        ExecResult r = shell("ls -1A " + Util.sq(rootPath(dir)) + " 2>/dev/null", true);
        if (!r.ok() || r.out == null) return new String[0];
        String[] raw = r.out.split("\n");
        ArrayList<String> out = new ArrayList<String>();
        for (String s : raw) {
            s = s.trim();
            if (!s.isEmpty() && !s.equals(".") && !s.equals("..")) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /** Root 下读取文件前 n 行（不含行号），失败返回 null。用于解析脚本标题。 */
    public static String rootHead(String file, int n) {
        if (!rootMode || !rootSessionAlive()) return null;
        ExecResult r = shell("head -n " + Math.max(1, n) + " " + Util.sq(rootPath(file)) + " 2>/dev/null", true);
        return r.ok() ? r.out : null;
    }

    /** Root 下把文件复制到本地目标路径（自动建父目录），用于加载 Root 专属字体。成功返回 true。 */
    public static boolean rootCopy(String src, String dst) {
        if (!rootMode || !rootSessionAlive()) return false;
        File parent = new File(dst).getParentFile();
        if (parent != null) parent.mkdirs();
        ExecResult r = shell("cp -f " + Util.sq(rootPath(src)) + " " + Util.sq(dst) + " 2>/dev/null", true);
        return r.ok() && new File(dst).exists();
    }

    /**
     * 结果窗口专用 Root 会话：独立的 su 进程 + 独立锁，与主界面的导航会话完全隔离。
     * 这样结果窗口里执行（或卡死）的命令绝不会阻塞主界面的 Root 操作，也互不串台。
     * 首次执行 Root 命令时会多弹一次授权请求（仅此一次），之后复用。
     */
    public static final class OutputSession {
        private final Object lock = new Object();
        /** volatile：kill() 需在「不抢锁」的前提下先读到当前会话引用以强制中断。 */
        private volatile RootSession s;
        private final NsInfo ns = new NsInfo();

        /** 建立（或复用）本窗口的 su 会话；已就绪直接返回 true。强制 su -mm。 */
        public boolean open(long timeoutMs) {
            synchronized (lock) {
                if (s != null && !s.dead) return true;
                RootSession t = tryOpen("su -mm", timeoutMs);
                if (t == null) t = tryOpen("su", timeoutMs);   // 极少数不支持 -mm 的方案兜底
                if (t == null) { s = null; return false; }
                s = t;
                probe(s, lock, ns);
                return true;
            }
        }

        /** 流式执行（逐行回传），调用方已持有本会话锁。 */
        public ExecResult stream(String script, int timeoutSec, LineSink sink) {
            synchronized (lock) {
                if (s == null || s.dead) {
                    RootSession t = tryOpen("su -mm", 30000);
                    if (t == null) t = tryOpen("su", 30000);   // 极少数不支持 -mm 的方案兜底
                    if (t == null) {
                        ExecResult r = new ExecResult();
                        r.code = -1;
                        r.err = "Root 会话未就绪：请重启应用，或到「设置 → Root 权限」重新开启以授权";
                        return r;
                    }
                    s = t;
                    probe(s, lock, ns);   // 重新探测命名空间 / 镜像视图
                }
                return s.runStream(script, timeoutSec <= 0 ? 120000 : (long) timeoutSec * 1000, sink);
            }
        }

        /** 一次性执行（不流式），调用方已持有本会话锁。 */
        public ExecResult shell(String script) {
            synchronized (lock) {
                if (s == null || s.dead) {
                    RootSession t = tryOpen("su -mm", 30000);
                    if (t == null) t = tryOpen("su", 30000);   // 极少数不支持 -mm 的方案兜底
                    if (t == null) {
                        ExecResult r = new ExecResult();
                        r.code = -1;
                        r.err = "Root 会话未就绪：请重启应用，或到「设置 → Root 权限」重新开启以授权";
                        return r;
                    }
                    s = t;
                    probe(s, lock, ns);   // 重新探测命名空间 / 镜像视图
                }
                return s.run(script, 30000);
            }
        }

        /** 按本会话的命名空间状态映射 Root 路径。 */
        public String rootPath(String p) {
            return mapRootPath(p, ns.globalMountNs, ns.mirrorRoot);
        }

        public boolean isOpen() {
            synchronized (lock) { return s != null && !s.dead; }
        }

        /**
         * 强制关闭本会话（「停止」按钮 / 窗口销毁时调用）。
         * 关键：先在「锁外」abort() 底层 su 进程。因为 stream() 会在 synchronized(lock) 内
         * 阻塞等命令结束、长期持锁；若先抢锁，onDestroy（UI 线程）会被卡死，「停止」也拿不到锁而无效。
         * abort() 投递 EOF 哨兵后，读循环立即返回并释放锁，随后这里再进锁清理引用（锁已空闲）。
         */
        public void kill() {
            RootSession cur = s;             // volatile 读，无需锁
            if (cur != null) cur.abort();    // 锁外强制中断，唤醒阻塞中的 stream()
            synchronized (lock) {
                s = null;
            }
        }
    }

    // ---------------- 列目录 ----------------

    public static ListResult list(String path) {
        ListResult r = rootMode ? listRoot(path) : listNormal(path);
        Dbg.log("FS.LIST", "path=" + path + " root=" + rootMode + " 条目=" + r.items.size()
                + (r.error == null ? "" : " 错误=" + Dbg.clip(r.error, 300)));
        return r;
    }

    private static ListResult listNormal(String path) {
        ListResult res = new ListResult();
        File d = new File(path);
        if (!d.exists()) { res.error = "目录不存在：" + path; return res; }
        if (!d.isDirectory()) { res.error = "不是目录：" + path; return res; }
        File[] fs = d.listFiles();
        if (fs == null) { res.error = "无法读取（权限不足或分区不可用）：" + path; return res; }
        if (!path.equals("/")) {
            String up = Util.parent(path);
            res.items.add(new FileItem(".", up, true, 0, 0, true));
        }
        for (File f : fs) {
            if (!showHidden && f.getName().startsWith(".")) continue;
            res.items.add(new FileItem(f.getName(), f.getAbsolutePath(),
                    f.isDirectory(), f.isDirectory() ? 0 : f.length(), f.lastModified()));
        }
        Collections.sort(res.items);
        return res;
    }

    /**
     * Root 模式列目录。
     *
     * 旧实现在 shell 循环里对每个文件执行两次 "stat -c"（大小、时间各一次），
     * 等于每个文件 fork 两个外部进程；目录里几百个文件就是几百次 fork，在 su
     * 交互式会话里尤其慢（这正是开启 Root 后加载明显比别的文件管理器慢的主因）。
     * 现在改为把 glob 展开后交给一次 "stat -L -c" 批量处理，只启动 1 个外部进程；
     * 若某些 ROM 的 stat 不支持这些选项，再退化为纯 shell builtin 循环（零 fork）。
     *
     * 两种方案统一输出 "类型\t大小\t修改时间(秒)\t完整路径" 四列，由 parseLines 解析。
     * 注意：stat -c 不解释 \t 转义（只有 --printf 才解释），因此格式串里必须是
     * 真实 TAB 字节；方案 B 由 sh 内建 printf 解释 \t，故用字面转义即可。
     */
    private static ListResult listRoot(String path) {
        ListResult res = new ListResult();
        String d = Util.sq(norm(rootPath(path)));

        // 方案 A：一次 stat 批量取全部条目。%A 是权限串，首字符 d 即目录。
        // 未匹配的 glob 字面量会让 stat 报错，stderr 直接丢弃；部分文件失败不影响
        // 其余输出，故不能用退出码判定成败（只要有可解析行就算成功）。
        String scriptA =
                "d=" + d + "; " +
                "set -- \"$d\"/* \"$d\"/.[!.]* \"$d\"/..?*; " +
                "stat -L -c '%A\t%s\t%Y\t%n' \"$@\" 2>/dev/null";
        ExecResult r = shell(scriptA, true);
        boolean got = r.code >= 0 && parseLines(res, path, r.out);

        if (!got && r.code < 0) {
            // shell 层失败（会话未就绪 / 超时），直接给出明确提示，不再回退
            String msg = r.err.trim();
            if (msg.isEmpty()) msg = r.out.trim();
            res.error = (msg.isEmpty() ? "Root 命令执行失败" : msg)
                    + "\n（如提示未就绪，请到「设置 → Root 权限」重新开启授权）";
            return res;
        }
        if (!got) {
            // 方案 B：纯 builtin 回退。[ / printf / case 都是内建命令，零外部进程，
            // 速度依然很快，只是没有大小与修改时间。
            String scriptB =
                    "d=" + d + "; " +
                    "for n in \"$d\"/* \"$d\"/.[!.]* \"$d\"/..?*; do " +
                    "  [ -e \"$n\" ] || continue; " +
                    "  b=${n##*/}; " +
                    "  case \"$b\" in .|..) continue;; esac; " +
                    "  if [ -d \"$n\" ]; then t=d; else t=f; fi; " +
                    "  printf '%s\\t0\\t0\\t%s\\n' \"$t\" \"$n\"; " +
                    "done";
            ExecResult r2 = shell(scriptB, true);
            parseLines(res, path, r2.out);
            if (res.items.isEmpty() && r2.code < 0) {
                String msg = r2.err.trim();
                res.error = msg.isEmpty() ? "Root 命令执行失败" : msg;
                return res;
            }
        }

        if (!path.equals("/")) {
            res.items.add(new FileItem(".", Util.parent(path), true, 0, 0, true));
        }
        Collections.sort(res.items);
        return res;
    }

    /**
     * 解析 "类型\t大小\t时间(秒)\t完整路径" 四列。
     * 类型以 d 开头视为目录（方案 A 的权限串 "drwx…" / 方案 B 的 "d"）；
     * 无制表符的行是 stat 的报错文本，直接跳过。
     */
    private static boolean parseLines(ListResult res, String path, String out) {
        if (out == null || out.length() == 0) return false;
        boolean any = false;
        for (String line : out.split("\n")) {
            if (line.length() == 0) continue;
            String[] a = line.split("\t", 4);
            if (a.length < 4) continue;
            String full = a[3];
            if (full.indexOf('/') < 0) continue;
            String name = Util.base(full);
            if (name.length() == 0 || name.equals(".") || name.equals("..")) continue;
            if (!showHidden && name.startsWith(".")) continue;
            boolean dir = a[0].startsWith("d");
            long size = 0, mtime = 0;
            try { size = Long.parseLong(a[1].trim()); } catch (Exception ignored) {}
            try { mtime = Long.parseLong(a[2].trim()) * 1000L; } catch (Exception ignored) {}
            res.items.add(new FileItem(name, Util.join(path, name), dir, dir ? 0 : size, mtime));
            any = true;
        }
        return any;
    }

    private static String norm(String p) {
        if (p.equals("/")) return "/";
        return p.replaceAll("/+$", "");
    }

    private static String err(ExecResult r) {
        String m = r.err.trim();
        if (m.isEmpty()) m = r.out.trim();
        return m.isEmpty() ? "命令执行失败 (exit " + r.code + ")" : m;
    }

    // ---------------- 文件操作 ----------------

    public static String mkdir(String path) {
        Dbg.log("FS.OP", "mkdir " + path + " root=" + rootMode);
        if (rootMode) {
            ExecResult r = shell("mkdir -p " + Util.sq(rootPath(path)), true);
            return r.ok() ? null : err(r);
        }
        File f = new File(path);
        return (f.mkdirs() || f.isDirectory()) ? null : "创建失败（权限不足或名称非法）";
    }

    /**
     * 新建空文件。已存在时直接报错，绝不覆盖或清空已有内容
     * （Root 模式用 ": > path" 重定向创建，若先不检查会把同名文件清成 0 字节）。
     */
    public static String createFile(String path) {
        Dbg.log("FS.OP", "createFile " + path + " root=" + rootMode);
        if (path == null || path.length() == 0) return "文件名不合法";
        if (rootMode) {
            if (exists(path)) return "文件已存在：" + Util.base(path);
            ExecResult r = shell(": > " + Util.sq(rootPath(path)), true);
            if (!r.ok()) return err(r);
            return exists(path) ? null : "创建失败（权限不足或路径不可写）";
        }
        File f = new File(path);
        if (f.exists()) return "文件已存在：" + f.getName();
        try {
            return f.createNewFile() ? null : "创建失败（权限不足或名称非法）";
        } catch (Exception ex) {
            return "创建失败：" + ex.getMessage();
        }
    }

    public static String delete(String path) {
        return delete(path, 30_000L);
    }

    /** 可指定超时：清理大目录（上万个文件）时用 BULK_TIMEOUT_MS。 */
    public static String delete(String path, long timeoutMs) {
        Dbg.log("FS.OP", "delete " + path + " root=" + rootMode + " timeout=" + timeoutMs);
        if (rootMode) {
            ExecResult r = shell("rm -rf " + Util.sq(rootPath(path)), true, timeoutMs);
            return r.ok() ? null : err(r);
        }
        return delRec(new File(path)) ? null : "删除失败（权限不足）";
    }

    private static boolean delRec(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) delRec(x);
        }
        return f.delete();
    }

    public static String rename(String src, String dst) {
        return rename(src, dst, 30_000L);
    }

    /** 可指定超时：同分区大目录搬移是瞬时 mv，跨分区退化成复制时给足超时。 */
    public static String rename(String src, String dst, long timeoutMs) {
        Dbg.log("FS.OP", "rename " + src + " -> " + dst + " root=" + rootMode
                + " timeout=" + timeoutMs);
        if (rootMode) {
            ExecResult r = shell("mv -f " + Util.sq(rootPath(src)) + " "
                    + Util.sq(rootPath(dst)), true, timeoutMs);
            return r.ok() ? null : err(r);
        }
        return new File(src).renameTo(new File(dst)) ? null : "重命名失败（权限不足或目标已存在）";
    }

    public static String copy(String src, String dst) {
        return copy(src, dst, 30_000L);
    }

    /** 可指定超时：复制大目录（上万个文件）时用 BULK_TIMEOUT_MS。 */
    public static String copy(String src, String dst, long timeoutMs) {
        Dbg.log("FS.OP", "copy " + src + " -> " + dst + " root=" + rootMode + " timeout=" + timeoutMs);
        if (rootMode) {
            ExecResult r = shell("cp -r " + Util.sq(rootPath(src)) + " "
                    + Util.sq(rootPath(dst)), true, timeoutMs);
            return r.ok() ? null : err(r);
        }
        try { copyRec(new File(src), new File(dst)); return null; }
        catch (Exception ex) { return "复制失败：" + ex.getMessage(); }
    }

    private static void copyRec(File s, File d) throws Exception {
        if (s.isDirectory()) {
            if (!d.mkdirs() && !d.isDirectory()) throw new Exception("无法创建 " + d);
            File[] c = s.listFiles();
            if (c != null) for (File x : c) copyRec(x, new File(d, x.getName()));
        } else {
            FileInputStream in = new FileInputStream(s);
            FileOutputStream out = new FileOutputStream(d);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static boolean exists(String path) {
        if (rootMode) return shell("[ -e " + Util.sq(rootPath(path)) + " ]", true).ok();
        return new File(path).exists();
    }

    /** 生成不冲突的目标名：name, name(1), name(2) ... */
    public static String unique(String dir, String name) {
        if (!exists(Util.join(dir, name))) return name;
        String e = Util.ext(name);
        String stem = e.isEmpty() ? name : name.substring(0, name.length() - e.length() - 1);
        String suffix = e.isEmpty() ? "" : "." + e;
        for (int i = 1; i < 1000; i++) {
            String cand = stem + "(" + i + ")" + suffix;
            if (!exists(Util.join(dir, cand))) return cand;
        }
        return name + System.currentTimeMillis();
    }
}
