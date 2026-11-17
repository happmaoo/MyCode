package com.myapp.myfilemanager.plugin.sevenzip;

import com.myapp.myfilemanager.Dbg;

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
        List<SevenZipEntry> es = parseListing(r.getText(), archive);
        if (es.isEmpty() && r.getCode() == 0 && r.getText().trim().length() > 0) {
            // 兜底：-slt 解析不出条目但 7z 正常退出 → 改跑表格模式再解析一次
            // （部分版本的 -slt 对某些格式可能连 Size 段都不同，表格行尾的条目名始终在）
            RootShell.Result r2 = run7z("l -ba -sccUTF-8 -y " + pwArg(password) + q(archive),
                    60_000L, 16 * 1024 * 1024);
            List<SevenZipEntry> es2 = parseListingTable(r2.getText());
            if (!es2.isEmpty()) {
                Dbg.log("7Z.list", "-slt 解析为空，表格模式兜底命中 条目数=" + es2.size());
                es = es2;
            }
        }
        if (es.isEmpty())   // 诊断：两种模式都空时记录原始输出，便于上报排查
            Dbg.log("7Z.list", "条目解析为空 code=" + r.getCode()
                    + " stdout=" + Dbg.clip(r.getText(), 400)
                    + " stderr=" + Dbg.clip(r.getStderr(), 400));
        return new ListResult(es, r.getCode(), r.getStderr());
    }

    /**
     * 表格模式（7z l -ba）回退解析。行格式：[Date Time] Attr Size Packed Name…（Name 含空格原样在行尾）：
     *                     .....     30914560      4512536  adwaita-icon-theme-51.0.tar
     * 识别条件：attr 列含 "....."（目录/加密为 D 开头），且行尾不是汇总行（"N files/folders"）。
     * 仅作 -slt 解析不出条目时的兜底，精度要求低。
     */
    private static final java.util.regex.Pattern TABLE_ROW = java.util.regex.Pattern.compile(
            "^(.*?)\\s{2,}(\\d+)\\s+(\\d+)\\s{2,}(.+)$");

    List<SevenZipEntry> parseListingTable(String text) {
        List<SevenZipEntry> out = new ArrayList<SevenZipEntry>();
        for (String line : StrUtil.lines(text.replace("\r\n", "\n"))) {
            boolean attrLike = line.contains(".....")
                    || line.replace('\t', ' ').trim().matches("[Dd].*");
            if (!attrLike) continue;
            java.util.regex.Matcher m = TABLE_ROW.matcher(line.trim());
            if (!m.matches()) continue;
            String[] pre = m.group(1).trim().split("\\s+");
            String attr = pre[pre.length - 1];
            if (attr.matches("\\d.*") || attr.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            String name = m.group(4).trim();
            if (name.isEmpty() || name.matches("\\d+\\s+(files|folders).*")) continue;
            out.add(new SevenZipEntry(name,
                    StrUtil.trimEndChar(name.replace('\\', '/'), '/'),
                    attr.contains("D") || attr.startsWith("d") || name.endsWith("/"),
                    StrUtil.parseLongOr(m.group(2), 0L),
                    StrUtil.parseLongOr(m.group(3), 0L),
                    "", false, ""));
        }
        return out;
    }

    /** 解析 -slt 输出：遇到 "Path = " 就开启一个新记录，不依赖空行分隔。
     *  特例：xz / gz / bz2 / zst 等单流归档的条目段【没有 Path 行】（7z 17.05+ 实测，
     *  只有 Size / Packed Size / Method），靠 Path 开块会得到 0 条 → 表现为「压缩包为空」。
     *  这类块补上「包名去掉最后一个扩展名」作为条目名 —— 与 7z x 解压落盘名一致，
     *  该名称也可直接作为 7z e 的文件参数用于后续提取（实测 23.01）。 */
    List<SevenZipEntry> parseListing(String text, String archivePath) {
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
            if (raw == null || raw.isEmpty()) {
                // 无 Path 的块：只有含 Size（条目必有）的才视作单流条目，
                // 归档信息段（Physical Size / Type，不带 -ba 时出现）无纯 Size 键，跳过。
                if (!m.containsKey("Size")) continue;
                raw = singleStreamEntryName(archivePath);
                if (raw == null) continue;
            }
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

    /**
     * 单流归档（xz / gz / bz2 / zst / lzma 等）的条目名：包名去掉最后一个扩展名。
     * 例如 a/b/c.tar.xz → c.tar、d.log.gz → d.log；无扩展名或只剩基名时返回 null（不造条目）。
     * 该名称与 7z x 的解压落盘名一致，也可直接作为 7z e 的文件参数提取。
     */
    private static String singleStreamEntryName(String archivePath) {
        if (archivePath == null) return null;
        String base = archivePath.replace('\\', '/');
        int s = base.lastIndexOf('/');
        base = (s >= 0) ? base.substring(s + 1) : base;
        int d = base.lastIndexOf('.');
        if (d <= 0 || d >= base.length() - 1) return null;
        String name = base.substring(0, d);
        return name.isEmpty() ? null : name;
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
        String args = "e -o" + q(outDir) + " -y -aoa -bd " + pwArg(password)
                + q(archive) + " " + q(rawPath);
        String script = "mkdir -p " + q(outDir) + " && " + RootShell.termuxCommand(bin, args);
        Dbg.log("7Z.extractAll", "archive=" + archive + " outDir=" + outDir
                + " 脚本长度=" + script.length());
        RootShell.Result r = RootShell.exec(script, 120_000L, 1024 * 1024, useMountMaster);
        Dbg.log("7Z.extractAll", "结果 code=" + r.getCode()
                + " stderr=" + Dbg.clip(r.getStderr(), 400));
        return r;
    }

    /** 把整个压缩包解压到指定目录（用 x 保留内部目录结构），返回执行结果。 */
    public RootShell.Result extractAll(String archive, String password, String outDir) {
        String args = "x -o" + q(outDir) + " -y -aoa -bd " + pwArg(password) + q(archive);
        String script = "mkdir -p " + q(outDir) + " && " + RootShell.termuxCommand(bin, args);
        RootShell.Result r = RootShell.exec(script, 120_000L, 1024 * 1024, useMountMaster);
        return r;
    }

    /**
     * 解压前估算「解压后实际大小」（Byte），用作进度条分母。
     *  - innerPaths 为空/null：解压整包 —— 跑一次 {@code 7z l}（表格输出，带 -y），从末尾
     *    汇总行「2026-11-13 04:36:06   33512732   2579814  10410 files, 191 folders」
     *    取第一个大数（33512732 = 解压后实际大小）。取不到时回退为累加 -slt 全部文件 Size。
     *  - innerPaths 非空：从压缩包复制部分条目 —— 累加这些条目（含文件夹展开）的 -slt Size。
     * 失败返回 -1（调用方按「总大小未知」降级处理）。
     */
    public long contentSize(String archive, List<String> innerPaths, String password) {
        boolean whole = (innerPaths == null || innerPaths.isEmpty());
        if (whole) {
            long s = summarySize(archive, password);
            if (s > 0) return s;
        }
        ListResult lr = list(archive, password);
        if (lr.entries.isEmpty()) return -1;
        // 用 rawPath 作键：ArchiveTree.collect 产出的正是 rawPath（可能含反斜杠），
        // 直接对应可避免规范化路径不一致导致漏算（Windows 打包的压缩包尤其明显）。
        Map<String, Long> sizeByRaw = new LinkedHashMap<String, Long>();
        for (SevenZipEntry e : lr.entries) {
            if (!e.isDir) sizeByRaw.put(e.rawPath, e.size);
        }
        if (whole) {
            long sum = 0;
            for (Long v : sizeByRaw.values()) sum += v;
            return sum;
        }
        // 部分条目：把选中的文件夹展开成具体文件，按 rawPath 累加实际大小
        TreeNode root = ArchiveTree.buildTree(lr.entries);
        LinkedHashSet<String> files = new LinkedHashSet<String>();
        LinkedHashSet<String> dirs = new LinkedHashSet<String>();
        for (String inner : innerPaths) {
            TreeNode n = ArchiveTree.find(root, inner);
            if (n == null) continue;
            ArchiveTree.collect(n, files, dirs);
        }
        long sum = 0;
        for (String raw : files) {
            Long v = sizeByRaw.get(raw);
            if (v != null) sum += v;
        }
        return sum > 0 ? sum : -1;
    }

    /** 跑一次 {@code 7z l}（表格输出，带 -y），解析末尾汇总行的第一个大数（解压后总大小）。 */
    private long summarySize(String archive, String password) {
        // 表格输出可能很长（上万条目），用 tail 只保留末尾几行，避免把 stdout 缓冲撑满
        String args = "l -sccUTF-8 -y " + pwArg(password) + q(archive);
        String script = RootShell.termuxCommand(bin, args) + " | tail -n 8";
        RootShell.Result r = RootShell.exec(script, 60_000L, 256 * 1024, useMountMaster);
        List<String> lines = StrUtil.lines(r.getText());
        // 汇总行示例：2026-11-13 04:36:06   33512732   2579814  10410 files, 191 folders
        // 结构固定为「日期 时间 <解压后大小> <压缩后大小> <文件数> files[, <目录数> folders]」。
        // 用两个/多个大数 + files 的模式，精确取第一个大数（解压后大小），避开日期时间中的数字。
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\\d+)\\s+\\d+\\s+\\d+\\s+files?\\b");
        for (int i = lines.size() - 1; i >= 0; i--) {
            java.util.regex.Matcher m = p.matcher(lines.get(i));
            if (m.find()) {
                try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        // 兜底：含 files 的行里取第一个 >=5 位的数字（兼容中间有 Attr 列等变体）
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("\\d{5,}");
        for (int i = lines.size() - 1; i >= 0; i--) {
            String ln = lines.get(i);
            if (!ln.contains("files") && !ln.contains("file")) continue;
            java.util.regex.Matcher m = p2.matcher(ln);
            if (m.find()) {
                try { return Long.parseLong(m.group()); } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
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
        Dbg.log("7Z.extractMany", "进入 archive=" + archive + " outDir=" + outDir
                + " inners(" + (innerPaths == null ? 0 : innerPaths.size()) + ")="
                + Dbg.clip(String.valueOf(innerPaths), 800));
        if (innerPaths == null || innerPaths.isEmpty()) return "没有可解压的条目";
        // 1) 一次列出全部条目并建树，再把选中的文件/文件夹展开成具体条目
        ListResult lr = list(archive, password);
        Dbg.log("7Z.extractMany", "list code=" + lr.code + " 条目数="
                + (lr.entries == null ? 0 : lr.entries.size())
                + " stderr=" + Dbg.clip(lr.stderr, 300));
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
        // 2) 一次 su：mkdir -p 目标与空目录，然后分批 7z x，最后把反斜杠条目归位。
        //    条目上万时脚本可达数百 KB，传入 outDir 作为落盘目录：RootShell 会在脚本超长时
        //    自动写成 .sh 再让 sh 读取，避免 su -c 的单个参数触发内核 E2BIG（Argument list too long）。
        final List<String> fileList = new ArrayList<String>(files);
        final List<String> dirList = new ArrayList<String>(dirs);
        String script = buildExtractScript(bin, archive, fileList, dirList, password, outDir);
        // 只记长度不记内容：上万条目的脚本可达数百 KB，打印出来会把日志撑爆
        Dbg.log("7Z.extractMany", "展开 files=" + fileList.size() + " dirs=" + dirList.size()
                + " 未找到=" + missing + " 脚本长度=" + script.length() + "字符");
        RootShell.Result r = RootShell.exec(script, 600_000L, 1024 * 1024, useMountMaster, outDir);
        Dbg.log("7Z.extractMany", "exec code=" + r.getCode()
                + " stdout=" + r.getStdout().length + "B 标记数=" + markerCount(r)
                + " stderr=" + Dbg.clip(r.getStderr(), 400));

        // 成功与否以「期望文件是否真实落地」为准，而不是 stdout 里的 __SZRC__ 标记数。
        // 标记偶发丢失（7z 子进程继承 stdout 管道写端 → 读线程 join 超时拿到空 stdout；
        // 或短时间内多次 su 申请被 root daemon 偶发拒绝）过去会被误报成
        // 「7z 未执行 / root 未授权」，其实文件已经解压成功。
        final String warn = missing > 0 ? "（" + missing + " 个条目在包内未找到，其余已解压）" : null;
        String miss = verifyOutputs(outDir, fileList, dirList);
        Dbg.log("7Z.extractMany", "verifyOutputs -> "
                + (miss == null ? "全部落地(成功)" : Dbg.clip(miss, 600)));
        if (miss == null) return warn;                 // 事实成功：忽略标记统计，不误报

        // 文件没齐且一个标记都没有 → 疑似这次 su 压根没跑起来（root daemon 抖动），重试一次脚本
        if (markerCount(r) == 0) {
            Dbg.log("7Z.extractMany", "标记数为 0，重试一次脚本");
            r = RootShell.exec(script, 600_000L, 1024 * 1024, useMountMaster, outDir);
            Dbg.log("7Z.extractMany", "重试 exec code=" + r.getCode()
                    + " 标记数=" + markerCount(r));
            miss = verifyOutputs(outDir, fileList, dirList);
            if (miss == null) return warn;
        }
        // 仍失败：优先给标记诊断（已中性化，不再断言 root 未授权），否则返回缺失清单
        String diag = parseExtractResult(r);
        String ret = diag != null ? diag : miss;
        Dbg.log("7Z.extractMany", "最终返回错误=" + Dbg.clip(ret, 800));
        return ret;
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
            args.append("x -y -aoa -bso0 -bsp0 -bd -sccUTF-8 -o").append(q(outDir)).append(' ')
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

    /** 统计 stdout 里 __SZRC__ 标记数；0 表示脚本输出一个标记都没读到。 */
    static int markerCount(RootShell.Result r) {
        int n = 0;
        for (String line : StrUtil.lines(r.getText())) {
            if (line.trim().startsWith("__SZRC__=")) n++;
        }
        return n;
    }

    /**
     * 解析批量执行结果：统计 __SZRC__ 标记；全 0 返回 null，否则返回错误说明。
     * 注意：标记数只作「辅助诊断」，成功与否由 verifyOutputs 以落盘文件为准。
     * total==0 不再武断断言「root 未授权」——标记丢失也可能只是 stdout 管道读取竞态 / 超时，
     * 只有 stderr 明确出现授权相关关键字或退出码为负时才提示 root 相关。
     */
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
            String low = err.toLowerCase(java.util.Locale.US);
            boolean rootHint = r.getCode() < 0
                    || low.contains("permission denied") || low.contains("su:")
                    || low.contains("not authorized") || low.contains("denied");
            String why = rootHint ? "可能是 root 授权超时或 su 未响应"
                                  : "7z 脚本未产生输出（可能被中断或超时）";
            return "解压未完成：" + why + "（退出码 " + r.getCode() + "）"
                    + (err.trim().isEmpty() ? "" : "\n" + err);
        }
        if (ok == total) return null;
        String err = StrUtil.take(r.getStderr(), 300);
        return "部分批次失败：" + ok + "/" + total + " 批成功（7z 退出码 " + r.getCode() + "）"
                + (err.trim().isEmpty() ? "" : "\n" + err);
    }
}
