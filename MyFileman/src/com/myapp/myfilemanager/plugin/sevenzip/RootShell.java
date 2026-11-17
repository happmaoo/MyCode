package com.myapp.myfilemanager.plugin.sevenzip;

import com.myapp.myfilemanager.Dbg;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过 su 在 root shell 里执行命令，并把 stdout 按字节原样取回。
 * 不依赖任何 Android API，可以单独用 javac 编译测试。
 *
 * 关键点：Termux 是 prefixed system，二进制在 $PREFIX/bin，动态库在 $PREFIX/lib。
 * 普通 root shell 里直接执行 /data/data/com.termux/files/usr/bin/7z 往往会报
 * "No such file or directory" 或找不到 .so，所以必须用 env 注入环境。
 */
public final class RootShell {

    /** Termux 标准 $PREFIX（仅作默认值，实际按探测到的 bin 路径推导） */
    static final String PREFIX = "/data/data/com.termux/files/usr";

    /**
     * Linux 内核对 execve 的「单个参数」有硬上限 MAX_ARG_STRLEN（32 * PAGE_SIZE = 128KB）。
     * `su -c <script>` 把整段脚本当成一个参数传进去，脚本一旦超过 128KB，execve 直接失败并报
     * E2BIG（error=7, Argument list too long）——进程根本起不来，常被误判成「root 未授权」。
     * 批量解压上万条目时脚本可达数百 KB，因此超长脚本必须先落盘成 .sh，再让 sh 从文件读取。
     * 100KB 阈值按 UTF-8 字节数计算，给中文路径留足余量。
     */
    private static final int MAX_INLINE_SCRIPT_BYTES = 100 * 1024;

    public static final class Result {
        private final int code;
        private final byte[] stdout;
        private final String stderr;

        Result(int code, byte[] stdout, String stderr) {
            this.code = code;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getCode() { return code; }
        public byte[] getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public String getText() { return new String(stdout, StandardCharsets.UTF_8); }
        public boolean isOk() { return code == 0; }
    }

    /** sh 安全的单引号包裹：a'b -> 'a'\''b'，防止文件名里的空格、引号、$() 造成注入 */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 只给 7z 这一条进程注入 Termux 环境。
     * 按 bin 的实际路径推导 PREFIX：Termux 用改版包名 / 装在多用户时也能正确注入 LD_LIBRARY_PATH。
     * 非 Termux 二进制（如 /system/bin/7z 静态版）不注入 LD_LIBRARY_PATH，避免误加载不兼容的库。
     */
    static String termuxCommand(String bin, String args) {
        int i = bin.indexOf("/bin/");
        String prefix = i > 0 ? bin.substring(0, i) : PREFIX;
        boolean isTermux = i > 0 && prefix.endsWith("/files/usr");
        // 等价 Kotlin removeSuffix("/usr") + "/home"
        String home = prefix.endsWith("/usr")
                ? prefix.substring(0, prefix.length() - 4) + "/home"
                : prefix + "/home";
        StringBuilder b = new StringBuilder();
        b.append("env ");
        if (isTermux) {
            b.append("PREFIX=").append(quote(prefix)).append(' ');
            b.append("LD_LIBRARY_PATH=").append(quote(prefix + "/lib")).append(' ');
            b.append("HOME=").append(quote(home)).append(' ');
            b.append("TMPDIR=").append(quote(prefix + "/tmp")).append(' ');
        }
        b.append("LANG=C.UTF-8 LC_ALL=C.UTF-8 ").append(quote(bin)).append(' ').append(args);
        return b.toString();
    }

    private static final String[] SU_CANDIDATES = {
            "su", "/system/xbin/su", "/system/bin/su", "/sbin/su", "/debug_ramdisk/su"
    };

    /** 当前正在执行的 su 进程（供「取消」跨线程销毁）。 */
    private static volatile Process sCurrent = null;

