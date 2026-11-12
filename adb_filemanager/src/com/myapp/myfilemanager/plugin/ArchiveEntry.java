package com.myapp.myfilemanager.plugin;

import android.os.Parcel;
import android.os.Parcelable;

/** 压缩包内单个条目（虚拟文件/文件夹），随 ArchiveListing 跨进程返回。 */
public class ArchiveEntry implements Parcelable {
    public String name;       // 显示名
    public String rawPath;    // 包内原始路径（用于 read/extract，可能含 \ 分隔符）
    public String innerPath;  // 规范化 / 分隔路径（用于虚拟导航，如 /docs/file.txt）
    public boolean isDir;
    public long size;
    public long modified;     // 毫秒
    public boolean encrypted;

    public ArchiveEntry() {}

    public ArchiveEntry(String name, String rawPath, String innerPath, boolean isDir,
                       long size, long modified, boolean encrypted) {
        this.name = name;
        this.rawPath = rawPath;
        this.innerPath = innerPath;
        this.isDir = isDir;
        this.size = size;
        this.modified = modified;
        this.encrypted = encrypted;
    }

    protected ArchiveEntry(Parcel in) {
        name = in.readString();
        rawPath = in.readString();
        innerPath = in.readString();
        isDir = in.readByte() != 0;
        size = in.readLong();
        modified = in.readLong();
        encrypted = in.readByte() != 0;
    }

    public static final Creator<ArchiveEntry> CREATOR = new Creator<ArchiveEntry>() {
        public ArchiveEntry createFromParcel(Parcel in) { return new ArchiveEntry(in); }
        public ArchiveEntry[] newArray(int size) { return new ArchiveEntry[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(rawPath);
        dest.writeString(innerPath);
        dest.writeByte((byte) (isDir ? 1 : 0));
        dest.writeLong(size);
        dest.writeLong(modified);
        dest.writeByte((byte) (encrypted ? 1 : 0));
    }
}
