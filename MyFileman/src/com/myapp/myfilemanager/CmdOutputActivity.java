package com.myapp.myfilemanager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 命令执行结果窗口：自定义命令与接收命令共用同一个 Activity（取代原对话框）。 */
public class CmdOutputActivity extends Activity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_AUTO_CLOSE = "auto_close";
    public static final String EXTRA_RUNNING = "running";   // 启动即处于「执行中」状态
    private static final long AUTO_CLOSE_DELAY_MS = 3000L;

    /**
     * 输出里若含形如 `[btn:标签]命令` 的行，会自动在底部生成按钮，
     * 点击即用 shell 执行 `]` 后面的命令（剩余文本，可含空格），结果追加到本窗口。
     * 这类控制行本身不会显示在正文里，避免重复。
     * 例：`[btn:open7z]/data/data/com.termux/files/home/apps/openwith.sh 7z`
     */
    static final Pattern BTN = Pattern.compile(
            "^\\s*\\[btn:([^\\]]+)]\\s*(.*)$", Pattern.MULTILINE);

    /** 当前可见的结果窗口实例，供后台命令执行线程把输出实时推送到这里。 */
    private static volatile CmdOutputActivity sActive = null;
    /** 窗口就绪后由 MainActivity 注入、在子线程里执行真正的命令（保证不丢最早的几行输出）。 */
    private static volatile Runnable sStarter = null;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable closeTask;

    private ScrollView sv;
    private HorizontalScrollView hsv;
    private TextView tv;
    private TextView titleTv;          // 顶栏标题，用于显示「执行中…」状态
    private StringBuilder body;        // 当前窗口显示的完整文本（含点击按钮后追加的结果）
    private String backup;             // 复制按钮使用的文本（与 body 同步）
    private boolean running = false;   // 是否仍在执行（标题附加「执行中…」）
    private boolean runningRoot = false; // 当前执行中的命令是否走 Root 会话（决定「停止」如何终止）
    private Button stopBtn;            // 执行中显示的「停止」按钮，用于卡死时强制终止命令
    /** 本窗口独立的 Root 会话（与主界面隔离）；仅当执行 Root 命令时才创建。 */
    private Fs.OutputSession outSession = null;

    // 动态生成的 [btn:标签] 命令按钮容器（始终创建，无按钮时隐藏）
    private HorizontalScrollView actScroll;
    private LinearLayout actRow;
    private View actDivider;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent it = getIntent();
        final String title = it.getStringExtra(EXTRA_TITLE);
        String text = it.getStringExtra(EXTRA_TEXT);
        if (text == null) text = "";
        boolean running = it.getBooleanExtra(EXTRA_RUNNING, false);

        // 解析 [btn:...] 标记：收集「标签 + 命令」，并从正文里剔除这些控制行。
        ArrayList<String[]> actions = parseActions(text);
        String display = actions.isEmpty() ? text : stripActions(text);

        body = new StringBuilder(display);
        backup = body.toString();
        this.running = running;

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
        applyTitle(title);
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

        // [btn:标签] 命令按钮：容器始终创建，无按钮时隐藏；流式输出过程中可动态新增。
        actScroll = new HorizontalScrollView(this);
        actScroll.setHorizontalScrollBarEnabled(false);
        actScroll.setVisibility(actions.isEmpty() ? View.GONE : View.VISIBLE);
        actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER_VERTICAL);
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
                    cm.setPrimaryClip(ClipData.newPlainText(title, backup));
                    toast("已复制执行结果");
                } catch (Throwable e) {
                    toast("复制失败");
                }
            }
        });
        row.addView(copy);

        // 停止按钮：仅在命令执行中可见；卡死时点它可强制终止当前命令，
        // 避免结果窗口挂死、并（对 Root 命令）释放主界面等待的 ROOT_LOCK。
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
            public void onClick(View v) { finish(); }
        });
        row.addView(close);
        bottom.addView(row);

        root.addView(bottom);
        setContentView(root);

        // 若启动即处于执行中（流式命令），显示「停止」按钮。
        if (running) stopBtn.setVisibility(View.VISIBLE);

        sActive = this;
        // 窗口已就绪：立刻在子线程里开始执行真正的命令（若本次是流式执行）。
        Runnable starter = sStarter;
        sStarter = null;
        if (starter != null) new Thread(starter).start();
    }

    private Button makeActionButton(final String label, final String cmd) {
        Button ab = Ui.solid(this, label, 1);
        ab.setEnabled(!running);   // 执行进行中时禁用，避免与流式命令争用 Root 会话导致死锁
        ab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runAction(label, cmd); }
        });
        return ab;
    }

    /** 顶栏标题 +（执行中…）状态后缀。 */
    private void applyTitle(String title) {
        String s = "执行结果 · " + (title == null ? "" : title);
        if (running) s += "（执行中…）";
        titleTv.setText(s);
    }

    /** 解析所有 `[btn:标签]命令` 行，去重（按标签）后返回 [标签, 命令]。 */
    private ArrayList<String[]> parseActions(String text) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        HashSet<String> seen = new HashSet<String>();
        Matcher m = BTN.matcher(text);
        while (m.find()) {
            String label = m.group(1).trim();
            String cmd = m.group(2).trim();
            if (TextUtils.isEmpty(label) || TextUtils.isEmpty(cmd)) continue;
            if (seen.contains(label)) continue;   // 同名标签只生成一个按钮
            seen.add(label);
            out.add(new String[]{ label, cmd });
        }
        return out;
    }

    /** 从正文里剔除所有 `[btn:...]` 控制行（连同其换行），压缩多余空行后再展示。 */
    private static String stripActions(String text) {
        String s = BTN.matcher(text).replaceAll("");
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
                final boolean asRoot;
                final Fs.ExecResult r;
                if (outSession != null && outSession.isOpen()) {
                    asRoot = true;
                    r = outSession.shell(cmd);
                } else {
                    asRoot = Fs.rootSessionAlive();
                    r = Fs.shell(cmd, asRoot);
                }
                final StringBuilder sb = new StringBuilder();
                sb.append("\n\n── ").append(label).append(asRoot ? "（Root）" : "").append(" ──\n");
                sb.append("$ ").append(cmd).append('\n');
                if (r.out != null && r.out.length() > 0) {
                    sb.append(r.out);
                    if (!r.out.endsWith("\n")) sb.append('\n');
                }
                if (r.err != null && r.err.trim().length() > 0) {
                    sb.append("[stderr] ").append(r.err.trim()).append('\n');
                }
                if (!r.ok()) sb.append("[退出码 ").append(r.code).append("]\n");
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        body.append(sb);
                        backup = body.toString();
                        tv.setText(body.toString());
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

    // ==================== 静态入口 / 流式推送 ====================

    /** 启动结果窗口的请求码；MainActivity 在 onActivityResult 中据此在窗口关闭后清空选中。 */
    static final int REQ_STREAM = 1002;

    /** 立即弹出一个「执行中」的结果窗口，并在窗口就绪后由 starter 在子线程执行命令。
     *  使用 startActivityForResult 启动，使 MainActivity 能在窗口关闭时收到回调（清空选中）。 */
    static void launchStreaming(Context c, String title, Runnable starter) {
        sStarter = starter;
        Intent it = new Intent(c, CmdOutputActivity.class);
        it.putExtra(EXTRA_TITLE, title);
        it.putExtra(EXTRA_TEXT, "");
        it.putExtra(EXTRA_RUNNING, true);
        it.putExtra(EXTRA_AUTO_CLOSE, false);   // 自动关闭延后到命令真正结束（liveComplete）时
        if (c instanceof Activity) ((Activity) c).startActivityForResult(it, REQ_STREAM);
        else c.startActivity(it);
    }

    /** 由执行线程在开始一条命令前调用：记录是否走 Root，并让「停止」按钮生效。 */
    static void setRunningRoot(boolean root) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() { public void run() {
            a.runningRoot = root;
            a.stopBtn.setEnabled(true);
            a.stopBtn.setVisibility(View.VISIBLE);
        }});
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

    /** 用本窗口独立会话一次性执行 Root 命令（如 [btn] 按钮命令）。 */
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

    /** 关闭本窗口独立会话（「停止」按钮 / 窗口销毁时调用）。 */
    static void stopOutputRoot() {
        final CmdOutputActivity a = sActive;
        if (a != null && a.outSession != null) a.outSession.kill();
    }

    /** 标记当前命令结束（无论成功/超时/停止），隐藏并禁用「停止」按钮。 */
    private void endRunning() {
        running = false;
        runningRoot = false;
        stopBtn.setEnabled(false);
        stopBtn.setVisibility(View.GONE);
        applyTitle(getIntent().getStringExtra(EXTRA_TITLE));
    }

    /** 强制停止当前执行中的命令：非 Root 销毁子进程，Root 关闭本窗口独立 su 会话（不波及主界面）。 */
    private void stopRunning() {
        if (!running) return;
        if (runningRoot) {
            if (outSession != null && outSession.isOpen()) outSession.kill();
            else Fs.closeRootSession();   // 兜底：理论上不会走到（[btn] 命令也走独立会话）
        } else {
            Fs.killCurrentShell();
        }
        ui.post(new Runnable() { public void run() {
            if (isFinishing()) return;
            body.append("\n── 已手动停止 ──\n");
            backup = body.toString();
            tv.setText(body.toString());
            endRunning();
            for (int i = 0; i < actRow.getChildCount(); i++)
                actRow.getChildAt(i).setEnabled(true);
        }});
    }

    /** 把一行原始文本追加到当前结果窗口（流式输出用）。 */
    static void liveAppend(String s) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() { public void run() { a.appendText(s); } });
    }

    /** 流式输出中遇到 `[btn:标签]命令`，动态在底部生成一个按钮。 */
    static void liveAddButton(String label, String cmd) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() {
            public void run() {
                if (a.actRow.getChildCount() == 0) {
                    a.actScroll.setVisibility(View.VISIBLE);
                    a.actDivider.setVisibility(View.VISIBLE);
                }
                a.actRow.addView(a.makeActionButton(label, cmd));
            }
        });
    }

    /** 命令执行完毕：去掉「执行中…」状态；autoClose 时约 3 秒后自动关闭窗口。 */
    static void liveComplete(boolean autoClose) {
        final CmdOutputActivity a = sActive;
        if (a == null) return;
        a.ui.post(new Runnable() {
            public void run() {
                a.endRunning();   // 隐藏「停止」、恢复标题与按钮
                for (int i = 0; i < a.actRow.getChildCount(); i++)
                    a.actRow.getChildAt(i).setEnabled(true);   // 重新启用 [btn] 按钮
                if (autoClose) {
                    a.closeTask = new Runnable() {
                        public void run() {
                            try { if (!a.isFinishing()) a.finish(); } catch (Throwable ignored) {}
                        }
                    };
                    a.ui.postDelayed(a.closeTask, AUTO_CLOSE_DELAY_MS);
                }
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
    protected void onDestroy() {
        super.onDestroy();
        if (closeTask != null) ui.removeCallbacks(closeTask);
        if (outSession != null) outSession.kill();   // 关闭本窗口独立会话，释放 su 进程
        if (sActive == this) sActive = null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
