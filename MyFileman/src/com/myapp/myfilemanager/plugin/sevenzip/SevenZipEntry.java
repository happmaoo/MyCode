package com.myapp.myfilemanager.plugin.sevenzip;

/**
 * 7z l -slt -ba 输出的单个条目。
 * 注意：构造参数顺序必须与字段声明一一对应（rawPath / path / size / packedSize 容易调错），
 * Java 没有命名参数，调错不会编译报错，只会在界面显示错或让 7z e -so 找不到条目。
 */
public final class SevenZipEntry {
    /** 7z 输出的原样 Path，用于 e -so 精确定位（分隔符可能是 \ 或 /，不要改写） */
    public final String rawPath;
    /** 规范化成 / 分隔的路径，仅用于建树 */
    public final String path;
    public final boolean isDir;
    public final long size;
    public final long packedSize;
    public final String modified;
    public final boolean encrypted;
    public final String method;

    public boolean isDir() { return isDir; }

    SevenZipEntry(String rawPath, String path, boolean isDir, long size,
                 long packedSize, String modified, boolean encrypted, String method) {
        this.rawPath = rawPath;
        this.path = path;
        this.isDir = isDir;
        this.size = size;
        this.packedSize = packedSize;
        this.modified = modified;
        this.encrypted = encrypted;
        this.method = method;
    }
}
