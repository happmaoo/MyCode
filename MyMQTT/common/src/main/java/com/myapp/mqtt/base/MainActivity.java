package com.myapp.mqtt.base;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.text.Layout;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面（两端共用）：参照 MyBleClient 的 sh 窗口布局。
 * 顶部 Server+Connect / 状态 / Pub 主题 / 工具行（自动换行、A-、A+、COPY、CLEAR），
 * 中间等宽日志，底部多行指令输入 + 历史上下箭头 + ⌄CMD 面板 + SEND。
 */
public class MainActivity extends Activity implements MqttCore.Listener {

    // ===== 外部编辑器（vi/vim 编辑回传）=====
    private static final int REQ_EDIT = 2001;
    private static final int REQ_UP = 2002;
    private String pendingUpPath;
    private String editLocal;      // 本地副本绝对路径
    private String editRemote;     // 执行端原始路径
    private String editMode = "644";
    private String editSha;        // 上次比对用的 sha256
    private boolean checkOnResume;  // 只有去编辑器后回来才检查，平常切界面不读文件不刷日志
    private long lastCheckMs;

    private static final int MAX_CHARS = 200000;
    private static final int FONT_MIN = 12, FONT_MAX = 48, FONT_STEP = 2;

    private EditText etMsg;
    private TextView tvServer;
    private Button btnConnect, btnSend, btnQuick, btnHistUp, btnHistDown;
    private Button btnFontUp, btnFontDown, btnCopy, btnClear, tabQuick, tabHist;
    private TextView tvInfo, tvResult, tvMonitor;
    private CheckBox cbWrap;
    private HorizontalScrollView hsvOut;
    private ScrollView svOut;
    private LinearLayout llCmdpad;
    private ListView lvCmdpad;

    private ArrayAdapter<String> padAdapter;
    private String curTab = "quick";
    private boolean padCollapsePending;   // 本次触摸起的点在 CMD 面板外：抬手后收起面板
    private final Rect padHitRect = new Rect();
    private View padTouchChild;           // 正被按住高亮的 CMD 条目
    private float padTouchDownY;
    private static final int PAD_HL_COLOR = 0xFFCFEAE6;   // 按住反馈色

    private final StringBuilder plain = new StringBuilder();
    private boolean wrap = true;
    private int fontSize = 12;
    private boolean renderScheduled;

    private String[] histArr;
    private int histIdx = -1;
    private String histDraft = "";


    private boolean executor;
    private boolean debugMode;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static WeakReference<MainActivity> curRef;

    /** 给控制广播用：把界面全部收掉（没有界面时什么也不做） */
    public static void closeAll() {
        MainActivity a = curRef == null ? null : curRef.get();
        if (a != null && !a.isFinishing()) a.finishAffinity();
    }

