package com.myapp.myfilemanager.plugin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import com.myapp.myfilemanager.Prefs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 宿主侧插件管理器：发现插件、绑定其独立进程 Service、按能力查询并缓存 Binder。
 *
 * 设计要点：
 *  - 每个插件跑在独立进程（同 APK 用 android:process，外部 APK 天然独立），崩了只死插件进程，
 *    宿主通过 onServiceDisconnected 感知并降级，不影响主界面。
 *  - 核心契约 IPlugin 永久稳定；新插件类型只新增能力接口（如 IViewer），本类加一个 getXxx 方法即可。
 *  - 所有插件调用都必须在后台线程执行（AIDL 是同步阻塞的），因此绑定也用 latch 在后台等待。
 */
public class PluginManager {
    public static final String CAP_ARCHIVE = "archive";
    public static final String CAP_VIEWER = "viewer";
    public static final int PROTOCOL = 1;

    /** 插件启用/禁用状态变更广播。MainActivity 收到后释放其持有的插件绑定（释放独立进程）。 */
    public static final String ACTION_PLUGIN_STATE = "com.myapp.myfilemanager.action.PLUGIN_STATE";

    /** 内置插件（同 APK 独立进程）。新增内置插件只需在此登记其 Service 组件。 */
    private static final List<ComponentName> BUILTIN = Arrays.asList(
        new ComponentName("com.myapp.myfilemanager",
                         "com.myapp.myfilemanager.plugin.ArchivePluginService"),
        new ComponentName("com.myapp.myfilemanager",
                         "com.myapp.myfilemanager.plugin.ImageViewerPluginService")
    );

    /** 内置插件已知能力预声明：compress 协商前先据此过滤，避免冷启无关插件进程。
     *  外部 APK 插件不在表中 → 返回空 → 仍会真实绑定询问（保持可扩展）。 */
    private static final Map<String, List<String>> BUILTIN_CAPS =
            new HashMap<String, List<String>>() {{
        put("com.myapp.myfilemanager.plugin.ArchivePluginService",
                Collections.singletonList(CAP_ARCHIVE));
        put("com.myapp.myfilemanager.plugin.ImageViewerPluginService",
                Collections.singletonList(CAP_VIEWER));
    }};

    /** 内置插件组件 → 插件 id（与 getInfo().id 对应），用于无绑定地查关联格式。 */
    private static final Map<String, String> BUILTIN_IDS =
            new HashMap<String, String>() {{
        put("com.myapp.myfilemanager.plugin.ArchivePluginService", "archive7z");
        put("com.myapp.myfilemanager.plugin.ImageViewerPluginService", "imageviewer");
    }};

    /** 取得压缩包提供者时的返回：能力 Binder + 插件 id（用于构造虚拟路径）。 */
    public static class ArchiveHandle {
        public IArchiveProvider provider;
        public String pluginId;
        public ComponentName cn;   // 对应插件组件，用于校验绑定是否仍存活
        public ArchiveHandle(IArchiveProvider p, String id) { provider = p; pluginId = id; }
    }

    /** 取得查看器时的返回：预览 Intent + 插件 id。 */
    public static class ViewerHandle {
        public Intent viewIntent;
        public String pluginId;
        public ViewerHandle(Intent i, String id) { viewIntent = i; pluginId = id; }
    }

    private final Context ctx;
    private final Map<ComponentName, PluginConn> conns = new HashMap<ComponentName, PluginConn>();
    /** 优化2：按压缩包路径缓存已建立的 ArchiveHandle，跳过重复的能力协商（getInfo/hasCapability/...）。 */
    private final Map<String, ArchiveHandle> archiveCache = new HashMap<String, ArchiveHandle>();
    private final Object lock = new Object();

    private static class PluginConn {
        IPlugin binder;
        boolean connecting = false;
        ServiceConnection sc;
    }

