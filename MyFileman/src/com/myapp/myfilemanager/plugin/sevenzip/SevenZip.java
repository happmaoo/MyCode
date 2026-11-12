package com.myapp.myfilemanager.plugin.sevenzip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 7z 调用封装：探测 7z 二进制位置、用 `7z l -slt -ba -sccUTF-8` 列目录、
 * 用 `e -so` 读单文件到 stdout、解压到目录。
 *
 * 关键设计（与原 SevenZipExplorer 一致）：
 *  - 二进制来自 Termux（/data/data/com.termux/files/usr/bin/7zz 等），通过 RootShell 注入环境后调用；
 *  - 绝不内置 7z 二进制，直接复用设备上已有的 Termux 安装；
 *  - 命名空间探测：先默认 ns，找不到再回退 su -mm（Magisk 全局命名空间）。
 */
public class SevenZip {

    /** 探测到的 7z 绝对路径 */
    private String bin = "";
    /** 用户手动指定的 7z 绝对路径，优先于自动探测 */
    private String manualBin = "";
    /** 7z 是否只在全局挂载命名空间下可见（模块/挂载路径） */
    private boolean useMountMaster = false;
    /** 最近一次探测的完整报告，供「设置 → 环境诊断」展示 */
    private String lastReport = "";

    public String getBin() { return bin; }
    public String getManualBin() { return manualBin; }
    public void setManualBin(String v) { manualBin = v; }
    public boolean isUseMountMaster() { return useMountMaster; }
    public String getLastReport() { return lastReport; }

    /** 从持久化缓存恢复：启动时不重复探测 */
    public void restore(String cachedBin, boolean mm) {
        bin = cachedBin;
        useMountMaster = mm;
    }

    /** 让缓存失效，下次访问时重新探测 */
    public void invalidate() {
        bin = "";
        useMountMaster = false;
    }

    private static String q(String s) { return RootShell.quote(s); }

    private static String getTrim(Map<String, String> m, String key) {
        String v = m.get(key);
        return v == null ? "" : v.trim();
    }

    private static final List<String> NAMES = Arrays.asList("7zz", "7z", "7za", "7zr", "7zzs");

    private static int rank(String p) {
        String n = p.substring(p.lastIndexOf('/') + 1);
        int idx = NAMES.indexOf(n);
        int k = idx < 0 ? 50 : idx;
        return (p.contains("/files/usr/bin/") ? 0 : 100) + k;
    }

    private static final class Probe {
        final List<String> bins;
        final boolean rootOk;
        final List<String> info;

        Probe(List<String> bins, boolean rootOk, List<String> info) {
            this.bins = bins;
            this.rootOk = rootOk;
            this.info = info;
        }
    }

    /**
     * 一次 su 调用收集全部诊断信息。刻意避开 shell 变量（$），
     * 用 find -path + sed 打标记，Java 侧按前缀解析。
     */
    private static String probeScript() {
        StringBuilder b = new StringBuilder();
        b.append("echo '===PROBE==='\n");
        b.append("id -u 2>/dev/null | sed 's|^|@ID@|'\n");
        b.append("readlink /proc/self/ns/mnt 2>/dev/null | sed 's|^|@NS@|'\n");
        b.append("getenforce 2>/dev/null | sed 's|^|@SE@|'\n");
        b.append("ls -d /data/data/*/files/usr/bin /data/user/0/*/files/usr/bin 2>/dev/null | sed 's|^|@PREFIXDIR@|'\n");
        b.append("[ -d /data/data/com.termux/files/usr/bin ] && echo '@DIROK@/data/data/com.termux/files/usr/bin'\n");
        b.append("find /data/data/ /data/user/0/ -maxdepth 6 -path '*/files/usr/bin/7z*' \\( -type f -o -type l \\) 2>/dev/null | head -40 | sed 's|^|@BIN@|'\n");
        b.append("find /system/bin /system/xbin /vendor/bin /sbin /debug_ramdisk /data/local/tmp /data/adb/modules -maxdepth 5 -name '7z*' \\( -type f -o -type l \\) 2>/dev/null | head -40 | sed 's|^|@BIN@|'\n");
        b.append("echo '===END==='\n");
        return b.toString();
    }

