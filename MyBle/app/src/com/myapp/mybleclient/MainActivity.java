package com.myapp.mybleclient;

import android.app.Activity;
import android.app.ActionBar;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import java.util.ArrayList;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * MyBleClient 命令客户端 —— 界面层。
 *
 * 职责只剩三件事：输入（广播名/密钥/命令）、权限申请、把服务推来的状态画出来。
 * 整条 BLE 连接住在 BleService（前台服务）里：
 *   - 切到别的 app / 回桌面：界面只是 onStop，服务继续跑，通知常驻；
 *   - 从通知栏点回来：singleTop 直接回到本实例，文字原样还在；
 *   - Task 被回收后从通知重建：onCreate 重新 bind，setUi() 整包回放
 *     （状态行/电量信号/按钮/日志），文字立即恢复，绝不会重新发起第二条连接；
 *   - Activity 的 onDestroy 不再碰连接，也就不存在"重复connect"问题。
 */
public class MainActivity extends Activity {

    EditText etServer, etKey, etCmd;
    boolean longPressCleared = false;   // 长按2秒清空输入框后置位，松手时不触发发送
    TextView tvInfo, tvBatt, tvResult;
    ScrollView svResult;
    LinearLayout llCmdpad;
    View cmdRow;   // 底部命令输入行（et_cmd 所在），与 ll_cmdpad 同属"命令面板区域"
    ListView lvCmdpad;
    Button tabQuick, tabHist, btnQuick;
    Button btnFiles;
    boolean uploading = false;        // Files 按钮当前是否处于 Cancel（上传中）状态
    String curPath = ".";
    ArrayList<String> fileRows = new ArrayList<String>();   // 每行 "d 0 name" / "f size name"
    ArrayAdapter<String> fileAdapter;
    String curTab = "quick";
    ArrayAdapter<String> padAdapter;
    Button btnConnect, btnDisconnect;

    BleService svc;                 // 绑定后拿到引擎；为 null 时动作先暂存
    String pendingConnect;          // 服务还没绑上就点了 Connect 时的暂存

    // ------------------------------------------------------------- 服务绑定

