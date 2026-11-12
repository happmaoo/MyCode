package com.myapp.myfilemanager.plugin;

import com.myapp.myfilemanager.plugin.PluginInfo;

/**
 * 插件核心契约（永久稳定，只增不删）。
 * 主机通过它发现插件、协商能力，再用 getCapability 拿到具体能力接口的 Binder。
 * 新增插件类型 = 新增一个能力 AIDL 接口 + 在 capabilities 里登记，核心永不改动。
 */
interface IPlugin {
    /** 协议版本，主机与插件双向协商用。 */
    int getProtocolVersion();

    /** 插件元信息：id / 名称 / 版本 / 支持的扩展名与能力列表。 */
    PluginInfo getInfo();

    /** 是否实现了某能力（"archive" / "viewer" / ...）。 */
    boolean hasCapability(String cap);

    /** 返回对应能力接口的 Binder（宿主 asInterface 后即可跨进程调用）。 */
    IBinder getCapability(String cap);
}
