package com.myapp.myfilemanager.plugin;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.myapp.myfilemanager.plugin.sevenzip.ArchiveTree;
import com.myapp.myfilemanager.plugin.sevenzip.DetectResult;
import com.myapp.myfilemanager.plugin.sevenzip.ListResult;
import com.myapp.myfilemanager.plugin.sevenzip.RootShell;
import com.myapp.myfilemanager.plugin.sevenzip.SevenZip;
import com.myapp.myfilemanager.plugin.sevenzip.SevenZipEntry;
import com.myapp.myfilemanager.plugin.sevenzip.TreeNode;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 7z 压缩包插件，运行在独立进程 :plugin.archive。
 * 直接复用设备上 Termux 安装的 7z（7zz/7z/7za），不内置二进制、不需要存储权限，
 * 全程通过 su 调用，因此插件进程卡死/崩溃也不会影响主界面。
 */
public class ArchivePluginService extends Service {
    private final SevenZip sz = new SevenZip();
    private boolean detectedRestored = false;   // 是否已从磁盘恢复过探测缓存
    private final Set<String> ARCHIVE_EXT = new HashSet<String>(Arrays.asList(
            "7z", "zip", "rar", "tar", "gz", "bz2", "xz", "tgz", "tar.gz",
            "zst", "lz4", "cab", "iso", "jar", "apk", "deb", "tzst"));

    private final IPlugin.Stub pluginStub = new IPlugin.Stub() {
        public int getProtocolVersion() { return PluginManager.PROTOCOL; }

        public PluginInfo getInfo() {
            return new PluginInfo(
                    "archive7z", "7z 压缩包", 250, 1,
                    ARCHIVE_EXT.toArray(new String[0]),
                    new String[]{},
                    new String[]{PluginManager.CAP_ARCHIVE});
        }

        public boolean hasCapability(String cap) {
            return PluginManager.CAP_ARCHIVE.equals(cap);
        }

        public IBinder getCapability(String cap) {
            return PluginManager.CAP_ARCHIVE.equals(cap) ? archiveStub : null;
        }
    };

