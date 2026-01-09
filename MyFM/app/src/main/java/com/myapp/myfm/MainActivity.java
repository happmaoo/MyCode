package com.myapp.myfm;

import android.Manifest;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast; // 方便提示用户
import android.widget.ToggleButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.flexbox.FlexboxLayout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private BroadcastReceiver responseReceiver;

    private static final int PERMISSION_REQUEST_CODE = 10;

    private FMService fmService;
    private boolean isBound = false;

    private FMStateManager stateManager;

    // 从 主界面和通知栏 发送服务启动停止命令
    public static final String ACTION_SERVICE_CMD = "com.myapp.myfm.SERVICE_CMD";

    // FileEdit 界面返回参数
    private static final int FILE_EDIT_REQUEST_CODE = 1001;


    TextView tvFreq;
    TextView tvInfo,tvName;
    Button btnPower,btnPre,btnNext,btnTuneDown,btnTuneUp,btnEdit,btnScan,btnMenu;
    ToggleButton btnToggleDisp;
    FlexboxLayout flexboxLayoutButtons;
    Button selectedButton = null;
    LinearLayout rssi_meter_wrap;
    View rssi_meter;

    String freq;
    String color1="#bffac1";



    MyFmApp myapp;
    MyFmApp application;
    List<RadioStation> stations;
    String plistfile = "myfm.txt";//电台列表名


    LinearLayout vol_meter_wrap;
    View vol_meter;
    int lastLevel = -1;




    // ------------------------- 广播接收器 ---------------------------
    private final BroadcastReceiver fmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            String msg = intent.getStringExtra(FMService.EXTRA_MESSAGE);
            if (msg == null) msg = "";

            if (FMService.ACTION_LOG_UPDATE.equals(intent.getAction())) {
                handleLogMessage(msg);
                Log.d("ACTION_LOG_UPDATE", msg);
            } else if (FMService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                handleStatusMessage(msg);
                Log.d("ACTION_STATUS_UPDATE", msg);
            }
        }
    };

    private void handleLogMessage(String message) {


        tvInfo.setText(message);

        // RSSI 处理
        Matcher matcher_RSSI = Pattern.compile("RSSI:(\\d+)").matcher(message);
        if (matcher_RSSI.find()) {
            try {
                int rssi = Integer.parseInt(matcher_RSSI.group(1));
                updateRSSIView(rssi_meter_wrap, rssi_meter, rssi);
            } catch (NumberFormatException e) {
                Log.e("MainActivity", "RSSI format error");
            }
        }

        // 频率处理
        Matcher matcher_FREQ = Pattern.compile("FREQ:(\\d+\\.?\\d*)").matcher(message);
        if (matcher_FREQ.find()) {
            String freqStr = matcher_FREQ.group(1);
            tvFreq.setText(freqStr);
            myapp.setString("freq", freqStr);

            String pname = RadioStation.findNameByNumber(stations, freqStr);
            if (pname == null) { pname = ""; }
            Log.i("pname", String.valueOf(stations));
            Log.i("pname", freqStr+pname);
            tvName.setText(pname);
            setCurrButtonStyle(freqStr);
        }




        // 扫描结果处理
        Matcher matcher_SCAN = Pattern.compile("SCANED:(.*)").matcher(message);
        if (matcher_SCAN.find()) {
            String scanlist = matcher_SCAN.group(1);
            btnScan.setEnabled(true);
            if (scanlist.trim().isEmpty()) {Toast.makeText(this, String.format("没有找到电台."), Toast.LENGTH_LONG).show();return;}

            String[] parts = scanlist.split(",");
            Toast.makeText(this, String.format("找到 %d 个电台.已添加到临时列表.", parts.length), Toast.LENGTH_LONG).show();
            plistfile = "myfm-temp.txt";
            reloadRadioStations();
            addScanList(scanlist);
            myapp.setString("plistfile",plistfile);
        }
    }

    // 接收服务发送的状态
    private void handleStatusMessage(String status) {
        switch (status) {
            case "PLAY":
                stateManager.setState(FMState.PLAY);
                break;
            case "PAUSE":
                stateManager.setState(FMState.PAUSE);
                break;
            case "ERROR":
                stateManager.setState(FMState.ERROR);
                break;
        }
    }

    // --- Service 连接回调 (核心逻辑修改) ---
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            FMService.LocalBinder binder = (FMService.LocalBinder) service;
            fmService = binder.getService();
            isBound = true;

            // 获取服务的真实状态
            String serviceState = fmService.getCurrentState();
            if (serviceState != null) {
                try {
                    FMState actualState = FMState.valueOf(serviceState);
                    stateManager.setState(actualState);  // 同步服务状态
                } catch (Exception e) {
                    //stateManager.setState(FMState.PAUSE);  // 默认
                }
            } else {
                //stateManager.setState(FMState.PAUSE);
            }

            // 如果是播放状态，恢复数据推送
            if (stateManager.getCurrentState() == FMState.PLAY) {
                fmService.sendFmCommand("PUSH 1");
            }

            Log.d("MainActivity", "Service connected. State: " + stateManager.getCurrentState());
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.w("MainActivity", "Service disconnected unexpectedly");
            isBound = false;
            fmService = null;
            stateManager.setState(FMState.ERROR);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFreq = findViewById(R.id.tvFreq);
        tvInfo = findViewById(R.id.tvInfo);
        tvName = findViewById(R.id.tvName);
        btnPower = findViewById(R.id.btnPower);
        btnScan = findViewById(R.id.btnScan);
        //btnToggleInfo = findViewById(R.id.btnToggleInfo);
        btnTuneDown = findViewById(R.id.btnTuneDown);
        btnTuneUp = findViewById(R.id.btnTuneUp);
        btnPre = findViewById(R.id.btnPre);
        btnNext = findViewById(R.id.btnNext);
        btnMenu = findViewById(R.id.btnMenu);
        flexboxLayoutButtons = findViewById(R.id.flexboxLayoutButtons);
        rssi_meter_wrap = findViewById(R.id.rssi_meter_wrap);
        rssi_meter = findViewById(R.id.rssi_meter);
        vol_meter_wrap = findViewById(R.id.vol_meter_wrap);
        vol_meter = findViewById(R.id.vol_meter);
        btnToggleDisp = findViewById(R.id.btnToggleDisp);


        // SharedPreferences 全局保存
        myapp = (MyFmApp) getApplicationContext();
        stateManager = new FMStateManager(this, myapp);

        application = (MyFmApp) getApplication();
        stations = application.getRadioStations();

        plistfile = myapp.getString("plistfile","");

        // 检查并请求权限
        checkAndRequestPermissions();


        //首次启动显示上次频率
        handleLogMessage("FREQ:"+myapp.getString("freq",""));

        // --- 设置按钮点击事件 ---

        btnPower.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                toggleFmService();

            }
        });



        //-----------Button TuneDown----------------
        btnTuneDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 将频率转换为整数（乘以10处理小数）
                int freqInt = (int)(Float.parseFloat(freq) * 10);
                freqInt -= 1; // +0.1
                freq = String.format("%.1f", freqInt / 10.0f);
                myapp.setString("freq", freq);
                tvFreq.setText(freq);
                fmService.sendFmCommand("TUNE " + freq);
                //Toast.makeText(MainActivity.this, String.valueOf(freq), Toast.LENGTH_SHORT).show();
            }
        });

        //-----------Button TuneUp----------------
        btnTuneUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 将频率转换为整数（乘以10处理小数）
                int freqInt = (int)(Float.parseFloat(freq) * 10);
                freqInt += 1; // +0.1
                freq = String.format("%.1f", freqInt / 10.0f);
                myapp.setString("freq", freq);
                tvFreq.setText(freq);
                fmService.sendFmCommand("TUNE " + freq);
            }
        });

        //-----------Button Pre----------------
        btnPre.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fmService.sendFmCommand("SEEK 0");
            }
        });

        //-----------Button Next----------------
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                fmService.sendFmCommand("SEEK 1");
                tvInfo.setText("SEEKing...");
            }
        });

        //-----------Button Scan----------------
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                fmService.sendFmCommand("SCAN");
                tvInfo.setText("SCANING...");
                btnScan.setEnabled(false);
            }
        });


        //-----------Button ToggleDisp----------------
        btnToggleDisp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                keepDispOn(true);
            } else {
                keepDispOn(false);
            }
        });

        if(myapp.keepDispOn){btnToggleDisp.setChecked(true);keepDispOn(true);}


        //-----------Button Menu----------------
        btnMenu.setOnClickListener(view -> {
            PopupMenu popup = new PopupMenu(this, view);
            popup.getMenuInflater().inflate(R.menu.menu, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();

                if (id == R.id.menu_settings) {
                    Toast.makeText(MainActivity.this, "暂时没有用...", Toast.LENGTH_SHORT).show();
                    // startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    return true;

                } else if (id == R.id.menu_about) {
                    //Toast.makeText(MainActivity.this, "点击了关于", Toast.LENGTH_SHORT).show();
                    showAboutDialog();
                    return true;
                } else if (id == R.id.menu_plist1) {
                    plistfile = "myfm.txt";
                    reloadRadioStations();
                    myapp.setString("plistfile",plistfile);
                    return true;
                } else if (id == R.id.menu_plist2) {
                    plistfile = "myfm-temp.txt";
                    reloadRadioStations();
                    myapp.setString("plistfile",plistfile);
                    return true;
                }else if (id == R.id.menu_editlist) {
                    Intent EditIntent = new Intent(MainActivity.this, FileEdit.class);
                    startActivityForResult(EditIntent, FILE_EDIT_REQUEST_CODE);
                    return true;
                }


                return false;
            });
            popup.show();
        });





        FMState currentState = stateManager.getCurrentState();
        if (currentState == FMState.STOPPED) {

        stateManager.setState(FMState.CONNECTING);

            // 启动服务
            Intent intent = new Intent(this, FMService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

        }

        //checkAppUpdate();
    }




