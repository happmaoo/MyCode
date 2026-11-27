package com.myapp.mqtt.base;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行端的「持久 shell 会话」：整个 app 生命周期只起一个 sh 进程，
 * 后续命令都写进这个进程的 stdin，所以 cd / export / alias 等状态会延续。
 *
 * 每条命令的结束靠一个随机哨兵判断：
 *   { 命令
 *   } < /dev/null
 *   echo <TAG> $?
 * 读到 TAB 那一行就算本条命令结束，行尾是退出码。
 * 命令的 stdin 指向 /dev/null，避免 cat / read 之类把后面写入的哨兵吃掉。
 */
public final class ExecShell {

    /** 默认截断长度（由设置页 out_limit 覆盖） */
    private static final int DEFAULT_MAX = 4000;

    private static final Object LOCK = new Object();
    private static Process proc;
    private static BufferedWriter stdin;
    private static BufferedReader stdout;
    private static int seq = 0;
    /** 新会话自动执行的目录命令（从执行端「启动命令」里的 cd 行提取），保证超时重置后目录不丢 */
    private static volatile String persistCwd;
    /** 非空时用它来启动持久 shell（例如 su -c 'sh 某个脚本'），让整个会话换一个 shell/用户 */
    private static volatile String launchCmd;

    private ExecShell() {
    }

    public static final class Result {
        public final String output;
        public final int code;

        Result(String output, int code) {
            this.output = output;
            this.code = code;
        }
    }

    /** 丢弃当前会话（下次执行时重新起一个），用于超时/异常自愈 */
    public static void reset() {
        synchronized (LOCK) {
            try {
                if (stdin != null) stdin.close();
            } catch (Exception ignore) {
            }
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Exception ignore) {
                }
            }
            proc = null;
            stdin = null;
            stdout = null;
        }
    }

    private static boolean ensure() throws IOException {
        if (proc != null) {
            try {
                proc.exitValue();     // 抛异常说明还活着
            } catch (IllegalThreadStateException alive) {
                return false;
            } catch (Exception dead) {
                // 落到下面重建
            }
            try {
                if (stdin != null) stdin.close();
            } catch (Exception ignore) {
            }
            proc = null;
            stdin = null;
            stdout = null;
        }
        String lc = launchCmd;
        ProcessBuilder pb = (lc == null || lc.isEmpty())
                ? new ProcessBuilder("sh")
                : new ProcessBuilder("sh", "-c", lc);
        Process p = pb.redirectErrorStream(true).start();
        proc = p;
        stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), "UTF-8"));
        stdout = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        return true;
    }

    /** 设置新会话自动执行的 cd 命令（没有 cd 行就传空） */
    public static void setLaunchCmd(String cmd) {
        launchCmd = (cmd == null || cmd.trim().isEmpty()) ? null : cmd.trim();
    }

    public static void setPersistCwd(String cmd) {
        persistCwd = (cmd == null || cmd.trim().isEmpty()) ? null : cmd.trim();
    }

    /** 在持久会话里执行一条命令，超时则重置会话 */
    public static Result run(String cmd, int timeoutMs, int maxChars) {
        if (cmd == null || cmd.trim().isEmpty()) return new Result("(空命令)", -1);
        synchronized (LOCK) {
            try {
                boolean created = ensure();
                String pc = persistCwd;
                if (created && pc != null && !pc.equals(cmd.trim())) {
                    // 会话刚（重新）建立：先补回 startup 里的 cd，再跑本条命令
                    run(pc, 6000, 200);
                }
            } catch (IOException e) {
                proc = null;
                return new Result("无法启动 shell: " + e, -1);
            }

            final String tag = "__MYMQTT_EOX_" + (++seq) + "_"
                    + Long.toHexString(System.nanoTime() & 0xffffff);
            final BufferedReader out = stdout;
            try {
                stdin.write("{ " + cmd + "\n} < /dev/null\n");
                stdin.write("echo " + tag + " $?\n");
                stdin.flush();
            } catch (IOException e) {
                reset();
                return new Result("写入失败（会话已重置）: " + e, -1);
            }

            FutureTask<String[]> task = new FutureTask<>(() -> readUntil(out, tag, maxChars));
            Thread t = new Thread(task, "execshell-read");
            t.setDaemon(true);
            t.start();

            try {
                String[] rr = task.get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
                return new Result(rr[1], parseCode(rr[0]));
            } catch (TimeoutException te) {
                task.cancel(true);
                reset();                       // 杀掉进程 → 读线程读到 EOF 退出
                return new Result("…(超时 " + timeoutMs + " ms，shell 会话已重置)", -2);
            } catch (ExecutionException ee) {
                reset();
                return new Result("读取异常（会话已重置）: " + ee.getCause(), -1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                reset();
                return new Result("被中断（会话已重置）", -1);
            }
        }
    }

    private static int parseCode(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 读到哨兵行为止，返回 {退出码, 输出} */
    private static String[] readUntil(BufferedReader r, String tag, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith(tag + " ")) {
                String s = line.substring(tag.length() + 1).trim();
                if (s.matches("-?\\d+")) return new String[]{s, sb.toString()};
            }
            if (!truncated) {
                if (sb.length() < maxChars) {
                    sb.append(line).append('\n');
                }
                if (sb.length() >= maxChars) {
                    sb.append("…(输出已截断)\n");
                    truncated = true;
                }
            }
        }
        throw new IOException("shell 已退出");
    }

    /** 一次性执行（不影响持久会话，例如截屏） */
    public static Result once(String cmd, int timeoutMs, int maxChars) {
        Process p = null;
        StringBuilder sb = new StringBuilder();
        int code = -1;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < maxChars) {
                    sb.append(line).append('\n');
                    if (sb.length() >= maxChars) sb.append("…(输出已截断)\n");
                }
            }
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            code = done ? p.exitValue() : -2;
            if (!done) {
                p.destroy();
                sb.append("…(超时 ").append(timeoutMs).append("ms，已终止)\n");
            }
        } catch (Exception e) {
            sb.append("执行异常: ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage()).append('\n');
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignore) {
                }
            }
        }
        return new Result(sb.toString(), code);
    }
}
