package com.myapp.myfilemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import com.myapp.myfilemanager.plugin.ArchiveListing;
import com.myapp.myfilemanager.plugin.ArchiveEntry;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.TypedValue;
import android.view.inputmethod.InputMethodManager;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.format.Formatter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.myapp.myfilemanager.plugin.ArchiveEntry;
import com.myapp.myfilemanager.plugin.ArchiveListing;
import com.myapp.myfilemanager.plugin.IArchiveProvider;
import com.myapp.myfilemanager.plugin.PluginManager;

public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    // 长任务（搜索/du/复制/自定义命令）走 io；列目录单独走 listIo，
    // 避免一条慢 du/find 把「进目录」的列目录请求排在后面导致界面卡顿。
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService listIo = Executors.newSingleThreadExecutor();
    private static final long FLASH_MS = 130L;   // 单击高亮持续时间（也用于延后执行真正的动作）

    // ---------- 插件系统 ----------
    private PluginManager pluginMgr;            // 宿主侧插件管理器（发现/绑定/能力协商）
    private BroadcastReceiver pluginStateReceiver;   // 插件启用/禁用广播，用于释放已绑定的插件进程
    static final String ARCHIVE_SCHEME = "archive://";   // 虚拟路径前缀：archive://<pluginId>/<真实路径>#<内层路径>
    /** 自启动标记：本应用自己发起的「打开方式/分享」（如选择器里选回本应用）带此 extra，
     *  收到即视为「自己调用」，不新开标签页（仅导航当前标签）。其它 APP 发起则不带此标记 → 新标签。 */
    static final String EXTRA_SELF_INVOKED = "com.myapp.myfilemanager.self_invoked";

    static boolean isArchivePath(String p) { return p != null && p.startsWith(ARCHIVE_SCHEME); }

    /** 段转义：把虚拟路径段里的 URI 保留字转义，避免与 archive:// 的 '#' 分隔符冲突。
     *  仅转义 % # ? 三种；'/' 保留作为路径分隔符。 */
    static String encSeg(String s) {
        if (s == null) return "";
        return s.replace("%", "%25").replace("#", "%23").replace("?", "%3F");
    }
    static String decSeg(String s) {
        if (s == null) return "";
        return s.replace("%3F", "?").replace("%23", "#").replace("%25", "%");
    }

    /** 解析 archive:// 虚拟路径，返回 {pluginId, 真实压缩包路径, 内层路径(无首尾斜杠)}。
     *  段内原有的 '#' 已在拼接时转义为 %23，故用第一个未转义的 '#' 作为分隔符；
     *  解出后再 decSeg 还原段内被转义的保留字（含文件名里的 #）。 */
    static String[] parseArchive(String p) {
        String s = p.substring(ARCHIVE_SCHEME.length());
        int h = s.indexOf('#');
        String head = h < 0 ? s : s.substring(0, h);
        String inner = h < 0 ? "" : s.substring(h + 1);
        int sl = head.indexOf('/');
        String pluginId = sl < 0 ? head : head.substring(0, sl);
        String archivePath = sl < 0 ? "" : head.substring(sl);
        return new String[]{pluginId, decSeg(archivePath), decSeg(inner)};
    }

    /** archive:// 路径的「上级」：内层还有层级则上移一层，已在根则退出到压缩包所在真实目录。 */
    static String archiveParent(String p) {
        String[] a = parseArchive(p);
        String pluginId = a[0], archivePath = a[1], inner = a[2];
        if (inner == null || inner.isEmpty() || "/".equals(inner)) {
            return Util.parent(archivePath);   // 退出压缩包，回到设备目录
        }
        int i = inner.lastIndexOf('/');
        String up = (i <= 0) ? "" : inner.substring(0, i);
        return ARCHIVE_SCHEME + pluginId + encSeg(archivePath) + "#" + encSeg(up);
    }

    // ---------- Tab 模型 ----------
    private static class Tab {
        String path;
        final ArrayList<String> history = new ArrayList<String>();
        int pos = -1;
        final Set<String> sel = new HashSet<String>();
        int scroll = 0;
        boolean locked = false;     // 锁定后不可关闭（× 变为 🔒）
        /** 锁定时允许活动的最上层目录（锁定根）；为 null 表示未锁定。
         *  锁定后只能进入其子目录，不能回到父目录或跳到其它目录。 */
        String lockRoot = null;
        /** 上次实际加载到网格里的路径（仅运行时，不持久化）；用于区分「换目录」与「同目录刷新」。 */
        String loadedPath = null;
        Tab(String p) { path = ""; push(p); }
        /** 从持久化的会话状态还原一个标签页（含前进/后退历史）。 */
        static Tab from(Prefs.TabState s) {
            Tab t = new Tab(s.path);
            t.history.clear();
            t.history.addAll(s.history);
            t.pos = s.pos;
            t.path = s.path;
            t.locked = s.locked;
            t.lockRoot = s.lockRoot;
            return t;
        }
        /** 进入目录。同一目录不重复入栈，否则连续「返回」会多次停在同一处、看起来没反应。 */
        void push(String p) {
            if (pos >= 0 && p.equals(path)) return;
            while (history.size() > pos + 1) history.remove(history.size() - 1);
            history.add(p); pos = history.size() - 1; path = p;
        }
        boolean back() { if (pos > 0) { pos--; path = history.get(pos); return true; } return false; }
        boolean forward() { if (pos < history.size() - 1) { pos++; path = history.get(pos); return true; } return false; }
    }

    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private int active = 0;
    private final ArrayList<View> tabViews = new ArrayList<View>();
    private HorizontalScrollView tabScroll;

    // ---------- 视图 ----------
    private LinearLayout tabStrip, toolbar, cmdBox, bottomBox, cmdBar;

    // 左侧抽屉（原生实现，不依赖 Material）：遮罩 + 面板 + 状态/手势阈值
    private View drawerScrim;
    private LinearLayout drawerPanel;
    private boolean drawerOpen = false;
    private int drawerWidth;
    private float drawerEdge;       // 左边缘可触发滑动打开的宽度（px）
    private float drawerSlideOpen;  // 右滑超过该距离则打开（px）
    private View bottomBar;
    private RecyclerView grid;
    private GridLayoutManager gridLm;
    private boolean swipeTabs = true;            // 文件列表左右滑动切换标签页（设置项）
    private int swipeMinDist = 60;               // 滑动切换最小横向位移阈值（dp，设置项）
    private int swipeMinVel = 300;               // 滑动切换最小甩动速度阈值（px/s，设置项）
    private boolean gridAnimating = false;       // 滑动切换动画进行中（吞掉触摸，防止冲突）
    private GestureDetector swipeDetector;       // 检测列表区域横向甩动（onFling，原判定逻辑）
    private float swipeDownX;                    // 本次触摸起点 X（用于排除左边缘抽屉手势）
    private RvFileAdapter adapter;
    private EditText pathEdit;
    private TextView status, empty, titleTv, rootTag;
    private AlertDialog dlg;   // 当前属性对话框实例，供内部按钮关闭它
    private final ArrayList<Button> toolBtns = new ArrayList<Button>();
    private Button viewModeBtn;   // 工具栏「视图」按钮（网格 ⇄ 列表）
    private Button sortBtn;       // 工具栏「排序」按钮（图标 + 右侧小三角）
    private LinearLayout sortDropdown;   // 排序下拉菜单的内容 View（字母 / 时间）
    private android.widget.PopupWindow sortPopup;   // 锚在排序按钮下方的窄下拉浮层，不占文件列表区域
    private Button addBtn;        // 工具栏「新建」按钮（收起原新建文件夹 / 新建文件）
    private LinearLayout addDropdown;    // 新建下拉菜单的内容 View（文件夹 / 文件）
    private android.widget.PopupWindow addPopup;    // 锚在新建按钮下方的窄下拉浮层
    private Button favBtn;        // 工具栏「收藏」按钮
    private LinearLayout favDropdown;    // 收藏下拉菜单的内容 View（ScrollView 内，收藏条目列表）
    private android.widget.PopupWindow favPopup;    // 锚在收藏按钮下方的窄下拉浮层
    /** 当前激活标签是否为 archive:// 视图：决定工具栏用「压缩包版」（去掉新建/收藏、加解压）。 */
    private boolean toolbarArchiveMode = false;
    private final ArrayList<Button> addrBtns = new ArrayList<Button>();
    private final ArrayList<Button> bottomBtns = new ArrayList<Button>();

    // ---------- 外部「打开方式 / 分享」接收 ----------
    private String pendingIncomingPath = null;   // onCreate 解析到的外部文件路径（留给 onResume 处理）
    private boolean pendingIncomingExternal = false; // 该路径是否来自其他 APP（打开方式/分享），是则新开标签
    private Uri pendingIncomingUri = null;   // 解析不出本地路径的外部 content://（留给 onResume 后台复制后定位）
    // 持久标记：本 Activity 实例是否由「其它 APP」拉起。pendingIncomingExternal 会在 onResume 被消费重置，
    // 故单独保留一份，供返回键判断「直接结束 Activity 回外部 APP」，而非逐级返回上级目录。
    private boolean launchedFromExternal = false;
    private String pendingSelectPath = null;     // 列目录完成后要选中高亮的文件（完整路径）
    private Prefs.Cmd pendingRxCmd = null;       // 收到文件后自动执行的「接收命令」
    private Intent pendingDebugParams = null;    // 调试开关打开时，启动/收到外部 intent 后要展示的参数
    private final ArrayList<Button> cmdBtns = new ArrayList<Button>();

    // ---------- 状态 ----------
    private int cols = 5;
    private int textLv = 1;
    private final ArrayList<String> clipboard = new ArrayList<String>();
    private boolean clipboardCut = false;
    private boolean loading = false;
    private boolean rootWarmStarted = false;
    private int refreshSeq = 0;   // 只让最后一次刷新请求的结果生效，避免过期结果覆盖
    /** 当前「打开中...」提示框（archive:// 列目录期间），仅用于生命周期清理。 */
    private volatile OpenArchiveDlg archiveOpenDlg = null;
    /** 压缩包内文件提取进行中（底部状态栏显示「打开中…」）：供返回键拦截为「取消打开」。 */
    private volatile boolean archiveOpening = false;
    /** 提取取消请求：由返回键置位，io 任务在读取循环中检测以中止并删除半成品临时文件。 */
    private volatile boolean archiveOpenCancel = false;
    private int lastListFp = -1;  // 上次 onResume 时影响列表内容的设置指纹
    private int archiveExitScroll = 0;  // 进入压缩包前，真实目录列表的滚动位置；退出压缩包时恢复该位置
    // 命令执行期间，压缩包内虚拟路径 → 解压到 archive_tmp 后的真实临时文件路径；一次性缓存避免重复解压
    private final HashMap<String, String> cmdArchiveCache = new HashMap<String, String>();
    // 「返回记住位置」：按路径持久化的滚动记忆（见 ScrollMemory）。
    private ScrollMemory scrollMem;
    private int restoreTop = 0;          // 恢复滚动时首项的像素偏移，与 t.scroll 配套
    private boolean initialRestore = false;  // 重启后首次 onResume 是否恢复记忆位置
    private static final int REQ_PERM = 1001;
    private static final int REQ_CMD = 1002;   // 启动结果窗口（CmdOutputActivity）的请求码

    // ---------- 搜索 ----------
    private boolean searchActive = false;
    private boolean searchRecursive = false;   // false=当前目录（内存即时） true=全部子目录（find 流式）
    private boolean searchCase = false;        // false=忽略大小写
    private long searchSeq = 0;                // 作废过期异步搜索
    private List<FileItem> curItems = new ArrayList<FileItem>();   // 当前目录已加载项（供内存搜索过滤）
    private final ArrayList<FileItem> searchResults = new ArrayList<FileItem>();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchTask = null;
    private LinearLayout searchPanel;
    private EditText searchEdit;
    private TextView searchStatus;
    private Button searchScopeBtn;
    private CheckBox searchCaseCb;
    private static final int SEARCH_MAX = 3000;   // 结果上限，防止极端情况下 OOM

    /** 供日志头使用的版本/设备信息（读不到时降级，绝不影响启动）。 */
    private String appVersionInfo() {
        String ver = "?";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {}
        return "MyFileman " + ver + " | " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全局调试：安装未捕获异常处理器（覆盖所有线程/所有界面），并注入版本信息
        Dbg.install();
        Dbg.setAppInfo(appVersionInfo());
        Dbg.log("APP", "MainActivity.onCreate | action="
                + (getIntent() == null ? "null" : getIntent().getAction())
                + " | data=" + (getIntent() == null ? "" : String.valueOf(getIntent().getData()))
                + " | rootMode=" + Prefs.rootEnabled(this));
        Prefs.ensureDefaults(this);
        scrollMem = ScrollMemory.load(this);   // 按路径的滚动记忆，重启后仍可恢复
        Ui.loadAccent(this);        // 先加载用户自定义的着重色，后续界面统一取 Ui.PRIMARY
        IconFactory.attach(this);   // 彩色位图主题来自 assets，读取前先注入 AssetManager
        ToolbarIcons.attach(this);  // 工具栏图标同样来自 assets/ui
        setContentView(buildUi());
        ensurePermission();
        grid.setAdapter(adapter);
        bindEvents();
        applySettings();
        pendingIncomingPath = parseIncomingIntent(getIntent());  // 外部「打开方式/分享」带进来的文件
        // 其它 APP 发起才新开标签；本应用自己调用（选择器里选回本应用）则不打标记 → 不新开标签
        pendingIncomingExternal = (pendingIncomingPath != null)
                && !getIntent().getBooleanExtra(EXTRA_SELF_INVOKED, false);
        // 外部打开判定直接看 Intent action（content:// 常解析不出真实路径却仍是外部拉起），
        // 故单独持久保存，供返回键判断是否「直接 finish 回外部 APP」。
        launchedFromExternal = isExternalLaunch(getIntent());
        // 解析不出本地路径但 intent 数据是外部 content://：记下 Uri，onResume 后台复制到本地目录后再定位
        Uri d0 = getIntent().getData();
        if (pendingIncomingPath == null && d0 != null
                && "content".equalsIgnoreCase(d0.getScheme()) && isExternalLaunch(getIntent())) {
            pendingIncomingUri = d0;
        }
        pluginMgr = new PluginManager(this);   // 插件系统：发现/绑定独立进程插件
        // 监听插件启用/禁用广播：禁用时释放本 Activity 持有的插件绑定，腾出独立进程资源
        pluginStateReceiver = new BroadcastReceiver() {
            public void onReceive(Context ctx, Intent i) {
                if (pluginMgr != null) pluginMgr.release();
            }
        };
        registerReceiver(pluginStateReceiver, new IntentFilter(PluginManager.ACTION_PLUGIN_STATE));
        // 调试开关：把启动这个 Activity 的 intent 参数留到 onResume 展示（仅当有内容）
        if (Prefs.debugRx(this) && hasIntentParams(getIntent())) pendingDebugParams = getIntent();
        restoreTabs();   // 恢复上次打开的 Tab 与当前激活项；首次启动回退到内部存储
        // 重启后首次加载：若当前路径有记忆位置，标记待恢复（onResume 里以 restoreScroll 加载）
        if (scrollMem != null && !tabs.isEmpty()) {
            int[] mem0 = scrollMem.recall(cur().path);
            if (mem0 != null) {
                cur().scroll = mem0[0];
                restoreTop = mem0[1];
                initialRestore = true;
            }
        }
        // 刷新与 Root 授权统一交给 onResume（onCreate 之后必被调用），避免授权会话被建立两次

        // 启动清理：删除上次运行残留在 cache/archive_tmp 的解压文件（打开压缩包内文件、
        // 或对压缩包内条目跑自定义命令都会往这里写）。不主动清会只增不减、慢慢占满内部存储。
        // 放在 io 线程执行，避免挂载大压缩包残留时阻塞启动。
        io.execute(new Runnable() {
            public void run() { deleteArchiveTmp(); }
        });
    }

    /** 递归删除 getCacheDir()/archive_tmp 下的全部内容（启动时调用，清理压缩包解压残留）。
     *  用本地递归删除而非 Fs.delete，避免一启动就触发 Root 会话。 */
    private void deleteArchiveTmp() {
        try {
            File dir = new File(getCacheDir(), "archive_tmp");
            if (!dir.exists()) return;
            delRecNoRoot(dir);
        } catch (Exception ignored) {}
    }

    private static void delRecNoRoot(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) delRecNoRoot(x);
        }
        f.delete();
    }

    private String homePath() {
        String p = Environment.getExternalStorageDirectory().getAbsolutePath();
        return p == null || p.isEmpty() ? "/sdcard" : p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Dbg.log("LIFE", "MainActivity.onResume path="
                + (tabs.isEmpty() ? "-" : cur().path) + " tabs=" + tabs.size());
        rebuildCmdButtons();     // 从设置页返回后，命令可能已被增删改
        applySettings();
        syncRootMode();
        updateBottomBar();
        if (tabs.isEmpty()) return;
        snapActiveTabVisible();   // 界面显示时确保当前标签可见（标签多且激活项在右侧时尤其需要）
        // 若结果窗口仍「被钉住」（命令尚未结束、未收到 [mycmd:exit] 且未手动关闭），回到前台时切回该窗口，
        // 而不是停留在主界面——满足「命令未结束就切后台、再次打开 APP 时自动回到窗口」的需求。
        // 命令已结束（收到 [mycmd:exit]）后窗口不再被钉住，切后台再打开不再自动切回。
        //  · 实例还在（切到别的 APP 未销毁 activity）：REORDER_TO_FRONT 直接带到前台；
        //  · 实例已被 singleTask clear-top 销毁（从桌面图标重进）：用内存快照重建窗口
        //    （标题/正文/按钮/执行中/日志续读/滚动位置全部还原，不重跑命令）。
        if (CmdOutputActivity.hasPinnedWindow()) {
            Intent intent = new Intent(this, CmdOutputActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivityForResult(intent, CmdOutputActivity.REQ_STREAM);
        } else {
            CmdOutputActivity.RestoreState rs = CmdOutputActivity.takeRestore();
            if (rs != null) {
                // 结构化快照走同进程静态对象直传（含按钮 / 日志偏移 / 执行中 / 滚动位置），Intent 只带布尔标记。
                CmdOutputActivity.setPendingRestore(rs);
                Intent intent = new Intent(this, CmdOutputActivity.class);
                intent.putExtra(CmdOutputActivity.EXTRA_RESTORE, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivityForResult(intent, CmdOutputActivity.REQ_STREAM);
            }
        }
        // 影响列表内容的设置指纹（隐藏文件 / Root 开关）；变化才需要真正重列
        int fp = (Prefs.showHidden(this) ? 1 : 0) | (Prefs.rootEnabled(this) ? 2 : 0);
        boolean fpChanged = fp != lastListFp;
        lastListFp = fp;
        if (Prefs.rootEnabled(this) && !Fs.rootSessionAlive() && !rootWarmStarted) {
            warmRoot();          // APP 打开时建立一次 Root 授权会话（整个进程仅此一次）
        } else if (fpChanged) {
            initialRestore = false;
            refreshForce();      // 隐藏文件 / Root 开关变了，必须重新列目录
        } else {
            boolean r = initialRestore;   // 重启后首次：恢复该路径的记忆位置
            initialRestore = false;
            refresh(r, false);   // 路径没变就直接跳过，列表纹丝不动
        }

        // 外部「打开方式 / 分享」带进来的文件：定位到其目录并选中，必要时自动执行接收命令
        if (pendingIncomingPath != null) {
            final String ip = pendingIncomingPath;
            final boolean ext = pendingIncomingExternal;
            pendingIncomingPath = null;
            pendingIncomingExternal = false;
            handleIncomingFile(ip, ext);
        } else if (pendingIncomingUri != null) {
            // 外部 content:// 解析不出本地路径：后台复制到本应用可访问目录，再定位选中
            final Uri u = pendingIncomingUri;
            pendingIncomingUri = null;
            io.execute(new Runnable() {
                public void run() {
                    final String local = copyContentToLocal(u);
                    ui.post(new Runnable() {
                        public void run() {
                            if (local != null) handleIncomingFile(local, true);
                            else toast("无法打开该文件（不支持的 content://）：" + u);
                        }
                    });
                }
            });
        }
        // 调试开关：把启动参数展示到结果窗口（仅一次，避免每次 resume 都弹）
        if (pendingDebugParams != null) {
            final Intent di = pendingDebugParams;
            pendingDebugParams = null;
            showDebugParams(di);
        }
    }

    // ==================== UI 构建（MD 扁平） ====================

    private View buildUi() {
        textLv = Prefs.textLevel(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶栏：白底 + 深灰标题 + 扁平文字按钮
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.WHITE);
        int pad = Util.dp(this, 12);
        top.setPadding(pad, Util.dp(this, 8), Util.dp(this, 6), Util.dp(this, 8));

        // 左侧抽屉汉堡按钮（置于顶栏最左，点击切换抽屉）
        TextView ham = new TextView(this);
        ham.setText("≡");
        ham.setTextSize(Ui.sp(textLv, 20, 22, 24));
        ham.setTextColor(Ui.TEXT);
        ham.setPadding(Util.dp(this, 4), 0, Util.dp(this, 12), 0);
        TypedValue hamOv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, hamOv, true);
        if (hamOv.resourceId != 0) ham.setBackgroundResource(hamOv.resourceId);
        ham.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleDrawer(); }
        });
        top.addView(ham, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleTv = new TextView(this);
        titleTv.setText(getString(R.string.app_name));
        titleTv.setTextColor(Ui.TEXT);
        titleTv.setTextSize(Ui.sp(textLv, 16, 18, 20));
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        rootTag = new TextView(this);
        rootTag.setText("ROOT");
        rootTag.setTextColor(Ui.PRIMARY);
        rootTag.setTextSize(Ui.sp(textLv, 9, 10, 11));
        rootTag.setTypeface(null, android.graphics.Typeface.BOLD);
        rootTag.setPadding(0, 0, Util.dp(this, 8), 0);
        rootTag.setVisibility(View.GONE);
        top.addView(rootTag);

        // 右上角 3 点溢出菜单（MD1 默认风格）：含「设置 / 插件」两项，替代原先单独的「设置」文字按钮
        ImageButton overflow = new ImageButton(this);
        overflow.setImageResource(R.drawable.ic_overflow);
        overflow.setBackgroundColor(Color.TRANSPARENT);
        overflow.setPadding(Util.dp(this, 10), Util.dp(this, 6), Util.dp(this, 10), Util.dp(this, 6));
        TypedValue ov = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ov, true);
        if (ov.resourceId != 0) overflow.setBackgroundResource(ov.resourceId);
        overflow.setContentDescription("菜单");
        overflow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showOverflowMenu(overflow); }
        });
        top.addView(overflow);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addLine(root);

        // Tab 栏：选中标签底部显示着重色条（8dp），文字左上角、× 在右上角，标签左右 1dp 细边框（同上方横线色）
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tabRow.addView(tabScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button addTab = Ui.flat(this, "＋", textLv);
        iconBtn(addTab, "新建标签页", "addTab");
        // 标签栏压窄时同步减小 ＋ 按钮的垂直 padding，否则它会反过来顶住整条栏的高度。
        // 只改上下；左右沿用 Ui.flat 的 10dp，保证「左右不变」。
        addTab.setPadding(addTab.getPaddingLeft(), Util.dp(this, 4),
                addTab.getPaddingRight(), Util.dp(this, 4));
        tabRow.addView(addTab);
        addTab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 当前在压缩包视图（archive://）时，新标签页改开「内部存储」而非停在压缩包内
                addTab(isArchivePath(cur().path) ? homePath() : cur().path, true);
            }
        });
        root.addView(tabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addLine(root);

        // 地址栏
        LinearLayout addr = new LinearLayout(this);
        addr.setOrientation(LinearLayout.HORIZONTAL);
        addr.setGravity(Gravity.CENTER_VERTICAL);
        addr.setPadding(Util.dp(this, 8), Util.dp(this, 1), Util.dp(this, 4), Util.dp(this, 1));
        pathEdit = new EditText(this);
        pathEdit.setSingleLine(true);
        pathEdit.setTextSize(Ui.sp(textLv, 12, 13, 15));
        // 压扁：去掉主题默认的上下内边距与最小高度（Material 主题下 EditText 约 48dp 高，
        // 是地址栏偏高的主因）；字号、左右结构不动。
        pathEdit.setPadding(0, Util.dp(this, 2), 0, Util.dp(this, 2));
        pathEdit.setMinHeight(0);
        pathEdit.setMinimumHeight(0);
        pathEdit.setTextColor(Ui.TEXT);
        pathEdit.setHintTextColor(Ui.HINT);
        pathEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        pathEdit.setHint("输入路径，如 /sdcard 或 /system");
        // 默认不抢焦点：点击文件列表时地址栏不会获得焦点（避免光标跳动）；
        // 直接点地址栏时才临时开启焦点，让键盘弹出可输入。锁定标签下地址栏禁用（isEnabled=false 时不会抢焦点）。
        pathEdit.setFocusable(false);
        pathEdit.setFocusableInTouchMode(false);
        // 去掉系统自带（跟随主题 accent 的蓝色）下划线，改为自绘灰色 1px 线，颜色不受强调色影响
        pathEdit.setBackgroundColor(Color.TRANSPARENT);
        // 默认不显示光标；只有真正获得焦点（用户直接点地址栏）时才显示，避免「光标在跳」
        pathEdit.setCursorVisible(false);
        pathEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                pathEdit.setCursorVisible(has);
            }
        });
        pathEdit.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP && pathEdit.isEnabled()) {
                    pathEdit.setFocusable(true);
                    pathEdit.setFocusableInTouchMode(true);
                    pathEdit.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(pathEdit, InputMethodManager.SHOW_IMPLICIT);
                }
                return false;
            }
        });
        // 地址栏容器：EditText + 底部灰色下划线（默认灰，不随强调色变化）
        LinearLayout pathWrap = new LinearLayout(this);
        pathWrap.setOrientation(LinearLayout.VERTICAL);
        pathWrap.addView(pathEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View addrLine = new View(this);
        addrLine.setBackgroundColor(0xFF9AA0A6);   // 默认灰色
        pathWrap.addView(addrLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        addr.addView(pathWrap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String[] addrNames = {"前往", "上级", "刷新"};
        String[] addrIcons = {"go", "up", "refresh"};
        for (int i = 0; i < addrNames.length; i++) {
            Button b = Ui.flat(this, addrNames[i], textLv);
            b.setTextColor(Ui.SUB);
            iconBtn(b, addrNames[i], addrIcons[i]);
            // 压扁：仅减上下 padding，左右间距不动（「＋」按钮同理，Ui.flat 默认上下 6dp）
            b.setPadding(b.getPaddingLeft(), Util.dp(this, 2), b.getPaddingRight(), Util.dp(this, 2));
            addr.addView(b);
            addrBtns.add(b);
        }
        root.addView(addr, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addLine(root);

        // ----- 搜索面板（默认隐藏，点顶栏「搜索」展开） -----
        searchPanel = new LinearLayout(this);
        searchPanel.setOrientation(LinearLayout.VERTICAL);
        searchPanel.setVisibility(View.GONE);
        int spad = Util.dp(this, 8);
        searchPanel.setPadding(Util.dp(this, 8), spad, Util.dp(this, 8), spad);
        searchPanel.setBackgroundColor(Ui.SURFACE);

        // 第 1 行：输入框 + 取消
        LinearLayout sRow1 = new LinearLayout(this);
        sRow1.setOrientation(LinearLayout.HORIZONTAL);
        sRow1.setGravity(Gravity.CENTER_VERTICAL);
        searchEdit = new EditText(this);
        searchEdit.setSingleLine(true);
        searchEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchEdit.setTextSize(Ui.sp(textLv, 12, 13, 15));
        searchEdit.setTextColor(Ui.TEXT);
        searchEdit.setHintTextColor(Ui.HINT);
        searchEdit.setHint("搜索文件名（当前目录或子目录）");
        sRow1.addView(searchEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchEdit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { scheduleSearch(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        Button searchCancel = Ui.flat(this, "取消", textLv);
        searchCancel.setTextColor(Ui.SUB);
        searchCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitSearch(); }
        });
        sRow1.addView(searchCancel);
        searchPanel.addView(sRow1);

        // 第 2 行：范围切换 + 区分大小写 + 状态
        LinearLayout sRow2 = new LinearLayout(this);
        sRow2.setOrientation(LinearLayout.HORIZONTAL);
        sRow2.setGravity(Gravity.CENTER_VERTICAL);
        searchScopeBtn = Ui.flat(this, "当前目录", textLv);
        searchScopeBtn.setTextColor(Ui.PRIMARY);
        searchScopeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                searchRecursive = !searchRecursive;
                searchScopeBtn.setText(searchRecursive ? "全部子目录" : "当前目录");
                runSearch();   // 范围切换后按当前关键字重搜
            }
        });
        sRow2.addView(searchScopeBtn);
        searchCaseCb = new CheckBox(this);
        searchCaseCb.setText("区分大小写");
        searchCaseCb.setTextSize(Ui.sp(textLv, 11, 12, 14));
        searchCaseCb.setTextColor(Ui.TEXT);
        searchCaseCb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        searchCaseCb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                searchCase = on;
                runSearch();
            }
        });
        sRow2.addView(searchCaseCb);
        searchStatus = new TextView(this);
        searchStatus.setTextSize(Ui.sp(textLv, 10, 11, 13));
        searchStatus.setTextColor(Ui.SUB);
        searchStatus.setPadding(Util.dp(this, 8), 0, 0, 0);
        sRow2.addView(searchStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchPanel.addView(sRow2);

        root.addView(searchPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addLine(root);

        // 第 1 行工具栏：导航 / 全局按钮（横向滚动）+ 右侧钉死的 3 点溢出菜单（MD1 风格）
        LinearLayout toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setGravity(Gravity.CENTER_VERTICAL);
        toolRow.setBackgroundColor(0xFFFFFFFF);   // MD1 白底应用栏
        HorizontalScrollView toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolScroll.addView(toolbar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 工具栏按「当前标签是否为 archive://」动态重建，统一走 rebuildToolbar()（见 applyArchiveUi）。
        rebuildToolbar();
        toolRow.addView(toolScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toolRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addLine(root);

        // 排序下拉菜单：锚在「排序」按钮正下方的窄浮层，覆盖在文件列表上方，
        // 不占用布局空间（不会把列表往下推），宽度只跟按钮 / 内容对齐。
        sortDropdown = new LinearLayout(this);
        sortDropdown.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable sdbg = new android.graphics.drawable.GradientDrawable();
        sdbg.setColor(0xFFFFFFFF);
        sdbg.setCornerRadius(Util.dp(this, 6));
        sdbg.setStroke(1, Ui.LINE);
        sortDropdown.setBackgroundDrawable(sdbg);
        sortDropdown.setPadding(0, Util.dp(this, 4), 0, Util.dp(this, 4));

        sortPopup = new android.widget.PopupWindow(sortDropdown,
                Util.dp(this, 148), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        sortPopup.setOutsideTouchable(true);   // 点菜单外部：收起并吞掉这次点击，不误触下面的文件
        sortPopup.setFocusable(true);          // 返回键先收起菜单
        // outsideTouchable 需要非 null 的 background 才生效
        sortPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        if (android.os.Build.VERSION.SDK_INT >= 21) sortPopup.setElevation(Util.dp(this, 6));
        sortPopup.setOnDismissListener(new android.widget.PopupWindow.OnDismissListener() {
            public void onDismiss() { updateSortBtn(); }   // 收起后恢复按钮底色
        });

        // 新建下拉菜单：与排序同形态，锚在「新建」按钮正下方的窄浮层，
        // 装原工具栏的「新建文件夹」「新建文件」两项，同样不占文件列表区域。
        addDropdown = new LinearLayout(this);
        addDropdown.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable adbg = new android.graphics.drawable.GradientDrawable();
        adbg.setColor(0xFFFFFFFF);
        adbg.setCornerRadius(Util.dp(this, 6));
        adbg.setStroke(1, Ui.LINE);
        addDropdown.setBackgroundDrawable(adbg);
        addDropdown.setPadding(0, Util.dp(this, 4), 0, Util.dp(this, 4));

        addPopup = new android.widget.PopupWindow(addDropdown,
                Util.dp(this, 168), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        addPopup.setOutsideTouchable(true);
        addPopup.setFocusable(true);
        addPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        if (android.os.Build.VERSION.SDK_INT >= 21) addPopup.setElevation(Util.dp(this, 6));
        addPopup.setOnDismissListener(new android.widget.PopupWindow.OnDismissListener() {
            public void onDismiss() { updateAddBtn(); }   // 收起后恢复按钮底色
        });

        // 收藏下拉菜单：列出收藏夹条目。收藏项可能较多，用 ScrollView 承载，
        // 展开时按内容测量高度并封顶到半屏，超出部分在菜单内滚动。
        android.widget.ScrollView favScroll = new android.widget.ScrollView(this);
        favScroll.setVerticalScrollBarEnabled(false);
        favDropdown = new LinearLayout(this);
        favDropdown.setOrientation(LinearLayout.VERTICAL);
        favDropdown.setPadding(0, Util.dp(this, 4), 0, Util.dp(this, 4));
        android.graphics.drawable.GradientDrawable fdbg = new android.graphics.drawable.GradientDrawable();
        fdbg.setColor(0xFFFFFFFF);
        fdbg.setCornerRadius(Util.dp(this, 6));
        fdbg.setStroke(1, Ui.LINE);
        favScroll.setBackgroundDrawable(fdbg);
        favScroll.addView(favDropdown, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        favPopup = new android.widget.PopupWindow(favScroll,
                Util.dp(this, 220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        favPopup.setOutsideTouchable(true);
        favPopup.setFocusable(true);
        favPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        if (android.os.Build.VERSION.SDK_INT >= 21) favPopup.setElevation(Util.dp(this, 6));
        favPopup.setOnDismissListener(new android.widget.PopupWindow.OnDismissListener() {
            public void onDismiss() { updateFavBtn(); }   // 收起后恢复按钮底色
        });

        // 网格 + 空视图（SwipeHost：在 dispatchTouchEvent 旁听完整触摸序列，供左右滑动切换标签页）
        android.widget.FrameLayout body = new SwipeHost(this);
        grid = new RecyclerView(this);
        gridLm = new GridLayoutManager(this, Prefs.viewMode(this) == Prefs.VIEW_LIST ? 1 : Prefs.columns(this));
        grid.setLayoutManager(gridLm);
        grid.setPadding(Util.dp(this, 4), Util.dp(this, 3), Util.dp(this, 4), Util.dp(this, 3));
        // 关闭边缘回弹：部分机型上拉/下拉过头时会出现整片空白
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 格子间距 2dp（GridView 的 horizontal/verticalSpacing 的等价物）
        final int sp2 = Util.dp(this, 2);
        grid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(android.graphics.Rect outRect, View view,
                    RecyclerView parent, RecyclerView.State state) {
                outRect.set(sp2, sp2, sp2, sp2);
            }
        });
        // 左右滑动切换标签页：判定逻辑沿用原来的 onFling（60dp / 300px/s），
        // 触发时切换到相邻标签并播放无缝滑入/滑出动画。检测器由 SwipeHost.dispatchTouchEvent 喂事件。
        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!swipeTabs || tabs.size() <= 1) return false;
                if (gridAnimating) return false;                     // 动画进行中，不重复触发
                if (swipeDownX <= drawerEdge) return false;          // 左边缘留给抽屉右滑手势
                if (Math.abs(vx) <= Math.abs(vy)) return false;       // 纵向滚动手势不触发
                if (Math.abs(vx) < swipeMinVel) return false;
                float dx = (e2 != null && e1 != null) ? e2.getX() - e1.getX() : 0;
                if (Math.abs(dx) < Util.dp(MainActivity.this, swipeMinDist)) return false;
                int target = vx < 0 ? active + 1 : active - 1;     // 左滑→下一页，右滑→上一页
                if (target < 0 || target >= tabs.size()) return false;
                animateSwipeToTab(target, vx < 0 ? 1 : -1);
                return true;
            }
        });
        adapter = new RvFileAdapter(this, Util.dp(this, 40));
        grid.setAdapter(adapter);
        body.addView(grid, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setText("目录为空，或没有访问权限");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(Ui.sp(textLv, 13, 14, 16));
        empty.setTextColor(Ui.HINT);
        empty.setVisibility(View.GONE);
        body.addView(empty, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部操作栏：只在选中文件（或剪贴板有内容可粘贴）时显示
        LinearLayout bottomWrap = new LinearLayout(this);
        bottomWrap.setOrientation(LinearLayout.VERTICAL);
        bottomWrap.setBackgroundColor(Ui.SURFACE);
        bottomWrap.setVisibility(View.GONE);
        bottomBar = bottomWrap;
        bottomWrap.addView(Ui.line(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));

        // 命令行：与文件操作栏一起放在底部，且仅在选中文件/文件夹时显示
        cmdBar = new LinearLayout(this);
        cmdBar.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView cmdScroll = new HorizontalScrollView(this);
        cmdScroll.setHorizontalScrollBarEnabled(false);
        cmdBox = new LinearLayout(this);
        cmdBox.setOrientation(LinearLayout.HORIZONTAL);
        cmdBox.setGravity(Gravity.CENTER_VERTICAL);
        cmdScroll.addView(cmdBox, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cmdBar.addView(cmdScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cmdBar.addView(Ui.line(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        cmdBar.setVisibility(View.GONE);
        bottomWrap.addView(cmdBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView bottomScroll = new HorizontalScrollView(this);
        bottomScroll.setHorizontalScrollBarEnabled(false);
        bottomBox = new LinearLayout(this);
        bottomBox.setOrientation(LinearLayout.HORIZONTAL);
        bottomBox.setGravity(Gravity.CENTER_VERTICAL);
        bottomScroll.addView(bottomBox, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomWrap.addView(bottomScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String[] bNames = {"打开", "复制", "剪切", "粘贴", "重命名", "删除", "分享", "属性"};
        String[] bActs = {"openWith", "copy", "cut", "paste", "rename", "delete", "share", "info"};
        for (int i = 0; i < bNames.length; i++) {
            if (i > 0) addVLine(bottomBox);
            final String a = bActs[i];
            Button b = Ui.flat(this, bNames[i], textLv);
            b.setTextColor(Ui.PRIMARY);
            b.setTag(a);       // 供 updateBottomBar 按动作控制可用性
            iconBtn(b, bNames[i], bActs[i]);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { onAction(a); }
            });
            bottomBox.addView(b);
            bottomBtns.add(b);
        }
        bottomWrap.addView(Ui.line(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        root.addView(bottomWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 状态栏
        status = new TextView(this);
        status.setTextSize(Ui.sp(textLv, 10, 11, 13));
        status.setTextColor(Ui.SUB);
        status.setBackgroundColor(Ui.SURFACE);
        status.setPadding(pad, Util.dp(this, 6), pad, Util.dp(this, 6));
        status.setMaxLines(2);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 左侧抽屉（原生实现，不依赖 Material 组件）=====
        drawerWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f);
        if (drawerWidth > Util.dp(this, 280)) drawerWidth = Util.dp(this, 280);
        drawerEdge = Util.dp(this, 30);
        drawerSlideOpen = Util.dp(this, 48);

        // 主内容容器（自定义 FrameLayout，拦截左边缘右滑手势）
        DrawerContainer content = new DrawerContainer(this, this);
        content.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 遮罩层：点击关闭抽屉
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });
        content.addView(drawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 抽屉面板（初始移到屏幕左外）
        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.LEFT | Gravity.FILL_VERTICAL;
        content.addView(drawerPanel, dlp);
        drawerPanel.setTranslationX(-drawerWidth);

        FrameLayout outer = new FrameLayout(this);
        outer.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return outer;
    }

    private void addLine(LinearLayout parent) {
        View v = Ui.line(this);
        parent.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
    }

    private void addVLine(LinearLayout parent) {
        View sep = new View(this);
        sep.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Util.dp(this, 1), Util.dp(this, 16));
        slp.gravity = Gravity.CENTER_VERTICAL;
        parent.addView(sep, slp);
    }

    // ==================== 事件 ====================

    /**
     * 把文字按钮换成图标按钮：清空文字、设置图标、长按弹出名称提示。
     * 图标资源缺失时保留原文字，避免出现一个看不见也点不到的空按钮。
     */
    private void iconBtn(Button b, final String label, String icon) {
        iconBtn(b, label, icon, ToolbarIcons.ICON_DP);
    }

    /** 同上，但可指定图标尺寸（dp），用于命令栏等需要更小图标的位置。 */
    private void iconBtn(Button b, final String label, String icon, int dp) {
        android.graphics.drawable.Drawable d = ToolbarIcons.get(this, icon, dp);
        if (d == null) { b.setText(label); return; }
        b.setText("");
        b.setCompoundDrawables(d, null, null, null);
        b.setContentDescription(label);
        b.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { toast(label); return true; }
        });
    }

    private void bindEvents() {
        addrBtns.get(0).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gotoPath(pathEdit.getText().toString().trim()); dropAddrFocus(); }
        });
        // 长按「前往」：复制当前所在路径到剪贴板（系统默认长按阈值触发，短按仍执行「前往」）
        addrBtns.get(0).setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                String p = cur().path;
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("path", p));
                    toast("已复制路径：" + p);
                }
                return true;   // 消费长按，避免同时触发「前往」
            }
        });
        // 「上级」是明确去父文件夹的入口；列表第一项与「后退」则走浏览历史。
        addrBtns.get(1).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dropAddrFocus();
                // 搜索态：结果是跨目录的，跳父目录会冲掉结果 —— 静默拦截，不弹提示。
                if (searchActive) return;
                gotoPath(isArchivePath(cur().path) ? archiveParent(cur().path) : Util.parent(cur().path));
            }
        });
        addrBtns.get(2).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshForce(); dropAddrFocus(); }
        });

        adapter.setListener(new RvFileAdapter.Listener() {
            @Override public void onItemClick(final FileItem it, View v) {
                // 先亮一下高亮（flashCell 会在 FLASH_MS 后自行恢复透明），再把真正动作延后执行。
                // 延时动作挂在稳定的主线程 Handler 上，不挂在格子视图上（RecyclerView 会回收视图）。
                flashCell(v);
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (searchActive) {
                            if (!cur().sel.isEmpty()) toggleSel(it);
                            else onSearchResultClick(it);
                        } else handleItemClick(it);
                    }
                }, FLASH_MS);
            }
            @Override public void onItemLongClick(FileItem it, View v) {
                if (!it.parent) toggleSel(it);
            }
        });
    }

    /**
     * 单击反馈：把被点格子的背景短暂变为灰色再恢复（与选中态同款浅灰）。
     * 单选路径下被点的项不会是已选中项，所以复位回透明永远正确；
     * 进入文件夹会立即刷新列表、视图被复用覆盖，灰色闪烁自然消失，符合直觉。
     */
    private void flashCell(final View cell) {
        if (cell == null) return;
        cell.setBackgroundColor(Ui.PRIMARY_SOFT);   // 浅灰高亮（与选中背景一致）
        cell.postDelayed(new Runnable() {
            public void run() {
                try { cell.setBackgroundColor(Color.TRANSPARENT); } catch (Throwable ignored) {}
            }
        }, FLASH_MS);
    }

    /** 单击格子后的真正动作（已先让高亮亮出来，再延后到这里执行）。 */
    private void handleItemClick(FileItem it) {
        if (it == null) return;
        if (it.parent) {
            if (searchActive) return;   // 搜索态：静默拦截跳上级，保持搜索结果
            // 列表里的「返回 / ..」语义是「上一级目录」，直接跳父目录（与安卓返回键一致），
            // 不走历史栈 goBack()，否则会出现 A↔B 打转、点返回却不去父目录的「失效」。
            if (isArchivePath(cur().path)) gotoPath(archiveParent(cur().path));
            else if (!cur().path.equals("/")) gotoPath(Util.parent(cur().path));
            return;
        }
        if (!cur().sel.isEmpty()) { toggleSel(it); return; }
        if (it.dir) { gotoPath(it.path); return; }
        // 普通文件：若是压缩包且插件可用，点击直接「进入」浏览内容；否则按原方式打开
        if (!isArchivePath(it.path) && isArchiveExt(it.name)) {
            enterArchive(it);
            return;
        }
        if (isArchivePath(it.path)) { openArchiveFile(it); return; }  // 压缩包内的虚拟文件：经插件读出再打开
        openFile(it);
    }

    /** 是否按压缩包打开：严格以「插件关联格式」为准（用户设置优先，否则插件默认关联）。
     *  不再用写死的扩展名白名单，避免 apk 等被无条件当成压缩包、与系统安装器冲突。 */
    private boolean isArchiveExt(String name) {
        if (name == null) return false;
        String ext = Util.ext(name);
        // assocOk 支持复合后缀（如 tar.gz）与用户自定义格式（如 apkm）。
        // 关联集合为空（用户清空并保存）→ 不接管任何格式，交给系统打开。
        return pluginMgr.isAssociated(name, ext, PluginManager.CAP_ARCHIVE);
    }

    /** 点击压缩包文件：立即显示「打开中...」（可取消），后台探测插件后进入其虚拟根目录。 */
    private void enterArchive(final FileItem it) {
        final String fp = it.path;
        final OpenArchiveDlg dlg = new OpenArchiveDlg(it.name);
        archiveOpenDlg = dlg;
        Dbg.log("ARCHIVE", "打开压缩包开始 file=" + fp);
        io.execute(new Runnable() {
            public void run() {
                final PluginManager.ArchiveHandle h = pluginMgr.getArchiveProvider(fp, null);
                ui.post(new Runnable() {
                    public void run() {
                        if (dlg.cancelled || dlg.finished) return;   // 探测期间已被取消
                        if (h != null) {
                            // 进入压缩包前记下真实目录列表的当前滚动位置，退出压缩包时恢复
                            archiveExitScroll = firstVisible();
                            gotoPath(ARCHIVE_SCHEME + h.pluginId + encSeg(fp) + "#");
                            // refresh 若走了早退分支（同路径已加载 / 搜索态）不会接管提示框，这里兜底关掉
                            if (dlg.seq < 0 && !dlg.finished) dlg.finish();
                        } else {
                            dlg.finish();      // 没有可用插件：关框后按普通文件打开
                            openFile(it);
                        }
                    }
                });
            }
        });
    }

    /** 打开压缩包内的虚拟文件：经插件读出字节 → 落缓存 → 用现有 openFile 交给系统查看器。
     *  提取期间底部状态栏显示「打开中…」，并返回键可「取消打开」（不退出压缩包视图）。 */
    private void openArchiveFile(final FileItem it) {
        final String[] a = parseArchive(it.path);
        archiveOpening = true;
        archiveOpenCancel = false;
        status.setText("打开中… " + it.name);
        loading = true;
        io.execute(new Runnable() {
            public void run() {
                PluginManager.ArchiveHandle h = pluginMgr.getArchiveProvider(a[1], null);
                if (h == null) {
                    ui.post(new Runnable() { public void run() { finishArchiveOpen("没有可用的压缩包插件"); } });
                    return;
                }
                try {
                    ParcelFileDescriptor pfd = h.provider.open(a[1], a[2], "");
                    if (pfd == null) {
                        ui.post(new Runnable() { public void run() {
                            finishArchiveOpen("无法读取该文件（可能已加密或不存在）");
                        } });
                        return;
                    }
                    File dir = new File(getCacheDir(), "archive_tmp");
                    dir.mkdirs();
                    File cache = new File(dir, System.currentTimeMillis() + "_" + it.name);
                    InputStream in = new FileInputStream(pfd.getFileDescriptor());
                    FileOutputStream out = new FileOutputStream(cache);
                    byte[] buf = new byte[16384];
                    int n;
                    boolean cancelled = false;
                    while ((n = in.read(buf)) >= 0) {
                        if (archiveOpenCancel) { cancelled = true; break; }
                        out.write(buf, 0, n);
                    }
                    in.close();
                    out.close();
                    pfd.close();
                    if (cancelled) {
                        cache.delete();   // 删除半成品临时文件
                        ui.post(new Runnable() { public void run() { finishArchiveOpen("已取消打开"); } });
                        return;
                    }
                    final FileItem ci = new FileItem(it.name, cache.getAbsolutePath(),
                            false, cache.length(), cache.lastModified());
                    ui.post(new Runnable() { public void run() {
                        loading = false; archiveOpening = false; archiveOpenCancel = false;
                        updateStatus(); openFile(ci);
                    } });
                } catch (Exception ex) {
                    ui.post(new Runnable() {
                        public void run() {
                            loading = false; archiveOpening = false; archiveOpenCancel = false;
                            updateStatus();
                            toast("读取压缩包内文件失败：" + ex.getMessage());
                        }
                    });
                }
            }
        });
    }

    /** 压缩包内文件提取结束 / 取消后的状态复位。msg 非空则弹 toast 提示。 */
    private void finishArchiveOpen(String msg) {
        loading = false;
        archiveOpening = false;
        archiveOpenCancel = false;
        if (msg != null) toast(msg);
        updateStatus();
    }

    /**
     * 【v2.51 起废弃，由粘贴分支的批量解压取代】
     * 旧实现按单条目走插件 extract（7z e 平铺），有两个致命问题：
     *  1) 文件夹条目：zip 等格式常无显式目录条目，find 返回 null；即使找到，e 对目录也提不出内容；
     *  2) 多选时每个条目各自全量 l -slt + 各起一次 su，N 项 N 倍慢。
     * 新实现见 onAction("paste")：按压缩包分组 → 插件 extractBatch（一次列表 + 一次 su 分批
     * 7z x，保留结构，空目录 mkdir）→ 再从临时目录逐项复制到目标位置。
     */

    /**
     * 顶部「解压」按钮：先读取压缩包根层条目，决定解压目标——
     *   - 仅 1 个根条目：直接解压到「压缩包所在文件夹」（落盘为该条目原名）；
     *   - 多于 1 个根条目：解压到「压缩包所在文件夹/压缩文件名(去后缀)」新建子文件夹。
     * 默认不再每次弹确认框；仅当最终落盘路径已存在时才提示「覆盖/取消」。解压过程用进度条提示，
     * 完成后退出压缩包视图（count>1 进入新建子文件夹，count==1 回到所在文件夹）。
     * 仅当当前标签为 archive:// 视图时可用。
     */
    private void extractArchiveToParent() {
        final String p = cur().path;
        if (!isArchivePath(p)) { toast("当前不在压缩包视图"); return; }
        String[] a = parseArchive(p);
        if (a[1] == null || a[1].isEmpty()) { toast("压缩包路径无效"); return; }
        final String archivePath = a[1];
        final String containingDir = Util.parent(archivePath);   // 压缩包所在文件夹
        final String archiveName = Util.base(archivePath);

        // 解压全程共用一个对话框：先「读取压缩包」，随后直接进入「解压中」显示进度条
        final ExtractProgress prog = showExtractProgress("解压中", "正在读取压缩包内容…");
        loading = true;
        io.execute(new Runnable() {
            public void run() {
                final ArchiveListing lst = pluginMgr.listArchiveRoot(archivePath);
                ui.post(new Runnable() {
                    public void run() {
                        if (prog.cancelled) { loading = false; return; }  // 读取阶段已取消：静默
                        if (lst == null) {
                            prog.finish(false);
                            loading = false;
                            toast("无法读取压缩包（需 root + Termux 的 7z）");
                            return;
                        }
                        final int count = (lst.entries == null) ? 0 : lst.entries.size();
                        if (count == 0) {
                            prog.finish(false); loading = false; toast("压缩包为空"); return;
                        }
                        final boolean single = (count == 1);
                        // 单条目 → 直接解压到压缩包所在文件夹（落盘为条目原名，不再套一层文件夹）；
                        // 多条目 → 解压到「文件名(去后缀)」新建子文件夹，避免散落一堆文件。
                        // 注意：-o 目标是目录，单条目时绝不能写成 containingDir/条目名（7z 会把它当目录建出来）。
                        final String target = single
                                ? containingDir
                                : Util.join(containingDir, stripArchiveExt(archiveName));
                        // 冲突判定：单条目看最终落盘的文件/文件夹名；多条目看新建的子文件夹。
                        // （不能只判 target：单条目时 target 即所在文件夹，必然存在，会误报冲突。）
                        final String conflictPath = single
                                ? Util.join(containingDir, lst.entries.get(0).name)
                                : target;
                        if (new File(conflictPath).exists()) {
                            showOverwriteConfirm(archivePath, target, conflictPath, prog);
                        } else {
                            runExtract(archivePath, target, prog);
                        }
                    }
                });
            }
        });
    }

    /** 同名冲突时弹「覆盖/取消」；非冲突情况不再打扰用户。 */
    private void showOverwriteConfirm(final String archivePath, final String target,
                                      final String conflictPath, final ExtractProgress prog) {
        new AlertDialog.Builder(this)
                .setTitle("同名文件已存在")
                .setMessage("目标位置已存在：\n" + conflictPath + "\n是否覆盖？")
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { prog.finish(false); loading = false; }
                })
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        runExtract(archivePath, target, prog);
                    }
                }).show();
    }

    /** 执行解压：复用传入的对话框，解压前用 7z l 取总大小并启动 2 秒进度轮询，完成后退出压缩包视图。 */
    private void runExtract(final String archivePath, final String target, final ExtractProgress prog) {
        prog.setMessage("正在解压… " + target);
        loading = true;
        io.execute(new Runnable() {
            public void run() {
                if (prog.cancelled) { loading = false; return; }   // 排队期间已取消：不启动 7z
                // 解压前估算「解压后实际大小」作分母；输出目录当前大小作基线（单条目时可能与压缩包同目录）
                final long total = pluginMgr.contentSize(archivePath, new ArrayList<String>());
                if (prog.cancelled) { loading = false; return; }   // 估算阶段已取消：不启动解压
                final File out = new File(target);
                final long baseline = dirSize(out);
                prog.configure(total, baseline, out);
                final int code = pluginMgr.extractAll(archivePath, target);
                Dbg.log("EXTRACT", "extractAll code=" + code
                        + " archive=" + archivePath + " target=" + target
                        + " 目标存在=" + out.exists());
                ui.post(new Runnable() {
                    public void run() {
                        loading = false;
                        if (prog.cancelled) return;   // 已取消：静默，不提示、不跳转、不重复 dismiss
                        prog.finish(code == 0);
                        if (code == 0) {
                            toast("解压完成：" + target);
                            // 退出压缩包视图：>1 进入新建子文件夹看结果，==1 回到压缩包所在文件夹
                            gotoPath(target);
                        } else {
                            toast("解压失败（7z 返回 " + code + "，需 root + Termux 的 7z）");
                            updateStatus();
                        }
                    }
                });
            }
        });
    }

    /** 递归统计目录内已落盘文件的总大小（Byte）；读不到返回 0，容错不抛异常。 */
    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) {
            // 超长解压脚本会临时落盘在被监视的输出目录里（fmsz_*.sh，可达数百 KB），
            // 它不是解压产物，必须排除：否则「已解压大小」会超过分母，且 maxDone 单调递增不回退。
            String n = f.getName();
            if (n.startsWith("fmsz_") && n.endsWith(".sh")) return 0;
            return f.length();
        }
        File[] ch = f.listFiles();
        if (ch == null) return 0;
        long s = 0;
        for (File c : ch) s += dirSize(c);
        return s;
    }

    /**
     * 「解压」与「从压缩包复制」共用的进度对话框。
     *  - 初始为不确定进度条（读取 / 准备阶段），configure() 后切换为确定性 0..100；
     *  - 用独立轮询线程每 2 秒统计输出目录增量算「已解压大小」，不占用会被 7z 阻塞的 io 线程池；
     *  - 分母来自解压前 pluginMgr.contentSize()（7z l 汇总行 / 选中条目累加），取不到则降级只显示字节数；
     *  - 进度取历史最大值，保证临时目录被清理时不倒退；finish() 置 100% 并关闭。
     */
    private final class ExtractProgress {
        final Dialog dlg;
        final ProgressBar bar;
        final TextView msg;
        volatile boolean running = true;
        volatile boolean cancelled = false; // 用户点了「取消」（跨线程可见）
        volatile long total = -1;         // 解压后总大小（分母）
        volatile long baseline = 0;       // 解压前输出目录已有大小
        volatile File[] watch = null;     // 需要统计的输出目录
        volatile long maxDone = 0;        // 已解压大小（单调递增）
        volatile boolean extractDone = false;  // 解压阶段已结束，进度冻结在 100%
        volatile String verb = "已解压";        // 轮询文案动词：「已解压」（解压）/「已复制」（粘贴）等
        Thread poller;

        /** 修改轮询文案动词（须在 configure 前调用）。 */
        void setVerb(String v) { verb = v; }

        ExtractProgress(String title, String subtitle) {
            dlg = new Dialog(MainActivity.this);
            dlg.setCancelable(false);
            dlg.setCanceledOnTouchOutside(false);
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Util.dp(MainActivity.this, 24), Util.dp(MainActivity.this, 20),
                    Util.dp(MainActivity.this, 24), Util.dp(MainActivity.this, 20));
            TextView tv = new TextView(MainActivity.this);
            tv.setText(title);
            tv.setTextSize(Ui.sp(textLv, 15, 16, 18));
            tv.setTextColor(Ui.TEXT);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            root.addView(tv);
            bar = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Util.dp(MainActivity.this, 14);
            root.addView(bar, blp);
            msg = new TextView(MainActivity.this);
            msg.setText(subtitle);
            msg.setTextSize(Ui.sp(textLv, 12, 13, 14));
            msg.setTextColor(Ui.SUB);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = Util.dp(MainActivity.this, 10);
            root.addView(msg, mlp);
            // 「取消」按钮：右对齐。点击后立即关框并跨进程中断正在执行的 7z/su
            LinearLayout btnRow = new LinearLayout(MainActivity.this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            Button cancelBtn = Ui.flat(MainActivity.this, "取消", textLv);
            cancelBtn.setTextColor(Ui.PRIMARY);
            cancelBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cancel(); }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Util.dp(MainActivity.this, 8);
            btnRow.addView(cancelBtn, clp);
            root.addView(btnRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dlg.setContentView(root);
            dlg.show();
        }

        /** 用户点「取消」：立即关框、停轮询、标记已取消；并在独立线程通知插件进程销毁当前 7z/su。
         *  不用 io 线程池——它此刻正被阻塞的解压调用占满。AIDL cancel 为 oneway，调用立即返回。 */
        void cancel() {
            if (cancelled || !running) return;
            cancelled = true;
            loading = false;                     // 复位加载态（取消后不再有后续动作）
            finish(false);                       // 关闭对话框、停止轮询
            new Thread(new Runnable() {
                public void run() {
                    if (pluginMgr != null) pluginMgr.cancel();
                }
            }, "extract-cancel").start();
            // 解压完成后还有「cp -r 到目标 / 清理临时目录」两阶段长命令，
            // 它们在 APP 自己的 Root 会话里执行，插件 cancel 管不到，需一并中断。
            Fs.cancelRootCommand();
        }

        void setMessage(final String s) {
            ui.post(new Runnable() { public void run() { if (running) msg.setText(s); } });
        }

        /** 设置分母与输出目录并启动 2 秒轮询；可在任意线程调用。 */
        void configure(final long totalBytes, final long base, final File... dirs) {
            total = totalBytes;
            baseline = base;
            watch = dirs;
            ui.post(new Runnable() {
                public void run() {
                    if (!running) return;
                    if (total > 0) {          // 有分母才用确定性进度条
                        bar.setIndeterminate(false);
                        bar.setMax(100);
                        bar.setProgress(0);
                    }
                    startPoller();
                }
            });
        }

        /** 解压阶段完成：冻结进度为 100%，后续「复制到目标目录」不再统计临时目录，
         *  避免临时目录被清理/内容变动时文案跳动，也不会把复制阶段误算进「已解压大小」。 */
        void markExtractDone() {
            extractDone = true;
            ui.post(new Runnable() {
                public void run() {
                    if (!running) return;
                    if (total > 0) { bar.setIndeterminate(false); bar.setProgress(100); }
                    msg.setText("解压完成，正在搬移到目标目录…");
                }
            });
        }

        private void startPoller() {
            poller = new Thread(new Runnable() {
                public void run() {
                    while (running) {
                        try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                        if (!running) break;
                        if (extractDone) continue;   // 进度已冻结，不再统计临时目录
                        long done = 0;
                        File[] ds = watch;
                        if (ds != null) for (File d : ds) done += dirSize(d);
                        done -= baseline;
                        if (done < 0) done = 0;
                        // 钳制到分母：分母来自 7z l 估算，落盘统计口径可能略有出入，
                        // 绝不让「已解压大小」超过「总大小」（旧版会显示 32.6MB / 31.96MB）。
                        if (total > 0 && done > total) done = total;
                        if (done > maxDone) maxDone = done;      // 只增不减，临时目录清理时不回退
                        if (total > 0 && maxDone > total) maxDone = total;
                        final long d = maxDone, t = total;
                        ui.post(new Runnable() {
                            public void run() {
                                if (!running) return;
                                int pct = (t > 0) ? (int) Math.min(100, d * 100 / t) : 0;
                                if (t > 0) bar.setProgress(pct);
                                msg.setText(verb + " " + Util.size(d)
                                        + (t > 0 ? " / " + Util.size(t) + "  (" + pct + "%)" : ""));
                            }
                        });
                    }
                }
            }, "extract-progress");
            poller.setDaemon(true);
            poller.start();
        }

        void finish(final boolean ok) {
            running = false;
            if (poller != null) poller.interrupt();
            ui.post(new Runnable() {
                public void run() {
                    if (ok && total > 0) { bar.setIndeterminate(false); bar.setProgress(100); }
                    dlg.dismiss();
                }
            });
        }
    }

    /**
     * 「删除」专用进度对话框（进度条 + 取消），复用 ExtractProgress 的视觉风格。
     *  - 本地删除：先递归统计总条目数作分母，删除时逐项 done+1，显示确定进度条；
     *  - Root 删除：rm -rf 无法逐项计数，按「顶层条目数」作分母、每项完成 done+1（粗粒度）；
     *  - 取消：本地置 cancelled 让 delTree 提前结束；Root 调 Fs.cancelRootCommand() 中断 su 的 rm -rf。
     */
    private final class DeleteProgress {
        final Dialog dlg;
        final ProgressBar bar;
        final TextView msg;
        volatile boolean running = true;
        volatile boolean cancelled = false;
        volatile long total = -1;          // 分母（总条目数）；<=0 保持不确定进度条
        volatile long done = 0;            // 已删除条目数（单调递增）
        volatile boolean isRoot = false;
        Thread poller;

        DeleteProgress(String title, String subtitle) {
            dlg = new Dialog(MainActivity.this);
            dlg.setCancelable(false);
            dlg.setCanceledOnTouchOutside(false);
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Util.dp(MainActivity.this, 24), Util.dp(MainActivity.this, 20),
                    Util.dp(MainActivity.this, 24), Util.dp(MainActivity.this, 20));
            TextView tv = new TextView(MainActivity.this);
            tv.setText(title);
            tv.setTextSize(Ui.sp(textLv, 15, 16, 18));
            tv.setTextColor(Ui.TEXT);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            root.addView(tv);
            bar = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Util.dp(MainActivity.this, 14);
            root.addView(bar, blp);
            msg = new TextView(MainActivity.this);
            msg.setText(subtitle);
            msg.setTextSize(Ui.sp(textLv, 12, 13, 14));
            msg.setTextColor(Ui.SUB);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = Util.dp(MainActivity.this, 10);
            root.addView(msg, mlp);
            // 「取消」按钮：右对齐
            LinearLayout btnRow = new LinearLayout(MainActivity.this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            Button cancelBtn = Ui.flat(MainActivity.this, "取消", textLv);
            cancelBtn.setTextColor(Ui.PRIMARY);
            cancelBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cancel(); }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Util.dp(MainActivity.this, 8);
            btnRow.addView(cancelBtn, clp);
            root.addView(btnRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dlg.setContentView(root);
            dlg.show();
        }

        void cancel() {
            if (cancelled || !running) return;
            cancelled = true;
            loading = false;                     // 复位加载态（取消后不再有后续动作）
            finish(false);                       // 关闭对话框、停止轮询
            if (isRoot) Fs.cancelRootCommand();  // 中断 su 的 rm -rf（0~250ms 内生效）
        }

        void setMessage(final String s) {
            ui.post(new Runnable() { public void run() { if (running) msg.setText(s); } });
        }

        /** 设置分母并启动轮询（total<=0 时保持不确定进度条）。 */
        void configure(final long totalItems) {
            total = totalItems;
            ui.post(new Runnable() {
                public void run() {
                    if (!running) return;
                    if (total > 0) { bar.setIndeterminate(false); bar.setMax(100); bar.setProgress(0); }
                    startPoller();
                }
            });
        }

        private void startPoller() {
            poller = new Thread(new Runnable() {
                public void run() {
                    while (running) {
                        try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                        if (!running) break;
                        final long d = done, t = total;
                        ui.post(new Runnable() {
                            public void run() {
                                if (!running) return;
                                if (t > 0) {
                                    int pct = (int) Math.min(100, d * 100 / t);
                                    bar.setProgress(pct);
                                    msg.setText("已删除 " + d + " / " + t + "  (" + pct + "%)");
                                } else {
                                    msg.setText("正在删除…（已删除 " + d + " 项）");
                                }
                            }
                        });
                    }
                }
            }, "delete-progress");
            poller.setDaemon(true);
            poller.start();
        }

        void finish(final boolean ok) {
            running = false;
            if (poller != null) poller.interrupt();
            ui.post(new Runnable() {
                public void run() {
                    if (ok && total > 0) { bar.setIndeterminate(false); bar.setProgress(100); }
                    dlg.dismiss();
                }
            });
        }
    }

    /** 递归统计一个路径下的条目总数（文件 + 目录，含自身），用于本地删除的进度分母。 */
    private long countItems(File f) {
        long n = 1;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) n += countItems(x);
        }
        return n;
    }

    /** 递归删除（本地）：每删一个条目 done+1；cancelled 时提前中止。返回是否完整删除。 */
    private boolean delTree(File f, DeleteProgress prog) {
        if (prog.cancelled) return false;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) {
                if (!delTree(x, prog)) return false;
                if (prog.cancelled) return false;
            }
        }
        boolean ok = f.delete();
        if (ok) prog.done++;
        return ok;
    }

    private ExtractProgress showExtractProgress(String title, String subtitle) {
        return new ExtractProgress(title, subtitle);
    }

    // ==================== 打开压缩包提示 ====================

    /** 「打开中...」副标题：压缩包名 + 当前内层目录。 */
    private String archiveDlgLabel(String path) {
        String[] a = parseArchive(path);
        if (a[1] == null || a[1].isEmpty()) return path;
        String label = Util.base(a[1]);
        if (a[2] != null && !a[2].isEmpty()) label += "  /  " + a[2];
        return label;
    }

    /**
     * 打开压缩包（或进入压缩包内子目录）时的「打开中...」提示框，带取消按钮。
     * archive:// 列目录需跨进程让插件跑 7z l，大包/加密包可能耗时数秒以上，故给出明确反馈并允许取消。
     * 取消会：让本次列目录结果失效、中断插件进程里正在执行的 7z/su、把标签页路径退回打开前的位置。
     */
    private final class OpenArchiveDlg {
        final Dialog dlg;
        volatile String path;        // 目标 archive:// 路径（bind 后才有值）
        volatile String fallback;    // 取消后回退路径（上一次已加载的目录）
        volatile int seq = -1;       // 对应 refresh 序号；-1 = 尚在插件探测阶段
        volatile boolean cancelled = false;
        volatile boolean finished = false;

        OpenArchiveDlg(String subtitle) {
            dlg = new Dialog(MainActivity.this);
            dlg.setCancelable(true);               // 返回键等同「取消」
            dlg.setCanceledOnTouchOutside(false);  // 点框外不误关
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Util.dp(MainActivity.this, 24), Util.dp(MainActivity.this, 20),
                    Util.dp(MainActivity.this, 24), Util.dp(MainActivity.this, 16));
            TextView title = new TextView(MainActivity.this);
            title.setText("打开中...");
            title.setTextSize(Ui.sp(textLv, 15, 16, 18));
            title.setTextColor(Ui.TEXT);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            root.addView(title);
            TextView sub = new TextView(MainActivity.this);
            sub.setText(subtitle);
            sub.setTextSize(Ui.sp(textLv, 11, 12, 14));
            sub.setTextColor(Ui.SUB);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Util.dp(MainActivity.this, 6);
            root.addView(sub, slp);
            ProgressBar bar = new ProgressBar(MainActivity.this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Util.dp(MainActivity.this, 14);
            root.addView(bar, blp);
            LinearLayout btnRow = new LinearLayout(MainActivity.this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            Button cancelBtn = Ui.flat(MainActivity.this, "取消", textLv);
            cancelBtn.setTextColor(Ui.PRIMARY);
            cancelBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cancel(); }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Util.dp(MainActivity.this, 8);
            btnRow.addView(cancelBtn, clp);
            root.addView(btnRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dlg.setContentView(root);
            dlg.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                public void onCancel(android.content.DialogInterface d) { cancel(); }
            });
            dlg.show();
        }

        /** 进入列目录阶段：绑定目标路径 / 回退路径 / refresh 序号，取消时才能精确回滚。 */
        void bind(String p, String fb, int s) {
            path = p;
            fallback = fb;
            seq = s;
        }

        /** 用户点「取消」或按返回键：立即关框、丢弃列目录结果、中断插件 7z、路径退回打开前。 */
        void cancel() {
            if (cancelled || finished) return;
            cancelled = true;
            Dbg.log("ARCHIVE", "打开压缩包被取消 path=" + path);
            if (seq >= 0 && seq == refreshSeq) {
                refreshSeq++;       // 本次列目录返回后不再覆盖界面
                loading = false;
            }
            finish();
            // AIDL cancel 为 oneway，调用立即返回；不能用 io/listIo 线程池（此刻可能被阻塞调用占满）
            new Thread(new Runnable() {
                public void run() { if (pluginMgr != null) pluginMgr.cancel(); }
            }, "archive-open-cancel").start();
            // 仅当已真正进入列目录阶段才回退路径；探测阶段取消时界面还停在原目录，无需处理
            if (path != null && !tabs.isEmpty() && path.equals(cur().path)) {
                Tab t = cur();
                if (t.pos >= 0 && t.pos < t.history.size()
                        && path.equals(t.history.get(t.pos))) {
                    t.history.remove(t.pos);
                    t.pos--;
                }
                if (t.pos < 0) t.pos = 0;
                String back = (fallback != null && !fallback.isEmpty()) ? fallback : homePath();
                t.path = back;
                if (pathEdit != null) pathEdit.setText(back);
                updateStatus();
            }
            toast("已取消打开");
        }

        void finish() {
            if (finished) return;
            finished = true;
            if (archiveOpenDlg == this) archiveOpenDlg = null;
            ui.post(new Runnable() {
                public void run() {
                    try { if (dlg.isShowing()) dlg.dismiss(); } catch (Throwable ignored) {}
                }
            });
        }
    }

    /** 去掉压缩包文件名后缀（含 .tar.gz 等复合后缀），用于「解压到新建子文件夹」的命名。
     *  能走到这里的文件已被 isArchiveExt / 插件关联 / 自定义规则判定为压缩包，故白名单未命中时
     *  一律兜底去掉最后一段扩展名：否则 apks / apkm / xapk 等后缀会原样返回，使输出目录撞上
     *  压缩包文件本身，7z 的 mkdir 报「File exists」、解压返回 1（表现为「改成 .apk 才能解压」）。 */
    private static String stripArchiveExt(String name) {
        if (name == null || name.isEmpty()) return "archive";
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "(?i)\\.(tar\\.gz|tar\\.bz2|tar\\.xz|tar\\.zst|tgz|zip|7z|rar|gz|bz2|xz|zst|iso|tar|deb|rpm|apk|jar)$");
        java.util.regex.Matcher m = pat.matcher(name);
        if (m.find()) {
            String base = name.substring(0, m.start());
            return base.isEmpty() ? name : base;
        }
        // 白名单未命中（apks/apkm/xapk 或任意自定义关联后缀）：兜底去掉最后一段 .xxx，
        // 避免输出目录等于压缩包文件本身。i>0 同时排除 .hidden 这类以点开头的隐藏文件名。
        int i = name.lastIndexOf('.');
        if (i > 0) {
            String base = name.substring(0, i);
            if (!base.isEmpty()) return base;
        }
        return name;
    }

    // ==================== 搜索 ====================

    /** 顶栏「搜索」按钮：展开 / 收起搜索面板。 */
    private void toggleSearch() {
        if (searchActive) { exitSearch(); return; }
        searchActive = true;
        searchPanel.setVisibility(View.VISIBLE);
        searchEdit.setText("");
        searchResults.clear();
        adapter.setData(new ArrayList<FileItem>());
        searchStatus.setText("输入关键字");
        searchEdit.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT);
    }

    /** 收起搜索面板并恢复当前目录列表。 */
    private void exitSearch() {
        searchActive = false;
        searchSeq++;                       // 作废任何进行中的异步搜索
        searchPanel.setVisibility(View.GONE);
        searchResults.clear();
        if (searchTask != null) searchHandler.removeCallbacks(searchTask);
        adapter.setData(curItems);
        empty.setVisibility(curItems.isEmpty() ? View.VISIBLE : View.GONE);
        if (curItems.isEmpty()) empty.setText("目录为空，或没有访问权限");
        updateStatus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
    }

    /** 输入框防抖：停 250ms 再触发搜索。 */
    private void scheduleSearch() {
        if (searchTask != null) searchHandler.removeCallbacks(searchTask);
        final String q = searchEdit.getText().toString();
        searchTask = new Runnable() { public void run() { runSearch(q); } };
        searchHandler.postDelayed(searchTask, 250);
    }

    /** 立即按当前关键字重搜（范围 / 大小写切换时调用）。 */
    private void runSearch() { runSearch(searchEdit.getText().toString()); }

    private void runSearch(String q) {
        if (!searchActive) return;
        final String query = q == null ? "" : q.trim();
        searchSeq++;                       // 作废上一次异步搜索
        if (query.length() == 0) {
            searchResults.clear();
            adapter.setData(curItems);
            empty.setVisibility(curItems.isEmpty() ? View.VISIBLE : View.GONE);
            if (curItems.isEmpty()) empty.setText("目录为空，或没有访问权限");
            searchStatus.setText("输入关键字");
            return;
        }
        if (!searchRecursive) {
            // 当前目录：内存即时过滤，零耗时
            searchResults.clear();
            String ql = searchCase ? query : query.toLowerCase(java.util.Locale.US);
            for (FileItem it : curItems) {
                if (it.parent) continue;
                String n = searchCase ? it.name : it.name.toLowerCase(java.util.Locale.US);
                if (n.contains(ql)) searchResults.add(it);
            }
            adapter.setData(new ArrayList<FileItem>(searchResults));
            empty.setVisibility(searchResults.isEmpty() ? View.VISIBLE : View.GONE);
            if (searchResults.isEmpty()) empty.setText("当前目录未找到匹配项");
            searchStatus.setText("当前目录找到 " + searchResults.size() + " 项");
            gridLm.scrollToPositionWithOffset(0, 0);
        } else {
            startFind(query);
        }
    }

    /** 递归搜索：用 find 流式逐条回传，边搜边显示。 */
    private void startFind(final String query) {
        final long mySeq = searchSeq;
        searchResults.clear();
        adapter.setData(new ArrayList<FileItem>());
        searchStatus.setText("搜索中… 0");
        empty.setVisibility(View.GONE);
        gridLm.scrollToPositionWithOffset(0, 0);
        final String dir = cur().path;
        String esc = query.replace("'", "'\\''");
        String patt = "*" + esc + "*";
        String nameOpt = searchCase ? "-name" : "-iname";
        final String cmd = "find " + Util.sq(dir) + " " + nameOpt + " " + Util.sq(patt) + " -print 2>/dev/null";
        final ArrayList<FileItem> batch = new ArrayList<FileItem>();
        io.execute(new Runnable() {
            public void run() {
                Fs.ExecResult r = Fs.shellStream(cmd, Fs.rootMode, 120, new Fs.LineSink() {
                    public void line(String ln) {
                        if (searchSeq != mySeq) return;
                        ln = ln.trim();
                        if (ln.length() == 0) return;
                        if (ln.equals(dir) || ln.endsWith("/.")) return;   // 跳过目录自身
                        if (searchResults.size() >= SEARCH_MAX) return;     // 已达上限，忽略其余
                        File f = new File(ln);
                        boolean isDir = f.isDirectory();
                        FileItem it = new FileItem(Util.base(ln), ln, isDir,
                                isDir ? 0 : f.length(), f.lastModified());
                        searchResults.add(it);
                        batch.add(it);
                        if (batch.size() >= 50) {
                            final ArrayList<FileItem> snap = new ArrayList<FileItem>(batch);
                            batch.clear();
                            ui.post(new Runnable() {
                                public void run() {
                                    if (searchSeq != mySeq) return;
                                    adapter.addItems(snap);
                                    searchStatus.setText("搜索中… " + searchResults.size());
                                }
                            });
                        }
                    }
                });
                // find 结束，回刷剩余批次与最终状态
                final ArrayList<FileItem> snap = new ArrayList<FileItem>(batch);
                batch.clear();
                final boolean capped = searchResults.size() >= SEARCH_MAX;
                ui.post(new Runnable() {
                    public void run() {
                        if (searchSeq != mySeq) return;
                        if (!snap.isEmpty()) adapter.addItems(snap);
                        String tail = capped ? "（已达上限 " + SEARCH_MAX + "）" : "";
                        if (r.code != 0) {
                            String err = r.err != null ? r.err.trim() : "find 执行失败";
                            searchStatus.setText("搜索出错：" + err);
                            empty.setVisibility(View.VISIBLE);
                            empty.setText("搜索出错：" + err);
                        } else {
                            searchStatus.setText("找到 " + searchResults.size() + " 项" + tail);
                            empty.setVisibility(searchResults.isEmpty() ? View.VISIBLE : View.GONE);
                            if (searchResults.isEmpty()) empty.setText("未找到匹配项");
                        }
                    }
                });
            }
        });
    }

    /** 点击搜索结果：目录则进入（同时退出搜索），文件则直接打开且保留搜索栏与结果。 */
    private void onSearchResultClick(FileItem it) {
        if (it == null) return;
        String path = it.path;
        if (it.dir) { exitSearch(); gotoPath(path); }
        else { openFile(it); }   // 不退出搜索：打开外部应用后返回，搜索栏与结果仍在
    }

    /** 右上角 3 点溢出菜单：设置 / 插件 */
    private void showOverflowMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        android.view.Menu menu = pm.getMenu();
        menu.add(0, 1, 0, "设置");
        menu.add(0, 2, 0, "插件");
        menu.add(0, 3, 0, "添加到收藏");
        menu.add(0, 4, 0, "关于");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                int id = item.getItemId();
                if (id == 1) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                } else if (id == 2) {
                    startActivity(new Intent(MainActivity.this, PluginsActivity.class));
                } else if (id == 3) {
                    boolean added = Prefs.addFavorite(MainActivity.this, cur().path);
                    toast(added ? ("已收藏当前目录：" + labelOf(cur().path)) : "当前目录已在收藏夹中");
                } else if (id == 4) {
                    startActivity(new Intent(MainActivity.this, AboutActivity.class));
                }
                return true;
            }
        });
        pm.show();
    }

    private void onAction(String a) {
        Dbg.log("ACTION", a + " | path=" + (tabs.isEmpty() ? "-" : cur().path)
                + " | 选中=" + (tabs.isEmpty() ? 0 : cur().sel.size())
                + " | 搜索=" + searchActive);
        if ("back".equals(a)) {
            // 搜索态：goBack 会推进历史栈但 refresh 因搜索态提前返回，状态不一致；
            // 兜底的跳上级也会冲掉结果 —— 一并静默拦截。
            if (searchActive) return;
            if (!goBack()) gotoPath(Util.parent(cur().path));
        } else if ("fwd".equals(a)) {
            if (cur().forward()) { pathEdit.setText(cur().path); refresh(); }
        } else if ("selectAll".equals(a)) {
            selectAll();
        } else if ("newFolder".equals(a)) {
            askName("新建文件夹", "", new NameCb() {
                public void onName(String n) {
                    final String target = Util.join(cur().path, n);
                    runFs("正在创建…", new FsTask() {
                        public String run() { return Fs.mkdir(target); }
                    });
                }
            });
        } else if ("newFile".equals(a)) {
            askName("新建文件", "", new NameCb() {
                public void onName(String n) {
                    final String target = Util.join(cur().path, n);
                    runFs("正在创建…", new FsTask() {
                        public String run() { return Fs.createFile(target); }
                    });
                }
            });
        } else if ("extract".equals(a)) {
            extractArchiveToParent();
        } else if ("openWith".equals(a)) {
            openWithChooser();
        } else if ("favorite".equals(a)) {
            favoriteSelection();
        } else if ("share".equals(a)) {
            shareSelection();
        } else if ("rename".equals(a)) {
            final List<String> s = selection();
            if (s.size() != 1) { toast("请先长按选中 1 个项目"); return; }
            final String old = s.get(0);
            final boolean isDir = new java.io.File(old).isDirectory();
            askName("重命名", Util.base(old), true, isDir, new NameCb() {
                public void onName(String n) {
                    final String dst = Util.join(Util.parent(old), n);
                    runFs("正在重命名…", new FsTask() {
                        public String run() { return Fs.rename(old, dst); }
                    });
                }
            });
        } else if ("delete".equals(a)) {
            final List<String> s = selection();
            if (s.isEmpty()) { toast("请先长按选中要删除的项目"); return; }
            new AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("将删除 " + s.size() + " 个项目" + (Fs.rootMode ? "（Root 模式，不可恢复）" : "") + "：\n\n" + joinNames(s))
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            final DeleteProgress prog = new DeleteProgress("正在删除",
                                    Fs.rootMode ? "正在删除（Root，可取消）…" : "正在统计大小…");
                            prog.isRoot = Fs.rootMode;
                            runFs("正在删除…", new FsTask() {
                                public String run() {
                                    if (prog.cancelled) return null;   // 排队期间已取消
                                    if (Fs.rootMode) {
                                        // Root：rm -rf 无法逐项计数，按顶层条目数作分母；
                                        // 取消经 Fs.cancelRootCommand 中断 su 的 rm -rf（0~250ms 内生效）。
                                        prog.configure(s.size());
                                        StringBuilder err = null;
                                        for (String p : s) {
                                            if (prog.cancelled) break;
                                            String e = Fs.delete(p, Fs.BULK_TIMEOUT_MS);
                                            if (e != null) {
                                                if (err == null) err = new StringBuilder();
                                                err.append(p).append("：").append(e).append("\n");
                                                break;
                                            }
                                            prog.done++;
                                        }
                                        return err == null ? null : err.toString().trim();
                                    }
                                    // 本地：先递归统计总条目数 → 确定性进度；再带计数器删除（可取消）
                                    long total = 0;
                                    for (String p : s) total += countItems(new File(p));
                                    prog.configure(total);
                                    if (prog.cancelled) return null;
                                    StringBuilder err = null;
                                    for (String p : s) {
                                        if (prog.cancelled) break;
                                        if (!delTree(new File(p), prog)) {
                                            if (err == null) err = new StringBuilder();
                                            err.append(p).append("：删除失败（权限不足或文件被占用）\n");
                                            break;
                                        }
                                    }
                                    return err == null ? null : err.toString().trim();
                                }
                            }, prog);
                        }
                    }).show();
        } else if ("copy".equals(a) || "cut".equals(a)) {
            List<String> s = selection();
            if (s.isEmpty()) { toast("请先长按选中项目"); return; }
            clipboard.clear(); clipboard.addAll(s);
            clipboardCut = "cut".equals(a);
            toast((clipboardCut ? "已剪切 " : "已复制 ") + s.size() + " 项，切到目标目录后点「粘贴」");
            clearSel();
        } else if ("paste".equals(a)) {
            if (clipboard.isEmpty()) { toast("剪贴板为空，请先复制或剪切"); return; }
            if (isArchivePath(cur().path)) { toast("压缩包内不支持粘贴，请先退到设备目录"); return; }
            final ArrayList<String> src = new ArrayList<String>(clipboard);
            final boolean cut = clipboardCut;
            Dbg.log("PASTE", "开始 dst=" + cur().path + " cut=" + cut
                    + " 条目数=" + src.size() + " src=" + Dbg.clip(src.toString(), 1200));
            // 统一显示进度对话框（进度条 + 取消）：压缩包来源监视临时解压目录；纯本地粘贴
            // 统计源项总大小为分母、监视目标目录增量。取消可中断 root 的 cp/mv 并停止剩余项。
            boolean hasArc = false;
            for (String s : src) { if (isArchivePath(s)) { hasArc = true; break; } }
            final boolean fArc = hasArc;   // 匿名类内使用需 effectively final
            final ExtractProgress prog = showExtractProgress("正在粘贴",
                    fArc ? "正在从压缩包解压文件…" : "正在统计大小…");
            if (!fArc) prog.setVerb("已复制");
            runFs("正在粘贴…", new FsTask() {
                public String run() {
                    if (prog != null && prog.cancelled) return null;   // 排队期间已取消：不启动
                    // 纯本地粘贴：分母=源项总大小（文件夹递归累加），基线=目标目录当前大小，
                    // 轮询目标目录增量即「已复制字节」。在 io 线程统计，统计完切确定性进度条。
                    if (!fArc) {
                        File dstDir = new File(cur().path);
                        long total = 0;
                        for (String p : src) {
                            File f = new File(p);
                            total += f.isDirectory() ? dirSize(f) : f.length();
                        }
                        prog.configure(total, dirSize(dstDir), dstDir);
                        if (prog.cancelled) return null;   // 统计/配置期间已取消
                    }
                    // 压缩包来源：按真实压缩包路径分组，每组一次 7z 批量解压到暂存目录
                    // （插件侧一次列表 + 一次 su 分批 x，文件夹展开成全部后代文件、空目录 mkdir）
                    // 暂存目录放在目标目录下：root 写 /sdcard 是「解压」按钮已验证的可靠路径，
                    // 应用 cache 目录对 root 进程存在 SELinux / 命名空间不确定性；失败再回退 cache。
                    Map<String, File> tmpByArchive = new LinkedHashMap<String, File>();
                    try {
                        for (String p : src) {
                            if (!isArchivePath(p)) continue;
                            String[] ap = parseArchive(p);
                            if (!tmpByArchive.containsKey(ap[1])) {
                                File tmp = new File(cur().path, ".7zcopy_" + System.nanoTime());
                                if (Fs.mkdir(tmp.getAbsolutePath()) != null) {
                                    tmp = new File(getCacheDir(), "arx_" + System.nanoTime());
                                    if (!tmp.mkdirs()) return "无法创建临时解压目录";
                                }
                                tmpByArchive.put(ap[1], tmp);
                            }
                        }
                        for (Map.Entry<String, File> en : tmpByArchive.entrySet()) {
                            File t = en.getValue();
                            Dbg.log("PASTE", "分组 archive=" + en.getKey()
                                    + " tmp=" + t.getAbsolutePath()
                                    + " exists=" + t.exists() + " canWrite=" + t.canWrite());
                        }
                        // 进度分母：各压缩包「选中条目解压后大小」之和；监视所有临时解压目录
                        if (prog != null) {
                            long total = 0;
                            for (String arc : tmpByArchive.keySet()) {
                                ArrayList<String> sel = new ArrayList<String>();
                                for (String p : src) {
                                    if (!isArchivePath(p)) continue;
                                    String[] ap = parseArchive(p);
                                    if (ap[1].equals(arc)) sel.add(ap[2]);
                                }
                                long s = pluginMgr.contentSize(arc, sel);
                                Dbg.log("PASTE", "contentSize(" + arc + ") 选中=" + sel.size() + " -> " + s);
                                if (s > 0) total += s;
                            }
                            File[] dirs = tmpByArchive.values().toArray(new File[0]);
                            prog.configure(total, 0, dirs);   // 临时目录是新建空目录，基线为 0
                        }
                        if (prog != null && prog.cancelled) return null;   // 估算阶段已取消：不启动解压
                        for (Map.Entry<String, File> en : tmpByArchive.entrySet()) {
                            if (prog != null && prog.cancelled) return null;   // 多包场景：取消后不继续下一个包
                            ArrayList<String> inners = new ArrayList<String>();
                            for (String p : src) {
                                if (!isArchivePath(p)) continue;
                                String[] ap = parseArchive(p);
                                if (ap[1].equals(en.getKey())) inners.add(ap[2]);
                            }
                            Dbg.log("PASTE", "调用 extractBatch archive=" + en.getKey()
                                    + " outDir=" + en.getValue().getAbsolutePath()
                                    + " inners(" + inners.size() + ")=" + Dbg.clip(inners.toString(), 1200));
                            String be = pluginMgr.extractBatch(en.getKey(), inners,
                                    en.getValue().getAbsolutePath());
                            Dbg.log("PASTE", "extractBatch 返回 -> "
                                    + (be == null ? "OK" : Dbg.clip(be, 800)));
                            if (be != null) return be;
                        }
                        if (prog != null && fArc) prog.markExtractDone();   // 解压完成，冻结进度，进入「复制到目标」阶段（仅压缩包分支有解压阶段）
                        if (prog != null && prog.cancelled) return null;   // 解压完成但已取消：不把半成品复制到目标
                        final AtomicBoolean alwaysOverwrite = new AtomicBoolean(false);
                        for (String p : src) {
                            if (prog != null && prog.cancelled) return null;   // 复制途中取消：停止复制剩余项
                            // 目标名：压缩包项取内层路径的最后一段（Util.base 对 archive:// 虚拟路径
                            // 会把「压缩包名#顶层内路径」整体当名字，导致复制出 temp_x.7z#docs 这种名字）
                            String name = Util.base(p);
                            if (isArchivePath(p)) {
                                String[] apn = parseArchive(p);
                                int si = apn[2].lastIndexOf('/');
                                name = si >= 0 ? apn[2].substring(si + 1) : apn[2];
                                if (name.isEmpty()) name = Util.base(apn[1]);   // 选中压缩包根：用压缩包文件名
                            }
                            String dst = Util.join(cur().path, name);
                            boolean overwrite = false;
                            if (Fs.exists(dst)) {
                                // 同名冲突：覆盖 / 重命名 / 跳过（可勾选「始终覆盖」对后续同名项不再询问）
                                if (alwaysOverwrite.get()) {
                                    overwrite = true;
                                } else {
                                    ConflictChoice cc = askPasteConflict(name, alwaysOverwrite);
                                    if (cc.action == ConflictChoice.SKIP) continue;   // 用户选择跳过
                                    if (cc.action == ConflictChoice.RENAME) {
                                        name = cc.newName;
                                        dst = Util.join(cur().path, name);
                                        overwrite = false;   // 已重命名到空闲名，直接写入不覆盖
                                    } else {
                                        overwrite = true;   // 覆盖
                                    }
                                }
                            }
                            if (overwrite) {
                                String de = Fs.delete(dst, Fs.BULK_TIMEOUT_MS);   // 覆盖前先删除目标
                                if (de != null) return "无法覆盖 " + name + "：" + de;
                            }
                            String e;
                            if (isArchivePath(p)) {
                                // 源来自压缩包：从暂存目录搬移（文件 / 文件夹均支持，保留包内结构；
                                // 压缩包只读，cut 退化为复制）
                                String[] ap = parseArchive(p);
                                File from = new File(tmpByArchive.get(ap[1]), ap[2]);
                                File tmpRoot = tmpByArchive.get(ap[1]);
                                File[] kids = tmpRoot == null ? null : tmpRoot.listFiles();
                                if (!from.exists()) {
                                    // 兜底：部分 7z 对反斜杠路径可能仍解成字面名文件
                                    File lit = new File(tmpByArchive.get(ap[1]), ap[2].replace("/", "\\"));
                                    if (lit.isFile()) from = lit;
                                    else {
                                        Dbg.log("PASTE", "解压结果缺失 name=" + name
                                                + " inner=" + ap[2] + " 字面名兜底也不存在"
                                                + " tmpRoot子项=" + (kids == null ? "null"
                                                    : java.util.Arrays.toString(kids)));
                                        return "解压结果缺失：" + name;
                                    }
                                }
                                // 暂存目录就建在目标目录下、与目标同一文件系统：用 mv 原子搬移
                                // 代替 cp -r 全量复制（上万小文件 cp 在 FUSE 上要几十秒，正是
                                // Root 会话超时与进度超出的根源），mv 是 O(1) 重命名、瞬时完成。
                                // 跨分区等罕见情况 mv 会退化为复制，失败且源仍在时回退 cp。
                                e = Fs.rename(from.getAbsolutePath(), dst, Fs.BULK_TIMEOUT_MS);
                                if (e != null && from.exists())
                                    e = Fs.copy(from.getAbsolutePath(), dst, Fs.BULK_TIMEOUT_MS);
                            } else if (cut) {
                                e = Fs.rename(p, dst);
                                if (e != null) e = Fs.copy(p, dst, Fs.BULK_TIMEOUT_MS);
                            } else {
                                e = Fs.copy(p, dst, Fs.BULK_TIMEOUT_MS);
                            }
                            if (e != null) return e;
                        }
                        return null;
                    } finally {
                        // 无论成败都清理临时解压目录
                        for (File t : tmpByArchive.values())
                            Fs.delete(t.getAbsolutePath(), Fs.BULK_TIMEOUT_MS);
                    }
                }
                public void after() { clipboard.clear(); }
            }, prog);
        } else if ("info".equals(a)) {
            showInfo();
        } else if ("home".equals(a)) {
            gotoPath(homePath());
        } else if ("rootDir".equals(a)) {
            gotoPath("/");
        } else if ("search".equals(a)) {
            toggleSearch();
        } else if ("viewMode".equals(a)) {
            toggleViewMode();
        } else if ("sort".equals(a)) {
            toggleSortDropdown();
        } else if ("newItem".equals(a)) {
            toggleAddDropdown();
        } else if ("fav".equals(a)) {
            toggleFavDropdown();
        }
    }

    /** 粘贴冲突的三选一结果：覆盖 / 跳过 / 重命名（RENAME 时 newName 有效）。 */
    private static final class ConflictChoice {
        static final int OVERWRITE = 0;
        static final int SKIP = 1;
        static final int RENAME = 2;
        int action = SKIP;
        String newName = null;
    }

    /**
     * 在工作线程里弹出「粘贴冲突」询问并阻塞，直到用户选择，支持：
     *   · 覆盖（可勾选「始终覆盖」对后续同名项不再询问）
     *   · 重命名（弹出输入框，预填「文件名(不含后缀)_1.后缀」供确认/修改；名称仍冲突则不允许确认）
     *   · 跳过
     */
    private ConflictChoice askPasteConflict(final String name, final AtomicBoolean alwaysOverwrite) {
        final ConflictChoice choice = new ConflictChoice();
        final CountDownLatch latch = new CountDownLatch(1);
        final String dir = cur().path;
        ui.post(new Runnable() {
            public void run() {
                ScrollView sv = new ScrollView(MainActivity.this);
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = Util.dp(MainActivity.this, 16);
                box.setPadding(pad, pad, pad, pad);
                TextView msg = new TextView(MainActivity.this);
                msg.setText("已存在同名文件：\n" + name + "\n\n覆盖、重命名还是跳过？");
                msg.setTextSize(15);
                msg.setTextColor(Ui.TEXT);
                box.addView(msg);
                final CheckBox cb = new CheckBox(MainActivity.this);
                cb.setText("始终覆盖（对剩余同名文件不再询问）");
                cb.setTextSize(14);
                cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
                cb.setTextColor(Ui.TEXT);
                box.addView(cb);
                sv.addView(box);

                final AlertDialog d = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("粘贴冲突")
                        .setView(sv)
                        .setCancelable(false)
                        .setPositiveButton("覆盖", null)
                        .setNeutralButton("重命名", null)
                        .setNegativeButton("跳过", null)
                        .show();
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        choice.action = ConflictChoice.OVERWRITE;
                        if (cb.isChecked()) alwaysOverwrite.set(true);
                        d.dismiss();
                    }
                });
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        choice.action = ConflictChoice.SKIP;
                        d.dismiss();
                    }
                });
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        // 直接自动重命名：文件名(不含后缀)_1，若仍冲突则递增 _2、_3…，不再弹输入框
                        choice.action = ConflictChoice.RENAME;
                        choice.newName = autoRenameSuggestion(name, dir);
                        d.dismiss();
                    }
                });
                d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface di) {
                        if (!d.isShowing()) latch.countDown();
                    }
                });
            }
        });
        try { latch.await(); } catch (InterruptedException e) { /* 被打断按跳过处理 */ }
        return choice;
    }

    /**
     * 自动生成不冲突的重命名名：文件名(不含后缀)_<i>，从 1 开始；
     * 若该名仍已存在则递增 i（_1 → _2 → …），直到找到空闲名。
     */
    private static String autoRenameSuggestion(String name, String dir) {
        int i = 1;
        String cand;
        do {
            cand = renameSuggestion(name, i);
            i++;
        } while (Fs.exists(Util.join(dir, cand)) && i < 10000);
        return cand;
    }

    /**
     * 按索引生成重命名建议名：保留后缀，文件名(不含后缀)后追加 _<i>；无后缀则整名后追加 _<i>。
     * 例：report.txt → report_1.txt / report_2.txt；photo → photo_1 / photo_2。
     */
    private static String renameSuggestion(String name, int i) {
        int dot = name.lastIndexOf('.');
        // 仅当存在「真正的」扩展名（非首字符的 . 隐藏文件，且不是末尾的点）时才拆分后缀
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(0, dot) + "_" + i + name.substring(dot);
        }
        return name + "_" + i;
    }

    private String joinNames(List<String> s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(s.size(), 6); i++) b.append("· ").append(displayName(s.get(i))).append('\n');
        if (s.size() > 6) b.append("… 等 ").append(s.size()).append(" 项");
        return b.toString();
    }

    /** 条目显示名：压缩包虚拟路径取内层最后一段（避免「压缩包名#内路径」混入名字），普通路径取叶子名 */
    private static String displayName(String p) {
        if (!isArchivePath(p)) return Util.base(p);
        String inner = parseArchive(p)[2];
        int i = inner.lastIndexOf('/');
        return i >= 0 ? inner.substring(i + 1) : inner;
    }

    // ==================== 设置应用 ====================

    /** 读取持久化设置并应用：固定每行列数、图标尺寸、列表名称字号、图标主题、Root、隐藏文件。 */
    private void applySettings() {
        Ui.loadFontScale(this);   // 设置页可能调整了界面字体大小，同步全局缩放
        cols = Prefs.columns(this);
        textLv = Prefs.textLevel(this);

        rootTag.setTextColor(Ui.PRIMARY);
        searchScopeBtn.setTextColor(Ui.PRIMARY);
        for (Button b : toolBtns) b.setTextColor(Ui.PRIMARY);
        for (Button b : cmdBtns) b.setTextColor(Ui.PRIMARY);
        for (Button b : bottomBtns) b.setTextColor(Ui.PRIMARY);
        for (Button b : addrBtns) b.setTextColor(Ui.PRIMARY);
        updateSortBtn();   // 着重色可能变化，重绘排序按钮右侧小三角
        updateAddBtn();    // 同步新建按钮底色 / 长按提示
        updateFavBtn();    // 同步收藏按钮底色 / 长按提示
        rebuildTabs();   // 标签底部着重色条 / 🔒 锁颜色随强调色刷新
        applyTabLockUi();   // 同步锁定态：地址栏禁用、导航按钮保持可用（长按复制路径）

        // 网格 / 列表视图：列数、图标尺寸、适配器布局统一在这里应用
        applyViewModeToGrid();
        adapter.setFont(Prefs.listFont(this));

        titleTv.setTextSize(Ui.sp(textLv, 16, 18, 20));
        rootTag.setTextSize(Ui.sp(textLv, 9, 10, 11));
        pathEdit.setTextSize(Ui.sp(textLv, 12, 13, 15));
        empty.setTextSize(Ui.sp(textLv, 13, 14, 16));
        status.setTextSize(Ui.sp(textLv, 10, 11, 13));
        for (Button b : toolBtns) b.setTextSize(Ui.sp(textLv, 11, 12, 14));
        for (Button b : cmdBtns) b.setTextSize(Ui.sp(textLv, 11, 12, 14));
        for (Button b : bottomBtns) b.setTextSize(Ui.sp(textLv, 11, 12, 14));
        for (Button b : addrBtns) b.setTextSize(Ui.sp(textLv, 11, 12, 14));

        Fs.showHidden = Prefs.showHidden(this);
        swipeTabs = Prefs.swipeTabs(this);
        swipeMinDist = Prefs.swipeDist(this);
        swipeMinVel = Prefs.swipeVel(this);
        // 图标主题：从设置页返回或冷启动时注入绘制器（变化会清缓存）
        IconFactory.setTheme(Prefs.iconTheme(this));
    }

    /** 锁定标签：地址栏禁用（变灰、不可输入、清焦点），但「前往 / 上级」按钮始终保持可用。
     *  按钮不禁用的原因：锁定时仍需长按「前往」把当前路径复制到剪贴板，而 setEnabled(false)
     *  会让长按监听器失效。按钮点击的越界跳转仍由 gotoPath → canEnter 静默拦截，
     *  保证「可用」与「不破坏锁定约束」（只能在锁定根目录及其子目录内活动）两者兼得。 */
    private void applyTabLockUi() {
        if (tabs.isEmpty()) return;
        boolean locked = cur().locked;
        pathEdit.setEnabled(!locked);
        pathEdit.setTextColor(locked ? Ui.HINT : Ui.TEXT);
        if (locked) {
            pathEdit.setFocusable(false);
            pathEdit.setFocusableInTouchMode(false);
            pathEdit.clearFocus();
        }
        // 导航按钮始终可用：锁定态下长按「前往」复制路径仍有效（enabled=false 会吞掉长按）。
        if (addrBtns.size() >= 1) addrBtns.get(0).setEnabled(true);   // 前往
        if (addrBtns.size() >= 2) addrBtns.get(1).setEnabled(true);   // 上级
        applyArchiveUi();   // 切换/刷新目录后，按当前标签是否 archive:// 同步工具栏与列表背景
    }

    /** 按「当前标签是否为 archive:// 视图」重建工具栏（压缩包版去掉新建/收藏、加解压），
     *  并把文件列表背景切到 #dddddd（压缩包视图）或白底（普通视图）。 */
    private void applyArchiveUi() {
        if (tabs.isEmpty() || grid == null || toolbar == null) return;
        boolean archive = isArchivePath(cur().path);
        grid.setBackgroundColor(archive ? 0xFFFDF5D3 : Color.WHITE);   // 压缩包视图：淡黄 FDF5D3
        if (archive != toolbarArchiveMode) {
            toolbarArchiveMode = archive;
            rebuildToolbar();
        }
    }

    /** 根据 toolbarArchiveMode 重建顶部工具栏按钮：普通视图=完整工具，压缩包视图=去新建+解压；
     *  收藏按钮两个视图都有（压缩包内点收藏项会新开标签跳到真实目录）。 */
    private void rebuildToolbar() {
        if (toolbar == null) return;
        closeSortDropdown();   // 工具栏重建（进出压缩包视图等）时先收起所有下拉菜单
        closeAddDropdown();
        closeFavDropdown();
        toolbar.removeAllViews();
        toolBtns.clear();
        String[] names, acts;
        if (toolbarArchiveMode) {
            names = new String[]{"后退", "前进", "视图", "全选", "解压", "根目录", "内部存储", "搜索", "排序", "收藏"};
            acts  = new String[]{"back", "fwd", "viewMode", "selectAll", "extract", "rootDir", "home", "search", "sort", "fav"};
        } else {
            // 原「新建文件夹 / 新建文件」两个按钮合并为一枚「新建」图标，两项收进其下拉菜单
            names = new String[]{"后退", "前进", "视图", "全选", "新建", "根目录", "内部存储", "搜索", "排序", "收藏"};
            acts  = new String[]{"back", "fwd", "viewMode", "selectAll", "newItem", "rootDir", "home", "search", "sort", "fav"};
        }
        for (int i = 0; i < names.length; i++) {
            if (i > 0) addVLine(toolbar);
            final String a = acts[i];
            Button b = Ui.flat(this, names[i], textLv);
            b.setTextColor(Ui.PRIMARY);   // 与 applySettings 对工具栏按钮上色一致（teal 强调色）
            // 所有工具栏按钮统一收紧左右内边距：图标间距从 10+1+10=21dp 降到 4+1+4=9dp
            int thp = Util.dp(this, 4), tvp = Util.dp(this, 6);
            b.setPadding(thp, tvp, thp, tvp);
            if ("viewMode".equals(a)) {
                viewModeBtn = b;
                updateViewModeBtn();   // 按当前模式设置图标 / 长按提示
            } else if ("sort".equals(a)) {
                sortBtn = b;
                updateSortBtn();       // 排序图标 + 右侧小三角 + 当前排序方式提示
            } else if ("fav".equals(a)) {
                favBtn = b;
                updateFavBtn();        // 收藏星标（图标 + 右侧小三角）
            } else if ("newItem".equals(a)) {
                addBtn = b;
                updateAddBtn();        // 新建图标（图标 + 右侧小三角）
            } else if ("extract".equals(a)) {
                // 解压图标：用矢量 drawable（assets/ui 无对应 png），否则回退为「解压」文字
                b.setText("");
                try {
                    android.graphics.drawable.Drawable ed = getResources().getDrawable(R.drawable.ic_extract);
                    int px = Util.dp(this, ToolbarIcons.ICON_DP);
                    ed.setBounds(0, 0, px, px);
                    b.setCompoundDrawables(ed, null, null, null);
                } catch (Throwable ignored) {
                    b.setText("解压");
                }
                b.setContentDescription("解压");
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { toast("解压"); return true; }
                });
            } else {
                iconBtn(b, names[i], acts[i]);
            }
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { onAction(a); }
            });
            toolbar.addView(b);
            toolBtns.add(b);
        }
    }

    /** 视图切换按钮：图标表示「点一下会切到的视图」，随当前着重色染色；长按显示名称。 */
    private void updateViewModeBtn() {
        if (viewModeBtn == null) return;
        final boolean list = Prefs.viewMode(this) == Prefs.VIEW_LIST;
        final String label = list ? "切换为网格视图" : "切换为列表视图";
        android.graphics.drawable.Drawable d = ToolbarIcons.get(this, list ? "viewGrid" : "viewList");
        if (d != null) {
            d = d.mutate();
            d.setColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN);
            viewModeBtn.setText("");
            viewModeBtn.setCompoundDrawables(d, null, null, null);
        } else {
            viewModeBtn.setText(list ? "网格" : "列表");
        }
        viewModeBtn.setContentDescription(label);
        viewModeBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { toast(label); return true; }
        });
    }

    /** 按当前视图模式设置列数、图标尺寸与适配器布局；从设置页返回 / 切换视图时共用。 */
    private void applyViewModeToGrid() {
        boolean list = Prefs.viewMode(this) == Prefs.VIEW_LIST;
        gridLm.setSpanCount(list ? 1 : cols);
        int iconPx;
        if (list) {
            iconPx = Util.dp(this, 36);   // 列表行固定小图标，不随「每行列数」放大
        } else {
            int w = getResources().getDisplayMetrics().widthPixels;
            int colPx = Math.max(1, w / cols);
            iconPx = (int) (colPx * 0.52f);
            iconPx = Math.max(Util.dp(this, 26), Math.min(Util.dp(this, 72), iconPx));
        }
        adapter.setSizes(iconPx, Prefs.listNameSp(this));
        adapter.setViewMode(list);
        updateViewModeBtn();   // 着重色可能刚变化，重新染色
    }

    /** 工具栏「视图」按钮：网格 ⇄ 列表，写入设置；不重新列目录，尽量保持当前位置。 */
    private void toggleViewMode() {
        final int next = Prefs.viewMode(this) == Prefs.VIEW_LIST ? Prefs.VIEW_GRID : Prefs.VIEW_LIST;
        Prefs.setViewMode(this, next);
        int pos = firstVisible();
        applyViewModeToGrid();
        int n = adapter.getCount();
        if (n > 0) gridLm.scrollToPositionWithOffset(Math.min(pos, n - 1), 0);
        Dbg.log("ACTION", "viewMode -> " + (next == Prefs.VIEW_LIST ? "列表" : "网格"));
    }

    // ==================== 排序（工具栏下拉菜单）====================

    private boolean isSortDropdownOpen() {
        return sortPopup != null && sortPopup.isShowing();
    }

    /** 排序按钮：左侧排序图标、右侧小三角；展开时加浅灰底；长按提示当前排序方式。 */
    private void updateSortBtn() {
        if (sortBtn == null) return;
        final int mode = Prefs.sortMode(this);
        final String label = "排序：" + (mode == Prefs.SORT_TIME ? "时间" : "字母");
        // 收紧尺寸：图标略小于其它按钮、三角更小、左右内边距减半，
        // 让「图标 + 三角」的整枚按钮与单图标按钮差不多宽，不再显得偏大。
        int hp = Util.dp(this, 4), vp = Util.dp(this, 6);
        sortBtn.setPadding(hp, vp, hp, vp);
        android.graphics.drawable.Drawable icon = ToolbarIcons.get(this, "sort", 20);
        if (icon != null) {
            sortBtn.setText("");
            sortBtn.setCompoundDrawables(icon, null, triangleDrawable(), null);
            sortBtn.setCompoundDrawablePadding(Util.dp(this, 1));
        } else {
            sortBtn.setText("排序 ▾");   // 图标资源缺失兜底
        }
        sortBtn.setContentDescription(label);
        sortBtn.setBackgroundColor(isSortDropdownOpen() ? 0xFFEEEEEE : Color.TRANSPARENT);
        sortBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { toast(label); return true; }
        });
    }

    /** 按钮右侧的小三角：纯代码绘制，固定黑色。 */
    private android.graphics.drawable.Drawable triangleDrawable() {
        int w = Util.dp(this, 7), h = Util.dp(this, 5);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(0, 0); path.lineTo(w, 0); path.lineTo(w / 2f, h); path.close();
        c.drawPath(path, p);
        android.graphics.drawable.BitmapDrawable d =
                new android.graphics.drawable.BitmapDrawable(getResources(), bm);
        d.setBounds(0, 0, w, h);
        return d;
    }

    /** 按当前排序方式重建下拉面板两行，当前项打勾并上着重色。 */
    private void rebuildSortDropdown() {
        if (sortDropdown == null) return;
        sortDropdown.removeAllViews();
        int mode = Prefs.sortMode(this);
        addSortItem("字母", Prefs.SORT_NAME, mode == Prefs.SORT_NAME);
        addLine(sortDropdown);   // 两项之间一条分隔线即可，底部不再留线
        addSortItem("时间", Prefs.SORT_TIME, mode == Prefs.SORT_TIME);
    }

    private void addSortItem(final String label, final int mode, boolean checked) {
        TextView tv = new TextView(this);
        tv.setText((checked ? "✓  " : "     ") + label);
        tv.setTextSize(Ui.sp(textLv, 12, 13, 15));
        tv.setTextColor(checked ? Ui.PRIMARY : Ui.TEXT);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(Util.dp(this, 12), Util.dp(this, 9), Util.dp(this, 12), Util.dp(this, 9));
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseSortMode(mode); }
        });
        sortDropdown.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 点排序按钮：在按钮正下方展开 / 收起窄下拉菜单（浮在文件列表之上，不推动列表）。 */
    private void toggleSortDropdown() {
        if (isSortDropdownOpen()) { closeSortDropdown(); return; }
        closeAddDropdown();   // 三个下拉菜单互斥，避免叠在一起
        closeFavDropdown();
        if (sortBtn == null || sortPopup == null) return;
        rebuildSortDropdown();
        int w = Math.max(sortBtn.getWidth(), Util.dp(this, 140));
        sortPopup.setWidth(w);
        sortPopup.showAsDropDown(sortBtn, 0, 0);
        sortDropdown.setAlpha(0f);
        sortDropdown.animate().alpha(1f).setDuration(140).start();
        updateSortBtn();
        Dbg.log("ACTION", "排序下拉菜单展开 width=" + w);
    }

    private void closeSortDropdown() {
        if (!isSortDropdownOpen()) return;
        sortDropdown.animate().cancel();
        sortPopup.dismiss();   // 触发 onDismiss -> updateSortBtn()
    }

    /** 选择排序方式：只重排当前内存列表，不重新读目录，滚动位置保持不变。 */
    private void chooseSortMode(int mode) {
        Prefs.setSortMode(this, mode);
        if (curItems != null && !curItems.isEmpty()) {
            applySort(curItems);
            // 搜索态下网格显示的是搜索结果，不能用目录列表覆盖；只重排内存数据，
            // 退出搜索（或下次列目录）时自然按新方式呈现。
            if (!searchActive) adapter.setData(curItems);
        }
        closeSortDropdown();
        Dbg.log("ACTION", "sortMode -> " + (mode == Prefs.SORT_TIME ? "时间" : "字母"));
    }

    // ==================== 新建（工具栏下拉菜单）====================

    private boolean isAddDropdownOpen() {
        return addPopup != null && addPopup.isShowing();
    }

    /** 新建按钮：纯 add 图标（不加小三角，避免像排序按钮那样偏大）；展开时加浅灰底。 */
    private void updateAddBtn() {
        if (addBtn == null) return;
        int hp = Util.dp(this, 4), vp = Util.dp(this, 6);
        addBtn.setPadding(hp, vp, hp, vp);
        android.graphics.drawable.Drawable icon = ToolbarIcons.get(this, "add", 20);
        if (icon != null) {
            addBtn.setText("");
            addBtn.setCompoundDrawables(icon, null, triangleDrawable(), null);
            addBtn.setCompoundDrawablePadding(Util.dp(this, 1));
        } else {
            addBtn.setText("＋");   // 图标资源缺失兜底
        }
        final String label = "新建：文件夹 / 文件";
        addBtn.setContentDescription(label);
        addBtn.setBackgroundColor(isAddDropdownOpen() ? 0xFFEEEEEE : Color.TRANSPARENT);
        addBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { toast(label); return true; }
        });
    }

    /** 重建新建菜单两项：左侧沿用原工具栏的 newFolder / newFile 图标，右侧文字。 */
    private void rebuildAddDropdown() {
        if (addDropdown == null) return;
        addDropdown.removeAllViews();
        addNewItem("新建文件夹", "newFolder", "newFolder");
        addLine(addDropdown);
        addNewItem("新建文件", "newFile", "newFile");
    }

    private void addNewItem(String label, String iconName, final String act) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(Ui.sp(textLv, 12, 13, 15));
        tv.setTextColor(Ui.TEXT);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(Util.dp(this, 12), Util.dp(this, 9), Util.dp(this, 14), Util.dp(this, 9));
        android.graphics.drawable.Drawable d = ToolbarIcons.get(this, iconName, 20);
        if (d != null) {
            tv.setCompoundDrawables(d, null, null, null);
            tv.setCompoundDrawablePadding(Util.dp(this, 10));
        }
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseNewItem(act); }
        });
        addDropdown.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 点新建按钮：在按钮正下方展开 / 收起窄下拉菜单（浮在文件列表之上）。 */
    private void toggleAddDropdown() {
        if (isAddDropdownOpen()) { closeAddDropdown(); return; }
        closeSortDropdown();   // 三个下拉菜单互斥，避免叠在一起
        closeFavDropdown();
        if (addBtn == null || addPopup == null) return;
        rebuildAddDropdown();
        int w = Math.max(addBtn.getWidth(), Util.dp(this, 160));
        addPopup.setWidth(w);
        addPopup.showAsDropDown(addBtn, 0, 0);
        addDropdown.setAlpha(0f);
        addDropdown.animate().alpha(1f).setDuration(140).start();
        updateAddBtn();
        Dbg.log("ACTION", "新建下拉菜单展开 width=" + w);
    }

    private void closeAddDropdown() {
        if (!isAddDropdownOpen()) return;
        addDropdown.animate().cancel();
        addPopup.dismiss();   // 触发 onDismiss -> updateAddBtn()
    }

    /** 选一项：先收起浮层，再走原来的 onAction 新建流程（弹输入名称对话框），避免窗口叠盖。 */
    private void chooseNewItem(String act) {
        closeAddDropdown();
        onAction(act);
    }

    // ==================== 收藏（工具栏下拉菜单）====================

    private boolean isFavDropdownOpen() {
        return favPopup != null && favPopup.isShowing();
    }

    /** 收藏按钮：纯星标图标（不加三角，与新建按钮同尺寸）；展开时加浅灰底。 */
    private void updateFavBtn() {
        if (favBtn == null) return;
        int hp = Util.dp(this, 4), vp = Util.dp(this, 6);
        favBtn.setPadding(hp, vp, hp, vp);
        android.graphics.drawable.Drawable icon = ToolbarIcons.get(this, "userBookmarks", 20);
        if (icon != null) {
            favBtn.setText("");
            favBtn.setCompoundDrawables(icon, null, triangleDrawable(), null);
            favBtn.setCompoundDrawablePadding(Util.dp(this, 1));
        } else {
            favBtn.setText("★");   // 图标资源缺失兜底
        }
        final String label = "收藏夹";
        favBtn.setContentDescription(label);
        favBtn.setBackgroundColor(isFavDropdownOpen() ? 0xFFEEEEEE : Color.TRANSPARENT);
        favBtn.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { toast(label); return true; }
        });
    }

    /** 每次展开都重新读收藏夹，保证与抽屉里的顺序 / 编辑结果一致。 */
    private void rebuildFavDropdown() {
        if (favDropdown == null) return;
        favDropdown.removeAllViews();
        ArrayList<String> favs = Prefs.favorites(this);
        if (favs.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("（暂无收藏，可在右上角菜单收藏当前目录）");
            tv.setTextSize(Ui.sp(textLv, 11, 12, 13));
            tv.setTextColor(Ui.HINT);
            tv.setPadding(Util.dp(this, 12), Util.dp(this, 10), Util.dp(this, 12), Util.dp(this, 10));
            favDropdown.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (int i = 0; i < favs.size(); i++) {
            if (i > 0) addLine(favDropdown);
            addFavItem(favs.get(i));
        }
    }

    /** 一条收藏：左侧星标小图标 + 名称 / 完整路径两行；点击跳到该路径。 */
    private void addFavItem(final String path) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Util.dp(this, 10), Util.dp(this, 3), Util.dp(this, 10), Util.dp(this, 3));

        android.widget.ImageView iv = new android.widget.ImageView(this);
        Bitmap fb = IconFactory.folder(Util.dp(this, 18));
        if (fb != null) iv.setImageBitmap(fb);
        row.addView(iv, new LinearLayout.LayoutParams(Util.dp(this, 18), Util.dp(this, 18)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = Util.dp(this, 9);
        TextView name = new TextView(this);
        name.setText(labelOf(path));
        name.setTextSize(Ui.sp(textLv, 12, 13, 14));
        name.setTextColor(Ui.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub = new TextView(this);
        sub.setText(path);
        sub.setTextSize(Ui.sp(textLv, 9, 10, 11));
        sub.setTextColor(Ui.HINT);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        info.addView(sub);
        row.addView(info, ilp);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseFavorite(path); }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { toast(path); return true; }
        });
        favDropdown.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 点收藏按钮：展开 / 收起；菜单高度按内容测量并封顶到半屏，多的话内部滚动。 */
    private void toggleFavDropdown() {
        if (isFavDropdownOpen()) { closeFavDropdown(); return; }
        closeSortDropdown();   // 三个下拉菜单互斥，避免叠在一起
        closeAddDropdown();
        if (favBtn == null || favPopup == null) return;
        rebuildFavDropdown();
        int w = Math.max(favBtn.getWidth(), Util.dp(this, 220));
        favPopup.setWidth(w);
        favDropdown.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentH = favDropdown.getMeasuredHeight() + Util.dp(this, 8);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
        favPopup.setHeight(Math.min(contentH, maxH));
        favPopup.showAsDropDown(favBtn, 0, 0);
        favDropdown.setAlpha(0f);
        favDropdown.animate().alpha(1f).setDuration(140).start();
        updateFavBtn();
        Dbg.log("ACTION", "收藏下拉菜单展开 width=" + w + " height=" + Math.min(contentH, maxH));
    }

    private void closeFavDropdown() {
        if (!isFavDropdownOpen()) return;
        favDropdown.animate().cancel();
        favPopup.dismiss();   // 触发 onDismiss -> updateFavBtn()
    }

    /** 选一条收藏：先收起浮层，再按抽屉同样的规则跳转（锁定标签 / 压缩包内会新开标签）。 */
    private void chooseFavorite(String path) {
        closeFavDropdown();
        openInAvailableTab(path);
        Dbg.log("ACTION", "收藏夹跳转 " + path);
    }

    /**
     * 列目录（普通目录 / 压缩包内）后按当前排序方式重排。
     * 「字母」沿用 FileItem.compareTo（返回项 → 文件夹 → 名称）；
     * 「时间」保留返回项与文件夹优先，其余按修改时间新→旧，同时间按名称。
     */
    private void applySort(java.util.List<FileItem> items) {
        if (items == null || items.isEmpty()) return;
        if (Prefs.sortMode(this) != Prefs.SORT_TIME) {
            java.util.Collections.sort(items);
            return;
        }
        java.util.Collections.sort(items, new java.util.Comparator<FileItem>() {
            public int compare(FileItem a, FileItem b) {
                if (a.parent != b.parent) return a.parent ? -1 : 1;
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                if (a.modified != b.modified) return a.modified > b.modified ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    /** 地址栏放弃焦点：收起光标与键盘，并把焦点状态复位（默认不抢焦点）。
     *  仅在用户直接点地址栏时才会重新获得焦点（见 pathEdit 的 OnTouchListener）。 */
    private void dropAddrFocus() {
        if (!pathEdit.hasFocus()) return;
        pathEdit.setFocusable(false);
        pathEdit.setFocusableInTouchMode(false);
        pathEdit.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(pathEdit.getWindowToken(), 0);
    }

    /** Root 模式由设置开关决定；ROOT 徽标只在授权会话真正就绪后显示。 */
    private void syncRootMode() {
        boolean want = Prefs.rootEnabled(this);
        Fs.rootMode = want;
        rootTag.setVisibility(want && Fs.rootSessionAlive() ? View.VISIBLE : View.GONE);
    }

    /**
     * APP 启动时（且设置里启用了 Root）建立一次持久 su 会话。
     * 这是整个进程生命周期里唯一一次 Root 授权请求；之后列目录、文件操作、
     * 自定义命令全部复用该会话，不再新建 su 进程，也就不再重复申请授权。
     */
    private void warmRoot() {
        rootWarmStarted = true;
        status.setText("正在获取 Root 授权…请在弹窗中允许（只需一次）");
        io.execute(new Runnable() {
            public void run() {
                final boolean ok = Fs.ensureRootSession(30000);
                ui.post(new Runnable() {
                    public void run() {
                        if (MainActivity.this.isFinishing()) return;
                        if (ok) {
                            Fs.rootMode = true;
                            rootTag.setVisibility(View.VISIBLE);
                            toast("Root 已就绪，后续操作不再申请授权");
                        } else {
                            // 授权失败：自动关掉开关切回普通模式，避免后续反复弹窗
                            Prefs.setRootEnabled(MainActivity.this, false);
                            Fs.rootMode = false;
                            rootTag.setVisibility(View.GONE);
                            toast("未获取到 Root 授权，已切回普通模式；可到「设置 → Root 权限」重新开启");
                        }
                        refreshForce();   // Root 模式可能刚切换，即使路径没变也要重列
                    }
                });
            }
        });
    }

    // ==================== 自定义命令 ====================

    /** 每条命令在独立的第二行命令工具栏生成一个按钮；从设置页返回后可动态重建。 */
    private void rebuildCmdButtons() {
        if (cmdBox == null) return;
        cmdBox.removeAllViews();
        cmdBtns.clear();
        List<Prefs.Cmd> list = Prefs.commands(this);
        if (list.isEmpty()) {
            Button b = Ui.flat(this, "＋ 添加命令", textLv);
            b.setTextColor(Ui.HINT);
            iconBtn(b, "添加命令", "addCmd", 16);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
            });
            cmdBox.addView(b);
            cmdBtns.add(b);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) addVLine(cmdBox);
            final Prefs.Cmd cmd = list.get(i);
            Button b = Ui.flat(this, cmd.name, textLv);
            b.setTextColor(Ui.PRIMARY);
            // 命令名必须保留；图标只作小尺寸前缀，避免每条命令看起来一样
            android.graphics.drawable.Drawable cd = ToolbarIcons.get(this, "cmd", 14);
            if (cd != null) {
                b.setCompoundDrawables(cd, null, null, null);
                b.setCompoundDrawablePadding(Util.dp(this, 4));
            }
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { runCmdDirect(cmd); }
            });
            b.setTag(cmd);   // 供 updateCmdEna 按 ena 选项决定是否置灰
            cmdBox.addView(b);
            cmdBtns.add(b);
        }
    }

    /** 点击命令按钮：不再弹「选命令 / 确认预览」两步，直接执行，结果弹窗显示输出。 */
    private void runCmdDirect(final Prefs.Cmd cmd) {
        if (!cmdEnabledByEna(cmd)) {
            toast("该命令当前不可用（请先选中符合条件的文件/文件夹）");
            return;
        }
        final ArrayList<String> targets = new ArrayList<String>();
        List<String> s = selection();
        if (s.isEmpty()) targets.add(cur().path);
        else targets.addAll(s);
        final boolean batch = cmd.body().contains("%F");
        // 指定了 size 选项：用自定义 Dialog（固定尺寸）执行，替代全屏结果窗口
        if (cmd.optSize() != null) {
            runCmdInDialog(cmd, targets, cur().path, batch);
            return;
        }
        // 插件命令：%pid:插件id 表示用指定插件打开 %F 的目标文件（如 %pid:archive7z %F）
        String pid = parsePid(cmd.body());
        if (pid != null) {
            for (String t : targets) {
                openByPluginId(pid, new FileItem(Util.base(t), t, false, 0, 0));
            }
            return;
        }
        executeCmd(cmd, targets, cur().path, batch);
    }

    /** 工具栏命令是否可按当前选中项启用（对应 ena 选项）：1 常启 / 2 仅选中 1 个文件 / 3 选中 1 个以上文件或文件夹（可混合）即启用。 */
    private boolean cmdEnabledByEna(Prefs.Cmd cmd) {
        int ena = cmd.optEna();
        if (ena <= 1) return true;
        List<String> s = selection();
        if (ena == 3) return !s.isEmpty();          // 至少一个（文件/文件夹，可混合）
        if (ena == 2) {                             // 恰好一个且为文件（非文件夹）
            if (s.size() != 1) return false;
            return !isDirectory(s.get(0));
        }
        return true;
    }

    /** 从命令模板提取 %pid:插件id 中的插件 id；不含该占位符则返回 null。 */
    private static String parsePid(String tmpl) {
        if (tmpl == null) return null;
        int i = tmpl.indexOf("%pid:");
        if (i < 0) return null;
        int end = tmpl.indexOf(' ', i);
        end = (end < 0) ? tmpl.length() : end;
        String pid = tmpl.substring(i + 5, end).trim();
        return pid.isEmpty() ? null : pid;
    }

    /** 用指定插件 id 打开一个文件：压缩包插件→进压缩包视图，查看器插件→图片查看器，否则回退普通打开。 */
    /** 用指定插件 id 打开文件（自定义命令 %pid: 走此路径）：无视扩展名/关联，强制用该插件打开。 */
    private void openByPluginId(final String pid, final FileItem it) {
        final String ext = Util.ext(it.name);
        io.execute(new Runnable() {
            public void run() {
                // 强制版本：只按 id + 启用状态匹配，跳过 assocOk 扩展名校验
                PluginManager.ArchiveHandle ah = pluginMgr.archiveByIdForce(pid, it.path);
                if (ah != null) {
                    ui.post(new Runnable() { public void run() { enterArchive(it); }});
                    return;
                }
                PluginManager.ViewerHandle vh = pluginMgr.viewerByIdForce(pid, it.path, "*/*", ext);
                if (vh != null) {
                    final PluginManager.ViewerHandle fvh = vh;
                    ui.post(new Runnable() { public void run() {
                        try { startActivity(fvh.viewIntent); } catch (Throwable t) {}
                    }});
                    return;
                }
                ui.post(new Runnable() { public void run() { openFile(it); }});
            }
        });
    }

    /** 底部栏常驻：自定义命令工具栏始终显示；文件操作按钮那排仅在有选中/可粘贴内容时出现。 */
    private void updateBottomBar() {
        if (bottomBar == null) return;
        boolean hasSel = !tabs.isEmpty() && !cur().sel.isEmpty();
        // 自定义命令工具栏常驻，故底部栏始终可见（tabs 就绪后）
        bottomBar.setVisibility(!tabs.isEmpty() ? View.VISIBLE : View.GONE);
        if (cmdBar != null) cmdBar.setVisibility(!tabs.isEmpty() ? View.VISIBLE : View.GONE);
        // 文件操作按钮那排：仅在有选中/可粘贴时显示；命令栏常驻不受其影响
        if (bottomBox != null)
            bottomBox.setVisibility((hasSel || !clipboard.isEmpty()) ? View.VISIBLE : View.GONE);
        // 无选中/无可粘贴内容时文件按钮置灰；「粘贴」只依赖剪贴板
        for (Button b : bottomBtns) {
            Object act = b.getTag();
            boolean ok = "paste".equals(act) ? !clipboard.isEmpty() : hasSel;
            b.setEnabled(ok);
            b.setTextColor(ok ? Ui.PRIMARY : Ui.HINT);
            b.setAlpha(ok ? 1f : 0.35f);   // 图标按钮禁用时整体变淡
        }
        updateCmdEna();   // 按 ena 选项同步自定义命令按钮的可用/置灰
    }

    /** 按 ena 选项刷新自定义命令按钮的可用/置灰状态（随选中项实时变化）。 */
    private void updateCmdEna() {
        for (Button b : cmdBtns) {
            Object tag = b.getTag();
            if (!(tag instanceof Prefs.Cmd)) continue;   // 「＋ 添加命令」占位按钮忽略
            Prefs.Cmd cmd = (Prefs.Cmd) tag;
            boolean ok = cmdEnabledByEna(cmd);
            b.setEnabled(ok);
            b.setTextColor(ok ? Ui.PRIMARY : Ui.HINT);
            b.setAlpha(ok ? 1f : 0.35f);
        }
    }

    // 命令执行期间最近一次 expandCmd 生成的 MYFM_* 环境变量前缀（export ...;），
    // 供 handleLine 给动态按钮命令加同样的变量上下文；主命令自身也已拼接该前缀。
    private volatile String myfmEnvPrefix = "";

    private String expandCmd(String tmpl, String path, String cwd, List<String> all,
                             boolean asRoot, boolean outputWindow, RootPathMapper mapper) {
        // %f / %n 不带引号：便于直接嵌进 su --mount-master -c '...' 这类带单引号的命令，
        // 否则占位符自带的单引号会与外层引号冲突导致命令解析错误。
        // 路径含空格时请在模板里自行加引号，例如 su --mount-master -c 'ls -l "%f"'。
        // 命令走 Root 会话时，/data/data 等路径自动换成 /data_mirror 真实视图。
        // 结果窗口走「独立会话」，须用本窗口会话自己的命名空间状态做路径映射，与主界面隔离。
        // 压缩包内虚拟路径在设备上没有实体文件，命令无法直接操作；故先解压到 archive_tmp，
        // 再用真实临时路径替换占位符（%f/%d/%n/%p/%F 全部指向解压出的真实文件/目录）。
        String realPath = resolveArchiveTarget(path);
        String realCwd = isArchivePath(cwd) ? archiveTempDir(cwd) : cwd;
        String f, d, p2, fbItem;
        if (asRoot) {
            if (outputWindow) {
                f = mapper.map(realPath);
                d = Util.sq(mapper.map(Util.parent(realPath)));
                p2 = Util.sq(CmdOutputActivity.mapRootPath(realCwd));
            } else {
                f = Fs.rootPath(realPath);
                d = Util.sq(Fs.rootPath(Util.parent(realPath)));
                p2 = Util.sq(Fs.rootPath(realCwd));
            }
        } else {
            f = realPath;
            d = Util.sq(Util.parent(realPath));
            p2 = Util.sq(realCwd);
        }
        String n = Util.base(realPath);
        StringBuilder fb = new StringBuilder();
        for (String x : all) {
            String rx = resolveArchiveTarget(x);
            if (fb.length() > 0) fb.append(' ');
            fb.append(Util.sq(asRoot
                    ? (outputWindow ? mapper.map(rx) : Fs.rootPath(rx)) : rx));
        }
        String out = tmpl.replace("%f", f).replace("%d", d)
                .replace("%n", n).replace("%p", p2);
        out = out.replace("%F", fb.toString());
        // 同步生成本次展开对应的 MYFM_* 环境变量前缀（脚本/自定义命令/按钮通用）。
        // 值沿用与占位符完全相同的映射（Root 视图 / archive 临时解压），保证 $MYFM_* 与 %f/%n 一致。
        // 注意 f 是裸路径（%f 约定不加引号），导出时用 Util.sq 加引号；d/p2 已带引号直接用。
        myfmEnvPrefix = "export MYFM_F=" + Util.sq(f)
                + "; export MYFM_D=" + d
                + "; export MYFM_N=" + Util.sq(n)
                + "; export MYFM_P=" + p2 + "; ";
        return out;
    }

    /** 若 vpath 是压缩包内虚拟路径，解压该条目到 archive_tmp（保留内层目录结构）并返回真实临时文件路径；
     *  否则原样返回。archive:// 根路径（无内层）返回压缩包自身真实路径，便于对整个包执行命令。 */
    private String resolveArchiveTarget(String vpath) {
        if (!isArchivePath(vpath)) return vpath;
        synchronized (cmdArchiveCache) {
            String c = cmdArchiveCache.get(vpath);
            if (c != null) return c;
        }
        String[] a = parseArchive(vpath);
        if (a[1] == null || a[1].isEmpty()) return vpath;
        String inner = (a[2] == null) ? "" : a[2];
        if (inner.isEmpty()) return a[1];   // 压缩包根：直接用压缩包自身真实路径
        int slash = inner.lastIndexOf('/');
        String parent = slash >= 0 ? inner.substring(0, slash) : "";
        String base = slash >= 0 ? inner.substring(slash + 1) : inner;
        File outDir = new File(new File(getCacheDir(), "archive_tmp"),
                a[0] + (parent.isEmpty() ? "" : "/" + parent));
        outDir.mkdirs();
        File outFile = new File(outDir, base);
        try {
            PluginManager.ArchiveHandle h = pluginMgr.getArchiveProvider(a[1], null);
            if (h != null) {
                ParcelFileDescriptor pfd = h.provider.open(a[1], a[2], "");
                if (pfd != null) {
                    InputStream in = new FileInputStream(pfd.getFileDescriptor());
                    FileOutputStream os = new FileOutputStream(outFile);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) >= 0) os.write(buf, 0, n);
                    in.close();
                    os.close();
                    pfd.close();
                    synchronized (cmdArchiveCache) { cmdArchiveCache.put(vpath, outFile.getAbsolutePath()); }
                    return outFile.getAbsolutePath();
                }
            }
        } catch (Exception ex) { /* 解压失败则回退原始虚拟路径 */ }
        return vpath;
    }

    /** 压缩包虚拟目录 → archive_tmp 下对应的真实目录（供 %p 等工作目录占位符使用）。 */
    private String archiveTempDir(String vpath) {
        String[] a = parseArchive(vpath);
        if (a[1] == null || a[1].isEmpty()) return vpath;
        String inner = (a[2] == null) ? "" : a[2];
        File d = new File(new File(getCacheDir(), "archive_tmp"),
                a[0] + (inner.isEmpty() ? "" : "/" + inner));
        d.mkdirs();
        return d.getAbsolutePath();
    }

    /** 命令执行的「输出接收器」：把流式输出 / 动态按钮 / 状态推送到界面（CmdOutputActivity 或 Dialog）。 */
    private interface CmdSink {
        void append(String s);
        void addButton(String label, String cmd);
        void removeButton(String label);   // [mycmd:btn:名称:0]：删除同名按钮
        void setRunningRoot(boolean root);
        void finished();
        void exit();                  // 收到 [mycmd:exit]：仅标记命令结束，不自动关闭窗口/对话框
        void startLog(String path);   // [mycmd:log]：增量轮询该文件作为日志输出
        void setTitle(String t);      // [mycmd:title:标题文本]：动态改变输出窗口顶栏标题
        void setDebug(boolean d);     // [mycmd:debug:1/0]：详细模式开关（显示/隐藏装饰性提示符）
        boolean isDebug();            // 读取当前详细模式开关
    }
    /** 命令执行的「流执行器」：封装 Root 会话的打开 / 流式执行 / 路径映射。 */
    private interface CmdStreamer {
        boolean openRoot(long timeoutMs);
        Fs.ExecResult stream(String script, int timeoutSec, Fs.LineSink sink);
        String mapRootPath(String p);
    }
    /** Root 路径映射器：模板展开时把 /data/data 等换成当前会话可见真实视图。 */
    private interface RootPathMapper { String map(String p); }

    private static final CmdSink ACTIVITY_SINK = new CmdSink() {
        public void append(String s) { CmdOutputActivity.liveAppend(s); }
        public void addButton(String l, String c) { CmdOutputActivity.liveAddButton(l, c); }
        public void removeButton(String l) { CmdOutputActivity.liveRemoveButton(l); }
        public void setRunningRoot(boolean r) { CmdOutputActivity.setRunningRoot(r); }
        public void finished() { CmdOutputActivity.liveComplete(); }
        public void exit() { CmdOutputActivity.liveExit(); }
        public void startLog(String p) { CmdOutputActivity.startLogPoll(p); }
        public void setTitle(String t) { CmdOutputActivity.liveSetTitle(t); }
        public void setDebug(boolean d) { CmdOutputActivity.setDebug(d); }
        public boolean isDebug() { return CmdOutputActivity.getDebug(); }
    };
    private static final CmdStreamer ACTIVITY_STREAMER = new CmdStreamer() {
        public boolean openRoot(long t) { return CmdOutputActivity.openOutputRoot(t); }
        public Fs.ExecResult stream(String s, int to, Fs.LineSink sk) {
            return CmdOutputActivity.streamOutputRoot(s, to, sk);
        }
        public String mapRootPath(String p) { return CmdOutputActivity.mapRootPath(p); }
    };
    private static final RootPathMapper ACTIVITY_MAPPER = new RootPathMapper() {
        public String map(String p) { return CmdOutputActivity.mapRootPath(p); }
    };

    private void executeCmd(final Prefs.Cmd cmd, final List<String> targets,
                            final String cwd, final boolean batch) {
        status.setText("正在执行：" + cmd.name);
        CmdOutputActivity.launchStreaming(this, cmd.name, new Runnable() {
            public void run() {
                execCore(cmd, targets, cwd, batch,
                        ACTIVITY_SINK, ACTIVITY_STREAMER, ACTIVITY_MAPPER);
            }
        });
    }

    /** 命令执行核心：展开模板 → 循环执行（Root 走独立会话流式 / 非 Root 走 shell 流式）→ 推送输出。
     *  输出目标抽象为 sink/streamer/mapper，使 CmdOutputActivity 与自定义 Dialog 共用同一套逻辑。 */
    private void execCore(final Prefs.Cmd cmd, final List<String> targets, final String cwd,
                          final boolean batch, final CmdSink sink, final CmdStreamer streamer,
                          final RootPathMapper mapper) {
        cmdArchiveCache.clear();   // 每次命令运行重新解压，避免复用上一次残留的临时文件映射
        final int timeout = Prefs.cmdTimeout(this);   // 全局执行超时（秒），0 = 不限时
        // 结果窗口使用「独立 Root 会话」（与主界面导航会话隔离）：
        // 首次会弹一次授权，之后复用；即便在此窗口卡死也不波及主界面。
        if (cmd.optRoot() && !streamer.openRoot(30000)) {
            sink.append("未获取到 Root 授权，命令未执行。\n"
                    + "请到「设置 → Root 权限」开启后再运行。\n");
            sink.finished();
            ui.post(new Runnable() {
                public void run() { status.setText("命令未执行：未获取到 Root"); }
            });
            return;
        }
        int times = batch ? 1 : targets.size();
        sink.setRunningRoot(cmd.optRoot());   // 进入「执行中」状态（显示「停止」按钮、禁用命令按钮）
        for (int i = 0; i < times; i++) {
            final String body = expandCmd(cmd.body(), targets.get(i), cwd, targets,
                    cmd.optRoot(), true, mapper);
            // 主命令也注入 MYFM_*（与脚本一致）：$MYFM_F/D/N/P 直接可读，无需写 % 占位符。
            final String script = myfmEnvPrefix + body;
            if (cmd.optPrintOrig() && sink.isDebug()) sink.append("$ " + script + "\n");
            Fs.ExecResult r;
            if (cmd.optRoot()) {
                r = streamer.stream(script, timeout, new Fs.LineSink() {
                    public void line(String line) { handleLine(line, sink); }
                });
            } else {
                r = Fs.shellStream(script, false, timeout, new Fs.LineSink() {
                    public void line(String line) { handleLine(line, sink); }
                });
            }
            if (r.err != null && r.err.trim().length() > 0) {
                sink.append("[stderr] " + r.err.trim() + "\n");
            }
            if (!r.ok()) sink.append("[退出码 " + r.code + "]\n");
            sink.append("\n");
        }
        sink.finished();
        ui.post(new Runnable() {
            public void run() { status.setText("命令执行完毕：" + cmd.name); }
        });
    }

    /** 处理流式输出的一行：识别控制行并路由。自定义命令与脚本共用，保证两者行为一致。
     *  统一前缀 [mycmd: 快速判断：非控制行直接进正文，避免逐行跑多次正则，效率更高。
     *  · `[mycmd:btn:按钮名称]命令`：动态生成按钮，不进正文；
     *  · `[mycmd:btn:按钮名称:0]`：`]` 后为空，删除同名按钮，不进正文；
     *  · `[mycmd:log]<路径>`：启动该公共目录文件的增量日志轮询（本进程直读，不走 Root），不进正文；
     *  · `[mycmd:title:标题文本]`：动态改变输出窗口顶栏标题，不进正文；
     *  · `[mycmd:exit]`：标记命令已结束（窗口不自动关闭，此后可常规关闭）；
     *  · 其余：原样追加到正文。 */
    private void handleLine(String line, CmdSink sink) {
        if (!line.trim().startsWith(CmdOutputActivity.TAG)) {
            sink.append(line + "\n"); return;
        }
        Matcher dbgm = CmdOutputActivity.DEBUG.matcher(line);
        if (dbgm.find() && dbgm.start() == 0) {
            sink.setDebug("1".equals(dbgm.group(1)));   // [mycmd:debug:1] 开，[mycmd:debug:0] 关
            return;
        }
        Matcher dm = CmdOutputActivity.BTN_DEL.matcher(line);
        if (dm.find() && dm.start() == 0) {
            String l = dm.group(1).trim();
            if (!l.isEmpty()) sink.removeButton(l);
            return;
        }
        Matcher m = CmdOutputActivity.BTN.matcher(line);
        if (m.find() && m.start() == 0
                && m.group(1).trim().length() > 0
                && m.group(2).trim().length() > 0) {
            String l = m.group(1).trim();
            String c = m.group(2).trim();
            // 按钮在独立进程执行，拿不到主命令 shell 的变量：把 MYFM_* 的 export 前缀
            // 直接嵌进按钮命令串，按钮里 $MYFM_N/$MYFM_P 等即可读取当前文件/目录。
            if (myfmEnvPrefix.length() > 0 && !c.contains("MYFM_F="))
                c = myfmEnvPrefix + c;
            sink.addButton(l, c);
            return;
        }
        Matcher lm = CmdOutputActivity.LOG.matcher(line);
        if (lm.find() && lm.start() == 0) {
            String p = lm.group(1).trim();
            if (!p.isEmpty()) sink.startLog(p);
            return;
        }
        Matcher tm = CmdOutputActivity.TITLE.matcher(line);
        if (tm.find() && tm.start() == 0) {
            String t = tm.group(1).trim();
            if (t.endsWith("]")) t = t.substring(0, t.length() - 1).trim();   // 兼容末尾可选 ]
            if (!t.isEmpty()) sink.setTitle(t);
            return;
        }
        Matcher em = CmdOutputActivity.EXIT.matcher(line);
        if (em.find() && em.start() == 0) {
            sink.exit();
            return;
        }
        sink.append(line + "\n");
    }

    /** 用自定义 Dialog（固定尺寸）执行命令：有 #options:// size:宽x高 时替代全屏结果窗口。 */
    private void runCmdInDialog(final Prefs.Cmd cmd, final List<String> targets,
                                final String cwd, final boolean batch) {
        final int[] sz = cmd.optSize();
        final int wPx = Util.dp(this, Math.max(80, sz[0]));
        final int hPx = Util.dp(this, Math.max(80, sz[1]));
        final Fs.OutputSession session = cmd.optRoot() ? new Fs.OutputSession() : null;

        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        final Handler uiH = new Handler(Looper.getMainLooper());
        final StringBuilder body = new StringBuilder();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // 顶栏：标题（居中）+ 右上角 X 关闭（间距已收紧）
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Util.dp(this, 8), Util.dp(this, 4), Util.dp(this, 4), Util.dp(this, 4));
        top.setBackgroundColor(Color.WHITE);
        final TextView title = new TextView(this);
        title.setText(cmd.name);
        title.setTextSize(Ui.sp(1, 13, 14, 16));
        title.setTextColor(Ui.TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button closeX = new Button(this);
        closeX.setText("✕");
        closeX.setTextSize(Ui.sp(1, 13, 14, 16));
        closeX.setTextColor(Ui.TEXT);
        closeX.setBackgroundColor(Color.TRANSPARENT);
        closeX.setPadding(Util.dp(this, 4), 0, Util.dp(this, 4), 0);
        top.addView(closeX);
        root.addView(top);
        View tline = Ui.line(this);
        tline.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        root.addView(tline);

        // 输出区：纵向 ScrollView + 等宽 TextView
        final ScrollView sv = new ScrollView(this);
        final TextView tv = new TextView(this);
        tv.setText("");
        tv.setTextSize(Ui.sp(Prefs.cmdTextLevel(this), 11, 12, 14));
        tv.setTextColor(Ui.TEXT);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int vp = Util.dp(this, 10);
        tv.setPadding(vp, vp, vp, vp);
        sv.addView(tv, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 底部：[btn] 按钮区（默认隐藏）+ 居中「确定」按钮
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(Util.dp(this, 8), Util.dp(this, 5), Util.dp(this, 8), Util.dp(this, 5));
        final HorizontalScrollView actScroll = new HorizontalScrollView(this);
        actScroll.setHorizontalScrollBarEnabled(false);
        actScroll.setVisibility(View.GONE);
        final LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER);   // 按钮在行内水平居中（与全屏结果窗口一致）
        actRow.setPadding(Util.dp(this, 4), 0, Util.dp(this, 4), 0);
        actScroll.addView(actRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(actScroll);
        Button ok = Ui.solid(this, "确定", 1);
        bottom.addView(ok);
        root.addView(bottom);

        dlg.setContentView(root);
        dlg.getWindow().setLayout(wPx, hPx);

        // 输出接收器：把流式输出推到 Dialog 的 TextView
        final java.util.ArrayList<Thread> dlgPollers = new java.util.ArrayList<Thread>();
        // 同一日志文件只起一个轮询（脚本重复输出 [mycmd:log] 时避免叠加）
        final java.util.HashSet<String> dlgLogPaths = new java.util.HashSet<String>();
        // holder 用于在 addButton 的 onClick 里把「同一个 sink」传给 runDialogAction，
        // 避免匿名类初始化时自引用自身变量导致 "might not have been initialized"。
        final CmdSink[] sinkHolder = new CmdSink[1];
        final CmdSink sink = new CmdSink() {
            boolean debug = false;     // 详细模式开关：默认 false（精简，隐藏装饰性提示符，结束只显示 ←END）
            boolean exited = false;    // 是否已收到 [mycmd:exit]（用于避免结束标记重复）
            public void append(String s) {
                uiH.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    body.append(s);
                    tv.setText(body.toString());
                    sv.post(new Runnable() { public void run() { sv.fullScroll(View.FOCUS_DOWN); } });
                }});
            }
            public void addButton(final String label, final String c) {
                uiH.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    if (actRow.getChildCount() == 0) actScroll.setVisibility(View.VISIBLE);
                    Button b = Ui.solid(MainActivity.this, label, 1);
                    b.setTag(label);   // 供 [mycmd:btn:名称:0] 删除时按标签定位
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { runDialogAction(dlg, session, body, tv, sv, sinkHolder[0], label, c); }
                    });
                    actRow.addView(b);
                }});
            }
            public void removeButton(final String label) {
                uiH.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    for (int i = actRow.getChildCount() - 1; i >= 0; i--) {
                        if (label.equals(actRow.getChildAt(i).getTag())) actRow.removeViewAt(i);
                    }
                    if (actRow.getChildCount() == 0) actScroll.setVisibility(View.GONE);
                }});
            }
            public void setRunningRoot(boolean r) {}
            public void setDebug(boolean d) { debug = d; }
            public boolean isDebug() { return debug; }
            public void finished() {
                // 自然结束（未收到 [mycmd:exit]）也补上结束标记：详细模式显示横幅，精简模式只显示空行 + ←END
                uiH.post(new Runnable() { public void run() {
                    if (!dlg.isShowing() || exited) return;
                    if (debug) body.append("\n── 命令已结束 ──\n");
                    else body.append("\n←END\n");
                    tv.setText(body.toString());
                }});
            }
            public void setTitle(final String t) {
                uiH.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    title.setText(t);
                }});
            }
            public void exit() {
                // [mycmd:exit] 仅标记命令结束：不自动关闭对话框（X /「确定」仍可随时关闭）
                exited = true;
                uiH.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    if (debug) body.append("\n── 命令已结束 [mycmd:exit] ──\n");
                    else body.append("\n←END\n");
                    tv.setText(body.toString());
                }});
            }
            public void startLog(final String p) {
                synchronized (dlgLogPaths) {
                    if (!dlgLogPaths.add(p)) return;   // 同一日志只轮询一次
                }
                uiH.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    if (debug) body.append("\n── 日志文件：" + p + " ──\n");
                    tv.setText(body.toString());
                }});
                // 日志增量按整行解析：写进日志文件里的 [mycmd:exit]/[mycmd:btn:]/[mycmd:log] 同样生效
                Thread t = CmdOutputActivity.startLogFilePoll(p, new CmdOutputActivity.LogLine() {
                    public void line(final String line) {
                        uiH.post(new Runnable() { public void run() {
                            if (!dlg.isShowing()) return;
                            handleLine(line, sinkHolder[0]);
                        }});
                    }
                });
                dlgPollers.add(t);
            }
        };
        sinkHolder[0] = sink;
        // 流执行器：复用 Dialog 自己的 Root 会话
        final CmdStreamer streamer = new CmdStreamer() {
            public boolean openRoot(long t) { return session != null && session.open(t); }
            public Fs.ExecResult stream(String s, int to, Fs.LineSink sk) {
                if (session == null) {
                    Fs.ExecResult r0 = new Fs.ExecResult();
                    r0.code = -1;
                    r0.err = "未启用 Root 会话";
                    return r0;
                }
                return session.stream(s, to, sk);
            }
            public String mapRootPath(String p) {
                return session != null ? session.rootPath(p) : Fs.rootPath(p);
            }
        };
        final RootPathMapper mapper = new RootPathMapper() {
            public String map(String p) {
                return session != null ? session.rootPath(p) : CmdOutputActivity.mapRootPath(p);
            }
        };

        // 关闭（X / 确定）：先停止命令，再关闭对话框
        View.OnClickListener close = new View.OnClickListener() {
            public void onClick(View v) {
                // 会话清理放子线程：命令仍在执行时会持有会话锁，不能在 UI 线程等待（会卡死）。
                if (session != null) {
                    final Fs.OutputSession os = session;
                    new Thread(new Runnable() {
                        public void run() { os.kill(); }
                    }, "dialog-session-kill").start();
                } else {
                    Fs.killCurrentShell();
                }
                dlg.dismiss();
            }
        };
        closeX.setOnClickListener(close);
        ok.setOnClickListener(close);
        // 对话框关闭（X / 确定）时中断日志文件轮询线程，避免泄漏
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface di) {
                for (Thread t : dlgPollers) { try { t.interrupt(); } catch (Exception ignored) {} }
                dlgPollers.clear();
            }
        });

        dlg.show();
        // 子线程执行，输出实时推回主线程的 TextView
        new Thread(new Runnable() {
            public void run() {
                execCore(cmd, targets, cwd, batch, sink, streamer, mapper);
            }
        }).start();
    }

    /** Dialog 内 [mycmd:btn:按钮名称] 命令按钮：后台执行后把结果追加到同一 Dialog。 */
    private void runDialogAction(final Dialog dlg, final Fs.OutputSession session,
                                 final StringBuilder body, final TextView tv, final ScrollView sv,
                                 final CmdSink sink, String label, String command) {
        final String cmd = command;
        toast("运行：" + label);
        new Thread(new Runnable() {
            public void run() {
                final boolean asRoot = session != null && session.isOpen();
                // Root 情形下走 su --mount-master -c，保证与后台任务同一挂载命名空间（[mycmd:btn:stop] 等）。
                final Fs.ExecResult r = asRoot ? Fs.shellMountMaster(cmd)
                        : Fs.shell(cmd, Fs.rootSessionAlive());
                ui.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    // 精简模式（默认）不显示「按钮动作 / 运行的命令」提示符，详细模式（[mycmd:debug:1]）才显示
                    if (sink.isDebug()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("\n\n── ").append(label).append(asRoot ? "（Root）" : "").append(" ──\n");
                        sb.append("$ ").append(cmd).append('\n');
                        body.append(sb);
                    }
                    // 按钮命令输出逐行按控制行规则解析（[mycmd:log] 轮询 / [mycmd:exit] 标记结束 / 其余进正文），与主命令一致
                    if (r.out != null && r.out.length() > 0) {
                        for (String ln : r.out.split("\n")) handleLine(ln, sink);
                    }
                    if (r.err != null && r.err.trim().length() > 0) sink.append("[stderr] " + r.err.trim() + "\n");
                    if (!r.ok()) sink.append("[退出码 " + r.code + "]\n");
                    tv.setText(body.toString());
                    sv.post(new Runnable() { public void run() { sv.fullScroll(View.FOCUS_DOWN); } });
                }});
            }
        }).start();
    }

    // ==================== 文件打开 / 属性 ====================

    /** 打开单个文件：图片优先交给内置「图片查看器」插件；其余走系统「打开方式」。 */
    private void openFile(final FileItem it) {
        final String ext = Util.ext(it.name);
        final Uri uri = new Uri.Builder().scheme("content")
                .authority(FileProvider.AUTH).encodedPath(encSeg(it.path)).build();
        String mime;
        try { mime = getContentResolver().getType(uri); }
        catch (Exception ex) { mime = "*/*"; }
        if (mime == null) mime = "*/*";
        final Prefs.OpenRule rule = Prefs.openRuleFor(this, ext);
        if (rule != null && rule.mime != null && rule.mime.length() > 0) mime = rule.mime;

        // 图片优先用内置图片查看器插件（本应用内的查看器）。AIDL 绑定可能阻塞，
        // 放到后台线程协商，协商完在主线程发起预览或回退系统打开。
        // 图片，或被「已启用」图片查看器插件关联的后缀（支持用户自定义格式）→ 优先内置查看器
        if (looksLikeImage(mime, ext) || pluginMgr.isAssociated(it.path, ext, PluginManager.CAP_VIEWER)) {
            final String fmime = mime;
            io.execute(new Runnable() {
                public void run() {
                    PluginManager.ViewerHandle vh = null;
                    try { vh = pluginMgr.getViewer(it.path, fmime, ext); } catch (Throwable ignored) {}
                    final PluginManager.ViewerHandle fvh = vh;
                    ui.post(new Runnable() {
                        public void run() {
                            if (fvh != null) {
                                try { startActivity(fvh.viewIntent); return; }
                                catch (Throwable t) { /* 插件异常则回退系统打开 */ }
                            }
                            openWithSystem(it, fmime, ext, rule);
                        }
                    });
                }
            });
            return;
        }
        openWithSystem(it, mime, ext, rule);
    }

    /** 系统「打开方式」：按扩展名打开规则 / MIME 唤起外部应用（或弹系统选择器）。 */
    private void openWithSystem(final FileItem it, final String mime, final String ext, final Prefs.OpenRule rule) {
        // APK 走安装流程前先确认「安装未知应用」授权：
        // Android 8+ 安装器会校验调用方（本应用）是否有该授权，未授权时安装器
        // 会被系统直接结束，表现为「点 APK 没反应」，所以这里主动引导开启。
        if (ext.equals("apk") && !canRequestPackageInstalls()) {
            toast("请先允许本应用「安装未知应用」，开启后重新点击 APK");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)); }
                catch (Exception ex2) { toast("请在系统设置中手动开启「安装未知应用」"); }
            }
            return;
        }

        Intent in = new Intent(Intent.ACTION_VIEW);
        in.setDataAndType(uriForPath(it.path), mime);
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        in.setClipData(ClipData.newRawUri("file", uriForPath(it.path)));
        in.putExtra(EXTRA_SELF_INVOKED, true);   // 本应用发起：若系统默认/用户选回本应用，收到即「自己调用」→ 不新开标签

        if (rule != null && rule.pkg != null && rule.pkg.length() > 0) {
            in.setPackage(rule.pkg);
            if (rule.cls != null && rule.cls.length() > 0) in.setClassName(rule.pkg, rule.cls);
            try { startActivity(in); return; }
            catch (Exception ex) { in.setComponent(null); }   // 指定应用/Activity 不存在则回退系统选择器
        }
        // 有具体 MIME：直接发起 VIEW，系统里已有该类型的默认应用就直接用（记住默认）。
        // 通用 MIME（未知格式/无后缀，系统按 octet-stream 一类处理）：用自定义选择器，
        // 选「始终」时把 扩展名→应用 写成打开规则（按扩展名记忆，不串台），下次直接打开。
        if (isGenericMime(mime)) {
            showOpenAppPicker(it, mime, ext, in);
            return;
        }
        try {
            startActivity(in);
        } catch (Exception ex) {
            // 没有任何应用能处理该类型：退回 chooser 让用户看到候选列表
            try {
                startActivity(Intent.createChooser(in, "用以下方式打开 " + it.name));
            } catch (Exception ex2) {
                toast("没有可打开此文件的应用");
            }
        }
    }

    /** 是否是无区分度的通用 MIME：这类格式若让系统记住默认，会导致不同格式共用一个默认应用。 */
    private static boolean isGenericMime(String mime) {
        if (mime == null) return true;
        String m = mime.toLowerCase().trim();
        return m.isEmpty() || "*/*".equals(m)
                || "application/octet-stream".equals(m)
                || "binary/octet-stream".equals(m)
                || "application/unknown".equals(m);
    }

    /** 通用 MIME 文件的自定义打开选择器：列表形式（图标在左、名称在右）+ 底部「记住此选择」复选框。
     *  点应用直接用它打开；若勾选复选框，则把 扩展名→应用 写成打开规则（按扩展名记忆、不串台），
     *  下次不再弹。无后缀时复选框禁用。 */
    private void showOpenAppPicker(final FileItem it, final String mime, final String ext, final Intent base) {
        final PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW);
        probe.setDataAndType(uriForPath(it.path), mime);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);

        final String selfPkg = getPackageName();
        // 本应用不再混进候选列表；单独记下自己的组件，供「直接打开」按钮调用
        final ArrayList<ResolveInfo> cands = new ArrayList<ResolveInfo>();
        ResolveInfo selfRi = null;
        if (found != null) {
            for (ResolveInfo ri : found) {
                if (ri.activityInfo == null) continue;
                if (selfPkg.equals(ri.activityInfo.packageName)) {
                    if (selfRi == null) selfRi = ri;
                } else {
                    cands.add(ri);
                }
            }
        }
        final String selfCls = selfRi != null ? selfRi.activityInfo.name : null;
        if (cands.isEmpty() && selfCls == null) { toast("没有可打开此文件的应用"); return; }

        final boolean canRemember = ext != null && ext.length() > 0;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        ListView list = null;
        CheckBox defCb = null;
        if (!cands.isEmpty()) {
            list = new ListView(this);
            list.setAdapter(new AppPickerAdapter(pm, cands));
            list.setDividerHeight(0);
            box.addView(list, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Util.dp(this, 320)));

            defCb = new CheckBox(this);
            defCb.setText(canRemember ? "始终用所选应用打开 ." + ext + " 文件，不再提示"
                                      : "（此文件无扩展名，无法记住默认应用）");
            defCb.setEnabled(canRemember);
            defCb.setTextColor(canRemember ? Ui.TEXT : Ui.SUB);
            defCb.setTextSize(Ui.sp(textLv, 11, 12, 13));
            int pad = Util.dp(this, 12);
            defCb.setPadding(pad, Util.dp(this, 6), pad, Util.dp(this, 2));
            box.addView(defCb);
        }
        final ListView fList = list;
        final CheckBox fDefCb = defCb;

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("用以下方式打开 " + it.name)
                .setView(box).setPositiveButton("取消", null);
        if (selfCls != null) {
            // 「直接打开」放左边（negative 在左）：用本应用自己的组件打开（带 EXTRA_SELF_INVOKED，走内置定位/查看）
            builder.setNegativeButton("直接打开", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    launchPicked(base, selfPkg, selfCls);
                }
            });
        }
        final AlertDialog dlg = builder.create();
        if (fList != null) {
            fList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                    ResolveInfo ri = cands.get(pos);
                    String pkg = ri.activityInfo.packageName;
                    String cls = ri.activityInfo.name;
                    if (fDefCb.isChecked() && canRemember) rememberOpenRule(ext, pkg, cls);
                    launchPicked(base, pkg, cls);
                    dlg.dismiss();
                }
            });
        }
        dlg.show();
    }

    /** 打开选择器列表项：图标在左、名称在右。 */
    private class AppPickerAdapter extends BaseAdapter {
        private final PackageManager pm;
        private final List<ResolveInfo> items;
        AppPickerAdapter(PackageManager pm, List<ResolveInfo> items) {
            this.pm = pm; this.items = items;
        }
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Util.dp(MainActivity.this, 8);
            row.setPadding(pad, Util.dp(MainActivity.this, 6), pad, Util.dp(MainActivity.this, 6));
            int sz = Util.dp(MainActivity.this, 28);
            ImageView iv = new ImageView(MainActivity.this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(items.get(pos).loadIcon(pm));
            row.addView(iv);
            TextView tv = new TextView(MainActivity.this);
            CharSequence lab = items.get(pos).loadLabel(pm);
            tv.setText(lab == null ? items.get(pos).activityInfo.packageName : lab.toString());
            tv.setTextColor(Ui.TEXT);
            tv.setTextSize(Ui.sp(textLv, 12, 13, 14));
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setPadding(Util.dp(MainActivity.this, 10), 0, 0, 0);
            row.addView(tv);
            return row;
        }
    }

    /** 按用户所选应用直接启动（指定 component，绕过系统默认）。 */
    private void launchPicked(Intent base, String pkg, String cls) {
        Intent in = new Intent(base);
        in.setComponent(new android.content.ComponentName(pkg, cls));
        in.putExtra(EXTRA_SELF_INVOKED, true);
        try { startActivity(in); }
        catch (Exception ex) { toast("打开失败：" + ex.getMessage()); }
    }

    /** 把「扩展名 → 应用」写成打开规则（后添加优先，覆盖同扩展名的旧规则）。 */
    private void rememberOpenRule(String ext, String pkg, String cls) {
        ArrayList<Prefs.OpenRule> rules = Prefs.openRules(this);
        ArrayList<Prefs.OpenRule> kept = new ArrayList<Prefs.OpenRule>();
        String e = Prefs.normalizeExts(ext);
        for (Prefs.OpenRule r : rules) {
            if (!Prefs.extList(r.ext).contains(e)) kept.add(r);
        }
        kept.add(new Prefs.OpenRule(e, "*/*", pkg, cls));
        Prefs.setOpenRules(this, kept);
        toast("已记住：." + e + " 用该应用打开（可在设置中修改）");
    }

    /** 构造 content:// 文件 URI（系统「打开方式」用）。 */
    private Uri uriForPath(String path) {
        return new Uri.Builder().scheme("content")
                .authority(FileProvider.AUTH).encodedPath(encSeg(path)).build();
    }

    /** 是否看起来是图片（用于路由到内置图片查看器插件）。 */
    private static boolean looksLikeImage(String mime, String ext) {
        if (mime != null && mime.toLowerCase().startsWith("image/")) return true;
        if (ext != null) {
            ext = ext.toLowerCase();
            for (String e : new String[]{"jpg","jpeg","png","gif","bmp","webp","heic","heif","tiff","tif","avif","svg","svgz"}) {
                if (e.equals(ext)) return true;
            }
        }
        return false;
    }

    /** 是否已获得「安装未知应用」授权（Android 8 以下视为已授权；异常/未声明权限一律按未授权处理）。 */
    private boolean canRequestPackageInstalls() {
        if (Build.VERSION.SDK_INT < 26) return true;
        try { return getPackageManager().canRequestPackageInstalls(); }
        catch (Throwable t) { return false; }
    }

    /**
     * 底部「打开」：始终调出系统「选择打开方式」对话框，
     * 不受扩展名规则里「指定应用」的限制，方便临时换一个应用。
     */
    private void openWithChooser() {
        List<String> s = selection();
        if (s.isEmpty()) { toast("请先长按选中要打开的文件"); return; }
        final String p = s.get(0);
        // Root 文件只有 su -mm 命名空间可见：用 Fs.exists / isDirectory 判定，避免普通 File 误报不存在
        if (isDirectory(p)) { toast("文件夹请直接点击进入，不能用应用打开"); return; }
        if (!Fs.exists(p)) { toast("文件不存在：" + p); return; }
        final FileItem it = new FileItem(new File(p).getName(), p, false, new File(p).length(), 0L);
        if (Util.ext(it.name).equals("apk") && !canRequestPackageInstalls()) {
            toast("请先允许本应用「安装未知应用」，开启后重新点击 APK");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ex) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)); }
                catch (Exception ex2) { toast("请在系统设置中手动开启「安装未知应用」"); }
            }
            return;
        }
        final Uri uri = new Uri.Builder().scheme("content")
                .authority(FileProvider.AUTH).encodedPath(encSeg(p)).build();
        String mime;
        try { mime = getContentResolver().getType(uri); }
        catch (Exception ex) { mime = "*/*"; }
        if (mime == null) mime = "*/*";
        final Prefs.OpenRule rule = Prefs.openRuleFor(this, Util.ext(it.name));
        if (rule != null && rule.mime != null && rule.mime.length() > 0) mime = rule.mime;

        Intent in = new Intent(Intent.ACTION_VIEW);
        in.setDataAndType(uri, mime);
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        in.setClipData(ClipData.newRawUri("file", uri));
        in.putExtra(EXTRA_SELF_INVOKED, true);   // 若用户在选择器里选回本应用，属「自己调用」→ 收到时不新开标签
        try {
            startActivity(Intent.createChooser(in, "选择打开方式 · " + it.name));
        } catch (Exception ex) {
            toast("没有可打开此文件的应用");
        }
    }

    // ==================== 收藏夹 ====================

    /** 底部「收藏」：把选中项加入收藏；无选中时兜底收藏当前目录。 */
    private void favoriteSelection() {
        List<String> s = selection();
        if (s.isEmpty()) {
            boolean added = Prefs.addFavorite(this, cur().path);
            toast(added ? ("已收藏当前目录：" + labelOf(cur().path)) : "当前目录已在收藏夹中");
            return;
        }
        int added = 0;
        for (String p : s) if (Prefs.addFavorite(this, p)) added++;
        toast(added > 0 ? ("已收藏 " + added + " 项，可在左侧抽屉「收藏书签」查看") : "选中项已全部在收藏夹中");
    }

    /** 底部「分享」：把选中项通过系统分享对话框发出去（单文件 ACTION_SEND，多个 ACTION_SEND_MULTIPLE）。 */
    private void shareSelection() {
        List<String> s = selection();
        if (s.isEmpty()) { toast("请先长按选中要分享的文件"); return; }
        ArrayList<Uri> uris = new ArrayList<Uri>();
        for (String p : s) {
            // Root 文件只有 su -mm 命名空间可见，用 Fs.exists 判定（内部会按 rootMode 走 su）
            if (!Fs.exists(p)) continue;
            uris.add(uriForPath(p));
        }
        if (uris.isEmpty()) { toast("选中的文件不存在"); return; }
        try {
            Intent it;
            if (uris.size() == 1) {
                it = new Intent(Intent.ACTION_SEND);
                it.setType("*/*");
                it.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                it = new Intent(Intent.ACTION_SEND_MULTIPLE);
                it.setType("*/*");
                it.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享到"));
        } catch (Throwable e) {
            Dbg.log("ACTION", "分享失败", e);
            toast("分享失败：" + e.getMessage());
        }
    }

    private void showInfo() {
        List<String> s = selection();
        if (s.isEmpty()) {
            showInfoDialog("当前目录",
                    "路径：" + cur().path + "\n\n项目数：" + adapter.getCount()
                            + "\n模式：" + (Fs.rootMode ? "Root" : "普通"),
                    cur().path, true);
            return;
        }
        final List<String> paths = new ArrayList<String>(s);
        io.execute(new Runnable() {
            public void run() {
                ArrayList<Props> items = new ArrayList<Props>();
                for (String p : paths) {
                    Props pr = statProps(p);
                    if (pr != null) items.add(pr);
                }
                final ArrayList<Props> list = items;
                ui.post(new Runnable() {
                    public void run() {
                        if (list.isEmpty())
                            showInfoDialog("属性", "无法读取属性：\n" + paths.get(0), paths.get(0), false);
                        else
                            showPropsDialog(list);
                    }
                });
            }
        });
    }

    /** 判定路径是否为目录（Root 模式走 shell 的 test -d，避免 per-app 命名空间误判）。 */
    private boolean isDirectory(String p) {
        if (Fs.rootMode) {
            Fs.ExecResult r = Fs.shell("test -d " + Util.sq(Fs.rootPath(p)), true);
            return r.ok();
        }
        return new File(p).isDirectory();
    }

    /**
     * 属性/信息对话框：正文可长按选中复制，底部按钮栏按
     * 「复制全部」「确定」顺序排列。
     */
    private void showInfoDialog(String title, final String text, final String path, final boolean isDir) {
        final int pad = Util.dp(this, 14);
        final int dh = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dh));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(14);
        tv.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));
        sv.addView(tv);
        root.addView(sv);

        // 底部按钮栏：复制定制为可长按选择的文本框同等体验
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));

        Button copy = Ui.solid(this, "复制全部", 1);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(title, text));
                    toast("已复制属性文本");
                } catch (Throwable t) {
                    toast("复制失败");
                }
            }
        });
        row.addView(copy);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(spacer);

        Button ok = Ui.solid(this, "确定", 1);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dlg != null) dlg.dismiss(); }
        });
        row.addView(ok);

        root.addView(row);

        dlg = new AlertDialog.Builder(MainActivity.this)
                .setTitle(title)
                .setView(root)
                .setCancelable(true)
                .show();
    }

    /**
     * 属性面板：一次 stat 取回结构化字段（含八进制权限 %a、uid/gid），
     * 供「文件属性」与「权限」两个 Tab 共用；stat 不可用时回退 Java File API。
     */
    private Props statProps(String p) {
        Props o = new Props();
        o.path = p; o.name = Util.base(p);
        // %F 类型 / %s 字节 / %Y 秒 / %A 符号权限 / %U 所有者名 / %G 组名 / %u uid / %g gid / %a 八进制权限
        // stat -c 不解释 \t，格式串里必须是真实 TAB 字符（Java 的 "\t"）。
        String fmt = "%F\t%s\t%Y\t%A\t%U\t%G\t%u\t%g\t%a";
        Fs.ExecResult r = null;
        try {
            String sp = Fs.rootMode ? Fs.rootPath(p) : p;
            r = Fs.shell("stat -L -c '" + fmt + "' " + Util.sq(sp) + " 2>/dev/null", Fs.rootMode);
        } catch (Throwable ignored) {}

        if (r != null && r.ok()) {
            String[] ls = r.out.split("\n");
            String line = null;
            for (int i = ls.length - 1; i >= 0; i--) {
                if (ls[i].indexOf('\t') >= 0) { line = ls[i].trim(); break; }
            }
            if (line != null) {
                String[] f = line.split("\t", -1);
                if (f.length >= 9) {
                    o.type = infoType(f[0]);
                    o.dir = "文件夹".equals(o.type);
                    o.size = infoLong(f[1], -1);
                    o.mtime = infoLong(f[2], 0);
                    o.modeSym = f[3];
                    o.ownerName = f[4]; o.ownerUid = (int) infoLong(f[6], -1);
                    o.groupName = f[5]; o.groupGid = (int) infoLong(f[7], -1);
                    // %a 是八进制权限串（如 755 / 2755），必须按 8 进制解析，否则复选框显示全错
                    try { o.modeOctal = Integer.parseInt(f[8].trim(), 8); }
                    catch (Exception e) { o.modeOctal = (int) infoLong(f[8], 0); }
                    return o;
                }
            }
        }

        // 兜底：stat 不可用（部分 ROM / Root 会话未就绪）或文件不存在
        File f = new File(p);
        if (!f.exists()) { o.type = "（文件不存在）"; o.modeOctal = -1; return o; }
        o.type = f.isDirectory() ? "文件夹" : "文件";
        o.dir = f.isDirectory();
        o.size = f.isFile() ? f.length() : -1;
        o.mtime = f.lastModified() / 1000L;
        o.modeSym = (f.canRead() ? "r" : "-") + (f.canWrite() ? "w" : "-") + (f.canExecute() ? "x" : "-");
        o.ownerName = infoOwnerOf(f); o.ownerUid = -1;
        o.groupName = "—"; o.groupGid = -1;
        o.modeOctal = -1;
        return o;
    }

    /** 把 Props 排版成「文件属性」Tab 的纯文本（与旧版 buildInfo 一致）。 */
    private String buildInfoText(Props o) { return buildInfoText(o, -1); }

    private String buildInfoText(Props o, long sizeOverride) {
        long sz = sizeOverride >= 0 ? sizeOverride : o.size;
        StringBuilder b = new StringBuilder();
        b.append("名称　　：").append(o.name).append('\n');
        b.append("路径　　：").append(o.path).append('\n');
        if (o.type != null) b.append("类型　　：").append(o.type).append('\n');
        b.append("大小　　：").append(sz >= 0 ? Util.size(sz) : "—").append('\n');
        b.append("修改时间：").append(o.mtime > 0 ? Util.timeSec(o.mtime * 1000L) : "—").append('\n');
        if (o.ownerUid >= 0) b.append("所有者　：").append(infoOwner(o.ownerName, String.valueOf(o.ownerUid))).append('\n');
        if (o.groupGid >= 0) b.append("用户组　：").append(infoOwner(o.groupName, String.valueOf(o.groupGid))).append('\n');
        b.append("权限　　：").append(o.modeSym != null ? o.modeSym : "—");
        return b.toString();
    }

    private static long infoLong(String s, long dft) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return dft; }
    }

    /** Android 上 %U 常返回数字或 UNKNOWN，此时只显示 uid/gid，避免重复或乱码。 */
    private static String infoOwner(String name, String id) {
        String n = name == null ? "" : name.trim();
        String i = id == null ? "" : id.trim();
        boolean bad = n.isEmpty() || "UNKNOWN".equalsIgnoreCase(n) || n.equals(i);
        if (bad) return i.isEmpty() ? "—" : i;
        if (i.isEmpty()) return n;
        return n + " (" + i + ")";
    }

    private static String infoType(String raw) {
        String s = raw.toLowerCase(java.util.Locale.US);
        if (s.contains("directory")) return "文件夹";
        if (s.contains("regular")) return "普通文件";
        if (s.contains("symbolic")) return "符号链接";
        if (s.contains("block")) return "块设备";
        if (s.contains("character")) return "字符设备";
        if (s.contains("socket")) return "套接字";
        if (s.contains("fifo") || s.contains("pipe")) return "管道";
        return raw;
    }

    private static String infoOwnerOf(File f) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                java.nio.file.attribute.UserPrincipal u = java.nio.file.Files.getOwner(f.toPath());
                if (u != null) return u.getName();
            } catch (Throwable ignored) {}
        }
        return "—";
    }

    // ==================== 文件属性 / 权限 双 Tab 对话框 ====================

    /** 解析后的文件结构化属性，供「文件属性」与「权限」两个 Tab 共用。 */
    private static class Props {
        String path, name, type, ownerName, groupName, modeSym;
        long size = -1, mtime = 0;
        int ownerUid = -1, groupGid = -1, modeOctal = 0;
        boolean dir = false;
    }

    /** 权限 Tab 中各控件的引用集合。 */
    private static class PermUI {
        ScrollView view;
        CheckBox uR, uW, uX, gR, gW, gX, oR, oW, oX, suid, sgid, sticky, recursive, dirsOnly;
        Spinner ownerSpin, groupSpin;
        TextView val;
        ArrayList<UidEntry> ownerList, groupList;
    }

    /** 一个可用的 uid 候选（数字 + 名称）。 */
    private static class UidEntry {
        int uid; String name;
        UidEntry(int u, String n) { uid = u; name = n; }
        public String toString() {
            return uid + (name != null && !name.equals(String.valueOf(uid)) ? " (" + name + ")" : "");
        }
    }

    /** 常用 Android AID（与截图里的 1002(bluetooth)…1010(wifi) 对应）。 */
    private static final Object[][] AID = {
        {0, "root"}, {1000, "system"}, {1001, "radio"}, {1002, "bluetooth"}, {1003, "graphics"},
        {1004, "input"}, {1005, "audio"}, {1006, "camera"}, {1007, "log"}, {1008, "compass"},
        {1009, "mount"}, {1010, "wifi"}, {1011, "media"}, {1013, "media_drm"}, {1014, "dhcp"},
        {1015, "vpn"}, {1016, "drm"}, {1017, "usb"}, {1018, "gps"}, {1019, "nfc"},
        {1020, "torque"}, {1021, "media_rw"}, {1023, "keystore"}, {1024, "drmrpc"},
        {1025, "nvram"}, {1026, "dns"}, {1027, "dhcp"}, {1028, "system"}, {1029, "rfs"},
        {1030, "nfc"}, {1031, "drm"}, {1032, "gps"}, {1033, "shell"},
        {2000, "shell"}, {2001, "cache"}, {2002, "diag"}, {2003, "cache"},
        {3001, "bluetooth_net"}, {3002, "bluetooth"}, {3003, "inet"}, {3004, "net_raw"}, {3005, "net_admin"},
    };

    /** Tab 文字控件（选中态高亮）。 */
    private TextView propTabText(String s, boolean sel) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(Ui.sp(textLv, 12, 13, 15));
        t.setPadding(Util.dp(this, 16), Util.dp(this, 8), Util.dp(this, 16), Util.dp(this, 8));
        styleTab(t, sel);
        return t;
    }
    private void styleTab(TextView t, boolean sel) {
        t.setBackgroundColor(sel ? Ui.PRIMARY_SOFT : Color.TRANSPARENT);
        t.setTextColor(sel ? Ui.PRIMARY : Ui.TEXT);
        t.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /**
     * 双 Tab 属性对话框（支持单文件/文件夹与多选项）：
     *  - 「文件属性」：单文件显示完整信息；多选项显示聚合信息（数量、总大小、逐项列表）。
     *  - 「权限」：读/写/执行矩阵 + 用户与用户组的 uid 选择 + 特殊权限，可一键应用到全部选中项。
     *
     * 修复首屏排版：两个 Tab 视图叠放在 FrameLayout 中，初始仅显示「文件属性」页，
     * 「权限」页默认 View.GONE，避免两者叠加显示。
     */
    private void showPropsDialog(final List<Props> items) {
        if (items == null || items.isEmpty()) return;
        final boolean single = items.size() == 1;
        final Props first = items.get(0);
        boolean anyDir = false;
        for (Props p : items) if (p.dir) { anyDir = true; break; }

        final int pad = Util.dp(this, 14);
        final int dh = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
        // 含文件夹时先显示「计算中…」，后台 du 完成后回填真实大小；纯文件则直接累加
        long initBytes = 0;
        for (Props o : items) if (!o.dir && o.size >= 0) initBytes += o.size;
        final String initSize = anyDir ? "计算中…" : (items.size() > 0 ? Util.size(initBytes) : "—");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dh));

        // Tab 选择条
        final TextView tabProp = propTabText("文件属性", true);
        final TextView tabPerm = propTabText("权限", false);
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.addView(tabProp);
        tabBar.addView(tabPerm);
        root.addView(tabBar);
        View tabLine = Ui.line(this);
        tabLine.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        root.addView(tabLine);

        // 内容区：两个 Tab 视图叠放在 FrameLayout，靠可见性切换；默认只显示「文件属性」页
        final FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Tab1：文件属性（结构化布局，路径可点击复制，多选显示父文件夹）
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));
        final StringBuilder infoSb = new StringBuilder();
        final TextView[] infoSizeRef = new TextView[1];

        if (single) {
            Props o = first;
            infoRow(infoBox, "名称　　：" + o.name);
            infoPathRow(infoBox, "路径　　：", o.path);
            if (o.type != null) infoRow(infoBox, "类型　　：" + o.type);
            infoSizeRef[0] = infoRow(infoBox, "大小　　：" + (o.size >= 0 ? Util.size(o.size) : "—"));
            infoRow(infoBox, "修改时间：" + (o.mtime > 0 ? Util.timeSec(o.mtime * 1000L) : "—"));
            if (o.ownerUid >= 0) infoRow(infoBox, "所有者　：" + infoOwner(o.ownerName, String.valueOf(o.ownerUid)));
            if (o.groupGid >= 0) infoRow(infoBox, "用户组　：" + infoOwner(o.groupName, String.valueOf(o.groupGid)));
            infoRow(infoBox, "权限　　：" + (o.modeSym != null ? o.modeSym : "—"));
            infoSb.append(buildInfoText(first));
        } else {
            int nDir = 0, nFile = 0;
            for (Props o : items) { if (o.dir) nDir++; else nFile++; }
            infoRow(infoBox, items.size() + "个对象(" + nDir + " 文件夹、" + nFile + " 文件)");
            // 父文件夹（去重）：单位置直接显示，多位置逐项列出
            LinkedHashSet<String> parents = new LinkedHashSet<String>();
            for (Props o : items) parents.add(parentOf(o.path));
            if (parents.size() == 1) {
                infoPathRow(infoBox, "父文件夹：", parents.iterator().next());
            } else {
                infoRow(infoBox, "父文件夹（" + parents.size() + " 个位置）：");
                for (String pp : parents) infoPathRow(infoBox, "", pp);
            }
            infoSizeRef[0] = infoRow(infoBox, "大小: " + initSize);
            infoSb.append(buildMultiInfoText(items, initSize));
        }
        sv.addView(infoBox);
        content.addView(sv);

        // Tab2：权限（初始隐藏，修复首屏叠加）
        final PermUI perm = buildPermView(items, pad);
        perm.view.setVisibility(View.GONE);
        content.addView(perm.view);

        root.addView(content);

        // 底部按钮栏
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));
        Button copy = Ui.solid(this, "复制全部", 1);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyToClipboard("属性", infoSb.toString());
            }
        });
        row.addView(copy);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(spacer);
        Button ok = Ui.solid(this, "确定", 1);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dlg != null) dlg.dismiss(); }
        });
        row.addView(ok);
        root.addView(row);

        dlg = new AlertDialog.Builder(MainActivity.this)
                .setTitle(single ? ("属性 · " + first.name) : ("属性 · 共 " + items.size() + " 项"))
                .setView(root).setCancelable(true).show();

        // 含文件夹时后台用 du 计算真实总大小，算完直接回填「大小」行（无需「计算大小」按钮）
        if (single && first.dir) {
            final Props p = first;
            io.execute(new Runnable() {
                public void run() {
                    final long total = computeTotalBytes(Collections.singletonList(p));
                    ui.post(new Runnable() {
                        public void run() {
                            if (infoSizeRef[0] != null)
                                infoSizeRef[0].setText("大小　　：" + Util.size(total));
                            infoSb.replace(0, infoSb.length(), buildInfoText(p, total));
                        }
                    });
                }
            });
        } else if (anyDir) {
            final List<Props> src = items;
            io.execute(new Runnable() {
                public void run() {
                    final long total = computeTotalBytes(src);
                    ui.post(new Runnable() {
                        public void run() {
                            if (infoSizeRef[0] != null)
                                infoSizeRef[0].setText("大小: " + Util.size(total));
                            infoSb.replace(0, infoSb.length(), buildMultiInfoText(src, Util.size(total)));
                        }
                    });
                }
            });
        }

        // Tab 切换
        View.OnClickListener switchTab = new View.OnClickListener() {
            public void onClick(View v) {
                boolean prop = v == tabProp;
                sv.setVisibility(prop ? View.VISIBLE : View.GONE);
                perm.view.setVisibility(prop ? View.GONE : View.VISIBLE);
                styleTab(tabProp, prop);
                styleTab(tabPerm, !prop);
            }
        };
        tabProp.setOnClickListener(switchTab);
        tabPerm.setOnClickListener(switchTab);
    }

    /** 多选项的「文件属性」聚合文本：N个对象(N 文件夹、M 文件) + 逐项大小 + 大小: <总大小>。
     *  sizeStr 为已算好的总大小文本（含文件夹时由后台 du 计算后回填）。 */
    private String buildMultiInfoText(List<Props> items, String sizeStr) {
        int nDir = 0, nFile = 0;
        for (Props o : items) { if (o.dir) nDir++; else nFile++; }
        StringBuilder b = new StringBuilder();
        b.append(items.size()).append("个对象(").append(nDir).append(" 文件夹、")
                .append(nFile).append(" 文件)\n\n");
        // 父文件夹（去重）：单位置时直接显示，多位置时列出全部
        LinkedHashSet<String> parents = new LinkedHashSet<String>();
        for (Props o : items) parents.add(parentOf(o.path));
        if (parents.size() == 1) {
            b.append("父文件夹：").append(parents.iterator().next()).append('\n');
        } else {
            b.append("父文件夹（").append(parents.size()).append(" 个位置）：\n");
            for (String pp : parents) b.append("  ").append(pp).append('\n');
        }
        b.append("\n大小: ").append(sizeStr);
        return b.toString();
    }

    /** 取路径的父目录（"/a/b" → "/a"，"/" 与无斜杠返回 "/"）。 */
    private static String parentOf(String path) {
        if (path == null || path.length() == 0) return "/";
        int i = path.lastIndexOf('/');
        if (i <= 0) return "/";
        return path.substring(0, i);
    }

    /** 复制文本到系统剪贴板并提示。 */
    private void copyToClipboard(String label, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            toast("已复制：" + text);
        } catch (Throwable t) { toast("复制失败"); }
    }

    /** 普通信息行（标签 + 值）。 */
    private TextView infoRow(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Ui.TEXT);
        t.setPadding(0, Util.dp(this, 2), 0, Util.dp(this, 2));
        box.addView(t);
        return t;
    }

    /** 可点击复制的路径行：路径以强调色显示，点击整行复制该路径到剪贴板。 */
    private void infoPathRow(LinearLayout box, String label, final String path) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Util.dp(this, 2), 0, Util.dp(this, 2));
        if (label != null && label.length() > 0) {
            TextView l = new TextView(this);
            l.setText(label);
            l.setTextSize(14);
            l.setTextColor(Ui.TEXT);
            r.addView(l);
        }
        TextView p = new TextView(this);
        p.setText(path);
        p.setTextSize(14);
        p.setTextColor(Ui.PRIMARY);
        p.setSingleLine(true);
        p.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(p, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyToClipboard("路径", path); }
        });
        box.addView(r);
    }

    /** 用 du -sb 累加所有选中路径（文件直接统计、文件夹递归其内容），返回总字节数。 */
    private long computeTotalBytes(List<Props> items) {
        long total = 0;
        for (Props o : items) {
            String mapped = Fs.rootMode ? Fs.rootPath(o.path) : o.path;
            Fs.ExecResult r = Fs.shell("du -sb " + Util.sq(mapped) + " 2>/dev/null", Fs.rootMode);
            if (r.ok() && r.out != null) {
                String[] t = r.out.trim().split("\\s+");
                if (t.length > 0) { try { total += Long.parseLong(t[0].trim()); continue; } catch (Exception ignored) {} }
            }
            if (!o.dir && o.size >= 0) total += o.size; // 命令失败的文件回退到 stat 大小
        }
        return total;
    }

    /** 构建「权限」Tab 视图，返回各控件引用。items 为本次选中的全部文件/文件夹。
     *  排版为「读 / 写 / 执行」三列矩阵：左侧身份（用户 / 用户组 / 其他），右侧对应用户/组选择。 */
    private PermUI buildPermView(final List<Props> items, int pad) {
        final boolean multi = items.size() > 1;
        final Props o = items.get(0);
        boolean anyDir = false;
        for (Props p : items) if (p.dir) { anyDir = true; break; }
        final boolean fAnyDir = anyDir;

        final PermUI ui = new PermUI();
        final int m = o.modeOctal >= 0 ? o.modeOctal : 0;

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));

        TextView title = new TextView(this);
        if (multi) title.setText("以下权限与所有者将应用到全部 " + items.size() + " 个选中项");
        else title.setText("设置所有者（用户 / 用户组）与 R / W / X 权限");
        title.setTextSize(Ui.sp(textLv, 12, 13, 15));
        title.setTextColor(Ui.SUB);
        box.addView(title);
        box.addView(Ui.line(this));

        ui.ownerList = buildUidEntries(o.ownerUid, o.groupGid);
        ui.groupList = buildUidEntries(o.ownerUid, o.groupGid);

        // 矩阵：读 / 写 / 执行 三列
        box.addView(permHeader());
        box.addView(permRow("用户", true, ui, m, 0, null));
        box.addView(permRow("用户组", true, ui, m, 1, null));
        // 「其他」行右侧（与上面两个 uid 下拉框同一列）放 Auto 按钮：
        // 点击后读取选中项所在父文件夹的所有者 / 用户组，自动选中上面两个下拉框。
        View.OnClickListener autoOwner = new View.OnClickListener() {
            public void onClick(View v) { autoSelectParentOwner(items, ui); }
        };
        box.addView(permRow("其他", false, ui, m, 2, autoOwner));

        // 特殊权限
        TextView st = new TextView(this);
        st.setText("特殊权限");
        st.setTextSize(Ui.sp(textLv, 12, 13, 15));
        st.setTextColor(Ui.SUB);
        st.setPadding(0, Util.dp(this, 10), 0, 0);
        box.addView(st);
        LinearLayout spRow = new LinearLayout(this);
        spRow.setOrientation(LinearLayout.HORIZONTAL);
        ui.suid = mkCb("SUID", (m & 04000) != 0, spRow);
        ui.sgid = mkCb("SGID", (m & 02000) != 0, spRow);
        ui.sticky = mkCb("Sticky", (m & 01000) != 0, spRow);
        box.addView(spRow);

        // 应用选项：含文件夹才显示「递归」；多选项才显示「只用于目录」
        if (fAnyDir || multi) {
            LinearLayout optRow = new LinearLayout(this);
            optRow.setOrientation(LinearLayout.HORIZONTAL);
            optRow.setPadding(0, Util.dp(this, 6), 0, 0);
            if (fAnyDir) ui.recursive = mkCb("递归", false, optRow);
            if (multi) ui.dirsOnly = mkCb("只用于目录", false, optRow);
            box.addView(optRow);
        }

        // 权限值（实时）
        ui.val = new TextView(this);
        ui.val.setTextSize(Ui.sp(textLv, 13, 14, 16));
        ui.val.setTypeface(android.graphics.Typeface.MONOSPACE);
        ui.val.setPadding(0, Util.dp(this, 8), 0, Util.dp(this, 4));
        box.addView(ui.val);

        // 应用按钮
        Button apply = Ui.solid(this, multi ? "应用权限到全部" : "应用权限", 1);
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { applyPerm(items, ui); }
        });
        LinearLayout ab = new LinearLayout(this);
        ab.setGravity(Gravity.END);
        ab.addView(apply);
        box.addView(ab);

        // 勾选框变化时实时刷新权限值
        CompoundButton.OnCheckedChangeListener cl = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean c) { updatePermVal(o, ui); }
        };
        for (CheckBox cb : new CheckBox[]{ui.uR, ui.uW, ui.uX, ui.gR, ui.gW, ui.gX,
                ui.oR, ui.oW, ui.oX, ui.suid, ui.sgid, ui.sticky}) cb.setOnCheckedChangeListener(cl);
        if (ui.recursive != null) ui.recursive.setOnCheckedChangeListener(cl);
        if (ui.dirsOnly != null) ui.dirsOnly.setOnCheckedChangeListener(cl);

        syncPerm(o, ui);
        sv.addView(box);
        ui.view = sv;
        return ui;
    }

    /** 「读 / 写 / 执行」列的表头行：左标签占位 + 三列等宽 + 右侧与 spinner 同宽占位。 */
    private View permHeader() {
        final int labW = Util.dp(this, 64);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Util.dp(this, 2), 0, Util.dp(this, 2));
        row.addView(new View(this), new LinearLayout.LayoutParams(labW, ViewGroup.LayoutParams.WRAP_CONTENT));
        final int permColW = Util.dp(this, 44);
        for (String s : new String[]{"R", "W", "X"}) {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(Ui.sp(textLv, 12, 13, 15));
            t.setTextColor(Ui.PRIMARY);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(permColW, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.gravity = Gravity.CENTER;
            row.addView(t, hp);
        }
        // 右侧占位与 spinner 同宽（占剩余空间），保证列对齐
        row.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** 带省略号截断的 uid 适配器：收起时文本过长以 "…" 结尾，避免撑爆布局挡住复选框。 */
    private ArrayAdapter<UidEntry> uidAdapter(ArrayList<UidEntry> list) {
        return new ArrayAdapter<UidEntry>(this, android.R.layout.simple_spinner_item, list) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setMaxWidth(Util.dp(MainActivity.this, 150));
                tv.setTextSize(Ui.sp(textLv, 11, 12, 14));
                return tv;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                tv.setSingleLine(false);
                tv.setTextSize(Ui.sp(textLv, 11, 12, 14));
                return tv;
            }
        };
    }

    /** 矩阵中的一行：左身份标签 + 读/写/执行三个无文字复选框（自然宽度居中）+ 右侧可选 uid 选择（占剩余空间）。
     *  「其他」行（hasSpinner=false）传入 autoClick 时，右侧改放一个「Auto」按钮，与上面两个下拉框同一列、右对齐。 */
    private View permRow(String label, boolean hasSpinner, PermUI ui, int mode, int role,
                         View.OnClickListener autoClick) {
        final int labW = Util.dp(this, 64);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Util.dp(this, 4), 0, Util.dp(this, 4));

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(Ui.sp(textLv, 12, 13, 15));
        lab.setTextColor(Ui.TEXT);
        row.addView(lab, new LinearLayout.LayoutParams(labW, ViewGroup.LayoutParams.WRAP_CONTENT));

        int shift = role == 0 ? 6 : (role == 1 ? 3 : 0);
        CheckBox r = mkBox((mode & (04 << shift)) != 0, row);
        CheckBox w = mkBox((mode & (02 << shift)) != 0, row);
        CheckBox x = mkBox((mode & (01 << shift)) != 0, row);
        // 三个复选框各占固定窄列（与表头的 R/W/X 三列对齐），居中显示、列间不留大间隙
        final int permColW = Util.dp(this, 44);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                permColW, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        r.setLayoutParams(cp); w.setLayoutParams(cp); x.setLayoutParams(cp);
        if (role == 0) { ui.uR = r; ui.uW = w; ui.uX = x; }
        else if (role == 1) { ui.gR = r; ui.gW = w; ui.gX = x; }
        else { ui.oR = r; ui.oW = w; ui.oX = x; }

        if (hasSpinner) {
            Spinner sp = new Spinner(this);
            ArrayList<UidEntry> list = role == 0 ? ui.ownerList : ui.groupList;
            ArrayAdapter<UidEntry> ad = uidAdapter(list);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(ad);
            // 占剩余宽度，文本过长由适配器省略号截断，不再挤压复选框
            row.addView(sp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (role == 0) ui.ownerSpin = sp; else ui.groupSpin = sp;
        } else if (autoClick != null) {
            // 「其他」行右侧：Auto 按钮，右对齐，与上面两个 uid 下拉框同一列
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            wrap.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            Button auto = Ui.solid(this, "Auto", 1);
            auto.setOnClickListener(autoClick);
            wrap.addView(auto);
            row.addView(wrap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        } else {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    private CheckBox mkBox(boolean c, ViewGroup parent) {
        CheckBox cb = new CheckBox(this);
        cb.setChecked(c);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        // 去掉复选框默认左右内边距（按钮-文字间隙），让三列更紧凑、与 R/W/X 对齐
        cb.setPadding(0, 0, 0, 0);
        parent.addView(cb);
        return cb;
    }

    private CheckBox mkCb(String t, boolean c, ViewGroup parent) {
        CheckBox cb = new CheckBox(this);
        cb.setText(t);
        cb.setTextSize(Ui.sp(textLv, 11, 12, 14));
        cb.setChecked(c);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
        parent.addView(cb);
        return cb;
    }

    /** 用 Props 的当前值刷新复选框 / uid 选择 / 权限值。 */
    private void syncPerm(Props o, PermUI ui) {
        int m = o.modeOctal >= 0 ? o.modeOctal : 0;
        ui.uR.setChecked((m & 0400) != 0); ui.uW.setChecked((m & 0200) != 0); ui.uX.setChecked((m & 0100) != 0);
        ui.gR.setChecked((m & 0040) != 0); ui.gW.setChecked((m & 0020) != 0); ui.gX.setChecked((m & 0010) != 0);
        ui.oR.setChecked((m & 0004) != 0); ui.oW.setChecked((m & 0002) != 0); ui.oX.setChecked((m & 0001) != 0);
        ui.suid.setChecked((m & 04000) != 0); ui.sgid.setChecked((m & 02000) != 0); ui.sticky.setChecked((m & 01000) != 0);
        if (ui.ownerSpin != null) selectUid(ui.ownerSpin, o.ownerUid);
        if (ui.groupSpin != null) selectUid(ui.groupSpin, o.groupGid);
        updatePermVal(o, ui);
    }

    private void selectUid(Spinner sp, int uid) {
        if (uid < 0) return;
        ArrayAdapter<UidEntry> a = (ArrayAdapter<UidEntry>) sp.getAdapter();
        for (int i = 0; i < a.getCount(); i++)
            if (a.getItem(i).uid == uid) { sp.setSelection(i); return; }
    }

    /**
     * 权限页「Auto」按钮：读取选中项所在父文件夹的所有者（用户 / 用户组），
     * 自动让上面的「用户」「用户组」两个 uid 下拉框选中它们。
     * stat 走 Fs.shell（Root 模式下会进全局挂载命名空间，可能较慢），放后台线程执行，
     * 结果回主线程刷新下拉框，避免点击后卡界面。
     */
    private void autoSelectParentOwner(final List<Props> items, final PermUI perm) {
        if (items == null || items.isEmpty() || perm == null) return;
        // 多选时各项目父目录可能不同，统一取第一个的父文件夹（与「权限」页以 items.get(0) 为基准一致）。
        final String parent = parentOf(items.get(0).path);
        toast("读取父文件夹所有者…");
        io.execute(new Runnable() {
            public void run() {
                // statProps 内部已按 Fs.rootMode 做路径映射（Fs.rootPath），与属性页读取方式一致。
                final Props pr = statProps(parent);
                final int ou = pr == null ? -1 : pr.ownerUid;
                final int gg = pr == null ? -1 : pr.groupGid;
                final String on = pr == null ? null : pr.ownerName;
                final String gn = pr == null ? null : pr.groupName;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ou < 0 && gg < 0) {
                            toast("无法读取父文件夹所有者：" + parent);
                            return;
                        }
                        boolean okU = ou >= 0 && selectUidOrAdd(perm.ownerSpin, perm.ownerList, ou, on);
                        boolean okG = gg >= 0 && selectUidOrAdd(perm.groupSpin, perm.groupList, gg, gn);
                        String u = okU ? (ou + (on != null ? "(" + on + ")" : "")) : "—";
                        String g = okG ? (gg + (gn != null ? "(" + gn + ")" : "")) : "—";
                        toast("已按父文件夹选中 → 用户 " + u + " / 用户组 " + g);
                    }
                });
            }
        });
    }

    /** 在 uid 下拉框里选中指定 uid；候选列表里没有该 uid 时补一条（按 uid 升序插入）再选中。
     *  例如父文件夹所有者是某个已安装应用 uid 或 AID 之外的 gid，原列表未必包含。 */
    private boolean selectUidOrAdd(Spinner sp, ArrayList<UidEntry> list, int uid, String name) {
        if (sp == null || list == null || uid < 0) return false;
        ArrayAdapter<UidEntry> a = (ArrayAdapter<UidEntry>) sp.getAdapter();
        for (int i = 0; i < a.getCount(); i++)
            if (a.getItem(i).uid == uid) { sp.setSelection(i); return true; }
        // 未收录：用 stat 读到的名字（%U/%G 常为 UNKNOWN 或数字，此时 UidEntry 只显示 uid）
        String nm = (name != null && !"UNKNOWN".equalsIgnoreCase(name.trim())
                && !name.trim().equals(String.valueOf(uid))) ? name.trim() : null;
        UidEntry add = new UidEntry(uid, nm);
        int pos = 0;
        while (pos < list.size() && list.get(pos).uid < uid) pos++;
        list.add(pos, add);
        a.notifyDataSetChanged();
        sp.setSelection(pos);
        return true;
    }

    private void updatePermVal(Props o, PermUI ui) {
        int u = (ui.uR.isChecked() ? 4 : 0) | (ui.uW.isChecked() ? 2 : 0) | (ui.uX.isChecked() ? 1 : 0);
        int g = (ui.gR.isChecked() ? 4 : 0) | (ui.gW.isChecked() ? 2 : 0) | (ui.gX.isChecked() ? 1 : 0);
        int t = (ui.oR.isChecked() ? 4 : 0) | (ui.oW.isChecked() ? 2 : 0) | (ui.oX.isChecked() ? 1 : 0);
        int sp = (ui.suid.isChecked() ? 04000 : 0) | (ui.sgid.isChecked() ? 02000 : 0) | (ui.sticky.isChecked() ? 01000 : 0);
        int mode = sp | (u << 6) | (g << 3) | t;
        ui.val.setText("权限值：" + String.format("%04o", mode) + "  (" + modeToSym(mode) + ")");
    }

    /** 应用权限：对选中项的每一项执行 chmod 八进制 + chown 用户:组（Root 模式映射到真实命名空间）。 */
    private void applyPerm(List<Props> items, PermUI ui) {
        if (items == null || items.isEmpty() || ui.ownerSpin == null || ui.groupSpin == null) {
            toast("当前无法获取权限信息，无法应用");
            return;
        }
        int u = (ui.uR.isChecked() ? 4 : 0) | (ui.uW.isChecked() ? 2 : 0) | (ui.uX.isChecked() ? 1 : 0);
        int g = (ui.gR.isChecked() ? 4 : 0) | (ui.gW.isChecked() ? 2 : 0) | (ui.gX.isChecked() ? 1 : 0);
        int t = (ui.oR.isChecked() ? 4 : 0) | (ui.oW.isChecked() ? 2 : 0) | (ui.oX.isChecked() ? 1 : 0);
        int sp = (ui.suid.isChecked() ? 04000 : 0) | (ui.sgid.isChecked() ? 02000 : 0) | (ui.sticky.isChecked() ? 01000 : 0);
        final int mode = sp | (u << 6) | (g << 3) | t;
        final int ou = ((UidEntry) ui.ownerSpin.getSelectedItem()).uid;
        final int gg = ((UidEntry) ui.groupSpin.getSelectedItem()).uid;
        final boolean recursive = ui.recursive != null && ui.recursive.isChecked();
        final boolean dirsOnly = ui.dirsOnly != null && ui.dirsOnly.isChecked();

        // 拼成一条批量命令，逐个文件/文件夹执行 chmod + chown（文件夹可选 -R 递归）
        StringBuilder sb = new StringBuilder();
        int applied = 0;
        for (Props o : items) {
            if (dirsOnly && !o.dir) continue;   // 「只用于目录」：跳过非文件夹项
            applied++;
            String mapped = Fs.rootMode ? Fs.rootPath(o.path) : o.path;
            String rc = (recursive && o.dir) ? "-R " : "";
            sb.append("chmod ").append(rc).append(String.format("%04o", mode)).append(' ')
              .append(Util.sq(mapped)).append(" 2>/dev/null ; ")
              .append("chown ").append(rc).append(ou).append(':').append(gg).append(' ')
              .append(Util.sq(mapped)).append(" 2>/dev/null ; ");
        }
        if (applied == 0) { toast("没有可应用的目标（已勾选「只用于目录」但选中项均为文件）"); return; }
        Fs.ExecResult r = Fs.shell(sb.toString(), Fs.rootMode);
        if (r.ok()) {
            toast("权限已更新（" + applied + " 项）");
            // 单文件时回读并刷新勾选框
            if (items.size() == 1) {
                Props o = items.get(0);
                Props n = statProps(o.path);
                if (n != null) {
                    o.modeOctal = n.modeOctal; o.ownerUid = n.ownerUid; o.groupGid = n.groupGid;
                    o.ownerName = n.ownerName; o.groupName = n.groupName; o.modeSym = n.modeSym;
                    syncPerm(o, ui);
                }
            }
        } else {
            String msg = (r.err != null && r.err.trim().length() > 0) ? r.err.trim() : ("退出码 " + r.code);
            toast("更新失败（可能需要 Root 授权）：" + msg);
        }
    }

    /** 构建 uid 候选列表：常用 AID + 已安装应用 uid（pm list packages -U）+ 当前文件的所有者/组。 */
    private ArrayList<UidEntry> buildUidEntries(int curOwner, int curGroup) {
        LinkedHashMap<Integer, String> map = new LinkedHashMap<Integer, String>();
        for (Object[] e : AID) map.put((Integer) e[0], (String) e[1]);
        try {
            Fs.ExecResult r = Fs.shell("pm list packages -U 2>/dev/null", Fs.rootMode);
            if (r.ok()) {
                for (String ln : r.out.split("\n")) {
                    ln = ln.trim();
                    int pi = ln.indexOf("package:"), ui = ln.indexOf("uid:");
                    if (pi >= 0 && ui > pi) {
                        String pkg = ln.substring(pi + 8, ui).trim();
                        try {
                            int id = Integer.parseInt(ln.substring(ui + 4).trim());
                            if (!map.containsKey(id)) map.put(id, pkg);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (curOwner >= 0) { if (!map.containsKey(curOwner)) map.put(curOwner, String.valueOf(curOwner)); }
        if (curGroup >= 0) { if (!map.containsKey(curGroup)) map.put(curGroup, String.valueOf(curGroup)); }
        ArrayList<UidEntry> list = new ArrayList<UidEntry>();
        for (Map.Entry<Integer, String> e : map.entrySet()) list.add(new UidEntry(e.getKey(), e.getValue()));
        Collections.sort(list, new Comparator<UidEntry>() {
            public int compare(UidEntry a, UidEntry b) { return a.uid - b.uid; }
        });
        return list;
    }

    /** 八进制权限转符号串，含 SUID/SGID/Sticky 标记（大小写表示执行位是否同时置位）。 */
    private static String modeToSym(int mode) {
        char[] c = new char[9];
        c[0] = (mode & 0400) != 0 ? 'r' : '-';
        c[1] = (mode & 0200) != 0 ? 'w' : '-';
        c[2] = (mode & 0100) != 0 ? ((mode & 04000) != 0 ? 's' : 'x') : ((mode & 04000) != 0 ? 'S' : '-');
        c[3] = (mode & 0040) != 0 ? 'r' : '-';
        c[4] = (mode & 0020) != 0 ? 'w' : '-';
        c[5] = (mode & 0010) != 0 ? ((mode & 02000) != 0 ? 's' : 'x') : ((mode & 02000) != 0 ? 'S' : '-');
        c[6] = (mode & 0004) != 0 ? 'r' : '-';
        c[7] = (mode & 0002) != 0 ? 'w' : '-';
        c[8] = (mode & 0001) != 0 ? ((mode & 01000) != 0 ? 't' : 'x') : ((mode & 01000) != 0 ? 'T' : '-');
        return new String(c);
    }

    // ==================== Tab ====================

    /** 冷启动时恢复上次打开的标签页：数量、各标签页路径与前进/后退历史、当前激活项。 */
    private void restoreTabs() {
        tabs.clear();
        tabViews.clear();
        Prefs.TabSession s = Prefs.loadTabs(this);
        if (s != null) {
            for (Prefs.TabState ts : s.tabs) tabs.add(Tab.from(ts));
            active = s.active;
        } else {
            tabs.add(new Tab(homePath()));
            active = 0;
        }
        rebuildTabs();
        snapActiveTabVisible();     // 冷启动恢复的激活标签可能位于标签栏视野外，即时定位
        applyTabLockUi();   // 恢复标签后同步锁定态（地址栏/导航按钮可用性等）
        pathEdit.setText(cur().path);
        saveTabs();   // 立即写回一份规范化快照
    }

    /** 把当前标签页会话写入磁盘，供下次冷启动恢复。 */
    private void saveTabs() {
        if (tabs.isEmpty()) return;
        Prefs.TabSession s = new Prefs.TabSession();
        for (Tab t : tabs) {
            Prefs.TabState ts = new Prefs.TabState();
            ts.path = t.path;
            ts.pos = t.pos;
            ts.locked = t.locked;
            ts.lockRoot = t.lockRoot;
            ts.history.addAll(t.history);
            s.tabs.add(ts);
        }
        s.active = active;
        Prefs.saveTabs(this, s);
    }

    private Tab cur() { return tabs.get(active); }

    /** 锁定标签只允许在「锁定根目录（含）及其子目录」内活动；未锁定或锁定时目标合法返回 true。 */
    private boolean canEnter(String p) {
        if (isArchivePath(p)) return true;   // 压缩包内的虚拟路径属于当前视图，不受标签锁定约束
        Tab t = cur();
        if (!t.locked || t.lockRoot == null) return true;
        if (t.lockRoot.equals(p)) return true;
        return p.startsWith(t.lockRoot + "/");
    }

    private void addTab(String path, boolean select) {
        tabs.add(new Tab(path));
        if (select) active = tabs.size() - 1;
        rebuildTabs();
        if (select) {
            pathEdit.setText(cur().path);
            // 新建标签后把激活标签滚入可视区域（含最左 / 最右边缘）
            ensureActiveTabVisible();
            refresh();
        }
        saveTabs();   // select=false 时不触发刷新，这里仍需落盘
    }

    /**
     * 把当前激活标签滚动到可视区域内：仅在标签左端或右端被裁掉时做最小滚动，
     * 已完整可见则不动。用于新建 / 关闭 / 点击 / 左右滑动切换标签后，
     * 避免最左或最右的激活标签被横向标签栏隐藏。
     */
    private void ensureActiveTabVisible() {
        if (tabScroll == null || active < 0 || active >= tabViews.size()) return;
        final int[] tries = {0};
        final Runnable task = new Runnable() {
            @Override public void run() {
                if (active < 0 || active >= tabViews.size()) return;
                final View v = tabViews.get(active);
                int t = tabScrollTarget(v.getLeft(), v.getRight(),
                        tabScroll.getWidth(), tabScroll.getScrollX());
                if (t == -2) {                       // 冷启动 / rebuild 后布局未完成，下一帧再试
                    if (tries[0]++ < 30) tabScroll.post(this);
                    return;
                }
                if (t >= 0) {
                    Dbg.log("TAB", "ensureActiveTabVisible 滚动标签栏 active=" + active
                            + " -> scrollX=" + t);
                    tabScroll.smoothScrollTo(t, 0);
                }
            }
        };
        tabScroll.post(task);
    }

    /** 窗口可见时把当前标签即时定位到可视区域（不带动画）：冷启动/onResume 时
     *  smoothScrollTo 的动画在窗口尚未完成显示时常被系统吞掉，导致激活标签停在视野外。
     *  这里用 scrollTo 保证一定到位，位置已正确时为空操作。 */
    private void snapActiveTabVisible() {
        if (tabScroll == null || active < 0 || active >= tabViews.size()) return;
        final View v = tabViews.get(active);
        final int[] tries = {0};
        final Runnable task = new Runnable() {
            @Override public void run() {
                if (active < 0 || active >= tabViews.size()) return;
                int t = tabScrollTarget(v.getLeft(), v.getRight(),
                        tabScroll.getWidth(), tabScroll.getScrollX());
                if (t == -2) { if (tries[0]++ < 30) tabScroll.post(this); return; }
                if (t >= 0) tabScroll.scrollTo(t, 0);
            }
        };
        tabScroll.post(task);
    }

    /** 纯计算：把 [left,right] 滚入 [scrollX, scrollX+viewW] 所需的最小 scrollX。
     *  返回 -1 = 已完整可见无需滚动；-2 = 布局未完成（viewW<=0 或视图未测量）。 */
    static int tabScrollTarget(int left, int right, int viewW, int scrollX) {
        if (viewW <= 0 || right <= left) return -2;
        if (left < scrollX) return Math.max(0, left);
        if (right > scrollX + viewW) return Math.max(0, right - viewW);
        return -1;
    }

    private void rebuildTabs() {
        tabStrip.removeAllViews();
        tabViews.clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            // 锁定标签：文字固定为锁定根目录名，进入子文件夹也不变
            String label = (t.locked && t.lockRoot != null) ? labelOf(t.lockRoot) : labelOf(t.path);

            final boolean isActive = (i == active);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            // 标签最小宽度：防止名称很短（如 /、单字）时标签被压得过窄，保证可点区域与 × / 🔒 有足够空间
            item.setMinimumWidth(Util.dp(this, 50));
            // 选中态改由「底部着重色条」表达：标签主体透明、文字始终深色。
            item.setBackgroundColor(Color.TRANSPARENT);

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.TOP | Gravity.START);   // 文字与 × 靠上、水平从左侧排起，不做居中
            line.setPadding(Util.dp(this, 8), Util.dp(this, 3), Util.dp(this, 4), Util.dp(this, 2));   // 上下压紧，左右保持 8/4 不变
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextSize(Ui.sp(textLv, 11, 12, 14));
            tv.setTextColor(isActive ? Ui.TEXT : Ui.HINT);   // 当前标签深色，非当前标签灰色
            tv.setGravity(Gravity.TOP | Gravity.START);   // 文字明确左上角，不用居中
            tv.setSingleLine(true);
            tv.setMaxWidth(Util.dp(this, 90));   // 超长名称截断，避免单个标签过宽
            line.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // 始终渲染 × / 🔒 指示器：单个标签页锁定后也要能看到 🔒。
            // （仅一个标签时点击 × 由 closeTab 守卫拦截，不会误关。）
            {
                TextView x = new TextView(this);
                if (t.locked) {
                    // 锁定态：× 变为 🔒，点击不关闭，需长按标签解锁
                    x.setText(" 🔒");
                    x.setTextColor(Ui.PRIMARY);
                    x.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { toast("标签已锁定，长按标签可解锁"); }
                    });
                } else {
                    x.setText(" ×");
                    x.setTextSize(Ui.sp(textLv, 11, 12, 14));
                    x.setTextColor(Ui.HINT);
                    x.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { closeTab(idx); }
                    });
                }
                x.setTextSize(Ui.sp(textLv, 11, 12, 14));
                line.addView(x);
            }
            item.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 底部着重色条：选中显示 Ui.PRIMARY，未选中用同高度透明条占位，
            // 保证所有标签等高、切换选中时标签栏不跳动；item 无 padding，故条通宽贴底。
            View bar = new View(this);
            bar.setBackgroundColor(isActive ? Ui.PRIMARY : Color.TRANSPARENT);
            item.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 8)));
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { switchTab(idx); }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    boolean locked = !tabs.get(idx).locked;
                    tabs.get(idx).locked = locked;
                    // 锁定时记录当前目录为「锁定根」，之后只能在其子目录内活动
                    tabs.get(idx).lockRoot = locked ? tabs.get(idx).path : null;
                    rebuildTabs();
                    applyTabLockUi();
                    saveTabs();
                    toast(locked ? "已锁定标签（长按可解锁）" : "已解锁标签");
                    return true;
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 自适应宽度：按标签文字实际长度排布，不再固定占满；文字最长 90dp（见 tv.setMaxWidth），
            // 单个标签宽度即被限制在该上限内，避免长名称标签过宽。
            // 左边框：每个标签左侧一条 1dp 细线，颜色走 Ui.line() 与 Tab 栏上方横线完全一致。
            // MATCH_PARENT 高度由 tabStrip 统一撑满；不加入 tabViews，故滚动定位的 getLeft()/getRight() 仍准确。
            View sep = Ui.line(this);
            tabStrip.addView(sep, new LinearLayout.LayoutParams(
                    Util.dp(this, 1), ViewGroup.LayoutParams.MATCH_PARENT));
            tabStrip.addView(item, lp);
            tabViews.add(item);
        }
        // 最后一个标签补一条右边框：与左边框同源同宽，形成完整的标签围框。
        // 同样不加入 tabViews，自动滚动定位不受影响。
        if (!tabs.isEmpty()) {
            View rightBorder = Ui.line(this);
            tabStrip.addView(rightBorder, new LinearLayout.LayoutParams(
                    Util.dp(this, 1), ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private String labelOf(String path) {
        if (path.equals("/")) return "根目录";
        String h = homePath();
        if (path.equals(h)) return "内部存储";
        return Util.base(path);
    }

    private void switchTab(int i) {
        closeSortDropdown();   // 切标签时收起所有下拉菜单
        closeAddDropdown();
        closeFavDropdown();
        Dbg.log("TAB", "switchTab " + active + " -> " + i + " 共 " + tabs.size() + " 个");
        if (i == active || i < 0 || i >= tabs.size()) return;
        if (searchActive) exitSearch();   // 搜索态下切标签需先退出搜索，否则 refresh 提前返回、网格不刷新
        rememberScroll();                 // 记住旧标签当前路径的滚动位置
        active = i;
        rebuildTabs();
        ensureActiveTabVisible();   // 连续左右滑动到边缘标签时，不能让当前标签留在视野外
        pathEdit.setText(cur().path);
        int[] mem = (scrollMem != null) ? scrollMem.recall(cur().path) : null;
        if (mem != null) { cur().scroll = mem[0]; restoreTop = mem[1]; }
        else { cur().scroll = 0; restoreTop = 0; }
        refresh(true, false);   // 仅切换标签时恢复该标签的滚动位置
    }

    // 横向滑动切换标签页：当前内容滑出屏外 → 切到目标标签 → 新内容从另一侧无缝滑入
    private void animateSwipeToTab(int target, int dir) {
        if (gridAnimating) return;
        gridAnimating = true;
        int w = grid.getWidth();
        if (w <= 0) { switchTab(target); gridAnimating = false; return; }
        float exitX = -dir * w;     // 当前内容滑出方向（dir=+1 左滑 → 向左滑出，X 为负）
        float enterX = dir * w;     // 新内容从对侧屏外进入
        // 1) 当前内容从手指位置继续滑出到屏外
        grid.animate().translationX(exitX).setDuration(150)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
            public void run() {
                switchTab(target);   // 切换内容（此时在屏外，用户不可见，避免闪烁）
                adapter.setData(new ArrayList<FileItem>());  // 清掉旧列表，滑入时只显示新页（清空发生在屏外，不可见）
                // 2) 新内容从对侧屏外滑入
                grid.setTranslationX(enterX);
                grid.animate().translationX(0).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(new Runnable() { public void run() { gridAnimating = false; } })
                        .start();
            }
        }).start();
    }

    private void closeTab(int i) {
        if (tabs.size() <= 1) return;
        closeSortDropdown();   // 关标签时收起所有下拉菜单
        closeAddDropdown();
        closeFavDropdown();
        if (searchActive) exitSearch();   // 同 switchTab：避免搜索态下 refresh 提前返回
        if (i >= 0 && i < tabs.size() && tabs.get(i).locked) {
            toast("该标签已锁定，长按标签解锁后再关闭");
            return;
        }
        final boolean closedActive = (i == active);   // 关掉的是否为当前正在显示的标签
        Dbg.log("TAB", "closeTab " + i + " 共 " + tabs.size() + " 个 closedActive=" + closedActive);
        tabs.remove(i);
        tabViews.remove(i);
        if (active >= tabs.size()) active = tabs.size() - 1;
        else if (i < active) active--;
        rebuildTabs();
        ensureActiveTabVisible();
        pathEdit.setText(cur().path);
        applyTabLockUi();   // 激活标签可能换了，地址栏锁定态立即同步
        if (closedActive) {
            // 网格里仍是刚被关掉标签的数据：新激活标签的 path 与它自己的 loadedPath 相同，
            // 若调用 refresh() 会命中「同目录已加载」早退、列表纹丝不动，于是错显旧标签内容。
            // 按 switchTab 的语义恢复滚动位置并重新列目录，把列表换成新激活标签的内容。
            curItems = new ArrayList<FileItem>();
            adapter.setData(curItems);   // 先清掉旧标签残留，加载期间不再错显
            int[] mem = (scrollMem != null) ? scrollMem.recall(cur().path) : null;
            if (mem != null) { cur().scroll = mem[0]; restoreTop = mem[1]; }
            else { cur().scroll = 0; restoreTop = 0; }
            refresh(true, false);
        } else {
            // 关闭的是后台标签：当前显示的列表本来就属于激活标签，保持纹丝不动
            saveTabs();                 // 但标签数量变了，会话状态仍需落盘
            updateBottomBar();
            updateStatus();
        }
    }

    // ==================== 导航 / 加载 ====================

    /** 第一可见项下标（RecyclerView 版）。 */
    private int firstVisible() {
        return gridLm == null ? 0 : gridLm.findFirstVisibleItemPosition();
    }

    /** 第一可见项的像素偏移（用于 scrollToPositionWithOffset 精确还原）。 */
    private int firstTop() {
        if (gridLm == null) return 0;
        int p = gridLm.findFirstVisibleItemPosition();
        View v = gridLm.findViewByPosition(p);
        return v == null ? 0 : v.getTop();
    }

    /** 离开当前目录前，把「第一可见项下标 + 首项像素偏移」按路径记入持久化记忆。 */
    private void rememberScroll() {
        if (scrollMem == null || tabs.isEmpty()) return;
        String p = cur().path;
        if (p == null || p.isEmpty()) return;
        int pos = firstVisible();
        int top = firstTop();
        Log.d("ScrollMem", "remember " + p + " pos=" + pos + " top=" + top);
        scrollMem.remember(p, pos, top);
        scrollMem.persist(this);
    }

    private void gotoPath(String raw) {
        gotoPath(raw, false);
    }

    /**
     * 切换目录。默认进入新目录回到顶部；
     * 但当「当前在压缩包视图、目标已退出到真实目录」时（从压缩包返回），恢复进入压缩包前的滚动位置，
     * 行为同图片查看器插件——返回时不跳回列表顶部。
     */
    private void gotoPath(String raw, boolean forceRestore) {
        Dbg.log("NAV", "gotoPath raw=" + raw + " forceRestore=" + forceRestore
                + " 当前=" + (tabs.isEmpty() ? "-" : cur().path));
        String p = raw;
        if (p == null || p.trim().isEmpty()) return;
        p = p.trim();
        if (p.length() > 1 && p.endsWith("/")) p = p.replaceAll("/+$", "");
        // 锁定标签：只允许在「锁定根目录（含）及其子目录」内活动，禁止返回上级或跳到其它位置。
        // 越界跳转在此统一静默拦截（不弹提示），地址栏回填当前真实路径避免显示与实际不符。
        if (!canEnter(p)) {
            Dbg.log("NAV", "gotoPath 被锁定拦截 raw=" + p + " 当前=" + cur().path);
            if (pathEdit != null) pathEdit.setText(cur().path);   // 越界路径不生效，回填当前真实路径
            return;
        }
        // 离开当前目录前按路径记住位置：返回上级 / 退出压缩包 / 重启后都能回到原处
        rememberScroll();
        cur().sel.clear();
        adapter.setChecked(cur().sel);
        // 目标路径若有记忆位置则恢复（含「从 7z 插件返回真实目录」）；否则进入新目录回顶部
        int[] mem = (scrollMem != null) ? scrollMem.recall(p) : null;
        boolean restore = forceRestore || mem != null
                || (isArchivePath(cur().path) && !isArchivePath(p));
        if (mem != null) { cur().scroll = mem[0]; restoreTop = mem[1]; }
        else if (restore) { cur().scroll = archiveExitScroll; restoreTop = 0; }
        else { cur().scroll = 0; restoreTop = 0; }
        Log.d("ScrollMem", "goto " + p + " mem=" + (mem == null ? "null" : mem[0] + "," + mem[1])
                + " restore=" + restore + " scroll=" + cur().scroll + " top=" + restoreTop);
        cur().push(p);
        pathEdit.setText(p);
        refresh(restore, false);
    }

    // ==================== 外部「打开方式 / 分享」接收 ====================

    /** 解析外部以「打开方式 / 分享」发来的文件，返回可访问的真实路径；无法定位返回 null。 */
    private String parseIncomingIntent(Intent intent) {
        if (intent == null) return null;
        String act = intent.getAction();
        if (Intent.ACTION_VIEW.equals(act)) {
            return resolveIncomingPath(intent.getData());
        }
        if (Intent.ACTION_SEND.equals(act)) {
            return resolveIncomingPath((Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM));
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty()) return resolveIncomingPath(list.get(0));
        }
        return null;
    }

    /** 判定本次启动/复用是否由「其它 APP」从外部以「打开方式 / 分享」拉起本界面。
     *  直接依据 Intent action，而非 parseIncomingIntent 是否解析出真实文件路径——
     *  外部共享的 content://（如其它 APP 的 fileprovider）往往解析不出真实路径，
     *  但依旧是外部拉起，按返回必须回到外部 APP，而不是逐级返回上级目录。
     *  本应用自己拉起自己会带 EXTRA_SELF_INVOKED，须排除。 */
    private boolean isExternalLaunch(Intent intent) {
        if (intent == null) return false;
        if (intent.getBooleanExtra(EXTRA_SELF_INVOKED, false)) return false;
        String act = intent.getAction();
        return Intent.ACTION_VIEW.equals(act)
                || Intent.ACTION_SEND.equals(act)
                || Intent.ACTION_SEND_MULTIPLE.equals(act);
    }

    /** 把外部 Uri 解析成真实文件路径：file:// 直接取；本 APP 自己的 content:// 取 path；
     *  其余 content:// 尽力从 MediaStore 的 _data 取真实路径，取不到返回 null（无法定位目录）。 */
    private String resolveIncomingPath(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) return uri.getPath();
        if ("content".equalsIgnoreCase(scheme)) {
            if (FileProvider.AUTH.equals(uri.getAuthority())) return uri.getPath();
            try {
                Cursor cur = getContentResolver().query(uri,
                        new String[]{android.provider.MediaStore.MediaColumns.DATA}, null, null, null);
                if (cur != null) {
                    try {
                        int i = cur.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                        if (i >= 0 && cur.moveToFirst()) {
                            String p = cur.getString(i);
                            if (p != null && new File(p).exists()) return p;
                        }
                    } finally { cur.close(); }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 把解析不出本地路径的外部 content:// 复制到本应用可访问的外部存储目录，
     *  返回本地真实路径；无读取权限或复制失败返回 null。供外部「打开方式」定位并选中该文件。 */
    private String copyContentToLocal(Uri uri) {
        if (uri == null) return null;
        File dir = new File(getExternalFilesDir(null), "external_open");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String name = queryDisplayName(uri);
        if (name == null || name.isEmpty()) name = "shared_file";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);   // 只取最后一段文件名
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");     // 去掉非法字符
        File out = new File(dir, name);
        int n = 1;
        while (out.exists()) {                              // 同名不覆盖，加序号
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            out = new File(dir, base + " (" + (n++) + ")" + ext);
        }
        InputStream in = null;
        FileOutputStream os = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (os != null) os.close(); } catch (Throwable ignored) {}
        }
    }

    /** 从 content:// 的 provider 取显示文件名，作为复制后的文件名；取不到则退化为末段。 */
    private String queryDisplayName(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri,
                    new String[]{"_display_name"}, null, null, null);
            if (c != null) {
                try {
                    int i = c.getColumnIndex("_display_name");
                    if (i >= 0 && c.moveToFirst()) {
                        String s = c.getString(i);
                        if (s != null && !s.isEmpty()) return s;
                    }
                } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        return uri.getLastPathSegment();
    }

    /** 收到外部文件：若扩展名命中某插件关联，直接用该插件打开（zip→压缩包视图，图片→查看器）；
     *  否则定位到其所在目录并选中高亮，必要时自动执行「接收命令」。
     *  @param external 是否由其它 APP 发起（打开方式/分享）。false 时仅定位选中，不自动打开插件。 */
    private void handleIncomingFile(String path, boolean external) {
        if (path == null) return;
        if (!external) { locateIncoming(path, false); return; }
        // 外部拉起：扩展名匹配插件关联则直接启动对应插件的独立 Activity（不进入 MainActivity 浏览界面）
        final String ext = Util.ext(path);
        io.execute(new Runnable() {
            public void run() {
                // 图片查看器插件：直接启动独立的 ImageViewerActivity
                PluginManager.ViewerHandle vh = pluginMgr.getViewer(path, "*/*", ext);
                if (vh != null) {
                    final PluginManager.ViewerHandle fvh = vh;
                    ui.post(new Runnable() { public void run() {
                        try { startActivity(fvh.viewIntent); finish(); } catch (Throwable t) {}
                    }});
                    return;
                }
                // 压缩包插件：在本应用内直接进压缩包视图（不依赖独立 Activity）
                PluginManager.ArchiveHandle ah = pluginMgr.getArchiveProvider(path, null);
                if (ah != null) {
                    final String ap = path;
                    ui.post(new Runnable() { public void run() {
                        try { enterArchive(new FileItem(Util.base(ap), ap, false, 0, 0)); }
                        catch (Throwable t) {}
                    }});
                    return;
                }
                ui.post(new Runnable() { public void run() { locateIncoming(path, true); }});
            }
        });
    }

    /** 定位外部文件所在目录并选中（原 handleIncomingFile 逻辑），必要时自动执行「接收命令」。 */
    private void locateIncoming(String path, boolean external) {
        String parent = Util.parent(path);
        if (parent == null || parent.isEmpty()) {
            toast("无法定位文件所在目录：" + path);
            return;
        }
        pendingSelectPath = path;
        Prefs.Cmd rx = Prefs.rxCommand(this);
        pendingRxCmd = (rx != null && rx.tmpl != null && !rx.tmpl.trim().isEmpty()) ? rx : null;
        if (external) {
            addTab(parent, true);          // 其它 APP 发起：新标签页打开，列目录后由 applyPendingSelect 选中
        } else if (parent.equals(cur().path)) {
            applyPendingSelect();          // 当前已在目标目录，直接选中
        } else {
            gotoPath(parent);              // 导航过去，列目录完成后由 applyPendingSelect 选中
        }
    }

    /** 判断 intent 是否携带有意义参数（用于决定是否展示调试窗口，避免普通启动也弹窗）。 */
    private static boolean hasIntentParams(Intent intent) {
        if (intent == null) return false;
        if (intent.getData() != null) return true;
        if (intent.getExtras() != null && !intent.getExtras().isEmpty()) return true;
        String a = intent.getAction();
        return a != null && !Intent.ACTION_MAIN.equals(a);
    }

    /** 调试：把接收 intent 的全部参数（Action / Data / Type / Categories / Flags / Extras）列到结果窗口。 */
    private void showDebugParams(final Intent intent) {
        if (intent == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Action   : ").append(intent.getAction()).append('\n');
        sb.append("Data     : ").append(intent.getData()).append('\n');
        sb.append("Type     : ").append(intent.getType()).append('\n');
        if (intent.getCategories() != null)
            sb.append("Categories: ").append(intent.getCategories()).append('\n');
        sb.append("Flags    : 0x").append(Integer.toHexString(intent.getFlags())).append('\n');
        Bundle b = intent.getExtras();
        if (b != null && !b.isEmpty()) {
            sb.append("\nExtras:\n");
            for (String k : b.keySet()) {
                Object v = b.get(k);
                sb.append("  ").append(k).append(" = ").append(stringifyExtra(v)).append('\n');
            }
        } else {
            sb.append("\n(无 Extras)\n");
        }
        final String text = sb.toString();
        CmdOutputActivity.launchStreaming(this, "调试：接收到的参数", new Runnable() {
            public void run() {
                CmdOutputActivity.liveAppend(text);
                CmdOutputActivity.liveComplete();
            }
        });
    }

    /** 把 Extra 值安全转成可读字符串（Uri/Collection 等逐个处理，其余用 toString）。 */
    private static String stringifyExtra(Object v) {
        if (v == null) return "null";
        if (v instanceof android.os.Parcelable) return String.valueOf(v);
        if (v instanceof java.io.Serializable) return String.valueOf(v);
        if (v instanceof java.util.ArrayList) {
            StringBuilder s = new StringBuilder("[");
            for (Object o : (java.util.ArrayList<?>) v) {
                if (s.length() > 1) s.append(", ");
                s.append(stringifyExtra(o));
            }
            s.append("]");
            return s.toString();
        }
        return v.getClass().getSimpleName() + "(" + v + ")";
    }

    /** 列目录完成后：若本次是被「打开方式」带进来的文件，选中它并自动执行接收命令。 */
    private void applyPendingSelect() {
        if (pendingSelectPath == null) return;
        final String want = pendingSelectPath;
        final String wantParent = Util.parent(want);
        pendingSelectPath = null;          // 先清空，避免重复触发
        if (wantParent == null || !wantParent.equals(cur().path)) return;
        for (int i = 0; i < adapter.getCount(); i++) {
            FileItem it = adapter.getItem(i);
            if (it.path.equals(want)) {
                cur().sel.add(want);
                adapter.setChecked(cur().sel);
                updateBottomBar();
                gridLm.scrollToPositionWithOffset(i, 0);
                break;
            }
        }
        if (pendingRxCmd != null) {
            final Prefs.Cmd cmd = pendingRxCmd;
            pendingRxCmd = null;
            executeCmd(cmd, java.util.Collections.singletonList(want), wantParent, false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Prefs.debugRx(this) && hasIntentParams(intent)) showDebugParams(intent);
        if (isExternalLaunch(intent)) launchedFromExternal = true;   // 其它 APP 复用本实例拉起，仍按「返回回外部 APP」
        String p = parseIncomingIntent(intent);
        boolean self = intent.getBooleanExtra(EXTRA_SELF_INVOKED, false);
        if (p != null) {
            handleIncomingFile(p, !self);   // 解析出本地路径：直接定位
        } else if (!self) {
            Uri data = intent.getData();
            if (data != null && "content".equalsIgnoreCase(data.getScheme())) {
                // 外部 content:// 解析不出本地路径：后台复制后定位选中
                final Uri u = data;
                io.execute(new Runnable() {
                    public void run() {
                        final String local = copyContentToLocal(u);
                        ui.post(new Runnable() {
                            public void run() {
                                if (local != null) handleIncomingFile(local, true);
                                else toast("无法打开该文件：" + u);
                            }
                        });
                    }
                });
            }
        }
    }

    /**
     * 「返回」＝回到浏览历史中的上一个目录，而不是父文件夹。
     * 逐层进入子目录时，上一站恰好就是父目录；而用地址栏、收藏夹、打开方式等
     * 跳转到一个不相干的目录后，按返回会回到跳转前所在的目录。
     * 有选中项时先取消选择；历史已到顶返回 false，由调用方决定兜底行为。
     */
    private boolean goBack() {
        if (!cur().sel.isEmpty()) { clearSel(); return true; }
        if (!cur().back()) return false;
        // 锁定标签：若历史回退会离开「锁定根目录及其子目录」，静默撤销本次回退（不允许返回上级）
        if (cur().locked && !canEnter(cur().path)) {
            cur().forward();   // 还原历史指针
            return true;
        }
        cur().sel.clear();
        adapter.setChecked(cur().sel);
        cur().scroll = 0;
        // 贴顶与否交给 refresh() 按路径变化判断（返回到的目录可能与当前不同，也可能是同一处）
        pathEdit.setText(cur().path);
        refresh();
        return true;
    }

    private void refresh() { refresh(false, false); }

    /** 强制重列目录：文件操作、命令执行、手动刷新、权限授予、隐藏文件/Root 开关变化后使用。 */
    private void refreshForce() { refresh(false, true); }

    /**
     * @param restoreScroll 是否恢复该标签上次记录的滚动位置（仅切换标签时为 true）。
     * @param force 即使路径没变也强制重新列目录。
     *
     * 只有「路径发生变化」或 force 时才真正列目录；同一目录的刷新（打开外部应用后
     * onResume 返回、从设置页返回且列表设置未变）直接跳过，完全不触碰 GridView，
     * 彻底杜绝「没换目录却被拉回顶部」。t.loadedPath 记录上次实际加载的路径。
     *
     * 换目录时做三重归位贴顶：notifyDataSetChanged 会让 GridView 在下次布局沿用旧滚动
     * 偏移，单靠一次 setSelection(0) 偶尔会被覆盖，因此换数据前、换数据后、
     * 布局完成后各归位一次（post 带刷新序号校验）。
     */
    /** 列目录统一入口：普通目录走 Fs，archive:// 虚拟路径走插件。后台线程调用。 */
    private Fs.ListResult loadItems(String path) {
        Fs.ListResult r = isArchivePath(path) ? loadArchive(path) : Fs.list(path);
        if (r != null && r.error == null && r.items != null) applySort(r.items);
        return r;
    }

    /** archive:// 虚拟路径：向压缩包插件取内层条目，构造标准 FileItem 列表（含「返回」项）。 */
    private Fs.ListResult loadArchive(String path) {
        Fs.ListResult out = new Fs.ListResult();
        String[] a = parseArchive(path);
        PluginManager.ArchiveHandle h = pluginMgr.getArchiveProvider(a[1], null);
        if (h == null) {
            out.error = "没有可用的压缩包插件（需 root + Termux 的 7z）";
            return out;
        }
        try {
            ArchiveListing lst = h.provider.list(a[1], a[2], "");
            if (lst.error != null && !lst.error.isEmpty()) {
                out.error = lst.error;
                return out;
            }
            out.items.add(new FileItem(".", archiveParent(path), true, 0, 0, true));
            for (ArchiveEntry e : lst.entries) {
                String vp = ARCHIVE_SCHEME + a[0] + encSeg(a[1]) + "#" + encSeg(e.innerPath);
                out.items.add(new FileItem(e.name, vp, e.isDir, e.size, e.modified));
            }
            return out;
        } catch (RemoteException ex) {
            out.error = "插件通信失败：" + ex.getMessage();
            return out;
        } catch (Throwable t) {
            out.error = "读取压缩包失败：" + t.getMessage();
            return out;
        }
    }

    private void refresh(final boolean restoreScroll, final boolean force) {
        if (tabs.isEmpty()) return;
        if (searchActive) return;   // 搜索进行中：保留结果网格，不重载当前目录
        saveTabs();          // 导航、前进/后退、文件操作后即时记住现场
        updateBottomBar();
        final Tab t = cur();
        final String path = t.path;
        if (force) clearSel();   // 强制刷新（手动刷新 / 文件操作 / Root 开关）后清空选中项
        adapter.setChecked(t.sel);

        if (!restoreScroll && !force && path.equals(t.loadedPath)) {
            // 同目录且已加载过：只同步状态栏文字，不重新列目录、不动滚动位置
            updateStatus();
            return;
        }

        final int seq = ++refreshSeq;
        final boolean dirChanged = !path.equals(t.loadedPath);
        loading = true;
        // 压缩包加载：底部状态栏显示友好提示（压缩包名 / 内层目录），而非不可读的 archive:// 路径
        status.setText(isArchivePath(path) ? "加载中… " + archiveDlgLabel(path) : "加载中… " + path);
        empty.setVisibility(View.GONE);   // 不残留上一次的错误 / 空目录提示
        // 换目录时立刻把旧列表拉回顶部，加载新目录期间也不会停在中间
        if (dirChanged && !restoreScroll) {
            gridLm.scrollToPositionWithOffset(0, 0);
        }
        // archive:// 列目录要跨进程让插件跑 7z l，大包可能明显耗时：
        //  - 首次从真实目录进入压缩包：显示「打开中...」并允许取消；
        //  - 压缩包内导航（已进入归档、切换内层目录/返回）：不再弹「打开中」，避免频繁打断浏览。
        final OpenArchiveDlg openDlg;
        if (isArchivePath(path)) {
            final OpenArchiveDlg pending = archiveOpenDlg;
            if (pending != null && !pending.cancelled && !pending.finished) {
                openDlg = pending;                 // 复用点击压缩包时提前弹出的框，全程只显示一次
            } else if (!isArchivePath(t.loadedPath)) {
                openDlg = new OpenArchiveDlg(archiveDlgLabel(path));   // 首次进入压缩包
                archiveOpenDlg = openDlg;
            } else {
                openDlg = null;                    // 压缩包内浏览：不弹「打开中」
            }
            if (openDlg != null) {
                openDlg.bind(path, t.loadedPath, seq);
                Dbg.log("ARCHIVE", "列目录开始 path=" + path + " 复用提示框=" + (openDlg == pending));
            } else {
                Dbg.log("ARCHIVE", "列目录开始(包内导航,不弹框) path=" + path);
            }
        } else {
            openDlg = null;
        }
        listIo.execute(new Runnable() {
            public void run() {
                Fs.ListResult res;
                try {
                    res = loadItems(path);
                } catch (Throwable t2) {
                    res = new Fs.ListResult();
                    res.error = "读取失败：" + t2.getMessage();
                }
                final Fs.ListResult rr = res;
                if (openDlg != null) {
                    openDlg.finish();       // 列目录返回即关框（成功 / 失败 / 已取消）
                    Dbg.log("ARCHIVE", "打开压缩包结束 path=" + path
                            + (rr.error != null ? " 失败=" + rr.error : " 成功"));
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (seq != refreshSeq) return;   // 已有更新的刷新请求，丢弃过期结果
                        loading = false;
                        if (!cur().path.equals(path)) return;
                        // 同目录刷新时记下第一可见项的像素偏移，刷新后原样保持
                        final int firstTop;
                        if (restoreScroll) {
                            firstTop = restoreTop;     // 恢复记忆位置：首项像素偏移原样对齐
                        } else {
                            firstTop = firstTop();
                        }
                        if (rr.error != null) {
                            curItems = new ArrayList<FileItem>();
                            adapter.setData(curItems);
                            status.setText("✗ " + rr.error);
                            empty.setVisibility(View.VISIBLE);
                            empty.setText(rr.error);
                        } else {
                            curItems = rr.items;
                            adapter.setData(rr.items);   // RecyclerView：notify 不重置滚动
                            empty.setVisibility(rr.items.isEmpty() ? View.VISIBLE : View.GONE);
                            if (rr.items.isEmpty()) empty.setText("目录为空，或没有访问权限");
                            updateStatus();
                        }
                        rebuildTabs();
                        applyTabLockUi();   // 切换/刷新目录后，按当前标签锁定态同步地址栏
                        int n = adapter.getCount();
                        final int target;
                        if (restoreScroll) {
                            target = (n > 0) ? Math.min(Math.max(0, t.scroll), n - 1) : 0;
                        } else if (dirChanged) {
                            target = 0;                 // 换目录 → 贴顶
                        } else {
                            // 同目录强制刷新 → 保持当前位置，仅防越界
                            target = (n > 0) ? Math.min(Math.max(0, firstVisible()), n - 1) : 0;
                        }
                        t.loadedPath = path;
                        Log.d("ScrollMem", "refresh path=" + path + " restore=" + restoreScroll
                                + " dirChanged=" + dirChanged + " target=" + target
                                + " firstTop=" + firstTop + " n=" + n + " seq=" + seq);
                        applyPendingSelect();   // 外部「打开方式」带进来的文件：列目录完成后选中并自动执行接收命令
                        if (n > 0) {
                            // RecyclerView 的 scrollToPositionWithOffset 立即生效且不会被后续布局覆盖，
                            // 无需 GridView 那套 post/postDelayed 多次归位。
                            if (restoreScroll) {
                                gridLm.scrollToPositionWithOffset(target, firstTop);
                            } else if (dirChanged) {
                                gridLm.scrollToPositionWithOffset(0, 0);
                            }
                        }
                    }
                });
            }
        });
    }

    // ==================== 选择 ====================

    private void toggleSel(FileItem it) {
        Set<String> s = cur().sel;
        if (s.contains(it.path)) s.remove(it.path); else s.add(it.path);
        adapter.setChecked(s);
        updateStatus();
        updateBottomBar();
    }

    private void clearSel() {
        cur().sel.clear();
        adapter.setChecked(cur().sel);
        updateStatus();
        updateBottomBar();
    }

    private void selectAll() {
        Set<String> s = cur().sel;
        boolean all = true;
        for (int i = 0; i < adapter.getCount(); i++) {
            FileItem it = adapter.getItem(i);
            if (!it.parent && !s.contains(it.path)) { all = false; break; }
        }
        if (all) s.clear();
        else for (int i = 0; i < adapter.getCount(); i++) {
            FileItem it = adapter.getItem(i);
            if (!it.parent) s.add(it.path);
        }
        adapter.setChecked(s);
        updateStatus();
        updateBottomBar();
    }

    // ==================== 状态栏 ====================

    /**
     * 状态栏统一格式：
     * 无选中 → 路径：… 共 N 项 ROOT 模式
     * 选中 1 个文件 → name.txt / 100MB / 2025-08-05 22:22:45
     * 选中 1 个文件夹 → dirname / 内含 2 个文件
     * 多选 → 已选 3 项 / 共 10MB（含文件夹时追加 N 个文件夹）
     */
    private void updateStatus() {
        Set<String> sel = cur().sel;
        if (sel.isEmpty()) {
            status.setText("路径：" + cur().path + "   共 " + adapter.getCount() + " 项"
                    + "   " + (Fs.rootMode ? "ROOT 模式" : "普通模式"));
            return;
        }
        if (sel.size() == 1) {
            String p = sel.iterator().next();
            FileItem it = findItem(p);
            String name = (it != null) ? it.name : Util.base(p);
            if (it == null) { status.setText(name); return; }
            if (it.dir) {
                File[] ch = new File(it.path).listFiles();
                status.setText(name + " / " + (ch == null ? "文件夹"
                        : (ch.length == 0 ? "空文件夹" : "内含 " + ch.length + " 个文件")));
            } else {
                status.setText(name + " / " + sizeShort(it.size)
                        + " / " + Util.timeSec(it.modified));
            }
        } else {
            long total = 0; int dirs = 0;
            for (String p : sel) {
                FileItem it = findItem(p);
                boolean isDir = (it != null) ? it.dir : new File(p).isDirectory();
                if (isDir) { dirs++; continue; }
                total += (it != null) ? it.size : new File(p).length();
            }
            String s = "已选 " + sel.size() + " 项 / 共 " + sizeShort(total);
            if (dirs > 0) s += " / " + dirs + " 个文件夹";
            status.setText(s);
        }
    }

    /** 在当前列表里按路径找条目；不在列表（已删除等）时兜底 stat 一次。 */
    private FileItem findItem(String p) {
        for (int i = 0; i < adapter.getCount(); i++) {
            FileItem it = adapter.getItem(i);
            if (it != null && p.equals(it.path)) return it;
        }
        File f = new File(p);
        if (!f.exists()) return null;
        return new FileItem(f.getName(), p, f.isDirectory(), f.length(), f.lastModified());
    }

    /** 状态栏用短大小：100MB / 1.5GB（去掉 Util.size 的小数尾零与空格）。 */
    private static String sizeShort(long n) {
        String s = Util.size(n);
        int sp = s.indexOf(' ');
        if (sp < 0) return s;
        String num = s.substring(0, sp);
        if (num.endsWith(".0")) num = num.substring(0, num.length() - 2);
        return num + s.substring(sp + 1);
    }

    private List<String> selection() {
        ArrayList<String> r = new ArrayList<String>();
        r.addAll(cur().sel);
        return r;
    }

    // ==================== 异步文件操作 ====================

    private abstract static class FsTask {
        abstract String run();
        void after() {}
    }
    private interface NameCb { void onName(String n); }

    private void runFs(final String tip, final FsTask task) {
        runFs(tip, task, (ExtractProgress) null);
    }

    /** 删除专用：完成后自动关闭对话框并刷新；取消时静默刷新反映真实状态。 */
    private void runFs(final String tip, final FsTask task, final DeleteProgress prog) {
        status.setText(tip);
        io.execute(new Runnable() {
            public void run() {
                final String e = task.run();
                Dbg.log("FS.TASK", tip + " -> " + (e == null ? "OK" : Dbg.clip(e, 600))
                        + (prog != null && prog.cancelled ? "（已取消）" : ""));
                ui.post(new Runnable() {
                    public void run() {
                        if (prog != null && prog.cancelled) {
                            updateStatus();
                            cur().sel.clear();
                            adapter.setChecked(cur().sel);
                            updateBottomBar();
                            refreshForce();   // 反映已删除/未删除的真实状态
                            return;
                        }
                        if (prog != null) prog.finish(e == null);
                        if (e != null) { toast("删除失败：" + e); status.setText("✗ " + e); }
                        try { task.after(); } catch (Throwable ignored) {}
                        cur().sel.clear();
                        adapter.setChecked(cur().sel);
                        updateBottomBar();
                        refreshForce();       // 文件操作改变了内容，路径没变也要重列
                    }
                });
            }
        });
    }

    /** 带进度对话框的重载：完成后自动关闭对话框（供「从压缩包复制」复用解压进度组件）。 */
    private void runFs(final String tip, final FsTask task, final ExtractProgress prog) {
        status.setText(tip);
        io.execute(new Runnable() {
            public void run() {
                final String e = task.run();
                Dbg.log("FS.TASK", tip + " -> " + (e == null ? "OK" : Dbg.clip(e, 600))
                        + (prog != null && prog.cancelled ? "（已取消）" : ""));
                ui.post(new Runnable() {
                    public void run() {
                        if (prog != null && prog.cancelled) {
                            // 已取消：对话框已在 cancel() 里关闭。静默——不提示成功/失败、不清剪贴板（便于重试），
                            // 仅刷新列表反映真实状态（临时解压目录已由 FsTask 的 finally 清理）。
                            updateStatus();
                            cur().sel.clear();
                            adapter.setChecked(cur().sel);
                            updateBottomBar();
                            refreshForce();
                            return;
                        }
                        if (prog != null) prog.finish(e == null);
                        if (e != null) { toast("操作失败：" + e); status.setText("✗ " + e); }
                        else toast("操作完成");
                        try { task.after(); } catch (Throwable ignored) {}
                        cur().sel.clear();
                        adapter.setChecked(cur().sel);
                        updateBottomBar();
                        refreshForce();   // 文件操作改变了内容，路径没变也要重列
                    }
                });
            }
        });
    }

    /** selBase=true 时只选中文件名主体（不含最后一个「.扩展名」），用于重命名；isDir=true 表示重命名的是文件夹，不选中。
     *  选中判定以「是否文件夹」为准，而非有没有点（如 elementary-xfce-0.23 这种带点的文件夹名不误判为扩展名）。 */
    private void askName(String title, String preset, boolean selBase, boolean isDir, final NameCb cb) {
        final EditText et = new EditText(this);
        et.setText(preset);
        et.setSingleLine(true);
        final int len = preset == null ? 0 : preset.length();
        // 选中范围：
        //  · 文件夹（isDir）：不选中任何字符，光标置末尾，保留原名供直接编辑；
        //  · 文件且 selBase 且存在合法「.扩展名」（dot 在中间、非首非尾）：只选扩展名前主名，如 photo.jpg → photo；
        //  · 其它（文件无扩展名 / 新建）：整段选中（空串时即无选中）。
        int selStart = 0, selEnd = len;
        if (isDir) {
            selStart = len; selEnd = len;                            // 文件夹：不选中
        } else if (selBase && len > 0) {
            int dot = preset.lastIndexOf('.');
            if (dot > 0 && dot < len - 1) { selStart = 0; selEnd = dot; }   // 文件有扩展名：选中主名
            // else 文件无扩展名：保持整段选中 (selStart=0, selEnd=len)
        }
        final int fSelStart = selStart, fSelEnd = selEnd;
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(title).setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.isEmpty() || n.contains("/")) { toast("名称不合法"); return; }
                        cb.onName(n);
                    }
                }).create();
        // 关键：让对话框显示时自动弹出软键盘（仅 setSoftInputMode 在对话框里最可靠，
        // showSoftInput 因窗口焦点时机问题经常不弹）。
        if (dlg.getWindow() != null)
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                et.post(new Runnable() { public void run() {
                    et.requestFocus();                   // 自动获得焦点
                    et.setSelection(fSelStart, fSelEnd); // 有扩展名选中主名；无扩展名（文件夹）不选中，光标置末尾
                }});
            }
        });
        dlg.show();
    }

    /** 兼容旧调用：整段选中、非文件夹。 */
    private void askName(String title, String preset, final NameCb cb) {
        askName(title, preset, false, false, cb);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ==================== 权限 ====================

    private void ensurePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("需要「所有文件访问」权限")
                        .setMessage("Android 11 及以上，文件管理器需要开启「所有文件访问权限」才能浏览存储。\n\n请在接下来的系统设置页中打开本应用的开关，返回后自动刷新。")
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                try {
                                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:" + getPackageName()));
                                    startActivity(i);
                                } catch (Exception ex) {
                                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                }
                            }
                        }).show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_PERM) refreshForce();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CMD && !tabs.isEmpty()) {
            clearSel();   // 结果窗口关闭后清空文件列表的选中项
        }
    }

    // 排序下拉菜单的点外部收起 / 返回键收起由 PopupWindow（outsideTouchable + focusable）处理：
    // 外部第一次点击只关菜单、不会误触下面的文件，Activity 侧无需再拦截触摸事件。

    @Override
    public void onBackPressed() {
        // 压缩包内文件提取进行中：按返回照常导航（退出压缩包 / 上级），但让后台提取任务取消，
        // 不在返回键上拦截，避免打断正常导航。任务中止后由 finishArchiveOpen 复位状态栏。
        if (archiveOpening) archiveOpenCancel = true;
        // 任一工具栏下拉菜单展开中：返回键先收起菜单，不执行任何导航
        if (isSortDropdownOpen() || isAddDropdownOpen() || isFavDropdownOpen()) {
            if (isSortDropdownOpen()) closeSortDropdown();
            if (isAddDropdownOpen()) closeAddDropdown();
            if (isFavDropdownOpen()) closeFavDropdown();
            return;
        }
        // 选中态：先取消选择
        if (!cur().sel.isEmpty()) { clearSel(); return; }
        // 搜索态：收起搜索回到当前目录列表，不跳上级、不弹提示（搜索结果是跨目录的，
        // 跳父目录会冲掉结果）。退出搜索后已是正常浏览态，再按返回才按常规跳上级。
        if (searchActive) { exitSearch(); return; }
        // 由「其它 APP」拉起本界面：按返回直接结束 Activity，回到发起的外部 APP，
        // 而不是逐级返回到上级目录 / 根目录。
        if (launchedFromExternal) { finish(); return; }
        // 应用内正常浏览的返回语义：回到当前目录的父目录（与顶部「向上」一致）。
        // 不再走历史栈回退——历史回退是「回到上个浏览目录」，在往返浏览时会原地打转，
        // 不稳定地往上级走，用户体感就是「按了回不到上级目录」。
        if (isArchivePath(cur().path)) { gotoPath(archiveParent(cur().path)); return; }
        if (!cur().path.equals("/")) { gotoPath(Util.parent(cur().path)); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        Dbg.log("LIFE", "MainActivity.onPause");
        rememberScroll();   // 退后台 / 进设置 / 锁屏前记住当前位置并落盘
        saveTabs();   // 按 Home、进设置、锁屏、进程被回收前兜底落盘
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Dbg.log("LIFE", "MainActivity.onDestroy");
        rememberScroll();
        saveTabs();
        io.shutdownNow();
        listIo.shutdownNow();
        // 未关闭的「打开中...」框必须直接 dismiss，否则 Activity 销毁时会泄漏窗口
        if (archiveOpenDlg != null) {
            try { archiveOpenDlg.dlg.dismiss(); } catch (Throwable ignored) {}
            archiveOpenDlg = null;
        }
        // 未关闭的下拉浮层也要 dismiss，避免 Activity 销毁时泄漏窗口
        if (sortPopup != null) {
            try { sortPopup.dismiss(); } catch (Throwable ignored) {}
        }
        if (addPopup != null) {
            try { addPopup.dismiss(); } catch (Throwable ignored) {}
        }
        if (favPopup != null) {
            try { favPopup.dismiss(); } catch (Throwable ignored) {}
        }
        if (adapter != null) adapter.release();
        if (pluginMgr != null) pluginMgr.release();
        if (pluginStateReceiver != null) {
            try { unregisterReceiver(pluginStateReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    // ==================== 左侧抽屉 ====================

    /** 抽屉内存储项。 */
    private static class StorageItem {
        final String path, label;
        final File file;
        StorageItem(String p, String l, File f) { path = p; label = l; file = f; }
    }

    /** 文件列表容器：在 dispatchTouchEvent 把完整触摸序列喂给手势检测器。
     *  文件项是 clickable，会消费落在它身上的触摸，导致挂在 RecyclerView 上的 OnTouchListener
     *  收不到 DOWN/MOVE（只有空白处能触发）。父容器的 dispatchTouchEvent 是事件分发第一入口，
     *  无论子 View 是否消费，每次都先经过这里，因此文件区与空白区都能稳定检测到横向甩动。
     *  这里只「旁听」、不拦截、不改变返回值，点击 / 长按 / 纵向滚动完全不受影响。 */
    private class SwipeHost extends FrameLayout {
        SwipeHost(Context c) { super(c); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                dropAddrFocus();
                swipeDownX = e.getX();   // 记录起点 X，供 onFling 排除左边缘抽屉手势
            }
            if (swipeDetector != null) swipeDetector.onTouchEvent(e);
            return super.dispatchTouchEvent(e);
        }
    }

    /** 包裹主内容、拦截左边缘右滑手势的容器（替代官方 DrawerLayout，避免引入 Material 依赖）。 */
    private static class DrawerContainer extends FrameLayout {
        boolean onEdge;
        float sx;
        MainActivity host;
        DrawerContainer(Context c, MainActivity h) { super(c); host = h; }
        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (host.drawerOpen) return false;
            int act = e.getAction();
            if (act == MotionEvent.ACTION_DOWN) {
                onEdge = e.getX() <= host.drawerEdge;
                sx = e.getX();
            } else if (act == MotionEvent.ACTION_MOVE && onEdge) {
                if (e.getX() - sx > host.drawerSlideOpen) { host.openDrawer(); onEdge = false; return true; }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                onEdge = false;
            }
            return false;
        }
    }

    private void toggleDrawer() { if (drawerOpen) closeDrawer(); else openDrawer(); }

    private void openDrawer() {
        if (drawerOpen) return;
        buildDrawerContent();
        drawerOpen = true;
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.setAlpha(0f);
        drawerScrim.animate().alpha(1f).setDuration(200).start();
        drawerPanel.animate().translationX(0f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawerPanel.animate().translationX(-drawerWidth).setDuration(200).start();
        drawerScrim.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            public void run() { drawerScrim.setVisibility(View.GONE); }
        }).start();
    }

    private void buildDrawerContent() {
        drawerPanel.removeAllViews();
        drawerPanel.setClipChildren(false);
        drawerPanel.setClipToPadding(false);
        int pad = Util.dp(this, 14);

        // 头部：应用名 + 版本
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(pad, Util.dp(this, 20), pad, Util.dp(this, 14));
        header.setBackgroundColor(Ui.PRIMARY);
        TextView ht = new TextView(this);
        ht.setText(getString(R.string.app_name));
        ht.setTextColor(Color.WHITE);
        ht.setTextSize(Ui.sp(textLv, 18, 20, 22));
        ht.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(ht);
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView hv = new TextView(this);
            hv.setText("v" + ver);
            hv.setTextColor(0xFFE0F2F1);
            hv.setTextSize(Ui.sp(textLv, 11, 12, 13));
            header.addView(hv);
        } catch (Throwable ignored) {}
        drawerPanel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第一节：存储设备
        drawerPanel.addView(sectionTitle("存储设备"));
        for (final StorageItem s : loadStorages()) {
            drawerPanel.addView(storageRow(s));
        }

        // 第二节：收藏书签（复用 Prefs.favorites），支持「长按拖动排序」
        drawerPanel.addView(sectionTitle("收藏书签（长按拖动排序）"));
        ArrayList<String> favs = Prefs.favorites(this);
        if (favs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无收藏，可长按选中文件后点底部「收藏」，或在右上角菜单收藏当前目录）");
            empty.setTextColor(Ui.HINT);
            empty.setTextSize(Ui.sp(textLv, 11, 12, 13));
            empty.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));
            drawerPanel.addView(empty);
        } else {
            // 用 RecyclerView + ItemTouchHelper 实现拖动排序，避免重排时重建整个抽屉
            drawerPanel.addView(buildFavList(favs));
        }

        // 第三节：Bash 脚本（加载脚本目录下全部 .sh，标题取文件名）
        // 标题可点击：打开独立对话框列出全部脚本按钮（运行脚本不关闭对话框）
        TextView bashTitle = sectionTitle("Bash");
        // 英文同样 sp 下字形视觉偏小，适当放大以与中文标题栏视觉统一
        bashTitle.setTextSize(Ui.sp(textLv, 14, 15, 16));
        TypedValue ddb = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ddb, true);
        if (ddb.resourceId != 0) bashTitle.setBackgroundResource(ddb.resourceId);
        bashTitle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); showBashDialog(); }
        });
        drawerPanel.addView(bashTitle);
        ArrayList<ScriptItem> scripts = loadScripts();
        if (scripts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（在「设置 → 自定义脚本」指定目录后，自动加载其中全部 .sh）");
            empty.setTextColor(Ui.HINT);
            empty.setTextSize(Ui.sp(textLv, 11, 12, 13));
            empty.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));
            drawerPanel.addView(empty);
        } else {
            // 一排 3 个脚本按钮，按钮文字取文件名（去 .sh）
            drawerPanel.addView(scriptGrid(scripts));
        }
    }

    private TextView sectionTitle(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Ui.PRIMARY);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextSize(Ui.sp(textLv, 12, 13, 14));
        int p = Util.dp(this, 14);
        tv.setPadding(p, Util.dp(this, 10), p, Util.dp(this, 4));
        return tv;
    }

    private View storageRow(final StorageItem s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Util.dp(this, 14), Util.dp(this, 5), Util.dp(this, 14), Util.dp(this, 5));
        TypedValue dd = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, dd, true);
        if (dd.resourceId != 0) row.setBackgroundResource(dd.resourceId);

        // 第一行：左=名称（粗体） 右=可用/共（两列排列）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(s.label);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(Ui.sp(textLv, 14, 15, 16));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        long total = -1, avail = -1;
        try {
            StatFs fs = new StatFs(s.file.getAbsolutePath());
            total = fs.getTotalBytes();
            avail = fs.getAvailableBytes();
        } catch (Throwable ignored) {}
        if (total > 0) {
            TextView cap = new TextView(this);
            cap.setText("可用 " + fmtSize(avail) + " / 共 " + fmtSize(total));
            cap.setTextColor(Ui.HINT);
            cap.setTextSize(Ui.sp(textLv, 10, 11, 12));
            cap.setSingleLine(true);
            cap.setGravity(Gravity.END);
            head.addView(cap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(head);
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress((int) (100 - (avail * 100 / total)));
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
            row.addView(pb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 3)));
        } else {
            // 拿不到容量信息时，名称仍靠左、右侧留空
            row.addView(head);
        }
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); openInAvailableTab(s.path); }
        });
        return row;
    }

    private View bookmarkRow(final String path) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TypedValue dd = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, dd, true);
        if (dd.resourceId != 0) row.setBackgroundResource(dd.resourceId);

        // 左侧：名称 + 子路径（点击跳转）
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Util.dp(this, 14), Util.dp(this, 5), Util.dp(this, 6), Util.dp(this, 5));
        TextView name = new TextView(this);
        name.setText(Util.base(path));
        name.setTextColor(Ui.TEXT);
        name.setTextSize(Ui.sp(textLv, 14, 15, 16));
        info.addView(name);
        TextView sub = new TextView(this);
        sub.setText(path);
        sub.setTextColor(Ui.HINT);
        sub.setTextSize(Ui.sp(textLv, 10, 11, 12));
        info.addView(sub);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); openInAvailableTab(path); }
        });
        row.addView(info);

        // 右侧：三点菜单（编辑 / 删除）
        TextView more = new TextView(this);
        more.setText("⋮");
        more.setTextColor(Ui.HINT);
        more.setTextSize(Ui.sp(textLv, 20, 22, 24));
        more.setPadding(Util.dp(this, 12), Util.dp(this, 5), Util.dp(this, 14), Util.dp(this, 5));
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showBookmarkMenu(v, path); }
        });
        row.addView(more);
        return row;
    }

    /** 收藏项右侧三点菜单：编辑（改路径）/ 删除。 */
    private void showBookmarkMenu(View anchor, final String path) {
        PopupMenu pm = new PopupMenu(this, anchor);
        android.view.Menu menu = pm.getMenu();
        menu.add(0, 1, 0, "编辑");
        menu.add(0, 2, 0, "删除");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                int id = item.getItemId();
                if (id == 1) {
                    editFavorite(path);
                } else if (id == 2) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("移除收藏")
                            .setMessage("确定从收藏夹移除？\n\n" + path)
                            .setNegativeButton("取消", null)
                            .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    Prefs.removeFavorite(MainActivity.this, path);
                                    buildDrawerContent();
                                    toast("已移除收藏");
                                }
                            }).show();
                }
                return true;
            }
        });
        pm.show();
    }

    /** 编辑收藏：预填当前路径，保存时去旧加新。 */
    private void editFavorite(final String oldPath) {
        final EditText et = new EditText(this);
        et.setText(oldPath);
        et.setSelection(oldPath.length());
        et.setSingleLine(false);
        new AlertDialog.Builder(this)
                .setTitle("编辑收藏")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String np = et.getText().toString().trim();
                        if (np.length() == 0) { toast("路径不能为空"); return; }
                        if (np.equals(oldPath)) { toast("未修改"); return; }
                        Prefs.removeFavorite(MainActivity.this, oldPath);
                        Prefs.addFavorite(MainActivity.this, np);
                        buildDrawerContent();
                        toast("已更新收藏");
                    }
                }).show();
    }

    /** 抽屉「收藏书签」列表：RecyclerView + ItemTouchHelper，支持长按拖动排序；点击打开、⋮ 编辑/删除。 */
    private RecyclerView buildFavList(final ArrayList<String> favs) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        // 列表高度按实际内容自适应（WRAP_CONTENT）：项数少时不留底部空白；
        // 项数过多时通过 post 测量把高度封顶到屏高 45%，超出部分在列表内滚动。
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        rv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rv.post(new Runnable() {
            public void run() {
                int m = rv.getMeasuredHeight();
                if (m > maxH) {
                    rv.getLayoutParams().height = maxH;
                    rv.requestLayout();
                }
            }
        });

        final FavDrawerAdapter[] adapterRef = new FavDrawerAdapter[1];
        final ItemTouchHelper[] touchRef = new ItemTouchHelper[1];
        touchRef[0] = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            public boolean onMove(RecyclerView r, RecyclerView.ViewHolder a, RecyclerView.ViewHolder b) {
                int from = a.getAdapterPosition(), to = b.getAdapterPosition();
                if (from < 0 || to < 0) return false;
                Collections.swap(favs, from, to);
                adapterRef[0].notifyItemMoved(from, to);
                return true;
            }
            public void onSwiped(RecyclerView.ViewHolder vh, int d) {}
            // 拖动结束（松手）时落盘新顺序
            public void clearView(RecyclerView r, RecyclerView.ViewHolder vh) {
                super.clearView(r, vh);
                Prefs.setFavorites(MainActivity.this, favs);
                vh.itemView.setBackgroundColor(Color.WHITE);
            }
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int state) {
                super.onSelectedChanged(vh, state);
                if (vh != null && state == ItemTouchHelper.ACTION_STATE_DRAG)
                    vh.itemView.setBackgroundColor(0xFFE0F2F1);  // 拖动中高亮
            }
        });
        final FavDrawerAdapter adapter = new FavDrawerAdapter(favs, new FavDrawerAdapter.Cb() {
            public void onOpen(String path) { closeDrawer(); openInAvailableTab(path); }
            public void onMenu(View anchor, String path) { showBookmarkMenu(anchor, path); }
            public void onDelete(int pos) {
                if (pos < 0 || pos >= favs.size()) return;
                favs.remove(pos);
                adapterRef[0].notifyItemRemoved(pos);
                Prefs.setFavorites(MainActivity.this, favs);
            }
        });
        adapterRef[0] = adapter;
        rv.setAdapter(adapter);
        touchRef[0].attachToRecyclerView(rv);
        return rv;
    }

    /** 抽屉收藏夹列表适配器：每行 = 拖动手柄 ≡ / 名称+路径(点击打开) / ⋮ 菜单；整行或手柄长按触发拖动排序。 */
    private static class FavDrawerAdapter extends RecyclerView.Adapter<FavDrawerAdapter.VH> {
        interface Cb {
            void onOpen(String path);
            void onMenu(View anchor, String path);
            void onDelete(int pos);
        }
        private final ArrayList<String> list;
        private final Cb cb;
        FavDrawerAdapter(ArrayList<String> l, Cb cb) { this.list = l; this.cb = cb; }
        public int getItemCount() { return list.size(); }
        public VH onCreateViewHolder(ViewGroup p, int t) {
            Context c = p.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Util.dp(c, 8), Util.dp(c, 6), Util.dp(c, 6), Util.dp(c, 6));
            // 关键：行必须填满抽屉宽度，否则 weight 无剩余空间可分、⋮ 会随文字长度漂移
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView handle = new TextView(c);
            handle.setText("≡");
            handle.setTextSize(18);
            handle.setTextColor(0xFF888888);
            handle.setPadding(0, 0, Util.dp(c, 4), 0);

            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(Util.dp(c, 4), 0, Util.dp(c, 4), 0);
            TextView name = new TextView(c);
            name.setTextSize(14);
            name.setTextColor(Ui.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView sub = new TextView(c);
            sub.setTextSize(10);
            sub.setTextColor(Ui.HINT);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            info.addView(name);
            info.addView(sub);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView more = new TextView(c);
            more.setText("⋮");
            more.setTextSize(20);
            more.setTextColor(Ui.HINT);
            more.setPadding(Util.dp(c, 8), 0, Util.dp(c, 8), 0);

            row.addView(handle);
            row.addView(info);
            row.addView(more);
            return new VH(row, handle, info, name, sub, more);
        }
        public void onBindViewHolder(final VH h, int pos) {
            final String path = list.get(pos);
            h.name.setText(Util.base(path));
            h.sub.setText(path);
            h.info.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cb.onOpen(path); }
            });
            h.more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { cb.onMenu(h.more, path); }
            });
            // 整行或手柄长按触发拖动排序（ItemTouchHelper 默认长按即拖动）
            View.OnLongClickListener dragL = new View.OnLongClickListener() {
                public boolean onLongClick(View v) { return true; }
            };
            h.handle.setOnLongClickListener(dragL);
            h.info.setOnLongClickListener(dragL);
        }
        static class VH extends RecyclerView.ViewHolder {
            TextView handle, name, sub, more;
            View info;
            VH(View v, TextView handle, View info, TextView name, TextView sub, TextView more) {
                super(v); this.handle = handle; this.info = info; this.name = name; this.sub = sub; this.more = more;
            }
        }
    }

    /** 枚举存储设备：内部存储、根目录、以及 SD 卡/OTG 等可移动卷。 */
    private ArrayList<StorageItem> loadStorages() {
        ArrayList<StorageItem> list = new ArrayList<StorageItem>();
        File internal = Environment.getExternalStorageDirectory();
        list.add(new StorageItem(internal.getAbsolutePath(), "内部存储", internal));
        list.add(new StorageItem("/", "根目录", new File("/")));
        try {
            StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (v.isPrimary()) continue;
                File dir = null;
                try { dir = v.getDirectory(); } catch (Throwable ignored) {}
                if (dir == null) continue;
                String label = null;
                try { label = v.getDescription(this); } catch (Throwable ignored) {}
                if (label == null || label.isEmpty()) label = "存储卡";
                list.add(new StorageItem(dir.getAbsolutePath(), label, dir));
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private String fmtSize(long b) {
        if (b < 0) return "-";
        return Formatter.formatFileSize(this, b).toString();
    }

    /** 在合适标签打开目标路径：当前是压缩包视图则新建普通标签，否则在当前标签跳转。 */
    private void openInAvailableTab(String path) {
        // 当前 tab 已锁定：新开一个 tab 打开，避免破坏锁定标签内的浏览
        if (cur().locked) { addTab(path, true); return; }
        if (isArchivePath(cur().path)) addTab(path, true);
        else gotoPath(path);
    }

    // ==================== 侧栏 Bash 脚本 ====================

    /** 一个可被侧栏 Bash 分类识别的脚本项（标题 + 完整路径）。 */
    private static class ScriptItem {
        String title;
        String path;
    }

    /** 读取「设置 → 自定义脚本」目录下的全部 .sh，直接用文件名（去掉 .sh）作标题。
     *  不再读取脚本文件内容，因此无需事先打开每个文件。APP 加载/每次开抽屉时调用。 */
    private ArrayList<ScriptItem> loadScripts() {
        ArrayList<ScriptItem> out = new ArrayList<ScriptItem>();
        String dir = Prefs.scriptDir(this);
        if (dir == null || dir.isEmpty()) return out;
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) {
            // 普通方式读不到（如 /data 下的目录）：已开启 Root 且会话存活时，改用 Root 列举
            if (Fs.rootMode && Fs.rootSessionAlive()) {
                String[] names = Fs.rootList(dir);
                for (String name : names) {
                    if (!name.toLowerCase().endsWith(".sh")) continue;
                    String full = dir.endsWith("/") ? dir + name : dir + "/" + name;
                    ScriptItem si = new ScriptItem();
                    si.title = scriptTitleOf(name);
                    si.path = full;
                    out.add(si);
                }
            }
            return out;
        }
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (!name.toLowerCase().endsWith(".sh")) continue;
            ScriptItem si = new ScriptItem();
            si.title = scriptTitleOf(name);
            si.path = f.getAbsolutePath();
            out.add(si);
        }
        return out;
    }

    /** 用文件名作标题：去掉末尾的 .sh（大小写不敏感），空文件名回退为原名。 */
    private static String scriptTitleOf(String name) {
        String t = (name.length() > 3) ? name.substring(0, name.length() - 3) : name;
        return t.length() > 0 ? t : name;
    }

    /** Bash 分类：一排 3 个按钮，按钮文字为文件名（去掉 .sh），点击在结果窗口运行脚本。 */
    private View scriptGrid(final ArrayList<ScriptItem> scripts) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        int side = Util.dp(this, 14), gap = Util.dp(this, 6);
        grid.setPadding(side, Util.dp(this, 2), side, Util.dp(this, 4));
        LinearLayout row = null;
        for (int i = 0; i < scripts.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setWeightSum(3f);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final ScriptItem s = scripts.get(i);
            Button b = new Button(this);
            b.setText(s.title);
            b.setTextSize(Ui.sp(textLv, 11, 12, 13));
            b.setTextColor(Ui.PRIMARY);
            b.setAllCaps(false);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setMinimumWidth(0);
            b.setMinimumHeight(Util.dp(this, 32));   // 紧凑高度，圆角仍完整
            // 灰色圆角背景，按下加深
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setCornerRadius(Util.dp(this, 4));
            gd.setColor(0xFFE8E8E8);
            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
            pressed.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            pressed.setCornerRadius(Util.dp(this, 4));
            pressed.setColor(0xFFD0D0D0);
            sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
            sld.addState(new int[]{}, gd);
            b.setBackgroundDrawable(sld);
            // 必须在 setBackgroundDrawable 之后设 padding：否则会被背景 drawable 的默认 padding 覆盖清零
            b.setPadding(Util.dp(this, 4), Util.dp(this, 4), Util.dp(this, 4), Util.dp(this, 4));
            b.setClipToOutline(false);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bp.setMargins(i % 3 == 0 ? 0 : gap, gap, gap, gap);   // 四边留白，底角不与下一行贴边
            b.setLayoutParams(bp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { closeDrawer(); runScript(s.path, s.title); }
            });
            row.addView(b);
        }
        return grid;
    }

    /** 点击抽屉「Bash」标题打开的脚本面板对话框：列出全部脚本按钮 + 关闭按钮。
     *  脚本按钮复用 scriptGrid（其 closeDrawer() 在抽屉已关时为空操作），点击只运行脚本、
     *  不 dismiss 对话框，方便连续运行多个脚本。 */
    private void showBashDialog() {
        ArrayList<ScriptItem> scripts = loadScripts();
        ScrollView sv = new ScrollView(this);
        if (scripts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（在「设置 → 自定义脚本」指定目录后，自动加载其中全部 .sh）");
            empty.setTextColor(Ui.HINT);
            empty.setTextSize(Ui.sp(textLv, 11, 12, 13));
            int p = Util.dp(this, 16);
            empty.setPadding(p, p, p, p);
            sv.addView(empty, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            sv.addView(scriptGrid(scripts), new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        new AlertDialog.Builder(this)
                .setTitle("Bash 脚本")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 在结果窗口流式运行一个 .sh 脚本（沿用自定义命令的 Root 会话逻辑）。 */
    private void runScript(final String path, final String title) {
        final int timeout = Prefs.cmdTimeout(this);
        CmdOutputActivity.launchStreaming(this, title, new Runnable() {
            public void run() {
                final boolean root = Prefs.scriptRootEnabled(MainActivity.this);   // 设置页「以 Root 运行脚本」
                if (root && !CmdOutputActivity.openOutputRoot(30000)) {
                    CmdOutputActivity.liveAppend("未获取到 Root 授权，脚本未执行。\n"
                            + "请到「设置 → Root 权限」开启后再运行。\n");
                    CmdOutputActivity.liveComplete();
                    return;
                }
                CmdOutputActivity.setRunningRoot(root);
                // 把与自定义命令一致的 4 个路径变量以环境变量注入脚本：
                //   MYFM_F 当前文件绝对路径(%f) / MYFM_D 其所在目录(%d) /
                //   MYFM_N 文件名(%n) / MYFM_P 当前浏览目录(%p)
                // 脚本内直接读 $MYFM_F 等即可，无需写 % 占位符；root / 非 root 同样生效。
                final String cwd = cur().path;
                final List<String> sel = selection();
                final String targetPath = sel.isEmpty() ? cwd : sel.get(0);
                final String envPrefix = "export MYFM_F=" + Util.sq(targetPath)
                        + "; export MYFM_D=" + Util.sq(Util.parent(targetPath))
                        + "; export MYFM_N=" + Util.sq(Util.base(targetPath))
                        + "; export MYFM_P=" + Util.sq(cwd) + "; ";
                myfmEnvPrefix = envPrefix;   // 脚本输出的动态按钮继承同样的 MYFM 上下文
                if (CmdOutputActivity.getDebug())
                    CmdOutputActivity.liveAppend("$ " + envPrefix + "sh " + path + "\n");
                final String cmd = envPrefix + "sh " + Util.sq(path);
                Fs.ExecResult r;
                if (root) {
                    r = CmdOutputActivity.streamOutputRoot(cmd, timeout, new Fs.LineSink() {
                        public void line(String line) { handleLine(line, ACTIVITY_SINK); }
                    });
                } else {
                    r = Fs.shellStream(cmd, false, timeout, new Fs.LineSink() {
                        public void line(String line) { handleLine(line, ACTIVITY_SINK); }
                    });
                }
                if (!r.ok()) {
                    if (r.err != null && r.err.trim().length() > 0)
                        CmdOutputActivity.liveAppend("[stderr] " + r.err.trim() + "\n");
                    CmdOutputActivity.liveAppend("[退出码 " + r.code + "]\n");
                }
                CmdOutputActivity.liveComplete();
            }
        });
    }
}