    public PluginManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 取得能处理该路径的压缩包提供者；无可用插件返回 null。必须在后台线程调用。 */
    public ArchiveHandle getArchiveProvider(String path, String mime) {
        // 优化2：同一压缩包命中已缓存且存活的 provider → 跳过全套能力协商
        ArchiveHandle cached = archiveCache.get(path);
        if (cached != null && alive(cached.cn)) return cached;
        // 优化3：仅遍历声明过 archive 能力的内置插件，避免冷启图片查看器等无关插件进程
        for (ComponentName cn : discover()) {
            if (!knownCaps(cn).contains(CAP_ARCHIVE)) continue;
            IPlugin p = bind(cn);
            if (p == null) continue;
            try {
                PluginInfo info = p.getInfo();
                // 禁用状态：立即解绑释放资源，并提供给调用方「该插件不可用」的信号
                if (info != null && !Prefs.pluginEnabled(ctx, info.id)) {
                    unbind(cn);
                    archiveCache.remove(path);
                    continue;
                }
                if (!p.hasCapability(CAP_ARCHIVE)) continue;
                IBinder cap = p.getCapability(CAP_ARCHIVE);
                if (cap == null) continue;
                IArchiveProvider ap = IArchiveProvider.Stub.asInterface(cap);
                if (ap != null) {
                    String pid = info != null ? info.id : cn.getClassName();
                    // 关联格式一旦被用户「保存」过即具最高权威，覆盖插件原生 canHandle 的默认扩展名列表，
                    // 这样用户自定义格式（如 apkm）也能以压缩包方式浏览；未配置过时回退到原生匹配 + 默认值。
                    boolean configured = Prefs.pluginAssocConfigured(ctx, pid);
                    boolean match = configured
                            ? assocOk(pid, path)
                            : (ap.canHandle(path, mime) || assocOk(pid, path));
                    if (match) {
                        ArchiveHandle h = new ArchiveHandle(ap, pid);
                        h.cn = cn;
                        archiveCache.put(path, h);   // 缓存，供后续导航复用
                        return h;
                    }
                }
            } catch (RemoteException e) {
                // 插件进程可能已死，尝试下一个
            }
        }
        return null;
    }

    /**
     * 取得能预览该文件的查看器插件（如图片查看器）；无可用/被禁用/不能预览返回 null。
     * 必须在后台线程调用（AIDL 阻塞 + 绑定最多 5s）。
     *
     * @return 含预览 Intent 的 ViewerHandle；调用方拿到后 startActivity 即可在本应用内预览
     */
    public ViewerHandle getViewer(String path, String mime, String ext) {
        for (ComponentName cn : discover()) {
            if (!knownCaps(cn).contains(CAP_VIEWER)) continue;   // 优化3：跳过无关插件
            IPlugin p = bind(cn);
            if (p == null) continue;
            try {
                PluginInfo info = p.getInfo();
                if (info != null && !Prefs.pluginEnabled(ctx, info.id)) {
                    unbind(cn);
                    continue;
                }
                if (!p.hasCapability(CAP_VIEWER)) continue;
                IBinder cap = p.getCapability(CAP_VIEWER);
                if (cap == null) continue;
                IViewer v = IViewer.Stub.asInterface(cap);
                if (v != null) {
                    String pid = info != null ? info.id : cn.getClassName();
                    // 关联格式一旦被用户「保存」过即具最高权威，覆盖插件原生 canView 的默认扩展名列表，
                    // 这样用户自定义格式（如 apkm）也能内开预览；未配置过时回退到原生匹配 + 默认值。
                    boolean configured = Prefs.pluginAssocConfigured(ctx, pid);
                    boolean match = configured
                            ? assocOk(pid, path)
                            : (v.canView(path, mime, ext) || assocOk(pid, path));
                    if (match) {
                        Intent it = v.getViewIntent(path, mime);
                        if (it != null) {
                            return new ViewerHandle(it, pid);
                        }
                    }
                }
            } catch (RemoteException ignored) {
            }
        }
        return null;
    }

    /** 按插件 id 取得压缩包提供者；id 与命中插件不一致（或无可用插件）则返回 null。必须在后台线程调用。 */
    public ArchiveHandle archiveById(String pid, String path, String mime) {
        ArchiveHandle h = getArchiveProvider(path, mime);
        if (h != null && pid.equals(h.pluginId)) return h;
        return null;
    }