    private final IArchiveProvider.Stub archiveStub = new IArchiveProvider.Stub() {
        public boolean canHandle(String path, String mime) {
            if (path == null) return false;
            String ext = extOf(path);
            return ARCHIVE_EXT.contains(ext);
        }

        public ArchiveListing list(String archivePath, String innerPath, String password) {
            try {
                ensureDetected();
                ListResult res = sz.list(archivePath, password == null ? "" : password);
                if (res.getEntries().isEmpty() && looksLikeBinMissing(res.getStderr())) {
                    sz.invalidate();
                    sz.detect(true);
                    res = sz.list(archivePath, password == null ? "" : password);
                }
                if (res.getEntries().isEmpty()) {
                    return new ArchiveListing(new ArrayList<ArchiveEntry>(), res.getCode(),
                            res.getStderr() == null ? "压缩包为空或无法读取" : res.getStderr());
                }
                TreeNode root = ArchiveTree.buildTree(res.getEntries());
                TreeNode node = nodeFor(root, innerPath);
                if (node == null) {
                    return new ArchiveListing(new ArrayList<ArchiveEntry>(), -1,
                            "找不到路径：" + (innerPath == null ? "/" : innerPath));
                }
                List<ArchiveEntry> out = new ArrayList<ArchiveEntry>();
                for (TreeNode child : ArchiveTree.sortedChildren(node)) {
                    SevenZipEntry e = child.getEntry();
                    String raw = (e != null) ? e.rawPath : child.getFullPath();
                    long size = (e != null) ? e.size : 0;
                    String modified = (e != null) ? e.modified : "";
                    boolean enc = (e != null) && e.encrypted;
                    out.add(new ArchiveEntry(child.getName(), raw, child.getFullPath(),
                            child.isDir(), size, parseTime(modified), enc));
                }
                return new ArchiveListing(out, res.getCode(), null);
            } catch (Throwable t) {
                return new ArchiveListing(new ArrayList<ArchiveEntry>(), -1,
                        "列出压缩包失败：" + t.getMessage());
            }
        }

        public ParcelFileDescriptor open(String archivePath, String innerPath, String password) {
            try {
                ensureDetected();
                SevenZipEntry e = sz.find(archivePath, innerPath, password == null ? "" : password);
                if (e == null) return null;
                RootShell.Result r = sz.read(archivePath, e.rawPath, password == null ? "" : password,
                        16 * 1024 * 1024);
                if (!r.isOk() && r.getStdout().length == 0) return null;
                return pipe(r.getStdout());
            } catch (Throwable t) {
                return null;
            }
        }

        public int extract(String archivePath, String innerPath, String password, String outDir) {
            try {
                ensureDetected();
                SevenZipEntry e = sz.find(archivePath, innerPath, password == null ? "" : password);
                if (e == null) return -1;
                RootShell.Result r = sz.extract(archivePath, e.rawPath,
                        password == null ? "" : password, outDir);
                return r.getCode();
            } catch (Throwable t) {
                return -1;
            }
        }

        public int extractAll(String archivePath, String password, String outDir) {
            try {
                ensureDetected();
                RootShell.Result r = sz.extractAll(archivePath,
                        password == null ? "" : password, outDir);
                return r.getCode();
            } catch (Throwable t) {
                return -1;
            }
        }

        public String extractBatch(String archivePath, List<String> innerPaths,
                                   String password, String outDir) {
            try {
                ensureDetected();
                return sz.extractMany(archivePath, innerPaths,
                        password == null ? "" : password, outDir);
            } catch (Throwable t) {
                return "批量解压失败：" + t.getMessage();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return pluginStub;
    }

    // ---------------- 内部辅助 ----------------

    private void ensureDetected() {
        // 优化1：进程冷启动后先从磁盘恢复探测结果，避免重复跑 su 探测
        if (!detectedRestored) {
            detectedRestored = true;
            SharedPreferences sp = getSharedPreferences("archive7z_cache", MODE_PRIVATE);
            String b = sp.getString("bin", null);
            if (b != null && !b.isEmpty()) {
                sz.restore(b, sp.getBoolean("mm", false));
            }
        }
        if (sz.getBin().isEmpty()) {
            DetectResult d = sz.detect(false);
            // 若默认命名空间没找到，再试全局命名空间
            if (d.getBin() == null) sz.detect(true);
            // 探测成功则写回磁盘，供下次冷启动直接复用
            if (!sz.getBin().isEmpty()) {
                getSharedPreferences("archive7z_cache", MODE_PRIVATE).edit()
                        .putString("bin", sz.getBin())
                        .putBoolean("mm", sz.isUseMountMaster())
                        .apply();
            }
        }
    }

    private static TreeNode nodeFor(TreeNode root, String inner) {
        String s = (inner == null) ? "" : inner;
        if (s.startsWith("/")) s = s.substring(1);
        TreeNode node = root;
        for (String seg : s.split("/")) {
            if (seg.isEmpty()) continue;
            node = node.getChildren().get(seg);
            if (node == null) return null;
        }
        return node;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i >= name.length() - 1) return "";
        String e = name.substring(i + 1).toLowerCase(Locale.ROOT);
        // 处理 .tar.gz 这类双扩展名
        int j = name.substring(0, i).lastIndexOf('.');
        if (j >= 0) {
            String dbl = name.substring(j + 1).toLowerCase(Locale.ROOT);
            if (dbl.equals("tar") && e.equals("gz")) return "tar.gz";
        }
        return e;
    }

    private static boolean looksLikeBinMissing(String err) {
        if (err == null) return false;
        String s = err.toLowerCase(Locale.ROOT);
        return s.contains("not found") || s.contains("no such file")
                || s.contains("cannot link") || s.contains("can't execute")
                || s.contains("permission denied");
    }

    private static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        // 7z 常见：2021-01-02 03:04:05 或 2021-01-02 03:04
        String[] fmts = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String f : fmts) {
            try {
                Date d = new SimpleDateFormat(f, Locale.US).parse(s.trim());
                if (d != null) return d.getTime();
            } catch (ParseException ignored) {}
        }
        return 0;
    }

    /** 把字节数组通过管道回传：写入端在子线程写，返回读端给调用方。 */
    private static ParcelFileDescriptor pipe(final byte[] data) {
        try {
            ParcelFileDescriptor[] pfd = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor write = pfd[1];
            final ParcelFileDescriptor read = pfd[0];
            new Thread(new Runnable() {
                public void run() {
                    FileOutputStream fos = new FileOutputStream(write.getFileDescriptor());
                    try {
                        fos.write(data);
                        fos.flush();
                    } catch (Exception ignored) {
                    } finally {
                        try { fos.close(); } catch (Exception ignored) {}
                        try { write.close(); } catch (Exception ignored) {}
                    }
                }
            }).start();
            return read;
        } catch (Exception e) {
            return null;
        }
    }
}
