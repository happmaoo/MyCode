package com.myapp.myfilemanager.plugin;

import android.os.Parcel;
import android.os.Parcelable;

/** 插件元信息，随 IPlugin.getInfo() 跨进程返回。 */
public class PluginInfo implements Parcelable {
    public String id;            // 唯一 id，如 "archive7z"
    public String name;          // 显示名
    public int versionCode;
    public int minHostVersion;   // 要求主程序最低版本
    public String[] extensions;  // 支持的扩展名，如 ["7z","zip"]
    public String[] mimeTypes;   // 支持的内部 MIME（可空）
    public String[] capabilities;// 已实现的能力，如 ["archive"]

    public PluginInfo() {}

    public PluginInfo(String id, String name, int versionCode, int minHostVersion,
                     String[] extensions, String[] mimeTypes, String[] capabilities) {
        this.id = id;
        this.name = name;
        this.versionCode = versionCode;
        this.minHostVersion = minHostVersion;
        this.extensions = extensions;
        this.mimeTypes = mimeTypes;
        this.capabilities = capabilities;
    }

    protected PluginInfo(Parcel in) {
        id = in.readString();
        name = in.readString();
        versionCode = in.readInt();
        minHostVersion = in.readInt();
        extensions = in.createStringArray();
        mimeTypes = in.createStringArray();
        capabilities = in.createStringArray();
    }

    public static final Creator<PluginInfo> CREATOR = new Creator<PluginInfo>() {
        public PluginInfo createFromParcel(Parcel in) { return new PluginInfo(in); }
        public PluginInfo[] newArray(int size) { return new PluginInfo[size]; }
    };

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(name);
        dest.writeInt(versionCode);
        dest.writeInt(minHostVersion);
        dest.writeStringArray(extensions);
        dest.writeStringArray(mimeTypes);
        dest.writeStringArray(capabilities);
    }
}