    /** 按插件 id 取得查看器；id 与命中插件不一致（或无可用插件）则返回 null。必须在后台线程调用。 */
    public ViewerHandle viewerById(String pid, String path, String mime, String ext) {
        ViewerHandle h = getViewer(path, mime, ext);
        if (h != null && pid.equals(h.pluginId)) return h;
        return null;
    }

    /**
     * 按 id 直取压缩包提供者并【跳过扩展名/关联格式校验】（供自定义命令 %pid: 显式调用，无视后缀）。
     * 命中后把 handle 按路径写入 archiveCache，使后续 listArchiveRoot/getArchiveProvider 命中缓存、
     * 不再因扩展名不符而被打回。必须在后台线程调用。
     */
    public ArchiveHandle archiveByIdForce(String pid, String path) {
        for (ComponentName cn : discover()) {
            if (!knownCaps(cn).contains(CAP_ARCHIVE)) continue;
            IPlugin p = bind(cn);
            if (p == null) continue;
            try {
                PluginInfo info = p.getInfo();
                if (info != null && !Prefs.pluginEnabled(ctx, info.id)) { unbind(cn); continue; }
                String id = info != null ? info.id : cn.getClassName();
                if (!pid.equals(id)) continue;          // 只取指定 id，无视扩展名
                if (!p.hasCapability(CAP_ARCHIVE)) continue;
                IBinder cap = p.getCapability(CAP_ARCHIVE);
                if (cap == null) continue;
                IArchiveProvider ap = IArchiveProvider.Stub.asInterface(cap);
                if (ap != null) {
                    ArchiveHandle h = new ArchiveHandle(ap, pid);
                    h.cn = cn;
                    archiveCache.put(path, h);          // 写入缓存，绕过后续扩展名校验
                    return h;
                }
            } catch (RemoteException e) {
            }
        }
        return null;
    }

    /**
     * 按 id 直取查看器并【跳过扩展名/关联格式校验】（供自定义命令 %pid: 显式调用，无视后缀）。
     * 必须在后台线程调用。
     */
    public ViewerHandle viewerByIdForce(String pid, String path, String mime, String ext) {
        for (ComponentName cn : discover()) {
            if (!knownCaps(cn).contains(CAP_VIEWER)) continue;
            IPlugin p = bind(cn);
            if (p == null) continue;
            try {
                PluginInfo info = p.getInfo();
                if (info != null && !Prefs.pluginEnabled(ctx, info.id)) { unbind(cn); continue; }
                String id = info != null ? info.id : cn.getClassName();
                if (!pid.equals(id)) continue;          // 只取指定 id，无视扩展名
                if (!p.hasCapability(CAP_VIEWER)) continue;
                IBinder cap = p.getCapability(CAP_VIEWER);
                if (cap == null) continue;
                IViewer v = IViewer.Stub.asInterface(cap);
                if (v != null) {
                    Intent it = v.getViewIntent(path, mime);
                    if (it != null) return new ViewerHandle(it, pid);
                }
            } catch (RemoteException ignored) {
            }
        }
        return null;
    }

