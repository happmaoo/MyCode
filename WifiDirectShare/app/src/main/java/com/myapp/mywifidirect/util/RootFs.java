package com.myapp.mywifidirect.util;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Root 文件系统访问通道。
 *
 * 用途：第三方文件管理器通过标准 FileProvider 分享「目录」时，接收端无法用
 * ContentResolver 列出子项；而 URI path 里携带的真实路径（如
 * /storage/emulated/0/temp2）在 Android 11+ 又被分区存储禁止普通 App 直接访问。
 * 已有 root 时，可用本工具直接按真实路径列目录、读文件，绕过上述两点限制。
 *
 * 只在已获得 root 时工作：优先复用缓存 shell，避免重复弹授权窗。
 */
public final class RootFs {

    private RootFs() {}

    /** 是否已有可用的 root shell（优先缓存，必要时建立一次）。 */
    public static boolean available() {
        try {
            Shell s = Shell.getCachedShell();
            if (s != null) return s.isAlive() && s.isRoot();
            s = Shell.getShell();
            return s != null && s.isRoot();
        } catch (Throwable t) {
            return false;
        }
    }

    /** shell 单引号转义，避免路径中的空格/引号破坏命令。 */
    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 用 root 判断真实路径是否为目录。 */
    public static boolean isDirectory(String path) {
        if (path == null || !path.startsWith("/") || !available()) return false;
        try {
            return Shell.cmd("[ -d " + q(path) + " ]").exec().isSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用 root 列出目录直接子项的绝对路径（文件与子目录都包含）。 */
    public static List<String> listChildren(String path) {
        List<String> out = new ArrayList<>();
        if (path == null || !available()) return out;
        try {
            Shell.Result r = Shell.cmd("ls -1 -- " + q(path)).exec();
            if (!r.isSuccess()) return out;
            for (String line : r.getOut()) {
                if (line == null || line.isEmpty() || ".".equals(line) || "..".equals(line)) continue;
                out.add(path.endsWith("/") ? path + line : path + "/" + line);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 用 root 复制文件到目标（cp 命令，二进制安全，不受分区存储限制）。 */
    public static boolean copyFile(String srcPath, File dst) {
        if (srcPath == null || !available()) return false;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Shell.Result r = Shell.cmd("cp -- " + q(srcPath) + " " + q(dst.getAbsolutePath())).exec();
            return r.isSuccess() && dst.exists();
        } catch (Throwable t) {
            return false;
        }
    }
}