    private static Probe parseProbe(RootShell.Result r) {
        List<String> bins = new ArrayList<String>();
        List<String> info = new ArrayList<String>();
        boolean rootOk = false;
        for (String raw : StrUtil.lines(r.getText())) {
            String s = raw.trim();
            if (s.startsWith("@ID@")) {
                rootOk = true;
                info.add("uid=" + s.substring("@ID@".length()) + "（root 已就绪）");
            } else if (s.startsWith("@NS@")) {
                info.add("mnt_ns=" + s.substring("@NS@".length()));
            } else if (s.startsWith("@SE@")) {
                info.add("SELinux=" + s.substring("@SE@".length()));
            } else if (s.startsWith("@PREFIXDIR@")) {
                info.add("Termux 前缀: " + s.substring("@PREFIXDIR@".length()));
            } else if (s.startsWith("@DIROK@")) {
                info.add("标准 Termux bin 目录存在");
            } else if (s.startsWith("@BIN@")) {
                String p = s.substring("@BIN@".length()).trim();
                if (p.startsWith("/")) bins.add(p);
            }
        }
        return new Probe(bins, rootOk, info);
    }

    public DetectResult detect() { return detect(false); }

    public DetectResult detect(boolean preferMountMaster) {
        StringBuilder report = new StringBuilder();
        boolean[] modes = preferMountMaster
                ? new boolean[]{true, false} : new boolean[]{false, true};

        if (!manualBin.isEmpty()) {
            for (boolean mm : modes) {
                String check = "if [ -x " + q(manualBin) + " ]; then echo YES; else echo NO; fi";
                RootShell.Result chk = RootShell.exec(check, 15_000L, 1024, mm);
                if (chk.getText().trim().equals("YES")) {
                    bin = manualBin;
                    useMountMaster = mm;
                    report.append("【手动指定】").append(manualBin).append("  可执行 ✔")
                            .append(mm ? "（全局命名空间）" : "").append('\n');
                    lastReport = report.toString();
                    return new DetectResult(manualBin, lastReport);
                }
            }
            report.append("【手动指定】").append(manualBin)
                    .append("  不可执行，或 root 未授权；转自动探测\n");
        }

        String script = probeScript();
        List<String> bins = Collections.emptyList();
        boolean usedMm = false;
        boolean everRoot = false;
        int round = 1;

        for (boolean mm : modes) {
            if (!bins.isEmpty()) break;
            RootShell.Result r = RootShell.exec(script, 30_000L, 512 * 1024, mm);
            Probe p = parseProbe(r);
            report.append("【第 ").append(round++).append(" 轮 ")
                    .append(mm ? "su -mm -c（全局挂载命名空间）】\n"
                            : "su -c（默认挂载命名空间）】\n");
            if (!p.rootOk) {
                report.append("  root 未就绪：su 没有返回任何输出，exit=").append(r.getCode()).append('\n');
                if (!r.getStderr().trim().isEmpty())
                    report.append("  stderr: ").append(StrUtil.take(r.getStderr(), 300)).append('\n');
                if (mm) report.append("  → 该 root 方案可能不支持 -mm，已忽略本轮。\n");
                else report.append("  → 请在 Magisk / KernelSU 里给本 App 授予 root 权限后重试。\n");
                continue;
            }
            everRoot = true;
            for (int i = 0; i < Math.min(24, p.info.size()); i++)
                report.append("  ").append(p.info.get(i)).append('\n');
            report.append("  找到 ").append(p.bins.size()).append(" 个 7z 候选：\n");
            for (int i = 0; i < Math.min(20, p.bins.size()); i++)
                report.append("    ").append(p.bins.get(i)).append('\n');
            if (!r.getStderr().trim().isEmpty())
                report.append("  stderr: ").append(StrUtil.take(r.getStderr(), 300)).append('\n');
            if (!p.bins.isEmpty()) {
                bins = p.bins;
                usedMm = mm;
                if (mm) report.append("  → 全局命名空间下找到 7z，后续所有调用自动带 -mm。\n");
            }
        }

        List<String> unique = new ArrayList<String>(new LinkedHashSet<String>(bins));
        Collections.sort(unique, new Comparator<String>() {
            public int compare(String a, String b) {
                int x = rank(a), y = rank(b);
                return x < y ? -1 : (x > y ? 1 : 0);
            }
        });
        String chosen = unique.isEmpty() ? null : unique.get(0);
        if (chosen != null) {
            bin = chosen;
            useMountMaster = usedMm;
            report.append("\n【结论】使用 ").append(chosen).append('\n');
        } else {
            useMountMaster = false;
            report.append("\n【结论】没有找到可执行的 7z。\n");
            if (!everRoot) {
                report.append("  ★ 首要原因：root 未授权 / su 不可用，不是 7z 没装。\n");
            }
            report.append("  排查顺序：\n");
            report.append("   1) 上面有没有「uid=0」——没有就是 root 没授权\n");
            report.append("   2) Termux 里执行：pkg install 7zip（得到 7zz）或 pkg install p7zip（得到 7z）\n");
            report.append("   3) 看「Termux 前缀」列出的真实路径，改版包名/多用户会不一样\n");
            report.append("   4) 最省事：在「设置」里直接填 7z 的绝对路径\n");
        }
        lastReport = report.toString();
        return new DetectResult(chosen, lastReport);
    }