    /** 取消当前正在执行的 7z/su 命令：销毁进程，使阻塞中的 exec() 立即返回非零码。
     *  由插件进程在独立 Binder 线程调用（主界面点「取消」时经 AIDL 触发）。 */
    public static void cancelCurrent() {
        Process p = sCurrent;
        Dbg.log("SU.cancel", "cancelCurrent sCurrent=" + (p != null));
        if (p == null) return;
        // 先关闭三个流：让 exec() 里的 stdout/stderr 读线程立即读到 EOF/抛错退出，
        // 即便 7z 子进程继承了管道写端也不会把 io 线程再拖住 3 秒；随后销毁 su 进程。
        try { p.getOutputStream().close(); } catch (Throwable ignored) {}
        try { p.getInputStream().close(); } catch (Throwable ignored) {}
        try { p.getErrorStream().close(); } catch (Throwable ignored) {}
        try { p.destroy(); } catch (Throwable ignored) {}
    }

    /**
     * mountMaster: 用 `su -mm` 强制全局挂载命名空间。
     * -mm 是 Magisk 的参数，KernelSU v0.5.0+ / APatch 才兼容，老版本不认识会直接报错。
     * 因此只作为「默认命名空间下找不到 7z」时的回退探测，绝不作为默认调用方式。
     */
    static Result exec(String script, long timeoutMs, int maxBytes, boolean mountMaster) {
        return exec(script, timeoutMs, maxBytes, mountMaster, null);
    }