/*



FM APP生命周期方法概览

onCreate():StartService
onStart():RegisterReceiver,BindService
onStop():UnbindService,UnregisterReceiver
onDestroy():判断是否正在播放，如果没有就 stopService();

不必担心 startService 会导致播放器重启。Android 系统的机制是：如果 Service 已经在运行，调用 startService 只会发送一个新的指令到 onStartCommand，而不会重新执行 onCreate 里的初始化逻辑。
永远记得在 onStop 或 onDestroy 中调用 unbindService，否则 Activity 无法被回收。



            // 注册顺序：
            1. startService
            2. registerReceiver(receiver1);    // 第一个注册
            3. registerReceiver(receiver2);    // 第二个注册
            4. bindService(serviceConn);       // 绑定服务

            // 注销顺序（必须反向）：
            1. unbindService(serviceConn);     // 解绑服务
            2. unregisterReceiver(receiver2);  // 注销倒数第二个
            3. unregisterReceiver(receiver1);  // 注销第一个
            4. stopService







*/
    @Override
    protected void onStart() {
        super.onStart();


        // 1. 注册广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(FMService.ACTION_LOG_UPDATE);
        filter.addAction(FMService.ACTION_STATUS_UPDATE);
        registerReceiver(fmReceiver, filter);
        LocalBroadcastManager.getInstance(this).registerReceiver(volumeReceiver, new IntentFilter(FMService.ACTION_VOLUME_UPDATE));


        // 绑定服务
        Intent intent = new Intent(this, FMService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

        if (stateManager.getCurrentState() == FMState.PLAY) {
            //stateManager.setState(FMState.PAUSE);
        }

        // 因为需要再次更新UI，不然不会更新
        stateManager.setState(stateManager.getCurrentState());


    }

    @Override
    protected void onStop() {



        unbindService(connection);

        unregisterReceiver(fmReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(volumeReceiver);


        super.onStop();

    }

    @Override
    protected void onDestroy() {

        FMState currentState = stateManager.getCurrentState();
        if (currentState != FMState.PLAY) {
            stopFMService();
        }


        //最后调用 super.onDestroy()
        super.onDestroy();

    }

    @Override
    protected void onResume() {
        super.onResume();

    }

    @Override
    protected void onPause() {

        FMState currentState = stateManager.getCurrentState();
        if (currentState == FMState.PLAY) {
            fmService.sendFmCommand("PUSH 0");
        }
        super.onPause();

    }

    //----------------------------------------------------------------------------


    //---------rssi meter---------------
    void updateRSSIView(final LinearLayout progressContainer, final View progressView,
                                    int currentProgress) {

        // 确保在布局完成后执行，以获取正确的父容器宽度
        progressContainer.post(() -> {
            // 获取布局宽度
            int containerWidth = progressContainer.getWidth();
            if (containerWidth <= 0) return;

            // 计算所需宽度
            float progressFraction = currentProgress / 100.0f;
            float progressWidth = progressFraction * containerWidth;

            // 设置新的宽度
            progressView.getLayoutParams().width = (int) progressWidth;
            progressView.requestLayout();
        });
    }
    /**
     * 切换FM服务的启动和停止状态。
     */
    private void toggleFmService() {


        Intent Intent = new Intent(ACTION_SERVICE_CMD);
        Intent.setPackage(getPackageName());

        if (!stateManager.canOperate()) {
            Log.d("MainActivity", "Operation not allowed in current state: " + stateManager.getCurrentState());
            return;
        }

        FMState currentState = stateManager.getCurrentState();

        switch (currentState) {
            case PAUSE:
                stateManager.setState(FMState.CONNECTING);
                Intent.putExtra("cmd", "start");
                sendBroadcast(Intent);
                break;

            case PLAY:
                Intent.putExtra("cmd", "pause");
                sendBroadcast(Intent);
                break;

            default:
                Log.w("MainActivity", "Unhandled state in toggle: " + currentState);
        }
    }

    private void startFMService() {


    }


    private void stopFMService() {
        stateManager.setState(FMState.STOPPING);

        stopService(new Intent(this, FMService.class));
        stateManager.setState(FMState.STOPPED);
    }




    // --------- Volume Meter---------------
    void updateVolumeView(final LinearLayout volumeContainer,
                                  final View volumeView,
                                  int volumeLevel) {
        // 先算出一个最终值
        final int level;
        if (volumeLevel < 0) {
            level = 0;
        } else if (volumeLevel > 100) {
            level = 100;
        } else {
            level = volumeLevel;
        }
        volumeContainer.post(() -> {
            int containerHeight = volumeContainer.getHeight();
            if (containerHeight <= 0) return;

            float fraction = level / 100.0f;
            int height = (int) (containerHeight * fraction);

            volumeView.getLayoutParams().height = height;
            volumeView.requestLayout();
        });
    }


    // ----------------电台列表按钮 存储------------------------
    void loadAndSetupButtons() {

        // 1. 检查 Application 中是否已缓存数据
        if (application.areStationsLoaded()) {
            stations = application.getRadioStations();
            Log.d("MainActivity", "Using cached radio stations.");
        } else {
            // 2. 如果没有缓存，则从文件加载
            Log.d("MainActivity", "Loading radio stations from file.");
            String content = loadRadioStationsFromFile(plistfile);
            stations = parseFileContent(content);

            // 3. 将数据存储到 Application 中进行缓存
            application.setRadioStations(stations);
        }

        // 确保在重新加载时清空旧视图，防止重复添加
        flexboxLayoutButtons.removeAllViews();

        // 4. 使用加载/缓存的数据创建按钮
        createAndAddButtons(stations);
    }


    // ------------- 电台列表数据解析 ---------------
    private List<RadioStation> parseFileContent(String content) {
        List<RadioStation> stations = new ArrayList<>();
        Scanner scanner = new Scanner(content);

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            // 使用空格分割，最多分割成两部分（频率和名称的剩余部分）
            String[] parts = line.split("\\s+", 2);
            // 确保至少有频率部分
            if (parts.length >= 1) {
                String frequency = parts[0];
                String name = "?"; // 默认名称设置为问号
                // 检查是否有第二部分（电台名称）
                if (parts.length == 2) {
                    String stationName = parts[1].trim();
                    // 如果第二部分不为空（有实际的电台名称）
                    if (!stationName.isEmpty()) {
                        name = stationName;
                    }
                }
                // 只有当频率本身不为空时才创建电台
                if (!frequency.isEmpty()) {
                    stations.add(new RadioStation(frequency, name));
                }
            }
        }
        scanner.close();
        return stations;
    }

    // ------------- 动态创建电台列表按钮部分 ----------------
    private void createAndAddButtons(List<RadioStation> stations) {

        for (final RadioStation station : stations) {

            // ⭐ 每个按钮都创建独立的 LayoutParams
            com.google.android.flexbox.FlexboxLayout.LayoutParams lp =
                    new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                            FlexboxLayout.LayoutParams.WRAP_CONTENT,
                            FlexboxLayout.LayoutParams.WRAP_CONTENT
                    );
            lp.setMargins(0, 0, 5, 0);

            Button button = new Button(this);
            // 给按钮设置一个 Tag，存入频率数值，方便以后查找
            button.setTag(station.getNumber());
            button.setLayoutParams(lp);   // 放在创建 lp 之后

            // 为两行文字设置不同颜色
            String line1 = station.getNumber();
            String line2 = station.getName();
            int line2Color = Color.parseColor("#aaaaaa");
            if (line2.startsWith("新电台")) {
                line2Color = Color.parseColor("#bd2a2a");
            }
            String text = line1 + "\n" + line2;
            SpannableString spannable = new SpannableString(text);
            spannable.setSpan(
                    new ForegroundColorSpan(Color.parseColor("#555555")),
                    0,
                    line1.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            spannable.setSpan(
                    new ForegroundColorSpan(line2Color),
                    line1.length() + 1,   // +1 是 \n
                    text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            button.setText(spannable);
            //button.setText(station.getButtonText());
            //button.setTextColor(Color.parseColor("#000000"));
            //button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#eeeeee")));
            //button.setMinHeight(0);
            //button.setMinWidth(50);
            button.setPadding(0, 0, 0, 0);   // ⭐ 给一点 padding 更好看
            button.setGravity(Gravity.CENTER);


            // 设置点击事件：更新上方 TextView
            button.setOnClickListener(v -> {

                // 没有打开收音机直接返回
                if (stateManager.getCurrentState() != FMState.PLAY) {
                    return;
                }

                tvFreq.setText(station.getNumber());
                tvName.setText(station.getName());
                // 如果有按钮被选中，恢复其默认颜色
                if (selectedButton != null) {
                    selectedButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#eeeeee"))); // 恢复默认颜色
                }
                button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#C0FFB0")));
                selectedButton = button;
                //发送频率到 server
                String freqStr = station.getNumber();
                if (!freqStr.isEmpty()) {
                    try {
                            myapp.setString("freq",freqStr);
                            freq = freqStr;
                            fmService.sendFmCommand("TUNE "+freqStr);
                            myapp.running = true;
                            btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(color1)));

                    } catch (NumberFormatException e) {
                        Toast.makeText(MainActivity.this, "Invalid frequency format", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(MainActivity.this, "Please enter a frequency", Toast.LENGTH_SHORT).show();
                }
            });

            flexboxLayoutButtons.addView(button);
        }
    }

    private void addSingleButton(RadioStation station) {
        List<RadioStation> singleStationList = new ArrayList<>();
        singleStationList.add(station);
        createAndAddButtons(singleStationList);
    }




    // --------------获取 内置存储卡里的电台列表 ----------------
    private File getRadioStationsFile(String filename) {
        // 获取内置存储的根目录 (注意：这个方法在API 29+已被弃用，但在API 26仍可用)
        File rootDir = Environment.getExternalStorageDirectory();
        File myfmDir = new File(rootDir, "myapp");
        return new File(myfmDir, filename);
    }

    private String loadRadioStationsFromFile(String filename) {
        File radioFile = getRadioStationsFile(filename);
        StringBuilder text = new StringBuilder();

        if (!radioFile.exists()) {
            // 如果文件不存在，写入默认内容
            saveDefaultFile(radioFile);
        }

        //测试
        //saveDefaultFile(radioFile);

        // 2. 读取文件内容
        try (BufferedReader br = new BufferedReader(new FileReader(radioFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                text.append(line).append('\n');
            }
        } catch (IOException e) {
            e.printStackTrace();
            return ""; // 读取失败返回空字符串
        }
        return text.toString();
    }

    // 写入默认数据
    private void saveDefaultFile(File file) {
        String defaultContent =
                "88.6 \n" +
                        "88.9 ？\n" +
                        "89.8 浙江音乐\n" +
                        "90.6 衢江\n" +
                        "91.7 \n" +
                        "93.0 浙交通\n" +
                        "93.8 中国之声\n" +
                        "94.2 金华2\n" +
                        "95.4 龙游\n" +
                        "96.2 浙江经济\n" +
                        "97.5 衢州交通\n" +
                        "99.4 浙江\n" +
                        "102.2 金华对农\n" +
                        "103.0 旅游之声\n" +
                        "104.4 金华\n" +
                        "104.7 江山\n" +
                        "105.3 衢州\n" +
                        "107.2 浙江音乐\n" +
                        "107.6 中国之声\n";

        // 确保 /myfm 目录存在
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // 写入数据
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(defaultContent);
        } catch (IOException e) {
            e.printStackTrace();
            // 写入失败处理
        }
    }



    // 添加扫描到的电台到按钮
    private void addScanList(String list){
        String frequencies = list;
        String[] parts = frequencies.split(",");
        ArrayList<RadioStation> scannedStations = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            String freq = parts[i].trim();

            RadioStation station = new RadioStation(freq, "");
            scannedStations.add(station);
        }
        writeScanListToFile(scannedStations);
    }


    // 将扫描到的电台列表写入txt文件
    private void writeScanListToFile(ArrayList<RadioStation> stations) {
        if (stations == null || stations.isEmpty()) {
            return;
        }
        try {
            File file = getRadioStationsFile("myfm-temp.txt");
            FileWriter writer = new FileWriter(file, false);
            BufferedWriter bufferedWriter = new BufferedWriter(writer);

            for (RadioStation station : stations) {
                String line = station.getNumber() + " ";
                bufferedWriter.write(line);
                bufferedWriter.newLine();
            }
            bufferedWriter.close();
            writer.close();
            Log.e("ScanRadio", "写入文件成功.");
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("ScanRadio", "写入文件失败: " + e.getMessage());
        }
        reloadRadioStations();
    }


    private void checkAndRequestPermissions() {
        // 检查是否已获得必要权限
        boolean hasAudioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        boolean hasStoragePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        // 如果没有获得权限，则请求权限
        if (!hasAudioPermission || !hasStoragePermission) {
            // 请求音频和存储权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
            finish();
        } else {
            // 所有权限都已授予
            //Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            loadAndSetupButtons();
        }
    }



    // --------------volume meter Receiver----------------------
    private BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (stateManager.getCurrentState() != FMState.PLAY) {
                lastLevel = 0;
                updateVolumeView(vol_meter_wrap, vol_meter, 0);
                return;
            }

            if (FMService.ACTION_VOLUME_UPDATE.equals(intent.getAction())) {
                int level = intent.getIntExtra("volume_level", 0);

                // ⭐ 节流：变化太小就不刷新 UI
                if (lastLevel >= 0 && Math.abs(level - lastLevel) < 3) {
                    return;
                }

                lastLevel = level;
                updateVolumeView(vol_meter_wrap, vol_meter, level);
            }
        }
    };



    // FileEdit 电台编辑列表完成后 onActivityResult 方法接收返回数据
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_EDIT_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // 获取文件是否已修改的标识
                boolean fileChanged = data.getBooleanExtra("file_changed", false);
                if (fileChanged) {
                    // 文件已修改，重新加载按钮
                    reloadRadioStations();
                    Toast.makeText(this, "电台列表已更新", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // 新增重新加载电台列表的方法
    private void reloadRadioStations() {
        MyFmApp application = (MyFmApp) getApplication();
        List<RadioStation> stations;

        Log.d("MainActivity", "Loading radio stations from file.");
        String content = loadRadioStationsFromFile(plistfile);
        stations = parseFileContent(content);

        // 3. 将数据存储到 Application 中进行缓存
        application.setRadioStations(stations);
        loadAndSetupButtons(); // 重新加载
    }


    // 设置当前频率的按钮颜色
    private void setCurrButtonStyle(String freq) {
        if (selectedButton != null) {
            selectedButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#eeeeee")));
        }
        Button targetBtn = flexboxLayoutButtons.findViewWithTag(freq);
        if (targetBtn != null) {
            targetBtn.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#C0FFB0")));
            selectedButton = targetBtn;
        }
    }




    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    private void showAboutDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("关于");

        // 创建 WebView
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);

        String template = getString(R.string.about_html);
        String htmlContent = String.format(template, BuildConfig.VERSION_NAME);

        webView.loadData(htmlContent, "text/html; charset=UTF-8", null);

        builder.setView(webView);
        builder.setPositiveButton("关闭", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();

        builder.setView(webView);
    }

    private void keepDispOn(Boolean disp) {
        if (disp) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            myapp.keepDispOn = true;
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            myapp.keepDispOn = false;

        }
    }



}