    private static String pwArg(String password) {
        return password == null || password.isEmpty() ? "" : "-p" + q(password) + " ";
    }

    private static String normInner(String p) {
        // 去掉首尾斜杠，统一用于比较
        String s = StrUtil.trimChar(p, '/');
        return s;
    }

    private RootShell.Result run7z(String args, long timeoutMs, int maxBytes) {
        return RootShell.exec(RootShell.termuxCommand(bin, args), timeoutMs, maxBytes, useMountMaster);
    }

    /**
     * 列出压缩包内容。
     * -slt  技术列表，逐条 key = value，机器可解析（比表格输出可靠得多）
     * -ba   去掉版权头/统计尾
     * -sccUTF-8  强制 console 输出 UTF-8，中文文件名不乱码
     */
    public ListResult list(String archive) { return list(archive, ""); }

    public ListResult list(String archive, String password) {
        String args = "l -slt -ba -sccUTF-8 -y " + pwArg(password) + q(archive);
        RootShell.Result r = run7z(args, 60_000L, 16 * 1024 * 1024);
        return new ListResult(parseListing(r.getText()), r.getCode(), r.getStderr());
    }

    /** 解析 -slt 输出：遇到 "Path = " 就开启一个新记录，不依赖空行分隔 */
    List<SevenZipEntry> parseListing(String text) {
        List<Map<String, String>> blocks = new ArrayList<Map<String, String>>();
        Map<String, String> cur = null;
        for (String line : StrUtil.lines(text.replace("\r\n", "\n"))) {
            int i = line.indexOf(" = ");
            if (i < 0) continue;
            String key = line.substring(0, i).trim();
            String value = line.substring(i + 3);
            if (key.equals("Path")) {
                Map<String, String> m = new LinkedHashMap<String, String>();
                blocks.add(m);
                cur = m;
            }
            if (cur != null) cur.put(key, value);
        }
        List<SevenZipEntry> out = new ArrayList<SevenZipEntry>();
        for (Map<String, String> m : blocks) {
            String raw = m.get("Path");
            if (raw == null || raw.isEmpty()) continue;
            String norm = StrUtil.trimEndChar(raw.replace('\\', '/'), '/');
            if (norm.isEmpty()) continue;
            String folder = m.get("Folder");
            String attrs = m.get("Attributes");
            boolean isDir = "+".equals(folder) || (attrs != null && attrs.startsWith("D"));
            out.add(new SevenZipEntry(
                    raw,
                    norm,
                    isDir,
                    StrUtil.parseLongOr(m.get("Size"), 0L),
                    StrUtil.parseLongOr(m.get("Packed Size"), 0L),
                    getTrim(m, "Modified"),
                    "+".equals(getTrim(m, "Encrypted")),
                    getTrim(m, "Method")
            ));
        }
        return out;
    }

    /** 在压缩包里按规范化内层路径（无首尾斜杠）反查原始条目，供 read/extract 定位。 */
    public SevenZipEntry find(String archive, String innerNorm, String password) {
        String want = normInner(innerNorm);
        ListResult r = list(archive, password);
        for (SevenZipEntry e : r.entries) {
            if (normInner(e.path).equals(want)) return e;
        }
        return null;
    }

    /**
     * 把包内单个文件解压到 stdout（-so），不落盘、不需要存储权限。
     * -bso0 -bsp0 关掉消息与进度行，保证 stdout 是纯数据；诊断信息走 stderr。
     */
    public RootShell.Result read(String archive, String rawPath, String password) {
        return read(archive, rawPath, password, 2 * 1024 * 1024);
    }

