package com.myapp.myfilemanager.plugin;

import com.myapp.myfilemanager.plugin.ArchiveListing;
import android.os.ParcelFileDescriptor;

/**
 * 压缩包 / 虚拟文件系统能力：把压缩包当成可逐级进入的虚拟文件夹。
 * 7z 插件实现此接口，直调 Termux 的 7z 二进制（在独立的 :plugin.archive 进程里）。
 */
interface IArchiveProvider {
    /** 该路径是否归本插件处理（按扩展名 / MIME 判断）。 */
    boolean canHandle(String path, String mime);

    /** 列出压缩包内某一层（innerPath）的直接条目；只列当前层，避免 Binder 体积超限。 */
    ArchiveListing list(String archivePath, String innerPath, String password);

    /** 以流方式读出一个文件的内容（ParcelFileDescriptor 管道，避免大文件撑爆 IPC）。 */
    ParcelFileDescriptor open(String archivePath, String innerPath, String password);

    /** 解压单个条目到 outDir，返回退出码。 */
    int extract(String archivePath, String innerPath, String password, String outDir);

    /** 解压整个压缩包到 outDir（保留内部目录结构），返回退出码。 */
    int extractAll(String archivePath, String password, String outDir);

    /**
     * 批量解压：innerPaths 可同时包含文件与文件夹（文件夹由插件展开成内部全部文件，
     * 空目录用 mkdir 补建），一次 root shell 分批执行 7z x，保留包内目录结构。
     * @return null = 全部成功；否则返回错误说明（供界面直接展示）
     */
    String extractBatch(String archivePath, in List<String> innerPaths, String password, String outDir);
}