    /**
     * 一次性把压缩包内一个条目解压到 outDir（保留内层相对路径），返回 7z 退出码。
     * 绑定 → 校验启用/能力 → 解压 → 立即解绑释放插件进程。必须在后台线程调用。
     *
     * @param archivePath 压缩包真实路径
     * @param innerPath   内层路径（无首尾斜杠，例如 "docs/foo.txt"）
     * @param outDir      解压目标目录（条目会被解压到 outDir 下、保留内层相对结构）
     * @return 7z 退出码；无可用/被禁用/能力不符的插件返回 -1
     */
    /**
     * 一次性把压缩包内一个条目解压到 outDir（保留内层相对路径），返回 7z 退出码。
     * 优化4：复用 getArchiveProvider 已持有的绑定，复制完不解绑——避免杀掉插件进程导致
     *        后续浏览冷启动 + 重新探测。仅「禁用」/Activity 销毁时（release）才释放。
     * 必须在后台线程调用。
     */
    public int extractEntry(String archivePath, String innerPath, String outDir) {
        ArchiveHandle h = getArchiveProvider(archivePath, null);
        if (h == null) return -1;
        try {
            return h.provider.extract(archivePath, innerPath, "", outDir);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * 把整个压缩包解压到 outDir（保留内部目录结构），返回 7z 退出码。
     * 复用 getArchiveProvider 已持有的绑定（不解绑），必须在后台线程调用。
     *
     * @param archivePath 压缩包真实路径
     * @param outDir      解压目标目录（整个压缩包会被解压到 outDir 下、保留内部目录结构）
     * @return 7z 退出码；无可用/被禁用插件返回 -1
     */
    public int extractAll(String archivePath, String outDir) {
        ArchiveHandle h = getArchiveProvider(archivePath, null);
        if (h == null) return -1;
        try {
            return h.provider.extractAll(archivePath, "", outDir);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * 批量解压：innerPaths 可同时包含文件与文件夹（文件夹由插件递归展开成内部全部文件，
     * 空目录 mkdir 补建），插件侧一次列表 + 一次 root shell 分批 7z x，保留包内目录结构。
     * 复用 getArchiveProvider 已持有的绑定（不解绑），必须在后台线程调用。
     *
     * @return null = 全部成功；否则返回错误说明（可直接展示给用户）
     */
    public String extractBatch(String archivePath, List<String> innerPaths, String outDir) {
        ArchiveHandle h = getArchiveProvider(archivePath, null);
        if (h == null) return "没有可用的压缩包插件";
        try {
            return h.provider.extractBatch(archivePath, innerPaths, "", outDir);
        } catch (Throwable t) {
            return "批量解压失败：" + t.getMessage();
        }
    }

    /**
     * 列出压缩包根层条目（用于决定解压目标与同名冲突检测：1 个条目直接解压、>1 个解压到
     * 「文件名(去后缀)」子文件夹；目标已存在才提示覆盖）。复用 getArchiveProvider 已持有的绑定
     * （不解绑），必须在后台线程调用。
     *
     * @return 根层条目列表；无可用/被禁用插件或读取失败返回 null
     */
    public ArchiveListing listArchiveRoot(String archivePath) {
        ArchiveHandle h = getArchiveProvider(archivePath, null);
        if (h == null) return null;
        try {
            return h.provider.list(archivePath, "", "");
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * 列出所有已发现的插件信息（内置 + 外部，含被禁用的）。必须在后台线程调用。
     * 用于「插件」页面的展示；插件进程未安装/未响应时自动跳过。
     * 注意：仅用于展示，列完即解绑，不长期持有绑定（便于「禁用」时回收进程）。
     * 是否启用由调用方依据 Prefs.pluginEnabled 决定，这里不做过滤，以便禁用项仍可显示并重新启用。
     */
    public List<PluginInfo> listPlugins() {
        List<PluginInfo> out = new ArrayList<PluginInfo>();
        for (ComponentName cn : discover()) {
            IPlugin p = bind(cn);
            if (p == null) continue;
            try {
                PluginInfo info = p.getInfo();
                if (info != null) out.add(info);
            } catch (RemoteException ignored) {
            } finally {
                unbind(cn);   // 展示用：列完即释放
            }
        }
        return out;
    }

    /**
     * 列出插件（内置 + 外部）。
     * 外部 APK 插件预留：通过 PackageManager.queryIntentServices 发现声明了
     * "com.myapp.myfilemanager.PLUGIN" action 的 Service，并做签名白名单校验。
     */
    private List<ComponentName> discover() {
        return new ArrayList<ComponentName>(BUILTIN);
    }

    /** 绑定插件 Service（同 APK 独立进程）。后台线程阻塞等待连接，超时（5s）返回 null。 */
    private IPlugin bind(ComponentName cn) {
        PluginConn c;
        synchronized (lock) {
            c = conns.get(cn);
            if (c != null && c.binder != null) return c.binder;
            if (c == null) { c = new PluginConn(); conns.put(cn, c); }
            if (c.connecting) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                return c.binder;   // 另一线程正在连，简单重试后取结果
            }
            c.connecting = true;
        }
        final PluginConn fc = conns.get(cn);
        final CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection sc = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                fc.binder = IPlugin.Stub.asInterface(binder);
                latch.countDown();
            }
            public void onServiceDisconnected(ComponentName name) {
                synchronized (lock) { fc.binder = null; fc.connecting = false; }
            }
        };
        fc.sc = sc;
        Intent it = new Intent();
        it.setComponent(cn);
        boolean bound;
        try {
            bound = ctx.bindService(it, sc, Context.BIND_AUTO_CREATE);
        } catch (Exception e) { bound = false; }
        if (!bound) {
            synchronized (lock) { fc.connecting = false; }
            return null;
        }
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        synchronized (lock) { fc.connecting = false; }
        return fc.binder;
    }

    /** 解绑所有插件（Activity 销毁时调用，释放独立进程）。 */
    public void release() {
        synchronized (lock) {
            for (PluginConn c : conns.values()) {
                if (c.sc != null) {
                    try { ctx.unbindService(c.sc); } catch (Exception ignored) {}
                }
            }
            conns.clear();
            archiveCache.clear();
        }
    }

    /** 解绑单个插件（禁用时立即释放其独立进程）。 */
    private void unbind(ComponentName cn) {
        PluginConn c;
        synchronized (lock) {
            c = conns.get(cn);
            if (c == null) return;
            if (c.sc != null) {
                try { ctx.unbindService(c.sc); } catch (Exception ignored) {}
            }
            conns.remove(cn);
            archiveCache.clear();   // 绑定变化后缓存失效，下次重建
        }
    }

    /** 绑定的插件进程是否仍存活（连接未断开）。 */
    private boolean alive(ComponentName cn) {
        PluginConn c;
        synchronized (lock) { c = conns.get(cn); }
        return c != null && c.binder != null;
    }

    /** 已知的内置插件能力（无需绑定即可判断）；外部插件返回空 → 由调用方真实绑定询问。 */
    private static List<String> knownCaps(ComponentName cn) {
        List<String> c = BUILTIN_CAPS.get(cn.getClassName());
        return c != null ? c : Collections.<String>emptyList();
    }

    /**
     * 校验文件是否属于该插件的「关联格式」：关联集合为空（用户清空并保存）→ 不使用该插件；
     * 否则，文件名最后一个后缀等于某 token，或文件名以 ".token" 结尾（兼容 tar.gz 这类复合后缀）即命中。
     */
    private boolean assocOk(String id, String path) {
        if (id == null || path == null) return false;
        Set<String> assoc = Prefs.pluginAssocExts(ctx, id);
        if (assoc.isEmpty()) return false;   // 未关联任何格式 → 该插件不自动接管文件
        String lp = path.toLowerCase(Locale.ROOT);
        int i = lp.lastIndexOf('.');
        String lastSeg = (i >= 0 && i < lp.length() - 1) ? lp.substring(i + 1) : "";
        for (String tok : assoc) {
            if (tok.equals(lastSeg)) return true;
            if (lp.endsWith("." + tok)) return true;
        }
        return false;
    }

    /**
     * 轻量、无绑定的判断：给定路径是否被任一「已启用」插件的关联格式覆盖。
     * 用于宿主点击文件时决定走哪条插件路径（从而支持用户自定义格式，如 apkm）。
     * 关联集合：用户保存过则以用户集合为准（最高权威），否则回退插件默认关联。
     * 可传入 cap 只匹配具备该能力的插件（如 viewer/archive），避免为无关扩展名误绑进程。
     */
    public boolean isAssociated(String path, String ext) {
        return isAssociated(path, ext, null);
    }

    public boolean isAssociated(String path, String ext, String cap) {
        for (ComponentName cn : discover()) {
            if (cap != null && !knownCaps(cn).contains(cap)) continue;
            String id = BUILTIN_IDS.get(cn.getClassName());
            if (id == null) continue;
            if (!Prefs.pluginEnabled(ctx, id)) continue;
            if (assocOk(id, path)) return true;
        }
        return false;
    }
}
