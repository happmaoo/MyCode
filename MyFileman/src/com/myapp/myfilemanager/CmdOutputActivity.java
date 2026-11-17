package com.myapp.myfilemanager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 命令执行结果窗口：自定义命令与接收命令共用同一个 Activity（取代原对话框）。 */
public class CmdOutputActivity extends Activity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_RUNNING = "running";   // 启动即处于「执行中」状态
    /** 「切后台再打开 APP」重建标记：恢复数据走同进程静态快照 sPendingRestore，此处仅作布尔开关，
     *  不把正文/按钮/偏移塞进 Intent，也避免残留快照被其它启动路径误用。 */
    public static final String EXTRA_RESTORE = "restore";

    /** 所有控制行统一前缀：脚本只需记一个前缀；解析时先做一次前缀判断（命中才跑具体正则），
     *  绝大多数普通输出行走快速路径，无需逐个正则匹配，效率更高。 */
    static final String TAG = "[mycmd:";

    /**
     * 输出里若含形如 `[mycmd:btn:按钮名称]命令` 的行，会自动在底部生成按钮，
     * 点击即用 shell 执行 `]` 后面的命令（剩余文本，可含空格），结果追加到本窗口。
     * 这类控制行本身不会显示在正文里，避免重复。
     * 例：`[mycmd:btn:open7z]/data/data/com.termux/files/home/apps/openwith.sh 7z`
     * 删除按钮请用 `[mycmd:btn:按钮名称:0]`（见 BTN_DEL）。
     */
    static final Pattern BTN = Pattern.compile(
            "^\\s*\\[mycmd:btn:([^\\]]+)]\\s*(.*)$", Pattern.MULTILINE);

    /**
     * 删除按钮控制行：形如 `[mycmd:btn:按钮名称:0]` 的整行——`:0` 是删除标记，
     * `]` 后面必须为空（不带命令），表示删除此前生成的同名按钮；控制行本身不进正文。
     * 删除后仍可用 `[mycmd:btn:按钮名称]命令` 再次生成同名按钮。
     * 例：`[mycmd:btn:btn1:0]` → 删除名为 btn1 的按钮。
     */
    static final Pattern BTN_DEL = Pattern.compile(
            "^\\s*\\[mycmd:btn:(.+):0\\]\\s*$", Pattern.MULTILINE);

    /**
     * 输出里若含形如 `[mycmd:log]<路径>` 的行，APP 会持续轮询该文件，把其增量文本作为日志输出到本窗口，
     * 控制行本身不进正文。脚本/命令可先把长任务输出重定向到该文件、再打印此行，让界面不被长命令阻塞。
     * 路径规定为本进程（APP）可直接读取的公共目录（如 /sdcard/Download/xxx.log），
     * 不走 Root、不跨命名空间，因此不会触发任何 su 授权。
     * 例：`[mycmd:log]/sdcard/Download/test.log`
     */
    static final Pattern LOG = Pattern.compile(
            "^\\s*\\[mycmd:log\\]\\s*(.*)$", Pattern.MULTILINE);

    /**
     * 输出里若出现形如 `[mycmd:exit]` 的整行，表示命令执行完毕：
     * 仅作「结束标记」——窗口不会自动关闭，只是解除「钉住」状态：
     * 收到后再点「关闭」/返回键直接关闭（不再弹确认框），切后台再打开 APP 也不再自动切回本窗口。
     */
    static final Pattern EXIT = Pattern.compile(
            "^\\s*\\[mycmd:exit\\]\\s*$", Pattern.MULTILINE);

    /**
     * 输出里若出现形如 `[mycmd:title:标题文本]` 的行，动态把本窗口顶栏标题改为「标题文本」。
     * 标题文本位于 `:` 之后、行尾可选 `]` 之前，可含空格；控制行本身不进正文。
     * 例：`[mycmd:title:正在压缩…]`
     */
    static final Pattern TITLE = Pattern.compile(
            "^\\s*\\[mycmd:title:(.*)$", Pattern.MULTILINE);

    /**
     * 调试开关控制行：`[mycmd:debug:1]` 打开「详细模式」——显示日志文件/按钮动作/运行的命令等提示符行；
     * `[mycmd:debug:0]` 关闭（回到默认精简模式）。控制行本身不进正文。
     * 默认（未读到任何 debug 控制行）为精简模式：隐藏所有装饰性提示符，命令结束只显示一行空行 + 一行 ←END。
     */
    static final Pattern DEBUG = Pattern.compile(
            "^\\s*\\[mycmd:debug:([01])\\]\\s*$", Pattern.MULTILINE);

    /** 日志文件轮询回调：把增量文本追加到界面（旧行为：整段文本，不解析控制行）。 */
    public interface LogAppend { void append(String text); }

    /** 日志轮询解析出的「完整一行」回调：由调用方按控制行规则路由（[mycmd:exit]/[mycmd:btn:]/[mycmd:log]/[mycmd:title:] 等）。 */
    public interface LogLine { void line(String line); }

    /** 单个日志轮询的运行态：已读字节偏移 + 尚未凑成一行的半行缓冲。
     *  由窗口持有，onStop 时可整体快照，重建窗口时据此「续读」而非从头重放。 */
    static final class LogState {
        final AtomicLong off = new AtomicLong(0);
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        LogState(long startOff, byte[] startPending) {
            off.set(startOff);
            if (startPending != null && startPending.length > 0)
                pending.write(startPending, 0, startPending.length);
        }
        byte[] pendingBytes() { synchronized (pending) { return pending.toByteArray(); } }
    }

    /** 日志轮询的快照（同进程直传，无需序列化）：已读偏移 + 半行缓冲。 */
    static final class LogSnapshot {
        final long off;
        final byte[] pending;
        LogSnapshot(long o, byte[] p) { off = o; pending = p; }
    }

    /**
     * 启动一个日志文件轮询线程：每约 300ms 读取 path 的增量，按换行切出「完整行」逐行回调。
     * 未凑成一行的字节留在缓冲里等下一轮，保证控制行不会被增量边界切成两截、UTF-8 多字节字符也不会被截断。
     * 返回线程，调用方须在窗口/对话框销毁时 interrupt() 停止。
     * 路径规定为本进程（APP）可直接读取的公共目录（如 /sdcard/Download/xxx.log）：只做本进程直读，
     * 不经过 Root / 跨命名空间，全程不新建 su 进程，因此不会触发任何授权通知。
     */
    /** 供外部（含 size: 对话框）使用：从文件头开始一条全新的轮询。 */
    static Thread startLogFilePoll(final String path, final LogLine lineCb) {
        return startLogFilePoll(path, new LogState(0, null), lineCb);
    }

    /** 带恢复起点（已读偏移 + 半行缓冲）的轮询：重建窗口时从上次中断处继续，不重放历史行。 */
    static Thread startLogFilePoll(final String path, final LogState st, final LogLine lineCb) {
        final File f = new File(path);
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] data = null;
                        // 公共目录日志文件：本进程直读增量；文件尚未生成/不可读时静默等待下一轮
                        if (f.exists() && f.canRead()) data = directRead(f, st);
                        if (data != null && data.length > 0) {
                            st.off.addAndGet(data.length);
                            for (byte b : data) {
                                if (b == '\n') {
                                    byte[] lineBytes = st.pending.toByteArray();
                                    st.pending.reset();
                                    String line = new String(lineBytes, "UTF-8");
                                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                                    if (lineCb != null) lineCb.line(line);
                                } else {
                                    st.pending.write(b);
                                }
                            }
                        }
                        Thread.sleep(300);
                    } catch (InterruptedException e) { break; }
                    catch (Exception e) {
                        try { Thread.sleep(800); } catch (InterruptedException ie) { break; }
                    }
                }
            }
        }, "log-poll:" + path);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 兼容旧调用：把日志增量整段当文本追加（不做控制行解析）。 */
    static Thread startLogFilePoll(final String path, final LogAppend cb) {
        return startLogFilePoll(path, new LogLine() {
            public void line(String line) { if (cb != null) cb.append(line + "\n"); }
        });
    }

    /** 直读公共目录 path 从 off 字节到末尾的增量（按 4KB 分块，避免大日志一次吃内存）。
     *  文件被清空/重建（长度小于已读偏移）时重置偏移与行缓冲，从头重新读。 */
    private static byte[] directRead(final File f, final LogState st) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            if (raf.length() < st.off.get()) { st.off.set(0); st.pending.reset(); }
            raf.seek(st.off.get());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = raf.read(buf)) > 0) bos.write(buf, 0, n);
            raf.close();
            return bos.size() > 0 ? bos.toByteArray() : null;
        } catch (Exception e) { return null; }
    }

    /** 当前可见的结果窗口实例，供后台命令执行线程把输出实时推送到这里。 */
    private static volatile CmdOutputActivity sActive = null;
    /** 窗口就绪后由 MainActivity 注入、在子线程里执行真正的命令（保证不丢最早的几行输出）。 */
    private static volatile Runnable sStarter = null;
    /**
     * 「切后台再打开 APP」的恢复快照：进入后台且命令尚未结束（未收到 [mycmd:exit]）时由 onStop 写入，
     * 供 MainActivity.onResume 在窗口被 singleTask clear-top 销毁后重建。取走（takeRestore）即清空。
     * 只保存在进程内存里（不做持久化）：进程被系统整杀后不还原，符合约定。
     */
    private static volatile RestoreState sRestore = null;
    /** 恢复快照转交给新窗口的中转站：MainActivity 取走 sRestore 后放这里，等新 Activity 的 onCreate 消费。 */
    private static volatile RestoreState sPendingRestore = null;

    /** 结构化窗口快照：按钮列表、日志轮询进度、执行中状态、滚动位置都显式保存，
     *  不再靠「从正文文本反推控制行」——那会丢掉按钮、并让日志从头重放。 */
    static class RestoreState {
        final String title;                              // 当前顶栏标题（含 [mycmd:title:] 动态值）
        final String body;                               // 纯正文（已剥离控制行）
        final ArrayList<String[]> actions;               // 底部按钮：{标签, 命令}
        final LinkedHashMap<String, LogSnapshot> logs;   // 日志路径 → 已读偏移/半行缓冲
        final boolean running;                           // 是否仍在执行（决定停止按钮 / 按钮启用态 / 关闭确认）
        final boolean debug;                             // 详细模式开关（[mycmd:debug:1]/[mycmd:debug:0] 设定，重建后保持）
        final int scrollY, scrollX;                      // 输出区滚动位置
        RestoreState(String t, String b, ArrayList<String[]> a,
                     LinkedHashMap<String, LogSnapshot> l, boolean r, boolean d, int sy, int sx) {
            title = t; body = b; actions = a; logs = l; running = r; debug = d; scrollY = sy; scrollX = sx;
        }
    }
    /** 取走并清空恢复态（线程安全由单线程 UI 调用保证）。 */
    static RestoreState takeRestore() {
        RestoreState r = sRestore;
        sRestore = null;
        return r;
    }
    /** MainActivity.onResume 把快照交给新窗口；启动全新的流式窗口时会清空以免误恢复。 */
    static void setPendingRestore(RestoreState r) { sPendingRestore = r; }
    static RestoreState takePendingRestore() {
        RestoreState r = sPendingRestore;
        sPendingRestore = null;
        return r;
    }

    /** 是否存在「命令尚未结束（未收到 [mycmd:exit]）、仍被钉住」的结果窗口（用于 APP 回到前台时切回该窗口）。
     *  收到 [mycmd:exit] 后 exited=true，窗口不再被钉住、不再自动还原。 */
    static boolean hasPinnedWindow() {
        return sActive != null && !sActive.exited;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ScrollView sv;
    private HorizontalScrollView hsv;
    private TextView tv;
    private TextView titleTv;          // 顶栏标题：只显示标题文本，不带前缀/状态后缀
    private StringBuilder body;        // 当前窗口显示的完整文本（含点击按钮后追加的结果）
    private String backup;             // 复制按钮使用的文本（与 body 同步）
    private boolean running = false;   // 是否仍在执行（决定「停止」按钮显隐、按钮启用态、关闭确认）
    private boolean runningRoot = false; // 当前执行中的命令是否走 Root 会话（决定「停止」如何终止）
    private boolean exited = false;    // 命令是否已结束（收到 [mycmd:exit]）：结束后可常规关闭、不再被钉住；此前手动关闭需先确认
    private volatile boolean debug = false;   // 详细模式开关：false=精简（默认，隐藏装饰性提示符，结束只显示 ←END）；true=显示全部提示符（读到 [mycmd:debug:1] 后打开）
    private String curTitle = "";      // 当前顶栏标题：初始取 EXTRA_TITLE，可由 [mycmd:title:...] 动态修改
    private Button stopBtn;            // 执行中显示的「停止」按钮，用于强制终止当前命令
    /** 本窗口独立的 Root 会话（与主界面隔离）；仅当执行 Root 命令时才创建。 */
    private Fs.OutputSession outSession = null;
    /** 本窗口参与的日志文件轮询线程（[mycmd:log] 触发），onDestroy 时统一中断。 */
    private final ArrayList<Thread> logPollers = new ArrayList<Thread>();
    /** 本窗口的日志轮询运行态（路径 → 偏移/半行缓冲），兼作去重表；onStop 时整体快照供重建续读。 */
    private final LinkedHashMap<String, LogState> logStates = new LinkedHashMap<String, LogState>();
    /** 本窗口底部按钮的权威列表（标签 + 命令）：流式新增与重建还原都写这里，快照直接存它。 */
    private final ArrayList<String[]> curActions = new ArrayList<String[]>();

    // 动态生成的 [mycmd:btn:按钮名称] 命令按钮容器（始终创建，无按钮时隐藏）
    private HorizontalScrollView actScroll;
    private LinearLayout actRow;
    private View actDivider;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dbg.log("ACTIVITY", "CmdOutputActivity.onCreate");
        Intent it = getIntent();
        final String title = it.getStringExtra(EXTRA_TITLE);
        String text = it.getStringExtra(EXTRA_TEXT);
        if (text == null) text = "";
        boolean running = it.getBooleanExtra(EXTRA_RUNNING, false);

        // 从「切后台再打开」的结构化快照重建：标题/正文/按钮/执行中/日志进度/滚动位置全部还原，不重跑命令。
        // 数据走同进程静态对象，EXTRA_RESTORE 只是布尔开关，避免 Intent 塞大文本、也避免残留快照被误用。
        final RestoreState rs = it.getBooleanExtra(EXTRA_RESTORE, false) ? takePendingRestore() : null;
        final boolean restored = rs != null;
        final ArrayList<String[]> actions;
        final String display;
        if (restored) {
            text = rs.body;
            running = rs.running;
            actions = new ArrayList<String[]>(rs.actions);
            display = text;   // 快照里的正文已是剥离控制行后的纯正文，不能再 strip
        } else {
            // 首次启动：从原始文本解析 [mycmd:btn:...] 控制行并剔除，生成初始按钮。
            actions = parseActions(text);
            display = actions.isEmpty() ? text : stripActions(text);
        }

        body = new StringBuilder(display);
        backup = body.toString();
        this.running = running;
        this.debug = restored ? rs.debug : false;
        curActions.addAll(actions);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：标题（居中）
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Util.dp(this, 12);
        top.setPadding(pad, Util.dp(this, 10), Util.dp(this, 6), Util.dp(this, 10));
        top.setBackgroundColor(Color.WHITE);
        titleTv = new TextView(this);
        curTitle = restored ? rs.title : title;
        applyTitle();
        titleTv.setTextSize(Ui.sp(1, 14, 16, 18));
        titleTv.setTextColor(Ui.TEXT);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        top.addView(titleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);
        View line = Ui.line(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        root.addView(line);

        // 输出区：纵向 ScrollView + 横向 HorizontalScrollView，不自动换行。
        // 注意：ScrollView 的子必须 WRAP_CONTENT 高度，否则在 Activity 里内容会被裁切看不到。
        sv = new ScrollView(this);
        tv = new TextView(this);
        tv.setText(body.toString());
        tv.setTextSize(Ui.sp(Prefs.cmdTextLevel(this), 11, 12, 14));
        tv.setTextColor(Ui.TEXT);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);   // 长按可选中局部文本复制
        int p = Util.dp(this, 14);
        tv.setPadding(p, p, p, p);
        hsv = new HorizontalScrollView(this);
        hsv.addView(tv, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(hsv, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 底部按钮栏：上方分割线，下方（命令按钮行 +）复制/关闭行。
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.WHITE);

        View bline = Ui.line(this);
        bline.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        bottom.addView(bline);

        // [mycmd:btn:按钮名称] 命令按钮：容器始终创建，无按钮时隐藏；流式输出过程中可动态新增。
        actScroll = new HorizontalScrollView(this);
        actScroll.setHorizontalScrollBarEnabled(false);
        actScroll.setFillViewport(true);   // 内容不足一行时撑满宽度，使内部按钮可水平居中
        actScroll.setVisibility(actions.isEmpty() ? View.GONE : View.VISIBLE);
        actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER);   // 按钮在行内水平居中（竖直也居中）
        actRow.setPadding(Util.dp(this, 10), Util.dp(this, 6), Util.dp(this, 10), Util.dp(this, 2));
        for (final String[] a : actions) {
            actRow.addView(makeActionButton(a[0], a[1]));
        }
        actScroll.addView(actRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(actScroll);

        actDivider = Ui.line(this);
        actDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 1)));
        actDivider.setVisibility(actions.isEmpty() ? View.GONE : View.VISIBLE);
        bottom.addView(actDivider);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Util.dp(this, 10), Util.dp(this, 8), Util.dp(this, 10), Util.dp(this, 8));

        Button copy = Ui.solid(this, "复制", 1);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(curTitle == null ? "" : curTitle, backup));
                    toast("已复制执行结果");
                } catch (Throwable e) {
                    toast("复制失败");
                }
            }
        });
        row.addView(copy);

        // 停止按钮：仅在命令执行中可见；点它强制终止当前命令（关闭 App 自己的 su 会话 / shell）。
        stopBtn = Ui.solid(this, "停止", 1);
        stopBtn.setEnabled(false);
        stopBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopRunning(); }
        });
        row.addView(stopBtn);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(spacer);

        Button close = Ui.solid(this, "关闭", 1);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { requestClose(); }
        });
        row.addView(close);
        bottom.addView(row);

        root.addView(bottom);
        setContentView(root);

        sActive = this;
        // 重建时按快照续接日志轮询（带上已读偏移与半行缓冲，不从头重放历史行与控制行）。
        if (restored && rs.logs != null) {
            for (Map.Entry<String, LogSnapshot> e : rs.logs.entrySet())
                startLogPoll(e.getKey(), e.getValue().off, e.getValue().pending, false);
        }
        // 重建后原 Root 会话已被 onDestroy 关闭，显示「停止」也杀不到后台命令，故不显示；
        // running 状态仍按快照还原（影响按钮启用态与关闭确认），日志里出现 [mycmd:exit] 后自动结束。
        if (running && !restored) {
            stopBtn.setVisibility(View.VISIBLE);
            stopBtn.setEnabled(true);
        }
        // 重建后没有流式命令争用会话，按钮恢复可点击（停止走脚本自带的 [mycmd:btn:停止]）。
        if (restored) {
            for (int i = 0; i < actRow.getChildCount(); i++)
                actRow.getChildAt(i).setEnabled(true);
        }
        // 还原输出区滚动位置（需等布局完成）。
        if (restored) {
            final int sy = rs.scrollY, sx = rs.scrollX;
            sv.post(new Runnable() { public void run() {
                sv.scrollTo(0, sy);
                hsv.scrollTo(sx, 0);
            }});
        }
        // 窗口已就绪：立刻在子线程里开始执行真正的命令（若本次是流式执行）。
        Runnable starter = sStarter;
        sStarter = null;
        if (starter != null) new Thread(starter).start();
    }

    private Button makeActionButton(final String label, final String cmd) {
        Button ab = Ui.solid(this, label, 1);
        ab.setTag(label);          // 供 [mycmd:btn:名称:0] 删除时按标签定位视图
        ab.setEnabled(!running);   // 执行进行中时禁用，避免与流式命令争用 Root 会话导致死锁
        ab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runAction(label, cmd); }
        });
        return ab;
    }

    /** 顶栏标题：不加任何前缀、不加状态后缀，直接显示 curTitle
     *  （初始取 EXTRA_TITLE / 命令名，可由 [mycmd:title:...] 动态修改）。
     *  执行中状态由「停止」按钮与按钮启用态体现，不占用标题文字。 */
    private void applyTitle() {
        titleTv.setText(curTitle == null ? "" : curTitle);
    }

    /** 解析所有 `[mycmd:btn:按钮名称]命令` 行，去重（按标签）后返回 [标签, 命令]。
     *  `[mycmd:btn:名称:0]` 删除行会移除此前收集的同名按钮（按出现顺序处理）。 */
    private ArrayList<String[]> parseActions(String text) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        HashSet<String> seen = new HashSet<String>();
        Matcher m = BTN.matcher(text);
        while (m.find()) {
            // 删除行：按标签移除已收集的同名按钮
            Matcher dm = BTN_DEL.matcher(m.group(0));
            if (dm.find()) {
                String del = dm.group(1).trim();
                for (int i = out.size() - 1; i >= 0; i--) {
                    if (out.get(i)[0].equals(del)) out.remove(i);
                }
                seen.remove(del);
                continue;
            }
            String label = m.group(1).trim();
            String cmd = m.group(2).trim();
            if (TextUtils.isEmpty(label) || TextUtils.isEmpty(cmd)) continue;
            if (seen.contains(label)) continue;   // 同名标签只生成一个按钮
            seen.add(label);
            out.add(new String[]{ label, cmd });
        }
        return out;
    }

    /** 从正文里剔除所有控制行（btn / btn 删除 / log / title / exit），压缩多余空行后再展示。 */
    private static String stripActions(String text) {
        String s = BTN.matcher(text).replaceAll("");
        s = LOG.matcher(s).replaceAll("");
        s = EXIT.matcher(s).replaceAll("");
        s = TITLE.matcher(s).replaceAll("");
        // 移除控制行后会留下空行，把 3 个及以上连续换行压成 2 个，并裁掉首尾空白。
        s = s.replaceAll("(?:\\r?\\n){3,}", "\n\n").trim();
        return s;
    }

    /** 点击底部生成的命令按钮：后台执行 `]` 后的命令，结果追加到同一窗口并滚到底部。 */
    private void runAction(final String label, final String cmd) {
        toast("运行：" + label);
        new Thread(new Runnable() {
            public void run() {
                // 优先用本窗口独立会话执行（与主界面隔离）；无则回退到主界面共享会话。
                // Root 情形下走 su --mount-master -c，保证与后台任务同一挂载命名空间（[mycmd:btn:stop] 等）。
                final boolean asRoot;
                final Fs.ExecResult r;
                if (outSession != null && outSession.isOpen()) {
                    asRoot = true;
                    r = Fs.shellMountMaster(cmd);
                } else {
                    asRoot = Fs.rootSessionAlive();
                    r = Fs.shell(cmd, asRoot);
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        // 精简模式（默认）不显示「按钮动作 / 运行的命令」提示符，详细模式（[mycmd:debug:1]）才显示
                        if (debug) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("\n\n── ").append(label).append(asRoot ? "（Root）" : "").append(" ──\n");
                            sb.append("$ ").append(cmd).append('\n');
                            body.append(sb);
                            backup = body.toString();
                            tv.setText(body.toString());
                        }
                        // 按钮命令的输出也按控制行规则逐行解析（与主命令一致）：
                        // [mycmd:log] 启动日志轮询、[mycmd:exit] 标记命令结束、[mycmd:btn:标签]命令 生成新按钮，其余进正文。
                        if (r.out != null && r.out.length() > 0) {
                            for (String ln : r.out.split("\n")) handleActionLine(ln);
                        }
                        if (r.err != null && r.err.trim().length() > 0) appendText("[stderr] " + r.err.trim() + "\n");
                        if (!r.ok()) appendText("[退出码 " + r.code + "]\n");
                        endRunning();
                        // 滚到最新结果：先纵向触底，再横向归零（保持最左对齐）
                        sv.post(new Runnable() {
                            public void run() {
                                sv.fullScroll(View.FOCUS_DOWN);
                                hsv.fullScroll(View.FOCUS_LEFT);
                            }
                        });
                    }
                });
            }
        }).start();
    }

    /** 处理按钮命令 / 日志文件输出的单行：与命令主输出共用控制行规则，其余进正文。
     *  统一前缀 [mycmd: 快速判断：非控制行直接进正文，避免逐行跑多次正则，效率更高。 */
    private void handleActionLine(String line) {
        if (!line.trim().startsWith(TAG)) { appendText(line + "\n"); return; }
        Matcher dbgm = DEBUG.matcher(line);
        if (dbgm.find() && dbgm.start() == 0) {
            debug = "1".equals(dbgm.group(1));   // [mycmd:debug:1] 开，[mycmd:debug:0] 关
            return;
        }
        Matcher dm = BTN_DEL.matcher(line);
        if (dm.find() && dm.start() == 0) {
            String l = dm.group(1).trim();
            if (!l.isEmpty()) liveRemoveButton(l);
            return;
        }
        Matcher m = BTN.matcher(line);
        if (m.find() && m.start() == 0
                && m.group(1).trim().length() > 0 && m.group(2).trim().length() > 0) {
            liveAddButton(m.group(1).trim(), m.group(2).trim());
            return;
        }
        Matcher lm = LOG.matcher(line);
        if (lm.find() && lm.start() == 0) {
            String p = lm.group(1).trim();
            if (!p.isEmpty()) startLogPoll(p);
            return;
        }
        Matcher tm = TITLE.matcher(line);
        if (tm.find() && tm.start() == 0) {
            String t = tm.group(1).trim();
            if (t.endsWith("]")) t = t.substring(0, t.length() - 1).trim();   // 兼容末尾可选 ]
            if (!t.isEmpty()) { curTitle = t; applyTitle(); }
            return;
        }
        Matcher em = EXIT.matcher(line);
        if (em.find() && em.start() == 0) {
            liveExit();
            return;
        }
        appendText(line + "\n");
    }

    // ==================== 静态入口 / 流式推送 ====================

    /** 启动结果窗口的请求码；MainActivity 在 onActivityResult 中据此在窗口关闭后清空选中。 */
    static final int REQ_STREAM = 1002;

    /** 立即弹出一个「执行中」的结果窗口，并在窗口就绪后由 starter 在子线程执行命令。
     *  使用 startActivityForResult 启动，使 MainActivity 能在窗口关闭时收到回调（清空选中）。 */
    static void launchStreaming(Context c, String title, Runnable starter) {
        sPendingRestore = null;   // 全新流式命令：清掉可能残留的恢复快照，避免误判为重建
        sStarter = starter;
        Intent it = new Intent(c, CmdOutputActivity.class);
        it.putExtra(EXTRA_TITLE, title);
        it.putExtra(EXTRA_TEXT, "");
        it.putExtra(EXTRA_RUNNING, true);
        if (c instanceof Activity) ((Activity) c).startActivityForResult(it, REQ_STREAM);
        else c.startActivity(it);
    }

    /** 由执行线程在开始一条命令前调用：记录是否走 Root，并让「停止」按钮生效。 */
    static void setRunningRoot(boolean root) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() { public void run() {
            a.running = true;
            a.runningRoot = root;
            a.stopBtn.setEnabled(true);
            a.stopBtn.setVisibility(View.VISIBLE);
            a.applyTitle();
        }});
    }

    /** 由执行线程/控制行解析在「当前活动窗口」上设置详细模式开关（读到 [mycmd:debug:1]/[mycmd:debug:0] 时调用）。 */
    static void setDebug(boolean d) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.debug = d;
    }
    /** 读取「当前活动窗口」的详细模式开关（供控制行解析、提示符行渲染、结束标记判断共用）。 */
    static boolean getDebug() {
        final CmdOutputActivity a = sActive;
        return a != null && a.debug;
    }

    /** 强制停止当前执行中的命令：非 Root 销毁 App 自己的 shell 子进程；Root 关闭本窗口独立 su 会话（App 直接子进程），并以 root 进程表兜底清理残留（孤儿 7z 等）。 */
    private void stopRunning() {
        if (!running) return;
        running = false;   // 先复位，避免重复触发
        appendText("\n── 已手动停止 ──\n");
        new Thread(new Runnable() {
            public void run() {
                if (runningRoot) {
                    if (outSession != null && outSession.isOpen()) outSession.kill();
                    // 兜底：App 非 root 只能杀自己直接子进程 su；su 关闭后若 7z 已成孤儿，
                    // 改以 root（su --mount-master）按名杀，确保停得掉（原理同你 termux 里 sudo pkill -9 7z）。
                    try { Fs.shellMountMaster("pkill -9 -f 7z"); } catch (Exception ignored) {}
                } else {
                    Fs.killCurrentShell();
                }
                ui.post(new Runnable() { public void run() {
                    if (isFinishing()) return;
                    endRunning();
                    for (int i = 0; i < actRow.getChildCount(); i++)
                        actRow.getChildAt(i).setEnabled(true);   // 重新启用 [mycmd:btn] 按钮
                }});
            }
        }).start();
    }

    /** 请求关闭窗口：未收到 `[mycmd:exit]`（命令未结束）前点「关闭」/系统返回键，先弹确认框，确认后才关。 */
    private void requestClose() {
        if (exited) { finish(); return; }   // 命令已结束（收到 [mycmd:exit]），可直接关
        new AlertDialog.Builder(this)
                .setTitle("确认关闭")
                .setMessage("命令还未结束（未收到 [mycmd:exit] 信号），确定关闭此窗口吗？\n关闭后若后台还有命令在跑，可能需要手动清理。")
                .setPositiveButton("确定关闭", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        exited = true;   // 标记为主动退出：不再被「钉住」重新拉回前台
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 系统返回键：未收到 [mycmd:exit] 前与「关闭」按钮同样需先确认。 */
    @Override
    public void onBackPressed() {
        requestClose();
    }

    /** 流式输出收到 `[mycmd:exit]`：仅标记「命令已结束」——窗口保持打开、不自动关闭。
     *  标记后：点「关闭」/返回键可直接关（不再弹确认框），切后台再打开 APP 也不再自动切回本窗口。
     *  同时结束「执行中」状态、恢复底部按钮，让窗口呈现明确的「已完成」状态。 */
    static void liveExit() {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() {
            public void run() {
                if (a.isFinishing()) return;
                a.exited = true;   // 命令已结束：解除「钉住」，此后可常规关闭
                sRestore = null;   // 清掉可能残留的旧快照，结束之后不再自动还原窗口
                a.endRunning();    // 结束执行中状态、隐藏「停止」按钮
                for (int i = 0; i < a.actRow.getChildCount(); i++)
                    a.actRow.getChildAt(i).setEnabled(true);   // 恢复 [mycmd:btn] 按钮
                a.appendEndMarker(true);   // 详细模式显示结束横幅，精简模式只显示一行空行 + ←END
            }
        });
    }

    /** 命令结束标记：详细模式（[mycmd:debug:1]）显示结束横幅，精简模式（默认）只显示一行空行 + 一行 ←END。
     *  @param explicit 是否由显式的 [mycmd:exit] 触发（用于横幅措辞区分）。 */
    private void appendEndMarker(boolean explicit) {
        if (debug) {
            appendText("\n── 命令已结束" + (explicit ? " [mycmd:exit]" : "") + " ──\n");
        } else {
            appendText("\n←END\n");   // 精简模式：一行空行 + 一行 ←END
        }
    }

    // ==================== 独立 Root 会话（与主界面隔离） ====================

    /** 打开本窗口独立的 Root 会话（首次会弹一次授权）；已就绪直接返回 true。 */
    static boolean openOutputRoot(long timeoutMs) {
        final CmdOutputActivity a = sActive;
        if (a == null) return false;
        if (a.outSession == null) a.outSession = new Fs.OutputSession();
        return a.outSession.open(timeoutMs);
    }

    /** 用本窗口独立会话流式执行 Root 命令（逐行回传）。 */
    static Fs.ExecResult streamOutputRoot(String script, int timeoutSec, Fs.LineSink sink) {
        final CmdOutputActivity a = sActive;
        if (a == null || a.outSession == null) {
            Fs.ExecResult r = new Fs.ExecResult();
            r.code = -1; r.err = "结果窗口未就绪";
            return r;
        }
        return a.outSession.stream(script, timeoutSec, sink);
    }

    /** 用本窗口独立会话一次性执行 Root 命令（如 [mycmd:btn] 按钮命令）。 */
    static Fs.ExecResult shellOutputRoot(String script) {
        final CmdOutputActivity a = sActive;
        if (a == null || a.outSession == null) {
            Fs.ExecResult r = new Fs.ExecResult();
            r.code = -1; r.err = "结果窗口未就绪";
            return r;
        }
        return a.outSession.shell(script);
    }

    /** 按本窗口会话的命名空间状态映射 Root 路径（供模板展开使用）。 */
    static String mapRootPath(String p) {
        final CmdOutputActivity a = sActive;
        if (a == null || a.outSession == null) return Fs.rootPath(p);
        return a.outSession.rootPath(p);
    }

    /** 为本窗口新增一条日志轮询（从文件头开始）：[mycmd:log] 控制行走这里。 */
    static void startLogPoll(String path) { startLogPoll(path, 0, null, true); }

    /** 带恢复起点的日志轮询：之后逐行解析文件增量，
     *  使写进日志文件里的控制行（[mycmd:exit]/[mycmd:btn:]/[mycmd:log]/[mycmd:title:]）也生效，其余行进正文。
     *  重建窗口时从快照的偏移/半行缓冲继续，不重放历史行；同一路径只起一个轮询。
     *  announce=false 用于重建：正文里已有原来的「日志文件：」提示行，不再重复追加。 */
    static void startLogPoll(final String path, final long startOff, final byte[] startPending,
                             final boolean announce) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        final LogState st = new LogState(startOff, startPending);
        synchronized (a.logStates) {
            if (a.logStates.containsKey(path)) return;
            a.logStates.put(path, st);
        }
        if (announce) {
            a.ui.post(new Runnable() { public void run() {
                if (a.isFinishing()) return;
                if (a.debug) a.appendText("\n── 日志文件：" + path + " ──\n");
            }});
        }
        Thread t = startLogFilePoll(path, st, new LogLine() {
            public void line(final String line) {
                final CmdOutputActivity a2 = sActive;
                if (a2 == null) return;
                a2.ui.post(new Runnable() { public void run() {
                    if (!a2.isFinishing()) a2.handleActionLine(line);
                }});
            }
        });
        a.logPollers.add(t);
    }

    /** 标记当前命令结束（无论成功/超时/停止）：隐藏「停止」、恢复标题与按钮。 */
    private void endRunning() {
        running = false;
        runningRoot = false;
        stopBtn.setEnabled(false);
        stopBtn.setVisibility(View.GONE);
        applyTitle();
    }

    /** 把一行原始文本追加到当前结果窗口（流式输出用）。 */
    static void liveAppend(String s) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() { public void run() { a.appendText(s); } });
    }

    /** 流式输出中遇到 `[mycmd:btn:按钮名称]命令`，动态在底部生成一个按钮；同名标签只生成一个，
     *  并登记到 curActions，供「切后台再打开」时按快照还原。 */
    static void liveAddButton(final String label, final String cmd) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() {
            public void run() {
                for (String[] x : a.curActions) if (x[0].equals(label)) return;   // 去重
                a.curActions.add(new String[]{ label, cmd });
                if (a.actRow.getChildCount() == 0) {
                    a.actScroll.setVisibility(View.VISIBLE);
                    a.actDivider.setVisibility(View.VISIBLE);
                }
                a.actRow.addView(a.makeActionButton(label, cmd));
            }
        });
    }

    /** 流式输出收到 `[mycmd:btn:名称:0]`：删除同名按钮。
     *  同时从 curActions（快照权威列表）与 actRow（界面视图）移除，按钮清空后隐藏整行。
     *  从子线程调用安全，内部切回 UI 线程执行。 */
    static void liveRemoveButton(final String label) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() { public void run() {
            for (int i = a.curActions.size() - 1; i >= 0; i--) {
                if (a.curActions.get(i)[0].equals(label)) a.curActions.remove(i);
            }
            for (int i = a.actRow.getChildCount() - 1; i >= 0; i--) {
                if (label.equals(a.actRow.getChildAt(i).getTag())) a.actRow.removeViewAt(i);
            }
            if (a.actRow.getChildCount() == 0) {
                a.actScroll.setVisibility(View.GONE);
                a.actDivider.setVisibility(View.GONE);
            }
        }});
    }

    /** 流式输出收到 `[mycmd:title:标题文本]`：动态更新顶栏标题（标题始终为纯文本，无前缀/状态后缀）。
     *  从子线程调用安全，内部切回 UI 线程执行。 */
    static void liveSetTitle(final String t) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() { public void run() {
            if (a.isFinishing()) return;
            a.curTitle = t;
            a.applyTitle();
        }});
    }

    /** 命令流执行完毕：结束执行中状态、重新启用底部按钮。窗口始终不会自动关闭，
     *  脚本可发出 `[mycmd:exit]` 标记命令结束（此后点「关闭」直接关），或用户手动「关闭」（未收到 exit 前会先确认）。 */
    static void liveComplete() {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() {
            public void run() {
                a.endRunning();   // 恢复标题与按钮
                for (int i = 0; i < a.actRow.getChildCount(); i++)
                    a.actRow.getChildAt(i).setEnabled(true);   // 重新启用 [mycmd:btn] 按钮
                // 自然结束（未收到 [mycmd:exit]）也补上结束标记：详细模式显示横幅，精简模式只显示空行 + ←END
                if (!a.exited) a.appendEndMarker(false);
            }
        });
    }

    private void appendText(String s) {
        if (isFinishing()) return;
        body.append(s);
        backup = body.toString();
        tv.setText(body.toString());
        sv.post(new Runnable() {
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
                hsv.fullScroll(View.FOCUS_LEFT);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 进入后台且尚未收到 [mycmd:exit]：保存结构化快照（仅进程内存，不做持久化），
        // 供「再次打开 APP 时切回窗口」重建（singleTask clear-top 会销毁本窗口，但进程常仍存活）。
        // 主动退出时已置 exited，下面 onDestroy 会清除。
        if (!exited) {
            LinkedHashMap<String, LogSnapshot> logs = new LinkedHashMap<String, LogSnapshot>();
            synchronized (logStates) {
                for (Map.Entry<String, LogState> e : logStates.entrySet())
                    logs.put(e.getKey(), new LogSnapshot(e.getValue().off.get(), e.getValue().pendingBytes()));
            }
            int sy = sv != null ? sv.getScrollY() : 0;
            int sx = hsv != null ? hsv.getScrollX() : 0;
            sRestore = new RestoreState(curTitle, body.toString(),
                    new ArrayList<String[]>(curActions), logs, running, debug, sy, sx);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Thread t : logPollers) { try { t.interrupt(); } catch (Exception ignored) {} }
        logPollers.clear();   // 中断并清理日志文件轮询线程
        // 会话清理一律放子线程：命令可能仍在执行中并持有会话锁，
        // 绝不能让 onDestroy 在 UI 线程上等锁（曾导致「确认关闭后回主界面卡死 / ANR」）。
        // kill() 会先在锁外 abort() 投递 EOF 哨兵唤醒阻塞中的 stream()，读循环随即返回、释放锁。
        final Fs.OutputSession os = outSession;
        outSession = null;
        if (os != null) {
            new Thread(new Runnable() {
                public void run() { os.kill(); }
            }, "output-session-kill").start();
        }
        if (sActive == this) sActive = null;
        // 命令已结束（[mycmd:exit]）或确认关闭后清除恢复态，避免「再次打开 APP」把它重建出来；
        // 切后台被系统销毁（未 exited）时不清，留给 MainActivity.onResume 重建。
        if (exited) sRestore = null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
