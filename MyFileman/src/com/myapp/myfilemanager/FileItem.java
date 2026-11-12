package com.myapp.myfilemanager;

/** 一个文件或目录条目。 */
public class FileItem implements Comparable<FileItem> {
    public final String name;
    public final String path;
    public final boolean dir;
    public final long size;
    public final long modified;   // 毫秒
    public final boolean parent;  // 是否为「返回」（回到浏览历史上一站）虚拟项

    public FileItem(String name, String path, boolean dir, long size, long modified) {
        this(name, path, dir, size, modified, false);
    }

    public FileItem(String name, String path, boolean dir, long size, long modified, boolean parent) {
        this.name = name;
        this.path = path;
        this.dir = dir;
        this.size = size;
        this.modified = modified;
        this.parent = parent;
    }

    @Override
    public int compareTo(FileItem o) {
        if (parent != o.parent) return parent ? -1 : 1;
        if (dir != o.dir) return dir ? -1 : 1;
        return name.compareToIgnoreCase(o.name);
    }
}