    @Override
    protected void onDestroy() {
        if (curRef != null && curRef.get() == this) curRef = null;
        super.onDestroy();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = P.isExecutor(this);
        debugMode = P.debug(this);
        curRef = new WeakReference<>(this);
        setContentView(R.layout.main);

        tvServer = findViewById(R.id.tv_server);
        etMsg = findViewById(R.id.et_msg);
        btnConnect = findViewById(R.id.btn_connect);
        btnSend = findViewById(R.id.btn_send);
        btnQuick = findViewById(R.id.btn_quick);
        btnHistUp = findViewById(R.id.btn_hist_up);
        btnHistDown = findViewById(R.id.btn_hist_down);
        btnFontUp = findViewById(R.id.btn_font_up);
        btnFontDown = findViewById(R.id.btn_font_down);
        btnCopy = findViewById(R.id.btn_copy);
        btnClear = findViewById(R.id.btn_clear);
        tabQuick = findViewById(R.id.tab_quick);
        tabHist = findViewById(R.id.tab_hist);
        tvInfo = findViewById(R.id.tv_info);
        tvMonitor = findViewById(R.id.tv_monitor);
        tvResult = findViewById(R.id.tv_result);
        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString("log");   // 界面被系统重建时恢复上次日志，避免“清空 + 从顶部开始”
            if (saved != null && !saved.isEmpty()) {
                plain.append(saved);
                rendered = "";
            }
        }
        cbWrap = findViewById(R.id.cb_wrap);
        hsvOut = findViewById(R.id.hsv_out);
        svOut = findViewById(R.id.sv_out);
        svOut.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) collapseCmdPad();  // 点日志区收起 CMD 面板
            return false;
        });
        llCmdpad = findViewById(R.id.ll_cmdpad);
        lvCmdpad = findViewById(R.id.lv_cmdpad);

        tvServer.setText(P.host(this) + ":" + P.port(this));

        // 恢复显示偏好
        SharedPreferences sp = uiPref();
        wrap = sp.getBoolean("wrap", true);
        fontSize = sp.getInt("font_sp", 12);
        if (fontSize < FONT_MIN) fontSize = FONT_MIN;
        cbWrap.setChecked(wrap);
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        applyWrap();

        String lastCrash = CrashLog.read(this);
        if (!lastCrash.isEmpty()) {
            appendPlain("=== 上次崩溃（可 COPY 后反馈） ===");
            appendPlain(lastCrash.trim());
            appendPlain("=== 崩溃日志结束 ===");
            CrashLog.clear(this);
            collapseCmdPad();
        }

        btnSend.setText("SEND");
        applyRoleUi();
        append(executor
                ? "[本端=执行端] 订阅 " + join(P.subTopics(this)) + "，结果发到 " + P.pubTopic(this)
                : "[本端=控制端] 发到 " + P.pubTopic(this) + "，监听 " + join(P.subTopics(this)));

        btnConnect.setOnClickListener(v -> {
            MqttCore core = MqttCore.get();
            String st = core.stateText();
            boolean busy = core.isConnecting() || st.startsWith("等待重连");
            if (core.isConnected() || busy) {
                stopService(new Intent(this, MqttService.class));
                core.disconnect();
                refresh();
            } else {
                if (P.host(this).trim().isEmpty()) {
                    toast("Server 不能为空（去 Settings 里填）");
                    return;
                }
                startMqttService(MqttService.ACTION_CONNECT);
                refresh();
            }
        });

        btnSend.setOnClickListener(v -> onSend(true));
        btnQuick.setOnClickListener(v -> toggleCmdPad());
        tabQuick.setOnClickListener(v -> setTab("quick"));
        tabHist.setOnClickListener(v -> setTab("hist"));
        // ListView 默认的 selector 高亮靠主题 drawable，在深色/无 selector 主题下几乎看不到，按下后要等抬手才变色。
        // 这里自己给按住的条目上色，DOWN 即生效；返回 false 不消费事件，滚动与点击照常交给 ListView。
        lvCmdpad.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                padTouchDownY = e.getY();
                int pos = lvCmdpad.pointToPosition((int) e.getX(), (int) e.getY());
                if (pos != AdapterView.INVALID_POSITION) {
                    View ch = lvCmdpad.getChildAt(pos - lvCmdpad.getFirstVisiblePosition());
                    if (ch != null) {
                        ch.setBackgroundColor(PAD_HL_COLOR);
                        padTouchChild = ch;
                    }
                }
            } else if (a == MotionEvent.ACTION_MOVE) {
                if (padTouchChild != null
                        && Math.abs(e.getY() - padTouchDownY) > 12 * getResources().getDisplayMetrics().density) {
                    releasePadHighlight();      // 变成滑动，撤掉高亮
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                releasePadHighlight();
            }
            return false;
        });

        lvCmdpad.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = parent.getItemAtPosition(position);
                String c = o == null ? null : o.toString();
                if (c != null && c.length() > 0 && !c.startsWith("(")) {
                    etMsg.setText(c);
                    etMsg.setSelection(c.length());
                    histIdx = -1;
                    onSend(true);
                }
            }
        });
        setTab("quick");

        btnHistUp.setOnClickListener(v -> histPrev());
        btnHistDown.setOnClickListener(v -> histNext());

        btnFontUp.setOnClickListener(v -> {
            fontSize = Math.min(FONT_MAX, fontSize + FONT_STEP);
            applyFont();
        });
        btnFontDown.setOnClickListener(v -> {
            fontSize = Math.max(FONT_MIN, fontSize - FONT_STEP);
            applyFont();
        });
        cbWrap.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                wrap = checked;
                uiPref().edit().putBoolean("wrap", wrap).apply();
                applyWrap();
            }
        });

        // 双击日志区域 = 全屏 / 退出全屏（替代原来的 FULL 按钮）
        final android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                        toggleFull();
                        return true;
                    }
                });
        tvResult.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) collapseCmdPad();  // 点日志区收起 CMD 面板
            gd.onTouchEvent(e);
            return false;   // 不消费，长按选择/复制照旧
        });

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("mqtt", tvResult.getText().toString()));
                toast("已复制");
            }
        });
        btnClear.setOnClickListener(v -> {
            plain.setLength(0);
            rendered = "";
            tvResult.setText("");
            scrollToBottom();
        });

        refresh();

        // 供自动化/快捷方式用：am start ... --ez auto true 时直接连
        if (getIntent() != null && getIntent().getBooleanExtra("auto", false)
                && !MqttCore.get().isConnected()) {
            startMqttService(MqttService.ACTION_CONNECT);
        }
    }

    private static String join(java.util.List<String> list) {
        StringBuilder b = new StringBuilder();
        for (String s : list) {
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.length() == 0 ? "(空)" : b.toString();
    }

    /** 标题和首行提示跟着角色走（同一个包，设置页里可以切换） */
    private void applyRoleUi() {
        executor = P.isExecutor(this);
        setTitle(executor ? "MyMQTT · Executor" : "MyMQTT · Control");
    }

    // ---------------- 菜单 ----------------

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_TOPICS = 2;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem s = menu.add(0, MENU_SETTINGS, 0, "Settings");
        s.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        MenuItem t = menu.add(0, MENU_TOPICS, 1, "Subscribe topics");
        t.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == MENU_TOPICS) {
            startActivity(new Intent(this, TopicsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (plain.length() > 0) {
            String s = plain.toString();
            if (s.length() > 20000) s = s.substring(s.length() - 20000);
            out.putString("log", s);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MqttCore.get().addListener(this);
        boolean nowExec = P.isExecutor(this);
        if (nowExec != executor) {
            // 在设置页换了角色：断开旧连接，主题按新角色重置
            executor = nowExec;
            MqttCore.get().disconnect();
            stopService(new Intent(this, MqttService.class));
            append("[角色已切换] 现在是" + (executor ? "执行端" : "控制端"));
            collapseCmdPad();
        }
        if (checkOnResume && editLocal != null) {
            checkOnResume = false;
            ui.postDelayed(new Runnable() {
                @Override public void run() { checkEditSaved(); }
            }, 500);
        }
        boolean nowDebug = P.debug(this);
        if (nowDebug != debugMode) {
            debugMode = nowDebug;
            plain.setLength(0);
            rendered = "";
            tvResult.setText("");
            appendPlain(debugMode ? "(debug on)" : "");
            collapseCmdPad();
        }
        applyRoleUi();
        tvServer.setText(P.host(this) + ":" + P.port(this));
        applyWrap();
        // 从桌面/后台回来：高度可能被系统重置，补两次贴底
        scrollToBottom();
        svOut.postDelayed(this::scrollToBottom, 400);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MqttCore.get().removeListener(this);
        ui.removeCallbacks(ticker);
    }

    private void startMqttService(String action) {
        Intent i = new Intent(this, MqttService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private SharedPreferences uiPref() {
        return getSharedPreferences("ui", MODE_PRIVATE);
    }

    // ---------------- 发送 ----------------

    private void onSend(boolean collapse) {
        String topic = P.pubTopic(this).trim();
        String msg = etMsg.getText().toString();
        if (topic.isEmpty()) {
            toast("发布主题不能为空");
            return;
        }
        if (msg.trim().isEmpty()) {
            toast("消息不能为空");
            return;
        }
        // 命令就是命令：原样发出去，执行端整条当 shell 命令，不解析 JSON
        String out = msg.trim();
        if (out.startsWith("mqttup:") || out.startsWith("mqttup ")) {
            // mqttup:<控制端本机路径> = 把本机这个文件传到「执行端当前目录」，同名
            String local = out.substring(7).trim();
            P.addHist(this, out);
            histArr = null;
            histIdx = -1;
            etMsg.setText("");
            collapseCmdPad();
            pendingUpPath = "";
            if (P.rootUp(this) && !local.isEmpty()) {
                // 勾了 root：冒号后就是本机路径，直读，不弹选择器
                byte[] d = readLocalBytes(local);
                if (d != null) {
                    String nm = new File(local).getName();
                    if (nm.isEmpty()) nm = "upload.bin";
                    // remote 只给文件名 → 执行端按持久 shell 的当前目录落盘
                    sendUpload(nm, nm, d);
                    return;
                }
                appendPlain("[上传] 读不到本机文件 " + local + "（root 是否授权？），改用文件选择器");
            }
            pickUploadFile();
            return;
        }
        if (out.startsWith("mqttdown:")) out = "mqttdown " + out.substring(9).trim();
        boolean ok = MqttCore.get().publish(topic, out, P.qos(this));
        append((ok ? "→ " : "✗ ") + topic + "  " + oneLine(out));
        // 只要提交过就进历史（和 MyBleClient 一样，发没发出去都记），这样 ▲ 第一条永远是刚发的那条
        P.addHist(this, msg.trim());
        histArr = null;
        histIdx = -1;
        etMsg.setText("");
        refresh();
        if (collapse) collapseCmdPad();
    }

    // ---------------- 日志渲染 ----------------

    /** Debug 行：只有勾了 Debug 才显示（时间戳 + 收发/订阅/状态） */
    private void append(String line) {
        if (!debugMode) return;
        plain.append('[').append(hm.format(new Date())).append("] ").append(line).append('\n');
        trimAndRender();
    }

    /** 纯输出行：任何时候都显示，不带时间戳（命令输出、错误提示、崩溃信息） */
    private void appendPlain(String text) {
        if (text == null) return;
        String v = text.replaceAll("\\s+$", "");
        if (v.isEmpty()) return;
        plain.append(v).append('\n');
        trimAndRender();
    }

    private void trimAndRender() {
        if (plain.length() > MAX_CHARS) plain.delete(0, plain.length() - MAX_CHARS);
        scheduleRender();
    }

    private void scheduleRender() {
        if (renderScheduled) return;
        renderScheduled = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderScheduled = false;
                render();
            }
        }, 120);
    }

    /** 已经写进 TextView 的内容，用来做增量追加 */
    private String rendered = "";

    private void render() {
        if (tvResult == null) return;
        String cur = plain.toString();
        if (cur.startsWith(rendered)) {
            // 正常情况只把新增的那段 append 进去。
            // 原来每行都 setText 全量重建：整段文本重新测量 + selection 归零，
            // ScrollView 的滚动位置会被 clamp 回 0 —— 这就是「有时候跳回最顶部」的根源。
            String delta = cur.substring(rendered.length());
            if (!delta.isEmpty()) tvResult.append(delta);
        } else {
            // 清屏 / 超长截断 / 界面重建后，内容不再以旧内容开头，只能整段重建。
            // 整段 setText 会把滚动位置冲掉（这就是「跳回顶部」），所以重建后强制跟到底一次。
            if (debugMode && rendered.length() > 0) {
                LogFile.write("[渲染] 整段重建 " + rendered.length() + "字 → " + cur.length() + "字");
            }
            tvResult.setText(cur);
            hsvOut.scrollTo(0, 0);
        }
        rendered = cur;
        if (!wrap) applyUnwrappedWidth();
        scrollToBottom();
    }

    /**
     * 滚到内容最底部（照 MyBleClient ShellActivity 的 scrollToBottom）：
     * 用 scrollTo 而不是 fullScroll，避免把焦点交给可聚焦的日志 TextView；
     * svOut.post 让它在本帧布局之后执行，child.getHeight() 才是新内容的真实高度。
     */
    private void scrollToBottom() {
        if (svOut == null) return;
        svOut.post(new Runnable() {
            @Override public void run() {
                View child = svOut.getChildAt(0);
                if (child == null) return;
                int target = child.getHeight() - svOut.getHeight();
                svOut.scrollTo(0, Math.max(0, target));
            }
        });
    }

    /** 换行/不换行：换行时宽度=可视宽度；不换行时宽度=最长行，横向滚动查看 */
    private void applyWrap() {
        if (tvResult == null || hsvOut == null) return;
        hsvOut.post(new Runnable() {
            @Override
            public void run() {
                layoutForWrap();
            }
        });
    }

    private int wrapRetry;

    private void layoutForWrap() {
        int viewport = hsvOut.getWidth() - hsvOut.getPaddingLeft() - hsvOut.getPaddingRight();
        if (viewport <= 0) {
            if (wrapRetry < 8) {
                wrapRetry++;
                hsvOut.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        layoutForWrap();
                    }
                }, 60);
            }
            return;
        }
        wrapRetry = 0;
        int width = wrap ? viewport : Math.max(viewport, desiredContentWidth() + dip(10));
        tvResult.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        svOut.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
        tvResult.setHorizontallyScrolling(!wrap);
        tvResult.setSingleLine(false);
        tvResult.setMaxLines(Integer.MAX_VALUE);
        tvResult.requestLayout();
        svOut.requestLayout();
        hsvOut.scrollTo(0, 0);
        scrollToBottom();
    }

    private void applyUnwrappedWidth() {
        if (wrap || tvResult == null || hsvOut == null) return;
        int viewport = hsvOut.getWidth() - hsvOut.getPaddingLeft() - hsvOut.getPaddingRight();
        if (viewport <= 0) return;
        int width = Math.max(viewport, desiredContentWidth() + dip(10));
        ViewGroup.LayoutParams lp = tvResult.getLayoutParams();
        if (lp != null && lp.width == width) return;      // 宽度没变就别再 requestLayout
        tvResult.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        svOut.setLayoutParams(new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
        tvResult.requestLayout();
        svOut.requestLayout();
    }

    private int desiredContentWidth() {
        try {
            return (int) Math.ceil(Layout.getDesiredWidth(plain, tvResult.getPaint()));
        } catch (Throwable t) {
            return 0;
        }
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyFont() {
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        uiPref().edit().putInt("font_sp", fontSize).apply();
        applyWrap();
    }

    // ---------------- 命令面板 ----------------

    private void toggleCmdPad() {
        boolean open = llCmdpad.getVisibility() == View.VISIBLE;
        llCmdpad.setVisibility(open ? View.GONE : View.VISIBLE);
        btnQuick.setText(open ? "⌄CMD" : "⌃CMD");
        if (!open) refreshCmdPad();
    }

    /** 全屏阅读：只留日志区和输入行，上面的连接/状态/主题/工具按钮全收起来，日志窗口更大 */
    private boolean fullMode;

    private void toggleFull() {
        fullMode = !fullMode;
        int v = fullMode ? View.GONE : View.VISIBLE;
        View server = findViewById(R.id.ll_server);
        server.setVisibility(v);
        tvInfo.setVisibility(v);
        tvMonitor.setVisibility(v);
        cbWrap.setVisibility(v);
        btnFontDown.setVisibility(v);
        btnFontUp.setVisibility(v);
        btnCopy.setVisibility(v);
        btnClear.setVisibility(v);
        scrollToBottom();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // 面板展开时，只要按下的点不在面板内、也不在 ⌄CMD 按钮上，就记为“抬手后收起”
            padCollapsePending = llCmdpad != null && llCmdpad.getVisibility() == View.VISIBLE
                    && !hitInside(llCmdpad, ev) && !hitInside(btnQuick, ev);
        }
        boolean handled = super.dispatchTouchEvent(ev);
        if (padCollapsePending && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            // 等这次点击走完再收面板：面板是竖排流式布局，立刻 GONE 会让下面的控件上移，正在进行的点击可能落空
            padCollapsePending = false;
            ui.post(this::collapseCmdPad);
        }
        return handled;
    }

    private boolean hitInside(View v, MotionEvent ev) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        return v.getGlobalVisibleRect(padHitRect)
                && padHitRect.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    @Override
    public void onBackPressed() {
        if (fullMode) {          // 全屏状态按返回键先退出全屏，不退出 app
            toggleFull();
            return;
        }
        super.onBackPressed();
    }

    private void releasePadHighlight() {
        View ch = padTouchChild;
        padTouchChild = null;
        if (ch != null && ch.isAttachedToWindow()) ch.setBackgroundColor(0);
    }

    private void collapseCmdPad() {
        if (llCmdpad != null && llCmdpad.getVisibility() == View.VISIBLE) {
            llCmdpad.setVisibility(View.GONE);
            btnQuick.setText("⌄CMD");
        }
    }

    private void setTab(String tab) {
        curTab = tab;
        tabQuick.setBackgroundColor(tab.equals("quick") ? 0xFFFFFFFF : 0xFFE0E0E0);
        tabHist.setBackgroundColor(tab.equals("hist") ? 0xFFFFFFFF : 0xFFE0E0E0);
        tabQuick.setTextColor(tab.equals("quick") ? 0xFF000000 : 0xFF888888);
        tabHist.setTextColor(tab.equals("hist") ? 0xFF000000 : 0xFF888888);
        refreshCmdPad();
    }

    private void refreshCmdPad() {
        if (lvCmdpad == null) return;
        String[] items = curTab.equals("quick") ? P.quickCmds(this) : P.histCmds(this);
        if (padAdapter == null) {
            padAdapter = new ArrayAdapter<String>(this, R.layout.cmdpad_item, new ArrayList<String>());
            lvCmdpad.setAdapter(padAdapter);
        }
        padAdapter.clear();
        if (items.length == 0) {
            padAdapter.add(curTab.equals("quick")
                    ? "(Quick commands empty; edit them in Settings)"
                    : "(No command sent yet)");
        } else {
            for (String it : items) padAdapter.add(it);
        }
        padAdapter.notifyDataSetChanged();
    }

    // ---------------- 历史导航 ----------------

    private void histPrev() {
        if (histArr == null || histArr.length == 0) histArr = P.histCmds(this);
        if (histArr.length == 0) {
            toast("暂无历史");
            return;
        }
        String cur = etMsg.getText().toString();
        if (histIdx < 0 || histIdx >= histArr.length || !histArr[histIdx].equals(cur)) {
            // 框里的内容跟游标对不上：刚发过命令、手改过、或点过 CMD 条目 —— 一律从头开始，▲ 给最近一条
            if (histIdx < 0) histDraft = cur;
            histIdx = 0;
        } else if (histIdx < histArr.length - 1) {
            histIdx++;
        }
        fillInput(histArr[histIdx]);
    }

    private void histNext() {
        if (histIdx < 0) return;
        String cur = etMsg.getText().toString();
        if (histArr == null || histIdx >= histArr.length || !histArr[histIdx].equals(cur)) {
            // 用户把历史内容改成了别的，▼ 不再继续往旧走，回到自己的草稿
            histIdx = -1;
            fillInput(histDraft);
            return;
        }
        histIdx--;
        fillInput(histIdx < 0 ? histDraft : histArr[histIdx]);
    }

    private void fillInput(String s) {
        etMsg.setText(s);
        etMsg.setSelection(etMsg.getText().length());
    }

    // ---------------- 状态 ----------------

    private void refresh() {
        MqttCore core = MqttCore.get();
        // Server 行已经显示 host:port，状态里就不要重复了，只留「已连接 / 连接中… / 已断开…」
        String st = core.stateText();
        String hp = P.host(this) + ":" + P.port(this);
        st = st.replace(hp, "").replace("(TLS)", "").replaceAll("\\s+", " ").trim();
        if (st.endsWith("…")) st = st.replace(" ", "");
        boolean conn = core.isConnected();
        boolean busy = core.isConnecting() || st.startsWith("等待重连");

        String label = conn ? "Disconnect" : (busy ? "Cancel" : "Connect");
        if (!label.contentEquals(btnConnect.getText())) btnConnect.setText(label);   // 文本没变就别重设，省得每秒重排

        StringBuilder b = new StringBuilder(st).append(". ↑ ").append(core.txCount)
                .append(" ↓ ").append(core.rxCount).append("  订阅:");
        b.append(join(P.subTopics(this))).append(" pub:").append(P.pubTopic(this));
        String srv = P.host(this) + ":" + P.port(this);
        if (!srv.contentEquals(tvServer.getText())) tvServer.setText(srv);
        String txt = b.toString();
        if (!txt.contentEquals(tvInfo.getText())) tvInfo.setText(txt);               // 同上：内容没变就不动，避免每秒刷新干扰滚动
    }

    private static String oneLine(String s) {
        String v = s.replace("\n", " ⏎ ");
        return v.length() > 150 ? v.substring(0, 150) + "…" : v;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- MqttCore.Listener ----------------

    @Override
    public void onState(String text, boolean connected) {
        refresh();
    }


    @Override
    public void onMessage(String topic, String payload) {
        String p = payload == null ? "" : payload;
        if (p.contains("\"battery\"")) {
            // 执行端主动推的电量：只更新「已连接.」下面那一行
            try {
                JSONObject o = new JSONObject(p);
                JSONObject b = o.optJSONObject("battery");
                if (b != null) {
                    int lv = b.optInt("level", -1);
                    if (lv >= 0) {
                        tvMonitor.setText("bat:" + lv + "%" + (b.optBoolean("charging", false) ? "  ⚡" : ""));
                        tvMonitor.setVisibility(View.VISIBLE);
                    }
                    if (debugMode) append("← " + topic + "  [电池] " + lv + "%");
                }
            } catch (Exception ignore) {
            }
            return;
        }
        if (p.trim().startsWith("{")) {
            try {
                JSONObject o = new JSONObject(p);
                JSONObject fj = o.optJSONObject("file");
                if (fj != null) {
                    // 不在日志里打 base64，只显示摘要
                    append("← " + topic + "  [文件] " + fj.optString("path")
                            + "  " + Fmt.size(fj.optLong("size")));
                    String sub = o.optString("cmd", "");
                    if (sub.startsWith("mqttdown")) saveDownloaded(fj);
                    else handleEditFile(fj);
                    return;
                }
                append("← " + topic + "  " + oneLine(p));
                String out = o.optString("output", "");
                if (!out.isEmpty()) {
                    if (debugMode) {
                        // 纯命令发的没有 id，就不显示 id 段
                        String idPart = (o.has("id") && !o.isNull("id") && o.opt("id") != null)
                                ? " id=" + o.opt("id") : "";
                        append("── 执行结果" + idPart + "  code=" + o.optInt("code", 0)
                                + "  " + o.optInt("ms", 0) + "ms ──");
                        append(out);
                    } else {
                        // 不勾 Debug：输出前加一行时间标记，然后只显示命令输出
                        appendPlain("──── " + hm.format(new Date()) + " ────");
                        appendPlain(out);
                    }
                }
            } catch (Exception ignore) {
            }
        } else {
            append("← " + topic + "  " + oneLine(p));
            if (!debugMode) {
                appendPlain("──── " + hm.format(new Date()) + " ────");
                appendPlain(p);
            }
        }
        refresh();
    }

    @Override
    public void onPeer(boolean online, String id) {
        String who = executor ? "控制端" : "执行端";
        appendPlain("[" + who + "] " + (online ? "在线" : "已断开")
                + (id == null || id.isEmpty() ? "" : "  " + id));
        refresh();
    }

    @Override
    public void onLog(String line) {
        if (debugMode) {
            append(line);
            return;
        }
        // 不勾 Debug 时，只把失败/断线这类错误放出来，其余（订阅、连接细节）不显示
        String t = line == null ? "" : line.trim();
        if (t.contains("失败") || t.contains("断线") || t.contains("异常") || t.contains("超时")) {
            appendPlain(t);
        }
    }

    // ================= mqttup / mqttdown 传文件 =================

    /** 选一个本地文件，发给执行端的 pendingUpPath */
    private void pickUploadFile() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_UP);
        } catch (Throwable t) {
            toast("打不开文件选择器: " + t);
        }
    }

    private void doUpload(Uri uri) {
        try {
            byte[] data = readUri(uri);
            if (data == null) {
                toast("读取文件失败");
                return;
            }
            sendUpload(pendingUpPath, queryName(uri), data);
        } catch (Throwable t) {
            toast("上传失败: " + t);
        }
    }

    /** remote 为空时用文件名当执行端路径 */
    private void sendUpload(String remote, String name, byte[] data) {
        try {
            if (data.length > P.editMaxKb(this) * 1024L) {
                toast("文件太大，上限 " + P.editMaxKb(this) + "KB");
                return;
            }
            if (remote == null || remote.isEmpty()) remote = name;
            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            String cmd = "!put " + remote + " 644\n" + b64;
            boolean ok = MqttCore.get().publish(P.pubTopic(this), cmd, MqttCore.get().qosLevel());
            if (ok) {
                appendPlain("[上传] " + name + " → " + remote + "  " + Fmt.size(data.length));
            } else {
                appendPlain("[上传] 失败：未连接");
            }
        } catch (Throwable t) {
            toast("上传失败: " + t);
        }
    }

    /** 读本机文件：先自己读，读不到走 su（受 SELinux 限制的目录也能读） */
    private byte[] readLocalBytes(String path) {
        try {
            File f = new File(path);
            if (f.isFile() && f.canRead()) {
                FileInputStream fi = new FileInputStream(f);
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
                    return bo.toByteArray();
                } finally {
                    try { fi.close(); } catch (Exception ignore) { }
                }
            }
        } catch (Throwable ignore) {
        }
        String qp = path.replace("'", "'\\''");
        String[] sus = { Exec.su(), "/sbin/su", "/system/bin/su", "/system/xbin/su", "su" };
        for (String su : sus) {
            try {
                Process p = new ProcessBuilder(su, "-c", "cat '" + qp + "'").redirectErrorStream(false).start();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                java.io.InputStream in = p.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                try { in.close(); } catch (Exception ignore) { }
                int rc = p.waitFor();
                byte[] out = bo.toByteArray();
                if (rc == 0 && out.length > 0) return out;
            } catch (Throwable ignore) {
            }
        }
        return null;
    }

    /** mqttdown 收到的文件存到 App 外部目录 download/ */
    private void saveDownloaded(JSONObject fj) {
        try {
            String name = fj.optString("name", "");
            if (name.isEmpty()) name = new File(fj.optString("path")).getName();
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            byte[] data = Base64.decode(fj.optString("data", ""), Base64.NO_WRAP);
            String src = fj.optString("path");
            String shown = null;

            // 1) MediaStore 写内置存储 Download/mymqtt（Android 10+ 免存储权限）
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.net.Uri uri = null;
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                    cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/mymqtt");
                    uri = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri != null) {
                        java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os == null) throw new java.io.IOException("openOutputStream null");
                        os.write(data);
                        os.close();
                        shown = "Download/mymqtt/" + name;
                    }
                } catch (Throwable t) {
                    if (uri != null) {
                        try { getContentResolver().delete(uri, null, null); } catch (Throwable ignore) { }
                    }
                }
            }

            // 2) 直接写公共 Download/mymqtt（API 29 legacy 或已给权限）
            if (shown == null) {
                try {
                    File pub = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    File dir = new File(pub, "mymqtt");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    if (f.exists()) f = new File(dir, System.currentTimeMillis() + "_" + name);
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(data);
                    fo.close();
                    shown = f.getAbsolutePath();
                } catch (Throwable ignore) {
                }
            }

            // 3) 兜底：App 外部私有目录
            if (shown == null) {
                File dir = new File(getExternalFilesDir(null), "download");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                if (f.exists()) f = new File(dir, System.currentTimeMillis() + "_" + name);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();
                shown = f.getAbsolutePath();
            }

            appendPlain("[下载] " + src + " → " + shown + "  " + Fmt.size(data.length));
            toast("已保存: " + name);
        } catch (Throwable t) {
            appendPlain("[下载] 保存失败: " + t);
        }
    }

    private byte[] readUri(Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignore) { }
        }
    }

    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String v = c.getString(idx);
                            if (v != null && !v.isEmpty()) return v;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignore) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "upload.bin" : last;
    }

    // ================= vi / vim 编辑回传 =================

    /** 收到执行端带回的文件：存本地 edit/ 目录，再用外部编辑器打开 */
    private void handleEditFile(JSONObject fj) {
        try {
            String remote = fj.optString("path", "");
            String name = fj.optString("name", "");
            if (name.isEmpty()) name = new File(remote).getName();
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (name.isEmpty()) name = "edit.txt";
            byte[] data = Base64.decode(fj.optString("data", ""), Base64.NO_WRAP);
            File dir = new File(getFilesDir(), "edit");
            if (!dir.exists()) dir.mkdirs();
            File local = new File(dir, name);
            FileOutputStream fo = new FileOutputStream(local);
            fo.write(data);
            fo.close();
            editLocal = local.getAbsolutePath();
            editRemote = remote;
            editMode = fj.optString("mode", "644");
            editSha = fj.optString("sha", sha256(data));
            append("[vi] 本地副本 " + editLocal + "（" + Fmt.size(data.length) + "）");
            openEditor();
        } catch (Throwable t) {
            append("[vi] 保存本地文件失败: " + t);
            toast("保存失败: " + t);
        }
    }

    private void openEditor() {
        if (editLocal == null) return;
        checkOnResume = true;
        Uri uri = EditFileProvider.uriFor(new File(editLocal).getName());
        String pkg = P.editorPkg(this);
        String cls = P.editorCls(this);
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            Intent it = new Intent(Intent.ACTION_EDIT);
            it.setDataAndType(uri, "text/plain");
            it.addFlags(flags);
            if (!pkg.isEmpty() && !cls.isEmpty()) it.setComponent(new ComponentName(pkg, cls));
            startActivityForResult(it, REQ_EDIT);
            append("[vi] 打开编辑器: " + (pkg.isEmpty() ? "(系统选择框)" : pkg + "/" + cls));
        } catch (Throwable t) {
            try {
                Intent pick = new Intent(Intent.ACTION_EDIT);
                pick.setDataAndType(uri, "text/plain");
                pick.addFlags(flags);
                startActivityForResult(Intent.createChooser(pick, "选择编辑器"), REQ_EDIT);
            } catch (Throwable t2) {
                append("[vi] 打不开编辑器: " + t2 + "（设置里检查编辑器包名/类名）");
                toast("打不开编辑器：" + t2);
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_UP) {
            if (res == RESULT_OK && data != null && data.getData() != null) doUpload(data.getData());
            return;
        }
        if (req != REQ_EDIT) return;
        ui.postDelayed(new Runnable() {
            @Override public void run() { checkEditSaved(); }
        }, 500);
    }

    /** 比对 sha256：内容变了才回写执行端，避免白白刷流量 */
    private void checkEditSaved() {
        if (editLocal == null || editSha == null) return;
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - lastCheckMs < 800) return;   // onActivityResult 和 onResume 会各来一次，去抖
        lastCheckMs = nowMs;
        try {
            File f = new File(editLocal);
            if (!f.exists()) return;
            byte[] data = readAll(f);
            String now = sha256(data);
            if (now.equalsIgnoreCase(editSha)) {
                LogFile.write("[vi] 内容未改动，跳过回写");   // 只进日志文件，不刷界面
                return;
            }
            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            String cmd = "!put " + editRemote + " " + editMode + "\n" + b64;
            boolean ok = MqttCore.get().publish(P.pubTopic(this), cmd, MqttCore.get().qosLevel());
            if (ok) {
                editSha = now;
                append("[vi] 已回写 " + editRemote + "（" + Fmt.size(data.length) + "）");
                appendPlain("[vi] 已回写 " + editRemote + "（" + Fmt.size(data.length) + "）");
                toast("已回写 " + editRemote);
            } else {
                appendPlain("[vi] 回写失败：未连接");
                toast("回写失败：未连接");
            }
        } catch (Throwable t) {
            append("[vi] 读取编辑文件失败: " + t);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignore) { }
        }
    }

    private static String sha256(byte[] d) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(d);
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