    public RootShell.Result read(String archive, String rawPath, String password, int maxBytes) {
        String args = "e -so -bso0 -bsp0 -bd -y " + pwArg(password)
                + q(archive) + " " + q(rawPath);
        return run7z(args, 60_000L, maxBytes);
    }

    /** 把包内单个文件解压到指定目录（用 e 平铺，不保留包内路径，落盘名即基本名）。 */
    public RootShell.Result extract(String archive, String rawPath, String password, String outDir) {
        String args = "e -o" + q(outDir) + " -y -bd " + pwArg(password)
                + q(archive) + " " + q(rawPath);
        String script = "mkdir -p " + q(outDir) + " && " + RootShell.termuxCommand(bin, args);
        return RootShell.exec(script, 120_000L, 1024 * 1024, useMountMaster);
    }

    /** 把整个压缩包解压到指定目录（用 x 保留内部目录结构），返回执行结果。 */
    public RootShell.Result extractAll(String archive, String password, String outDir) {
        String args = "x -o" + q(outDir) + " -y -bd " + pwArg(password) + q(archive);
        String script = "mkdir -p " + q(outDir) + " && " + RootShell.termuxCommand(bin, args);
        return RootShell.exec(script, 120_000L, 1024 * 1024, useMountMaster);
    }

    // ---------------- 批量解压（文件 + 文件夹多选复制） ----------------

    /**
     * 把包内多个条目（文件或文件夹，规范内层路径）一次解压到 outDir，保留包内目录结构。
     * 性能关键：整个流程只做一次 l -slt、起一次 su（内部按 60 个文件一批串联执行），
     * 与旧「每项单独 find + 单独 extract」相比，N 项多选从 N 次全量列表 + N 次 su 降到 1 + 1。
     *
     * @return null = 全部成功；否则返回错误说明
     */
    public String extractMany(String archive, List<String> innerPaths, String password, String outDir) {
        if (innerPaths == null || innerPaths.isEmpty()) return "没有可解压的条目";
        // 1) 一次列出全部条目并建树，再把选中的文件/文件夹展开成具体条目
        ListResult lr = list(archive, password);
        if (lr.entries.isEmpty()) {
            String err = StrUtil.take(lr.stderr, 300);
            return "无法读取压缩包（7z 退出码 " + lr.code + "）" + (err.trim().isEmpty() ? "" : "\n" + err);
        }
        TreeNode root = ArchiveTree.buildTree(lr.entries);
        LinkedHashSet<String> files = new LinkedHashSet<String>();
        LinkedHashSet<String> dirs = new LinkedHashSet<String>();
        int missing = 0;
        for (String inner : innerPaths) {
            TreeNode n = ArchiveTree.find(root, inner);
            if (n == null) { missing++; continue; }
            ArchiveTree.collect(n, files, dirs);
        }
        if (files.isEmpty() && dirs.isEmpty())
            return "找不到选中的条目（可能已改名或路径不规范）";
        // 2) 一次 su：mkdir -p 目标与空目录，然后分批 7z x，最后把反斜杠条目归位
        String script = buildExtractScript(bin, archive,
                new ArrayList<String>(files), new ArrayList<String>(dirs), password, outDir);
        RootShell.Result r = RootShell.exec(script, 600_000L, 1024 * 1024, useMountMaster);
        String msg = parseExtractResult(r);
        if (msg == null) msg = verifyOutputs(outDir, new ArrayList<String>(files), new ArrayList<String>(dirs));
        if (msg != null) return msg;
        return missing > 0 ? "（" + missing + " 个条目在包内未找到，其余已解压）" : null;
    }

