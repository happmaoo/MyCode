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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
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
    private RvFileAdapter adapter;
    private EditText pathEdit;
    private TextView status, empty, titleTv, rootTag;
    private AlertDialog dlg;   // 当前属性对话框实例，供内部按钮关闭它
    private final ArrayList<Button> toolBtns = new ArrayList<Button>();
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
    private int lastListFp = -1;  // 上次 onResume 时影响列表内容的设置指纹
    private int archiveExitScroll = 0;  // 进入压缩包前，真实目录列表的滚动位置；退出压缩包时恢复该位置
    // 「返回记住位置」：按路径持久化的滚动记忆（见 ScrollMemory）。
    private ScrollMemory scrollMem;
    private int restoreTop = 0;          // 恢复滚动时首项的像素偏移，与 t.scroll 配套
    private boolean initialRestore = false;  // 重启后首次 onResume 是否恢复记忆位置
    private static final int REQ_PERM = 1001;
    private static final int REQ_CMD = 1002;   // 启动结果窗口（CmdOutputActivity）的请求码
    private static final long AUTO_CLOSE_DELAY_MS = 3000L;   // 「自动关闭结果窗口」延时

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
    }

    private String homePath() {
        String p = Environment.getExternalStorageDirectory().getAbsolutePath();
        return p == null || p.isEmpty() ? "/sdcard" : p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildCmdButtons();     // 从设置页返回后，命令可能已被增删改
        applySettings();
        syncRootMode();
        updateBottomBar();
        if (tabs.isEmpty()) return;
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

        // Tab 栏：文字 + 下划线选中态
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
        addr.setPadding(Util.dp(this, 8), Util.dp(this, 2), Util.dp(this, 4), Util.dp(this, 2));
        pathEdit = new EditText(this);
        pathEdit.setSingleLine(true);
        pathEdit.setTextSize(Ui.sp(textLv, 12, 13, 15));
        pathEdit.setTextColor(Ui.TEXT);
        pathEdit.setHintTextColor(Ui.HINT);
        pathEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        pathEdit.setHint("输入路径，如 /sdcard 或 /system");
        // 默认不抢焦点：点击文件列表时地址栏不会获得焦点（避免光标跳动）；
        // 直接点地址栏时才临时开启焦点，让键盘弹出可输入。锁定标签会再被 applyTabLockUi 禁用。
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

        // 网格 + 空视图
        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        grid = new RecyclerView(this);
        gridLm = new GridLayoutManager(this, Prefs.columns(this));
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
        // 点文件列表区域时，让地址栏放弃焦点（关闭光标跳动 / 收起键盘）；返回 false 不影响列表项点击。
        grid.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) dropAddrFocus();
                return false;
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
        String[] bNames = {"打开", "复制", "剪切", "粘贴", "重命名", "删除", "收藏", "属性"};
        String[] bActs = {"openWith", "copy", "cut", "paste", "rename", "delete", "favorite", "info"};
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
                gotoPath(isArchivePath(cur().path) ? archiveParent(cur().path) : Util.parent(cur().path));
                dropAddrFocus();
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

    /** 压缩包扩展名集合（与插件插件能力保持一致）。 */
    private static final HashSet<String> ARCH_EXT = new HashSet<String>(Arrays.asList(
            "7z", "zip", "rar", "tar", "gz", "bz2", "xz", "tgz", "tar.gz",
            "zst", "lz4", "cab", "iso", "jar", "apk", "deb", "tzst"));

    private boolean isArchiveExt(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        if (lower.endsWith(".tar.gz")) return true;
        int i = lower.lastIndexOf('.');
        if (i < 0) return false;
        String ext = lower.substring(i + 1);
        // 写死集合 或 该后缀被「已启用」的压缩包插件关联（支持用户自定义格式，如 apkm）
        return ARCH_EXT.contains(ext) || pluginMgr.isAssociated(name, ext, PluginManager.CAP_ARCHIVE);
    }

    /** 点击压缩包文件：后台确认有插件可用后，进入其虚拟根目录（archive://...#）。 */
    private void enterArchive(final FileItem it) {
        final String fp = it.path;
        io.execute(new Runnable() {
            public void run() {
                final PluginManager.ArchiveHandle h = pluginMgr.getArchiveProvider(fp, null);
            ui.post(new Runnable() {
                public void run() {
                    if (h != null) {
                        // 进入压缩包前记下真实目录列表的当前滚动位置，退出压缩包时恢复
                        archiveExitScroll = firstVisible();
                        gotoPath(ARCHIVE_SCHEME + h.pluginId + encSeg(fp) + "#");
                    } else openFile(it);
                }
            });
            }
        });
    }

    /** 打开压缩包内的虚拟文件：经插件读出字节 → 落缓存 → 用现有 openFile 交给系统查看器。 */
    private void openArchiveFile(final FileItem it) {
        final String[] a = parseArchive(it.path);
        io.execute(new Runnable() {
            public void run() {
                PluginManager.ArchiveHandle h = pluginMgr.getArchiveProvider(a[1], null);
                if (h == null) {
                    ui.post(new Runnable() { public void run() { toast("没有可用的压缩包插件"); } });
                    return;
                }
                try {
                    ParcelFileDescriptor pfd = h.provider.open(a[1], a[2], "");
                    if (pfd == null) {
                        ui.post(new Runnable() { public void run() { toast("无法读取该文件（可能已加密或不存在）"); } });
                        return;
                    }
                    File dir = new File(getCacheDir(), "archive_tmp");
                    dir.mkdirs();
                    File cache = new File(dir, System.currentTimeMillis() + "_" + it.name);
                    InputStream in = new FileInputStream(pfd.getFileDescriptor());
                    FileOutputStream out = new FileOutputStream(cache);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    pfd.close();
                    final FileItem ci = new FileItem(it.name, cache.getAbsolutePath(),
                            false, cache.length(), cache.lastModified());
                    ui.post(new Runnable() { public void run() { openFile(ci); } });
                } catch (Exception ex) {
                    ui.post(new Runnable() {
                        public void run() { toast("读取压缩包内文件失败：" + ex.getMessage()); }
                    });
                }
            }
        });
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

        // 第 1 步：后台读取根层条目，决定解压目标与是否冲突
        final Dialog analyze = showProgressDialog("读取压缩包", "正在读取压缩包内容…");
        loading = true;
        io.execute(new Runnable() {
            public void run() {
                final ArchiveListing lst = pluginMgr.listArchiveRoot(archivePath);
                ui.post(new Runnable() {
                    public void run() {
                        analyze.dismiss();
                        loading = false;
                        if (lst == null) {
                            toast("无法读取压缩包（需 root + Termux 的 7z）");
                            return;
                        }
                        final int count = (lst.entries == null) ? 0 : lst.entries.size();
                        if (count == 0) { toast("压缩包为空"); return; }
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
                            showOverwriteConfirm(archivePath, target, conflictPath);
                        } else {
                            runExtract(archivePath, target);
                        }
                    }
                });
            }
        });
    }

    /** 同名冲突时弹「覆盖/取消」；非冲突情况不再打扰用户。 */
    private void showOverwriteConfirm(final String archivePath, final String target, final String conflictPath) {
        new AlertDialog.Builder(this)
                .setTitle("同名文件已存在")
                .setMessage("目标位置已存在：\n" + conflictPath + "\n是否覆盖？")
                .setNegativeButton("取消", null)
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        runExtract(archivePath, target);
                    }
                }).show();
    }

    /** 执行解压：弹进度条，后台调用插件的 extractAll，完成后退出压缩包视图。 */
    private void runExtract(final String archivePath, final String target) {
        final Dialog prog = showProgressDialog("解压中", "正在解压… " + target);
        loading = true;
        io.execute(new Runnable() {
            public void run() {
                final int code = pluginMgr.extractAll(archivePath, target);
                ui.post(new Runnable() {
                    public void run() {
                        prog.dismiss();
                        loading = false;
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

    /** 一个不可取消的「带进度条」对话框（indeterminate 横向进度条 + 文字），用于解压等耗时操作。 */
    private Dialog showProgressDialog(String title, String msg) {
        Dialog d = new Dialog(this);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Util.dp(this, 24), Util.dp(this, 20), Util.dp(this, 24), Util.dp(this, 20));
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(Ui.sp(textLv, 15, 16, 18));
        tv.setTextColor(Ui.TEXT);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(tv);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Util.dp(this, 14);
        root.addView(bar, blp);
        TextView mv = new TextView(this);
        mv.setText(msg);
        mv.setTextSize(Ui.sp(textLv, 12, 13, 14));
        mv.setTextColor(Ui.SUB);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Util.dp(this, 10);
        root.addView(mv, mlp);
        d.setContentView(root);
        d.show();
        return d;
    }

    /** 去掉压缩包文件名后缀（含 .tar.gz 等复合后缀），用于「解压到新建子文件夹」的命名。 */
    private static String stripArchiveExt(String name) {
        if (name == null || name.isEmpty()) return "archive";
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "(?i)\\.(tar\\.gz|tar\\.bz2|tar\\.xz|tar\\.zst|tgz|zip|7z|rar|gz|bz2|xz|zst|iso|tar|deb|rpm|apk|jar)$");
        java.util.regex.Matcher m = pat.matcher(name);
        if (m.find()) {
            String base = name.substring(0, m.start());
            return base.isEmpty() ? name : base;
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
        if ("back".equals(a)) {
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
        } else if ("favorites".equals(a)) {
            showFavorites();
        } else if ("extract".equals(a)) {
            extractArchiveToParent();
        } else if ("openWith".equals(a)) {
            openWithChooser();
        } else if ("favorite".equals(a)) {
            favoriteSelection();
        } else if ("rename".equals(a)) {
            final List<String> s = selection();
            if (s.size() != 1) { toast("请先长按选中 1 个项目"); return; }
            final String old = s.get(0);
            askName("重命名", Util.base(old), new NameCb() {
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
                            runFs("正在删除…", new FsTask() {
                                public String run() {
                                    for (String p : s) {
                                        String e = Fs.delete(p);
                                        if (e != null) return e;
                                    }
                                    return null;
                                }
                            });
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
            runFs("正在粘贴…", new FsTask() {
                public String run() {
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
                            ArrayList<String> inners = new ArrayList<String>();
                            for (String p : src) {
                                if (!isArchivePath(p)) continue;
                                String[] ap = parseArchive(p);
                                if (ap[1].equals(en.getKey())) inners.add(ap[2]);
                            }
                            String be = pluginMgr.extractBatch(en.getKey(), inners,
                                    en.getValue().getAbsolutePath());
                            if (be != null) return be;
                        }
                        final AtomicBoolean alwaysOverwrite = new AtomicBoolean(false);
                        for (String p : src) {
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
                                // 同名文件：询问是否覆盖（可勾选「始终覆盖」对后续同名项不再询问）
                                if (alwaysOverwrite.get()) {
                                    overwrite = true;
                                } else {
                                    boolean ow = askOverwrite(name, alwaysOverwrite);
                                    if (!ow) continue;   // 用户选择跳过
                                    overwrite = true;
                                }
                            }
                            if (overwrite) {
                                String de = Fs.delete(dst);   // 覆盖前先删除目标
                                if (de != null) return "无法覆盖 " + name + "：" + de;
                            }
                            String e;
                            if (isArchivePath(p)) {
                                // 源来自压缩包：从暂存目录复制（文件 / 文件夹均支持，保留包内结构；
                                // 压缩包只读，cut 退化为复制）
                                String[] ap = parseArchive(p);
                                File from = new File(tmpByArchive.get(ap[1]), ap[2]);
                                if (!from.exists()) {
                                    // 兜底：部分 7z 对反斜杠路径可能仍解成字面名文件
                                    File lit = new File(tmpByArchive.get(ap[1]), ap[2].replace("/", "\\"));
                                    if (lit.isFile()) from = lit;
                                    else return "解压结果缺失：" + name;
                                }
                                e = Fs.copy(from.getAbsolutePath(), dst);
                            } else if (cut) {
                                e = Fs.rename(p, dst);
                                if (e != null) e = Fs.copy(p, dst);
                            } else {
                                e = Fs.copy(p, dst);
                            }
                            if (e != null) return e;
                        }
                        return null;
                    } finally {
                        // 无论成败都清理临时解压目录
                        for (File t : tmpByArchive.values()) Fs.delete(t.getAbsolutePath());
                    }
                }
                public void after() { clipboard.clear(); }
            });
        } else if ("info".equals(a)) {
            showInfo();
        } else if ("home".equals(a)) {
            gotoPath(homePath());
        } else if ("rootDir".equals(a)) {
            gotoPath("/");
        } else if ("search".equals(a)) {
            toggleSearch();
        }
    }

    /**
     * 在工作线程里弹出「同名文件」询问并阻塞，直到用户选择；返回 true=覆盖、false=跳过。
     * 若勾选「始终覆盖」，通过 alwaysOverwrite 让后续同名项不再询问直接覆盖。
     */
    private boolean askOverwrite(final String name, final AtomicBoolean alwaysOverwrite) {
        final boolean[] res = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(new Runnable() {
            public void run() {
                ScrollView sv = new ScrollView(MainActivity.this);
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = Util.dp(MainActivity.this, 16);
                box.setPadding(pad, pad, pad, pad);
                TextView msg = new TextView(MainActivity.this);
                msg.setText("已存在同名文件：\n" + name + "\n\n是否覆盖？");
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
                        .setNegativeButton("跳过", null)
                        .show();
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        res[0] = true;
                        if (cb.isChecked()) alwaysOverwrite.set(true);
                        d.dismiss();
                    }
                });
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        res[0] = false;
                        d.dismiss();
                    }
                });
                d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface di) { latch.countDown(); }
                });
            }
        });
        try { latch.await(); } catch (InterruptedException e) { /* 被打断按跳过处理 */ }
        return res[0];
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
        cols = Prefs.columns(this);
        textLv = Prefs.textLevel(this);

        rootTag.setTextColor(Ui.PRIMARY);
        searchScopeBtn.setTextColor(Ui.PRIMARY);
        for (Button b : toolBtns) b.setTextColor(Ui.PRIMARY);
        for (Button b : cmdBtns) b.setTextColor(Ui.PRIMARY);
        for (Button b : bottomBtns) b.setTextColor(Ui.PRIMARY);
        for (Button b : addrBtns) b.setTextColor(Ui.PRIMARY);
        rebuildTabs();   // 标签下划线 / 🔒 锁颜色随强调色刷新
        applyTabLockUi();   // 锁定标签：地址栏禁用变灰、前往/上级按钮禁用

        gridLm.setSpanCount(cols);
        int w = getResources().getDisplayMetrics().widthPixels;
        int colPx = Math.max(1, w / cols);
        int iconPx = (int) (colPx * 0.52f);
        iconPx = Math.max(Util.dp(this, 26), Math.min(Util.dp(this, 72), iconPx));
        // 列表名称统一字号、列表字型（副标题行已移除）
        adapter.setSizes(iconPx, Prefs.listNameSp(this));
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
        // 图标主题：从设置页返回或冷启动时注入绘制器（变化会清缓存）
        IconFactory.setTheme(Prefs.iconTheme(this));
    }

    /** 锁定标签：地址栏禁用并变灰、清空焦点；前往/上级按钮禁用。
     *  解锁后地址栏仍保持「默认不抢焦点」——只有直接点它才会获得焦点（见 pathEdit 的 OnTouchListener）。 */
    private void applyTabLockUi() {
        if (tabs.isEmpty()) return;
        boolean locked = cur().locked;
        pathEdit.setEnabled(!locked);
        // 注意：不要在这里把 pathEdit 设回 focusable=true（否则未锁定时点文件列表也可能让它抢焦点）。
        // 默认保持不可聚焦，仅当用户直接点地址栏时，OnTouchListener 才临时开启焦点。
        pathEdit.setTextColor(locked ? Ui.HINT : Ui.TEXT);
        if (locked) {
            pathEdit.setFocusable(false);
            pathEdit.setFocusableInTouchMode(false);
            pathEdit.clearFocus();
        }
        if (addrBtns.size() >= 1) addrBtns.get(0).setEnabled(!locked);   // 前往
        if (addrBtns.size() >= 2) addrBtns.get(1).setEnabled(!locked);   // 上级
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

    /** 根据 toolbarArchiveMode 重建顶部工具栏按钮：普通视图=完整工具，压缩包视图=去新建/收藏+解压。 */
    private void rebuildToolbar() {
        if (toolbar == null) return;
        toolbar.removeAllViews();
        toolBtns.clear();
        String[] names, acts;
        if (toolbarArchiveMode) {
            names = new String[]{"后退", "前进", "全选", "解压", "内部存储", "根目录", "搜索"};
            acts  = new String[]{"back", "fwd", "selectAll", "extract", "home", "rootDir", "search"};
        } else {
            names = new String[]{"后退", "前进", "全选", "新建文件夹", "新建文件", "内部存储", "根目录", "搜索"};
            acts  = new String[]{"back", "fwd", "selectAll", "newFolder", "newFile", "home", "rootDir", "search"};
        }
        for (int i = 0; i < names.length; i++) {
            if (i > 0) addVLine(toolbar);
            final String a = acts[i];
            Button b = Ui.flat(this, names[i], textLv);
            b.setTextColor(Ui.PRIMARY);   // 与 applySettings 对工具栏按钮上色一致（teal 强调色）
            if ("extract".equals(a)) {
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

    private String expandCmd(String tmpl, String path, String cwd, List<String> all,
                             boolean asRoot, boolean outputWindow, RootPathMapper mapper) {
        // %f / %n 不带引号：便于直接嵌进 su --mount-master -c '...' 这类带单引号的命令，
        // 否则占位符自带的单引号会与外层引号冲突导致命令解析错误。
        // 路径含空格时请在模板里自行加引号，例如 su --mount-master -c 'ls -l "%f"'。
        // 命令走 Root 会话时，/data/data 等路径自动换成 /data_mirror 真实视图。
        // 结果窗口走「独立会话」，须用本窗口会话自己的命名空间状态做路径映射，与主界面隔离。
        String f, d, p2, fbItem;
        if (asRoot) {
            if (outputWindow) {
                f = mapper.map(path);
                d = Util.sq(mapper.map(Util.parent(path)));
                p2 = Util.sq(CmdOutputActivity.mapRootPath(cwd));
            } else {
                f = Fs.rootPath(path);
                d = Util.sq(Fs.rootPath(Util.parent(path)));
                p2 = Util.sq(Fs.rootPath(cwd));
            }
        } else {
            f = path;
            d = Util.sq(Util.parent(path));
            p2 = Util.sq(cwd);
        }
        String n = Util.base(path);
        StringBuilder fb = new StringBuilder();
        for (String x : all) {
            if (fb.length() > 0) fb.append(' ');
            fb.append(Util.sq(asRoot
                    ? (outputWindow ? mapper.map(x) : Fs.rootPath(x)) : x));
        }
        String out = tmpl.replace("%f", f).replace("%d", d)
                .replace("%n", n).replace("%p", p2);
        return out.replace("%F", fb.toString());
    }

    /** 命令执行的「输出接收器」：把流式输出 / 动态按钮 / 状态推送到界面（CmdOutputActivity 或 Dialog）。 */
    private interface CmdSink {
        void append(String s);
        void addButton(String label, String cmd);
        void setRunningRoot(boolean root);
        void finished(boolean autoClose);
    }
    /** 命令执行的「流执行器」：封装 Root 会话的打开 / 流式执行 / 路径映射 / 停止。 */
    private interface CmdStreamer {
        boolean openRoot(long timeoutMs);
        Fs.ExecResult stream(String script, int timeoutSec, Fs.LineSink sink);
        String mapRootPath(String p);
        void stopRoot();
    }
    /** Root 路径映射器：模板展开时把 /data/data 等换成当前会话可见真实视图。 */
    private interface RootPathMapper { String map(String p); }

    private static final CmdSink ACTIVITY_SINK = new CmdSink() {
        public void append(String s) { CmdOutputActivity.liveAppend(s); }
        public void addButton(String l, String c) { CmdOutputActivity.liveAddButton(l, c); }
        public void setRunningRoot(boolean r) { CmdOutputActivity.setRunningRoot(r); }
        public void finished(boolean ac) { CmdOutputActivity.liveComplete(ac); }
    };
    private static final CmdStreamer ACTIVITY_STREAMER = new CmdStreamer() {
        public boolean openRoot(long t) { return CmdOutputActivity.openOutputRoot(t); }
        public Fs.ExecResult stream(String s, int to, Fs.LineSink sk) {
            return CmdOutputActivity.streamOutputRoot(s, to, sk);
        }
        public String mapRootPath(String p) { return CmdOutputActivity.mapRootPath(p); }
        public void stopRoot() { CmdOutputActivity.stopOutputRoot(); }
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
        final int timeout = Prefs.cmdTimeout(this);   // 全局执行超时（秒），0 = 不限时
        final boolean[] allOk = { true };   // 全部执行成功才允许自动关闭结果窗口
        // 结果窗口使用「独立 Root 会话」（与主界面导航会话隔离）：
        // 首次会弹一次授权，之后复用；即便在此窗口卡死也不波及主界面。
        if (cmd.optRoot() && !streamer.openRoot(30000)) {
            sink.append("未获取到 Root 授权，命令未执行。\n"
                    + "请到「设置 → Root 权限」开启后再运行。\n");
            sink.finished(false);
            ui.post(new Runnable() {
                public void run() { status.setText("命令未执行：未获取到 Root"); }
            });
            return;
        }
        int times = batch ? 1 : targets.size();
        final Pattern btnP = CmdOutputActivity.BTN;
        sink.setRunningRoot(cmd.optRoot());   // 让「停止」按钮按正确方式终止命令
        for (int i = 0; i < times; i++) {
            final String script = expandCmd(cmd.body(), targets.get(i), cwd, targets,
                    cmd.optRoot(), true, mapper);
            if (cmd.optPrintOrig()) sink.append("$ " + script + "\n");
            Fs.ExecResult r;
            if (cmd.optRoot()) {
                r = streamer.stream(script, timeout, new Fs.LineSink() {
                    public void line(String line) {
                        // [btn:标签]命令：动态生成按钮，不进正文
                        Matcher m = btnP.matcher(line);
                        if (m.find() && m.start() == 0
                                && m.group(1).trim().length() > 0
                                && m.group(2).trim().length() > 0) {
                            sink.addButton(m.group(1).trim(), m.group(2).trim());
                            return;
                        }
                        sink.append(line + "\n");
                    }
                });
            } else {
                r = Fs.shellStream(script, false, timeout, new Fs.LineSink() {
                    public void line(String line) {
                        Matcher m = btnP.matcher(line);
                        if (m.find() && m.start() == 0
                                && m.group(1).trim().length() > 0
                                && m.group(2).trim().length() > 0) {
                            sink.addButton(m.group(1).trim(), m.group(2).trim());
                            return;
                        }
                        sink.append(line + "\n");
                    }
                });
            }
            if (!r.ok()) allOk[0] = false;
            if (r.err != null && r.err.trim().length() > 0) {
                sink.append("[stderr] " + r.err.trim() + "\n");
            }
            if (!r.ok()) sink.append("[退出码 " + r.code + "]\n");
            sink.append("\n");
        }
        sink.finished(cmd.optAutoClose() && allOk[0]);
        ui.post(new Runnable() {
            public void run() { status.setText("命令执行完毕：" + cmd.name); }
        });
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
        TextView title = new TextView(this);
        title.setText("执行结果 · " + cmd.name);
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
        actRow.setGravity(Gravity.CENTER_VERTICAL);
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
        final CmdSink sink = new CmdSink() {
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
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { runDialogAction(dlg, session, body, tv, sv, label, c); }
                    });
                    actRow.addView(b);
                }});
            }
            public void setRunningRoot(boolean r) {}
            public void finished(boolean ac) {}
        };
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
            public void stopRoot() {
                if (session != null) session.kill(); else Fs.killCurrentShell();
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
                if (session != null) session.kill(); else Fs.killCurrentShell();
                dlg.dismiss();
            }
        };
        closeX.setOnClickListener(close);
        ok.setOnClickListener(close);

        dlg.show();
        // 子线程执行，输出实时推回主线程的 TextView
        new Thread(new Runnable() {
            public void run() {
                execCore(cmd, targets, cwd, batch, sink, streamer, mapper);
            }
        }).start();
    }

    /** Dialog 内 [btn:标签] 命令按钮：后台执行后把结果追加到同一 Dialog。 */
    private void runDialogAction(final Dialog dlg, final Fs.OutputSession session,
                                 final StringBuilder body, final TextView tv, final ScrollView sv,
                                 String label, String command) {
        final String cmd = command;
        toast("运行：" + label);
        new Thread(new Runnable() {
            public void run() {
                final boolean asRoot = session != null && session.isOpen();
                final Fs.ExecResult r = asRoot ? session.shell(cmd)
                        : Fs.shell(cmd, Fs.rootSessionAlive());
                final StringBuilder sb = new StringBuilder();
                sb.append("\n\n── ").append(label).append(asRoot ? "（Root）" : "").append(" ──\n");
                sb.append("$ ").append(cmd).append('\n');
                if (r.out != null && r.out.length() > 0) {
                    sb.append(r.out);
                    if (!r.out.endsWith("\n")) sb.append('\n');
                }
                if (r.err != null && r.err.trim().length() > 0)
                    sb.append("[stderr] ").append(r.err.trim()).append('\n');
                if (!r.ok()) sb.append("[退出码 ").append(r.code).append("]\n");
                ui.post(new Runnable() { public void run() {
                    if (!dlg.isShowing()) return;
                    body.append(sb);
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
            try { startActivity(in); return; }
            catch (Exception ex) { in.setPackage(null); }   // 指定应用不存在则回退系统选择器
        }
        // 直接发起 VIEW：系统里已有该类型的默认应用就直接打开；没有默认才弹
        // 系统选择器，且系统选择器带「始终 / 仅此一次」，可设置默认应用。
        // 不能用 Intent.createChooser()——它发起的对话框被 Android 明确禁止设置
        // 默认（只有「仅此一次」），每次都会弹，这正是之前「所有文件都要选打开
        // 方式」的原因。底部「打开」按钮保持 chooser 行为不变（临时换应用用）。
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
            for (String e : new String[]{"jpg","jpeg","png","gif","bmp","webp","heic","heif","tiff","tif","avif"}) {
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
        File f = new File(p);
        if (f.isDirectory()) { toast("文件夹请直接点击进入，不能用应用打开"); return; }
        if (!f.exists()) { toast("文件不存在：" + p); return; }
        final FileItem it = new FileItem(f.getName(), p, false, f.length(), f.lastModified());
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
        toast(added > 0 ? ("已收藏 " + added + " 项，点顶部「★ 收藏夹」查看") : "选中项已全部在收藏夹中");
    }

    /** 顶部「★ 收藏夹」：列出收藏、点击打开，并提供管理与「收藏当前目录」。 */
    private void showFavorites() {
        final ArrayList<String> favs = Prefs.favorites(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("收藏夹（" + favs.size() + " 项）");
        if (favs.isEmpty()) {
            b.setMessage("还没有收藏。\n长按选中文件/文件夹后点底部「收藏」，或点下方按钮收藏当前目录。");
        } else {
            // 收藏项直接显示完整路径 /path/name，不再叠加类型、名称与分隔符
            final CharSequence[] labels = new CharSequence[favs.size()];
            for (int i = 0; i < favs.size(); i++) labels[i] = favs.get(i);
            b.setItems(labels, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { openFavorite(favs.get(w)); }
            });
        }
        b.setNeutralButton("＋ 收藏当前目录", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                boolean added = Prefs.addFavorite(MainActivity.this, cur().path);
                toast(added ? ("已收藏当前目录：" + labelOf(cur().path)) : "当前目录已在收藏夹中");
            }
        });
        // 对话框按钮从左到右为 Neutral / Negative / Positive：
        // 把「管理」放中间、「关闭」放最右，实现两者位置互换
        if (!favs.isEmpty()) {
            b.setNegativeButton("管理", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { manageFavorites(); }
            });
        }
        b.setPositiveButton("关闭", null).show();
    }

    /** 管理收藏：点击某项确认后移除。 */
    private void manageFavorites() {
        final ArrayList<String> favs = Prefs.favorites(this);
        if (favs.isEmpty()) { toast("收藏夹为空"); return; }
        final CharSequence[] labels = new CharSequence[favs.size()];
        for (int i = 0; i < favs.size(); i++) labels[i] = favs.get(i);
        new AlertDialog.Builder(this)
                .setTitle("管理收藏（点击移除）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, final int w) {
                        final String target = favs.get(w);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("移除收藏")
                                .setMessage("确定从收藏夹移除？\n\n" + target)
                                .setNegativeButton("取消", null)
                                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dd, int ww) {
                                        Prefs.removeFavorite(MainActivity.this, target);
                                        toast("已移除收藏");
                                    }
                                }).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 打开一个收藏项：目录则跳转，文件则交给 openFile。 */
    private void openFavorite(String path) {
        if (path == null || path.length() == 0) return;
        // 规范化：统一去掉末尾斜杠，避免「有无 /」导致存在判定不一致。
        final String norm = path.replaceAll("/+$", "");
        // 存在性探测（含 Root su 往返）与 stat 都放后台，避免在主线程阻塞 UI；
        // 结果回主线程后再导航/打开。
        io.execute(new Runnable() {
            public void run() {
                final boolean exists = new File(norm).exists()
                        || new File(norm + "/").exists() || Fs.exists(norm);
                final File f = new File(norm);
                final boolean isDir = Fs.rootMode || f.isDirectory();
                final FileItem fi = isDir ? null
                        : new FileItem(f.getName(), norm, false, f.length(), f.lastModified());
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (!exists) { toast("收藏项已不存在：\n" + norm); return; }
                        if (isDir) gotoPath(norm);
                        else openFile(fi);
                    }
                });
            }
        });
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
        else title.setText("设置所有者（用户 / 用户组）与读 / 写 / 执行权限");
        title.setTextSize(Ui.sp(textLv, 12, 13, 15));
        title.setTextColor(Ui.SUB);
        box.addView(title);
        box.addView(Ui.line(this));

        ui.ownerList = buildUidEntries(o.ownerUid, o.groupGid);
        ui.groupList = buildUidEntries(o.ownerUid, o.groupGid);

        // 矩阵：读 / 写 / 执行 三列
        box.addView(permHeader());
        box.addView(permRow("用户", true, ui, m, 0));
        box.addView(permRow("用户组", true, ui, m, 1));
        box.addView(permRow("其他", false, ui, m, 2));

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
        for (String s : new String[]{"读", "写", "执行"}) {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextSize(Ui.sp(textLv, 12, 13, 15));
            t.setTextColor(Ui.PRIMARY);
            t.setGravity(Gravity.CENTER);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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

    /** 矩阵中的一行：左身份标签 + 读/写/执行三个无文字复选框（自然宽度居中）+ 右侧可选 uid 选择（占剩余空间）。 */
    private View permRow(String label, boolean hasSpinner, PermUI ui, int mode, int role) {
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
        // 复选框保持自然宽度，居中显示，不被长文本挤压
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER;
        cp.leftMargin = Util.dp(this, 8);
        cp.rightMargin = Util.dp(this, 8);
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
        } else {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return row;
    }

    private CheckBox mkBox(boolean c, ViewGroup parent) {
        CheckBox cb = new CheckBox(this);
        cb.setChecked(c);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
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
        applyTabLockUi();   // 恢复标签后同步锁定态（地址栏禁用变灰等）
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
            // 新建标签后把标签条滚动到新标签，让「自动切换到新标签」可见
            if (active >= 0 && active < tabViews.size()) {
                final View v = tabViews.get(active);
                tabScroll.post(new Runnable() {
                    public void run() { tabScroll.smoothScrollTo(v.getLeft(), 0); }
                });
            }
            refresh();
        }
        saveTabs();   // select=false 时不触发刷新，这里仍需落盘
    }

    private void rebuildTabs() {
        tabStrip.removeAllViews();
        tabViews.clear();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            // 锁定标签：文字固定为锁定根目录名，进入子文件夹也不变
            String label = (t.locked && t.lockRoot != null) ? labelOf(t.lockRoot) : labelOf(t.path);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(Util.dp(this, 8), Util.dp(this, 7), Util.dp(this, 4), Util.dp(this, 5));
            // 当前选中 Tab 用浅灰底，未选中保持透明
            item.setBackgroundColor(i == active ? Ui.PRIMARY_SOFT : Color.TRANSPARENT);   // 与文件列表选中高亮同一灰色

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextSize(Ui.sp(textLv, 11, 12, 14));
            tv.setTextColor(Ui.TEXT);   // 标签文字默认黑色
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

            View underline = new View(this);
            underline.setBackgroundColor(i == active ? Ui.PRIMARY : Color.TRANSPARENT);
            item.addView(underline, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 2)));
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
                    toast(locked ? "已锁定标签（长按可解锁；只能进入子目录，地址栏已禁用）"
                                 : "已解锁标签");
                    return true;
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 自适应宽度：按标签文字实际长度排布，不再固定占满；文字最长 90dp（见 tv.setMaxWidth），
            // 单个标签宽度即被限制在该上限内，避免长名称标签过宽。
            tabStrip.addView(item, lp);
            tabViews.add(item);
        }
    }

    private String labelOf(String path) {
        if (path.equals("/")) return "根目录";
        String h = homePath();
        if (path.equals(h)) return "内部存储";
        return Util.base(path);
    }

    private void switchTab(int i) {
        if (i == active || i < 0 || i >= tabs.size()) return;
        if (searchActive) exitSearch();   // 搜索态下切标签需先退出搜索，否则 refresh 提前返回、网格不刷新
        rememberScroll();                 // 记住旧标签当前路径的滚动位置
        active = i;
        rebuildTabs();
        pathEdit.setText(cur().path);
        int[] mem = (scrollMem != null) ? scrollMem.recall(cur().path) : null;
        if (mem != null) { cur().scroll = mem[0]; restoreTop = mem[1]; }
        else { cur().scroll = 0; restoreTop = 0; }
        refresh(true, false);   // 仅切换标签时恢复该标签的滚动位置
    }

    private void closeTab(int i) {
        if (tabs.size() <= 1) return;
        if (searchActive) exitSearch();   // 同 switchTab：避免搜索态下 refresh 提前返回
        if (i >= 0 && i < tabs.size() && tabs.get(i).locked) {
            toast("该标签已锁定，长按标签解锁后再关闭");
            return;
        }
        tabs.remove(i);
        tabViews.remove(i);
        if (active >= tabs.size()) active = tabs.size() - 1;
        else if (i < active) active--;
        rebuildTabs();
        pathEdit.setText(cur().path);
        refresh();
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
        String p = raw;
        if (p == null || p.trim().isEmpty()) return;
        p = p.trim();
        if (p.length() > 1 && p.endsWith("/")) p = p.replaceAll("/+$", "");
        // 锁定标签：只允许在「锁定根目录（含）及其子目录」内活动，禁止返回上级或跳到其它位置
        if (!canEnter(p)) {
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
                CmdOutputActivity.liveComplete(false);
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
        // 锁定标签：若历史回退会离开「锁定根目录及其子目录」，撤销本次回退（不允许返回上级）
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
        if (isArchivePath(path)) return loadArchive(path);
        return Fs.list(path);
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
        status.setText("加载中… " + path);
        empty.setVisibility(View.GONE);   // 不残留上一次的错误 / 空目录提示
        // 换目录时立刻把旧列表拉回顶部，加载新目录期间也不会停在中间
        if (dirChanged && !restoreScroll) {
            gridLm.scrollToPositionWithOffset(0, 0);
        }
        listIo.execute(new Runnable() {
            public void run() {
                final Fs.ListResult res = loadItems(path);
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
                        if (res.error != null) {
                            curItems = new ArrayList<FileItem>();
                            adapter.setData(curItems);
                            status.setText("✗ " + res.error);
                            empty.setVisibility(View.VISIBLE);
                            empty.setText(res.error);
                        } else {
                            curItems = res.items;
                            adapter.setData(res.items);   // RecyclerView：notify 不重置滚动
                            empty.setVisibility(res.items.isEmpty() ? View.VISIBLE : View.GONE);
                            if (res.items.isEmpty()) empty.setText("目录为空，或没有访问权限");
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
        status.setText(tip);
        io.execute(new Runnable() {
            public void run() {
                final String e = task.run();
                ui.post(new Runnable() {
                    public void run() {
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

    private void askName(String title, String preset, final NameCb cb) {
        final EditText et = new EditText(this);
        et.setText(preset);
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(title).setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.isEmpty() || n.contains("/")) { toast("名称不合法"); return; }
                        cb.onName(n);
                    }
                }).show();
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

    @Override
    public void onBackPressed() {
        // 选中态：先取消选择
        if (!cur().sel.isEmpty()) { clearSel(); return; }
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
        rememberScroll();   // 退后台 / 进设置 / 锁屏前记住当前位置并落盘
        saveTabs();   // 按 Home、进设置、锁屏、进程被回收前兜底落盘
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        rememberScroll();
        saveTabs();
        io.shutdownNow();
        listIo.shutdownNow();
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

        // 第二节：收藏书签（复用 Prefs.favorites）
        drawerPanel.addView(sectionTitle("收藏书签"));
        ArrayList<String> favs = Prefs.favorites(this);
        if (favs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无收藏，可在文件上点「收藏」，或在菜单收藏当前目录）");
            empty.setTextColor(Ui.HINT);
            empty.setTextSize(Ui.sp(textLv, 11, 12, 13));
            empty.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));
            drawerPanel.addView(empty);
        } else {
            for (final String f : favs) {
                drawerPanel.addView(bookmarkRow(f));
            }
        }

        // 第三节：Bash 脚本（读取脚本目录下标记了 #myfileman_script: 的 .sh）
        drawerPanel.addView(sectionTitle("Bash"));
        ArrayList<ScriptItem> scripts = loadScripts();
        if (scripts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（在「设置 → 自定义脚本」指定目录后，自动加载其中标记 #myfileman_script: 的 .sh）");
            empty.setTextColor(Ui.HINT);
            empty.setTextSize(Ui.sp(textLv, 11, 12, 13));
            empty.setPadding(pad, Util.dp(this, 8), pad, Util.dp(this, 8));
            drawerPanel.addView(empty);
        } else {
            for (final ScriptItem s : scripts) {
                drawerPanel.addView(scriptRow(s));
            }
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
        TextView name = new TextView(this);
        name.setText(s.label);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(Ui.sp(textLv, 14, 15, 16));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(name);

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
            row.addView(cap);
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress((int) (100 - (avail * 100 / total)));
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.PRIMARY));
            row.addView(pb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 3)));
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

    /** 读取「设置 → 自定义脚本」目录下的全部 .sh，筛出前 5 行含
     *  #myfileman_script:标题 的文件，返回其（标题, 路径）。APP 加载/每次开抽屉时调用。 */
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
                    String title = parseScriptTitleRoot(full);
                    if (title == null) continue;
                    ScriptItem si = new ScriptItem();
                    si.title = title;
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
            String title = parseScriptTitle(f);
            if (title == null) continue;
            ScriptItem si = new ScriptItem();
            si.title = title;
            si.path = f.getAbsolutePath();
            out.add(si);
        }
        return out;
    }

    /** 通过 Root 会话读取文件前 5 行并解析 #myfileman_script: 标题（对应 root 权限下不可见的目录）。 */
    private String parseScriptTitleRoot(String path) {
        String head = Fs.rootHead(path, 5);
        if (head == null) return null;
        String[] lines = head.split("\n");
        for (String line : lines) {
            int idx = line.indexOf("#myfileman_script:");
            if (idx >= 0) {
                String rest = line.substring(idx + "#myfileman_script:".length()).trim();
                return rest.length() > 0 ? rest : null;
            }
        }
        return null;
    }

    /** 读取文件前 5 行，若出现 "#myfileman_script:标题"，返回冒号后的文字；否则 null。 */
    private String parseScriptTitle(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n < 5) {
                n++;
                int idx = line.indexOf("#myfileman_script:");
                if (idx >= 0) {
                    String rest = line.substring(idx + "#myfileman_script:".length()).trim();
                    return rest.length() > 0 ? rest : null;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Bash 分类下的一行：标题 + .sh 文件名，点击在结果窗口运行脚本。 */
    private View scriptRow(final ScriptItem s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Util.dp(this, 14), Util.dp(this, 5), Util.dp(this, 14), Util.dp(this, 5));
        TypedValue dd = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, dd, true);
        if (dd.resourceId != 0) row.setBackgroundResource(dd.resourceId);
        TextView name = new TextView(this);
        name.setText(s.title);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(Ui.sp(textLv, 14, 15, 16));
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);
        TextView sub = new TextView(this);
        sub.setText(new File(s.path).getName());
        sub.setTextColor(Ui.HINT);
        sub.setTextSize(Ui.sp(textLv, 9, 10, 11));
        sub.setPadding(Util.dp(this, 8), 0, 0, 0);
        row.addView(sub);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); runScript(s.path, s.title); }
        });
        return row;
    }

    /** 在结果窗口流式运行一个 .sh 脚本（沿用自定义命令的 Root 会话逻辑）。 */
    private void runScript(final String path, final String title) {
        final int timeout = Prefs.cmdTimeout(this);
        CmdOutputActivity.launchStreaming(this, title, new Runnable() {
            public void run() {
                final boolean root = Fs.rootMode;
                if (root && !CmdOutputActivity.openOutputRoot(30000)) {
                    CmdOutputActivity.liveAppend("未获取到 Root 授权，脚本未执行。\n"
                            + "请到「设置 → Root 权限」开启后再运行。\n");
                    CmdOutputActivity.liveComplete(false);
                    return;
                }
                CmdOutputActivity.setRunningRoot(root);
                CmdOutputActivity.liveAppend("$ sh " + path + "\n");
                final String cmd = "sh " + Util.sq(path);
                Fs.ExecResult r;
                if (root) {
                    r = CmdOutputActivity.streamOutputRoot(cmd, timeout, new Fs.LineSink() {
                        public void line(String line) { CmdOutputActivity.liveAppend(line + "\n"); }
                    });
                } else {
                    r = Fs.shellStream(cmd, false, timeout, new Fs.LineSink() {
                        public void line(String line) { CmdOutputActivity.liveAppend(line + "\n"); }
                    });
                }
                if (!r.ok()) {
                    if (r.err != null && r.err.trim().length() > 0)
                        CmdOutputActivity.liveAppend("[stderr] " + r.err.trim() + "\n");
                    CmdOutputActivity.liveAppend("[退出码 " + r.code + "]\n");
                }
                CmdOutputActivity.liveComplete(false);
            }
        });
    }
}
