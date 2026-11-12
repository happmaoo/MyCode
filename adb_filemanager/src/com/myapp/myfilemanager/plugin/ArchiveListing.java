package com.myapp.myfilemanager.plugin;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

/** IArchiveProvider.list() 的返回：当前层条目列表 + 退出码 + 错误信息。 */
public class ArchiveListing implements Parcelable {
    public List<ArchiveEntry> entries;
    public int code;       // 7z 退出码，0 为正常
    public String error;   // 出错时的描述（正常为 null/空）

    public ArchiveListing() {}

    public ArchiveListing(List<ArchiveEntry> entries, int code, String error) {
        this.entries = entries;
        this.code = code;
        this.error = error;
    }

    protected ArchiveListing(Parcel in) {
        entries = in.createTypedArrayList(ArchiveEntry.CREATOR);
        code = in.readInt();
        error = in.readString();
    }

    public static final Creator<ArchiveListing> CREATOR = new Creator<ArchiveListing>() {
        public ArchiveListing createFromParcel(Parcel in) { return new ArchiveListing(in); }
        public ArchiveListing[] newArray(int size) { return new ArchiveListing[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(entries);
        dest.writeInt(code);
        dest.writeString(error);
    }
}