    /**
     * 解压后校验：在插件进程（与主界面同一 uid / 同一视图）里逐个确认期望产物存在。
     * 背景：7z 对「一个都没匹配到」的文件名参数只报 warning、退出码仍是 0（实测 23.01），
     * 若不校验就会把空结果当成功上报。校验失败时返回带缺失清单与 stderr 的详细错误。
     */
    static String verifyOutputs(String outDir, List<String> files, List<String> dirs) {
        List<String> missing = new ArrayList<String>();
        int total = files.size() + dirs.size();
        for (String raw : files) {
            String norm = raw.replace('\\', '/');
            if (!new java.io.File(outDir, norm).exists()) missing.add(norm);
        }
        for (String d : dirs) {
            if (!new java.io.File(outDir, d).exists()) missing.add(d + "/");
        }
        if (missing.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        b.append("解压结果校验失败：期望 ").append(total).append(" 项，缺失 ")
                .append(missing.size()).append(" 项");
        if (missing.size() == total) {
            b.append("\n（7z 未匹配到任何文件名参数，或目标目录不可写）");
        }
        b.append('\n');
        for (int i = 0; i < Math.min(5, missing.size()); i++) {
            b.append("  缺失：").append(missing.get(i)).append('\n');
        }
        return b.toString();
    }

    /** 生成批量解压脚本：目录一律 mkdir -p（含空目录），文件按批传给 7z x（保留结构）。 */
    static String buildExtractScript(String bin, String archive,
                                     List<String> files, List<String> dirs,
                                     String password, String outDir) {
        final int BATCH = 60;
        StringBuilder b = new StringBuilder();
        b.append("mkdir -p ").append(q(outDir)).append('\n');
        for (int from = 0; from < dirs.size(); from += BATCH) {
            int to = Math.min(from + BATCH, dirs.size());
            b.append("mkdir -p");
            for (int i = from; i < to; i++) {
                String rel = dirs.get(i);
                b.append(' ').append(q(rel.isEmpty() ? outDir : outDir + "/" + rel));
            }
            b.append('\n');
        }
        if (files.isEmpty()) {
            // 只选了空目录：mkdir 已完成，补一个标记让结果统计能确认执行过
            b.append("echo __SZRC__=$?\n");
            return b.toString();
        }
        for (int from = 0; from < files.size(); from += BATCH) {
            int to = Math.min(from + BATCH, files.size());
            StringBuilder args = new StringBuilder();
            args.append("x -y -bso0 -bsp0 -bd -sccUTF-8 -o").append(q(outDir)).append(' ')
                    .append(pwArg(password)).append(q(archive)).append(' ');
            for (int i = from; i < to; i++) {
                if (i > from) args.append(' ');
                args.append(q(files.get(i)));
            }
            b.append(RootShell.termuxCommand(bin, args.toString())).append('\n');
            b.append("echo __SZRC__=$?\n");
        }

        // 反斜杠归位：Windows 等工具打包的 zip/rar 里路径以 \ 分隔（实测 7z 23.01），
        // 只有反斜杠原始参数能匹配，且解出来是「文件名字面含 \ 的散文件」而不是目录结构。
        // 这里把它们 mv 回规范化路径；若某些 7z 版本已按目录解出，[ -f ] 守卫会自动跳过。
        for (String raw : files) {
            int bs = raw.indexOf('\\');
            if (bs < 0) continue;
            String norm = raw.replace('\\', '/');
            String lit = outDir + "/" + raw;
            String tgt = outDir + "/" + norm;
            b.append("if [ -f ").append(q(lit)).append(" ]; then mkdir -p ")
                    .append(q(outDir + "/" + (norm.contains("/")
                            ? norm.substring(0, norm.lastIndexOf('/')) : "")))
                    .append("; mv -f ").append(q(lit)).append(' ').append(q(tgt))
                    .append("; fi\n");
        }
        return b.toString();
    }

    /** 解析批量执行结果：统计 __SZRC__ 标记；全 0 返回 null，否则返回错误说明。 */
    static String parseExtractResult(RootShell.Result r) {
        int total = 0, ok = 0;
        for (String line : StrUtil.lines(r.getText())) {
            String s = line.trim();
            if (s.startsWith("__SZRC__=")) {
                total++;
                if (s.endsWith("=0")) ok++;
            }
        }
        if (total == 0) {
            String err = StrUtil.take(r.getStderr(), 300);
            return "7z 未执行（root 未授权 / su 失败，退出码 " + r.getCode() + "）"
                    + (err.trim().isEmpty() ? "" : "\n" + err);
        }
        if (ok == total) return null;
        String err = StrUtil.take(r.getStderr(), 300);
        return "部分批次失败：" + ok + "/" + total + " 批成功（7z 退出码 " + r.getCode() + "）"
                + (err.trim().isEmpty() ? "" : "\n" + err);
    }
}