    /**
     * @param scriptDir 超长脚本的落盘目录（通常是解压目标目录，已确认可写）；
     *                  传 null 表示只允许内联执行。短脚本走原路径，行为完全不变。
     */
    static Result exec(String script, long timeoutMs, int maxBytes,
                       boolean mountMaster, String scriptDir) {
        String command = script;
        File tempScript = null;
        if (utf8Bytes(script) > MAX_INLINE_SCRIPT_BYTES) {
            tempScript = writeTempScript(script, scriptDir);
            if (tempScript != null) {
                Dbg.log("SU.exec", "脚本 " + utf8Bytes(script) + " 字节超上限，落盘为 "
                        + tempScript.getAbsolutePath());
                String p = quote(tempScript.getAbsolutePath());
                // 参数只剩几百字符，彻底避开 E2BIG；用临时变量把 sh 的退出码原样传回 su。
                command = "sh " + p + "; __FMRC__=$?; rm -f " + p + "; exit $__FMRC__";
            }
        }
        try {
            return execCommand(command, timeoutMs, maxBytes, mountMaster);
        } finally {
            // 双保险：脚本末尾已 rm，这里再删一次，确保取消/超时也不会在目标目录留残留。
            if (tempScript != null) {
                try { tempScript.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private static int utf8Bytes(String s) {
        try { return s.getBytes(StandardCharsets.UTF_8).length; }
        catch (Throwable t) { return s.length(); }
    }

    /** 把脚本写入目标目录下的临时 .sh 文件；失败返回 null（调用方退回内联执行）。 */
    private static File writeTempScript(String script, String scriptDir) {
        try {
            File dir = (scriptDir == null || scriptDir.isEmpty())
                    ? new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"))
                    : new File(scriptDir);
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return null;
            File f = File.createTempFile("fmsz_", ".sh", dir);
            FileOutputStream fo = new FileOutputStream(f);
            try { fo.write(script.getBytes(StandardCharsets.UTF_8)); } finally { fo.close(); }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Result execCommand(String script, long timeoutMs, int maxBytes, boolean mountMaster) {
        Dbg.log("SU.exec", "进入 timeoutMs=" + timeoutMs + " mm=" + mountMaster
                + " 命令长度=" + script.length());
        Process process = null;
        Exception lastError = null;
        for (String su : SU_CANDIDATES) {
            try {
                process = mountMaster
                        ? new ProcessBuilder(su, "-mm", "-c", script).start()
                        : new ProcessBuilder(su, "-c", script).start();
                break;
            } catch (Exception e) {
                lastError = e;
                Dbg.log("SU.exec", "su 候选 [" + su + "] 启动失败: " + e);
            }
        }
        final Process p = process;
        if (p == null) {
            Dbg.log("SU.exec", "全部 su 候选失败: " + lastError);
            // 不再武断断言「设备未 root」：E2BIG / Permission denied / 无 su 的原因各不相同，
            // 直接回传内核的真实错误信息，避免误导排查。
            return new Result(-1, new byte[0],
                    "无法启动 su：" + (lastError == null ? "未知原因" : lastError.getMessage()));
        }
        sCurrent = p;   // 登记为「当前可取消进程」（并发时后启动者覆盖，解压场景为串行调用）
        try {
            p.getOutputStream().close();   // 不让 7z 等待 stdin（密码错误时也直接 EOF 返回）
        } catch (Exception ignored) {
        }

        final AtomicReference<byte[]> outRef = new AtomicReference<byte[]>(new byte[0]);
        final AtomicReference<String> errRef = new AtomicReference<String>("");
        final int limit = maxBytes;

        // stdout 可能是二进制（-so 输出），必须按字节读
        Thread outThread = new Thread(new Runnable() {
            public void run() {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    InputStream stream = p.getInputStream();
                    byte[] buf = new byte[16 * 1024];
                    while (true) {
                        int n = stream.read(buf);
                        if (n < 0) break;
                        int remain = limit - bos.size();
                        if (remain > 0) bos.write(buf, 0, Math.min(n, remain));
                        // 超限后仍继续读到 EOF，否则管道写满会把 7z 卡死
                        // 边读边更新：即便 7z 子进程继承管道写端导致本线程迟迟等不到 EOF、
                        // 主流程 join 超时被放弃，也能拿到已读到的部分，不会返回空 stdout。
                        outRef.set(bos.toByteArray());
                    }
                } catch (Exception ignored) {
                }
                outRef.set(bos.toByteArray());
            }
        });
        Thread errThread = new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                    try {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (sb.length() < 64 * 1024) sb.append(line).append('\n');
                        }
                    } finally {
                        try { br.close(); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {
                }
                errRef.set(sb.toString());
            }
        });
        outThread.start();
        errThread.start();

        // 不用 Process.waitFor(timeout, unit)：那是 API 26 才有的 Java 8 方法。
        // 改成轮询 exitValue()，minSdk 可以下探到 21。
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean finished = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                p.exitValue();
                finished = true;
                break;
            } catch (IllegalThreadStateException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        int code;
        if (finished) {
            try {
                code = p.exitValue();
            } catch (Exception ignored) {
                code = -1;
            }
        } else {
            p.destroy();
            code = -1;
        }
        try { outThread.join(3000); } catch (InterruptedException ignored) {}
        try { errThread.join(3000); } catch (InterruptedException ignored) {}
        // 进程已退出但读线程仍被孤儿子进程占着管道写端时，主动关流让其读到 EOF / 抛错退出，
        // 再短等一次确保 outRef/errRef 已写入，避免把「已成功」的命令误判成「未执行」。
        if (outThread.isAlive()) {
            try { p.getInputStream().close(); } catch (Throwable ignored) {}
            try { outThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (errThread.isAlive()) {
            try { p.getErrorStream().close(); } catch (Throwable ignored) {}
            try { errThread.join(500); } catch (InterruptedException ignored) {}
        }
        Dbg.log("SU.exec", "结束 code=" + code + " 超时=" + !finished
                + " stdout=" + outRef.get().length + "B"
                + " stderr=" + Dbg.clip(errRef.get(), 400));
        if (sCurrent == p) sCurrent = null;   // 只清除自己，避免并发 exec 相互误清
        return new Result(code, outRef.get(), errRef.get());
    }

    // Kotlin 默认参数 -> Java 重载
    static Result exec(String script) {
        return exec(script, 30_000L, 8 * 1024 * 1024, false);
    }

    static Result exec(String script, long timeoutMs, int maxBytes) {
        return exec(script, timeoutMs, maxBytes, false);
    }
}
