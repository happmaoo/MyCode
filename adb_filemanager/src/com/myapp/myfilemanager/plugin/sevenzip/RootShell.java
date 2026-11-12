package com.myapp.myfilemanager.plugin.sevenzip;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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

    /**
     * mountMaster: 用 `su -mm` 强制全局挂载命名空间。
     * -mm 是 Magisk 的参数，KernelSU v0.5.0+ / APatch 才兼容，老版本不认识会直接报错。
     * 因此只作为「默认命名空间下找不到 7z」时的回退探测，绝不作为默认调用方式。
     */
    static Result exec(String script, long timeoutMs, int maxBytes, boolean mountMaster) {
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
            }
        }
        final Process p = process;
        if (p == null) {
            return new Result(-1, new byte[0],
                    "找不到 su，设备可能未 root：" + (lastError == null ? "" : lastError.getMessage()));
        }
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