    final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            svc = ((BleService.LocalBinder) b).getService();
            svc.setUi(uiCb);        // 重建后重绑：整包回放当前状态，文字即时恢复
            maybeAutoConnect();
            if (pendingConnect != null) {
                String s = pendingConnect;
                pendingConnect = null;
                svc.connect(s);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            svc = null;             // 只在服务被杀时发生；重连后 binder 会再回调
        }
    };

    /** 服务 → 界面的状态订阅。回调都来自服务主线程，直接操作视图即可 */
    final BleService.Ui uiCb = new BleService.Ui() {
        @Override public void onInfo(String s)      { if (alive()) tvInfo.setText(s); }
        @Override public void onBatt(String s)      { if (alive()) tvBatt.setText(s); }
        @Override public void onLog(String s)       { if (alive()) { tvResult.append(s); scrollDown(); } }
        @Override public void onLogReset(String s)  { if (alive()) { tvResult.setText(s); scrollDown(); } }
        @Override public void onBtn(String t, boolean en) {
            if (alive()) {
                btnConnect.setText(t);
                btnConnect.setEnabled(en);
                // 双保险：断开按钮状态直接跟着 onBtn 走，不依赖新增回调链路
                syncDisconnectBtn(!en && "Connected".equals(t));
            }
        }
        @Override public void onDisconnectBtn(boolean en) {
            if (alive()) syncDisconnectBtn(en);
        }
        @Override public void onToast(String s) {
            if (alive()) Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
        }
        @Override public void onProgress(String line) {
            if (alive()) tvInfo.setText(line);
        }
        @Override public void onUploadState(boolean uploading) {
            if (!alive()) return;
            MainActivity.this.uploading = uploading;
            // 普通按钮（与 Run 同款），仅切换文字：上传中显示 Cancel，空闲显示 Files
            btnFiles.setText(uploading ? "Cancel" : "Files");
        }
        @Override public void onFileList(java.util.ArrayList<String> items) {
            if (!alive()) return;
            fileRows.clear();
            if (items != null) fileRows.addAll(items);
            if (fileAdapter != null) fileAdapter.notifyDataSetChanged();
        }
    };

    boolean alive() { return !isFinishing() && !isDestroyed(); }

    /** 断开按钮：只在已连接时可用；禁用时文字直接改「未连接」，状态肉眼可确认 */
    void syncDisconnectBtn(boolean on) {
        btnDisconnect.setEnabled(on);
        btnDisconnect.setText(on ? "Disconnect" : "Not connected");
    }

    void scrollDown() {
        svResult.post(new Runnable() {
            @Override public void run() { svResult.fullScroll(View.FOCUS_DOWN); }
        });
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // 标题栏左上角放 App 图标（ActionBar logo，显示在标题文字左侧）
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayUseLogoEnabled(true);
            ab.setDisplayShowHomeEnabled(true);
            ab.setLogo(R.mipmap.ic_launcher);
        }

        etServer = (EditText) findViewById(R.id.et_server);
        etKey    = (EditText) findViewById(R.id.et_key);
        etCmd    = (EditText) findViewById(R.id.et_cmd);
        tvInfo   = (TextView) findViewById(R.id.tv_info);
        tvBatt   = (TextView) findViewById(R.id.tv_batt);
        tvResult = (TextView) findViewById(R.id.tv_result);
        svResult = (ScrollView) findViewById(R.id.sv_result);
        btnConnect = (Button) findViewById(R.id.btn_connect);
        btnDisconnect = (Button) findViewById(R.id.btn_disconnect);
        btnDisconnect.setEnabled(false);   // 初始未连接：断开按钮先禁用，等连接成功再启用
        btnDisconnect.setText("Not connected");    // 文字直接标明状态，便于确认禁用是否生效

        // 输入来自持久化设置；APK 内不内置任何密钥
        etServer.setText(P.server(this));
        etKey.setText(P.psk(this));

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });
        findViewById(R.id.btn_disconnect).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { userRelease(); }
        });
        findViewById(R.id.btn_run).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onRunClicked(); }
        });
        llCmdpad = (LinearLayout) findViewById(R.id.ll_cmdpad);
        cmdRow = findViewById(R.id.ll_cmdrow);
        lvCmdpad = (ListView) findViewById(R.id.lv_cmdpad);
        tabQuick = (Button) findViewById(R.id.tab_quick);
        tabHist  = (Button) findViewById(R.id.tab_hist);
        btnQuick = (Button) findViewById(R.id.btn_quick);
        btnQuick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (longPressCleared) { longPressCleared = false; return; }  // 长按2秒清空后松手，不展开/收起面板
                toggleCmdPad();
            }
        });
        // 长按 2 秒清空命令输入框（不干扰短按展开/收起面板）：按下起 2s 内没松手才清空
        final android.os.Handler lpHandler = new android.os.Handler();
        final Runnable lpClear = new Runnable() {
            @Override public void run() {
                etCmd.setText("");
                longPressCleared = true;
                toast("Command cleared");
            }
        };
        btnQuick.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        longPressCleared = false;
                        lpHandler.postDelayed(lpClear, 2000);
                        return false;   // 不消费事件，短按仍走 onClick 展开/收起面板
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        lpHandler.removeCallbacks(lpClear);  // 没满 2 秒松手 → 取消清空
                        return false;
                }
                return false;
            }
        });
        tabQuick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab("quick"); }
        });
        tabHist.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab("hist"); }
        });
        lvCmdpad.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String c = (String) parent.getItemAtPosition(position);
                if (c != null && c.length() > 0 && !c.startsWith("(")) {   // 跳过占位提示行
                    etCmd.setText(c);
                    etCmd.setSelection(c.length());
                    onRunClicked();
                    collapseCmdPad();   // 发送后收起面板，露出结果区
                }
            }
        });
        setTab("quick");
        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyResult(); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tvResult.setText("");
                if (svc != null) svc.clearLog();   // 缓冲不清，重建后又被回放出来
            }
        });

        // ---- 文件（v3）：Files 按钮直接打开安卓文件选择器（上传） ----
        // 上传进行中该按钮变为带边框的 Cancel：点它取消上传，结束后自动还原回 Files
        btnFiles = (Button) findViewById(R.id.btn_files);
        btnFiles.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (uploading) {           // 上传进行中 → 取消上传
                    if (svc != null) svc.cancelUpload();
                    return;
                }
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);   // 允许多选
                try { startActivityForResult(pick, 1); } catch (Throwable t) { toast("无法打开文件选择器"); }
            }
        });

        // ---- SH 按钮：进入 bash 风格的新 Activity（ShellActivity） ----
        findViewById(R.id.btn_shell).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, com.myapp.mybleclient.ShellActivity.class));
            }
        });

        // bind 即创建服务（空闲时不挂前台通知）。界面怎么重建都不影响引擎，
        // 引擎只在用户点断开或系统杀进程时才停。
        bindService(new Intent(this, BleService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);   // singleTop：从通知进来只复用，不新建
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从 ShellActivity 返回后重新认领 uiCb，恢复主界面日志/状态更新
        if (svc != null) svc.setUi(uiCb);
        maybeAutoConnect();  // 服务已就绪就直接触发；否则等 onServiceConnected 里补
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 关键：这里只解绑、注销回调，绝不关连接、绝不停服务。
        if (svc != null) svc.clearUi(uiCb);
        svc = null;
        try { unbindService(conn); } catch (Throwable ignored) { }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Settings");
        menu.add(0, 2, 1, "Disconnect & Release");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, com.myapp.mybleclient.Settings.class));
            return true;
        }
        if (item.getItemId() == 2) {
            userRelease();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 动作

    void maybeAutoConnect() {
        if (svc != null && P.autoConnect(this)) svc.ensureAutoConnect();
    }

    void onConnectClicked() {
        String want = etServer.getText().toString().trim();
        if (want.length() == 0) { toast("Please enter the advertised name"); return; }
        P.saveServerPsk(this, want, etKey.getText().toString().trim());
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = (bm == null) ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { info("Bluetooth is off; please turn it on first"); return; }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            info("Location permission requested (required for BLE scan); tap Connect again after granting");
            return;
        }
        if (svc != null) svc.connect(want);
        else pendingConnect = want;   // 服务还没绑上（极少见）：先暂存
    }

    void onRunClicked() {
        String c = etCmd.getText().toString().trim();
        if (c.length() == 0) { toast("Command is empty"); return; }
        if (svc == null) { toast("Engine not ready; try again later"); return; }
        svc.run(c);                   // 就绪门槛由服务把关，拒绝原因以 toast 回来
        P.pushHist(this, c);          // 记录到发送历史
        if (llCmdpad.getVisibility() == View.VISIBLE && curTab.equals("hist")) refreshCmdPad();
    }

    // ---------------------------------------------------------- 命令面板（双 Tab）
    /** 展开/收起命令面板（默认收起，避免挤压日志区） */
    void toggleCmdPad() {
        boolean open = llCmdpad.getVisibility() == View.VISIBLE;
        llCmdpad.setVisibility(open ? View.GONE : View.VISIBLE);
        btnQuick.setText(open ? "⌄Cmd" : "⌃Cmd");
        if (!open) refreshCmdPad();
    }

    /** 收起命令面板（点条目发送后调用），复位开关按钮文字 */
    void collapseCmdPad() {
        if (llCmdpad.getVisibility() == View.VISIBLE) {
            llCmdpad.setVisibility(View.GONE);
            btnQuick.setText("⌄Cmd");
        }
    }

    // 面板区域外任意点击 → 收起。挂在 Activity 的 dispatchTouchEvent（所有触摸的入口），
    // 这样即使触摸落在结果区 ScrollView/TextView（会消费事件）也能捕获，挂在父布局
    // OnTouchListener 上则会被子 View 消费而触发不到。
    private final android.graphics.Rect mPadRect = new android.graphics.Rect();
    private final android.graphics.Rect mRowRect = new android.graphics.Rect();
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN
                && llCmdpad.getVisibility() == View.VISIBLE) {
            llCmdpad.getGlobalVisibleRect(mPadRect);
            cmdRow.getGlobalVisibleRect(mRowRect);
            int x = (int) ev.getRawX(), y = (int) ev.getRawY();
            if (!mPadRect.contains(x, y) && !mRowRect.contains(x, y)) {
                collapseCmdPad();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 切换 Tab：左=快捷命令（设置维护），右=历史记录（自动记录已发送） */
    void setTab(String tab) {
        curTab = tab;
        // 背景：选中=白色(0xFFFFFFFF)，未选中=灰色(0xFFE0E0E0)
        tabQuick.setBackgroundColor(tab.equals("quick") ? 0xFFFFFFFF : 0xFFE0E0E0);
        tabHist.setBackgroundColor(tab.equals("hist") ? 0xFFFFFFFF : 0xFFE0E0E0);
        // 文字：选中=黑色，未选中=灰色
        tabQuick.setTextColor(tab.equals("quick") ? 0xFF000000 : 0xFF888888);
        tabHist.setTextColor(tab.equals("hist") ? 0xFF000000 : 0xFF888888);
        refreshCmdPad();
    }

    /** 按当前 Tab 刷新列表内容 */
    void refreshCmdPad() {
        String[] items = curTab.equals("quick") ? P.quickCmds(this) : P.histCmds(this);
        if (padAdapter == null) {
            padAdapter = new ArrayAdapter<String>(this, com.myapp.mybleclient.R.layout.cmdpad_item, new java.util.ArrayList<String>());
            lvCmdpad.setAdapter(padAdapter);
        }
        padAdapter.clear();
        if (items.length == 0) {
            padAdapter.add(curTab.equals("quick") ? "(Quick commands empty; add them in Settings -> Quick Commands)" : "(No commands sent yet)");
        } else {
            for (String s : items) padAdapter.add(s);
        }
        padAdapter.notifyDataSetChanged();
    }

    /** 手动断开并释放：关链路、撤通知、停服务（与 Activity 生命周期无关） */
    void userRelease() {
        if (svc != null) svc.userRelease();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            info("Location permission granted; tap Connect once more");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null && svc != null) {
            java.util.ArrayList<Uri> list = new java.util.ArrayList<Uri>();
            ClipData cd = data.getClipData();          // 多选：结果在 ClipData
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) list.add(u);
                }
            } else {
                Uri uri = data.getData();              // 单选：结果在 getData
                if (uri != null) list.add(uri);
            }
            if (!list.isEmpty()) svc.uploadUris(list);
        }
    }

    // ---------------------------------------------------------------- 显示

    void info(final String s) {
        tvInfo.setText(s);   // 只在主线程被界面自己的流程调用
    }

    void copyResult() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("blecmd", tvResult.getText().toString()));
        toast("Copied");
    }

    void toast(final String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
