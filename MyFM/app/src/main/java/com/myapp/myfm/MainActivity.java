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
import android.os.IBinder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast; // 方便提示用户
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.flexbox.FlexboxLayout;

import java.io.BufferedReader;
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

    private static final int PERMISSION_REQUEST_CODE = 10;

    private FMService fmService;
    private boolean isBound = false;

    // 新增标记：用于判断是否需要在绑定成功后立即连接 Socket
    // 只有点击“Connect”按钮启动服务时，才置为 true
    private boolean shouldConnectOnBind = false;
    private boolean isFmServiceRunning = false;

    private TextView tvFreq;
    private TextView tvInfo;
    private Button btnPower,btnPre,btnNext,btnTuneDown,btnTuneUp,btnEdit;
    private FlexboxLayout flexboxLayoutButtons;
    Button selectedButton = null;
    private LinearLayout rssi_meter_wrap;
    private View rssi_meter;

    private String freq;
    private String color1="#bffac1";

    private VolumeMeterView volumeMeterView;
    private volatile boolean isListenerActive = true;


    MyFmApp myapp;
    private boolean isAppInForeground = true;



    // ------------------------- 广播接收器 ---------------------------
    private final BroadcastReceiver fmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            String msg = intent.getStringExtra(FMService.EXTRA_MESSAGE);
            if (msg == null) msg = "";

            if (FMService.ACTION_LOG_UPDATE.equals(intent.getAction())) {

                // 获取 live update 数据
                tvInfo.setText(msg);

                Pattern pattern = Pattern.compile("RSSI:(\\d+)");
                Matcher matcher = pattern.matcher(msg);

                if (matcher.find()) {
                    try {
                        String rssiStr = matcher.group(1);
                        int rssi = Integer.parseInt(rssiStr);
                        //Log.d("---------------", String.valueOf(rssi));
                        updateRSSIView(rssi_meter_wrap, rssi_meter, rssi);
                    } catch (NumberFormatException e) {
                        //Log.e(TAG, "RSSI格式错误: " + message);
                    }
                }



                Pattern pattern2 = Pattern.compile("FREQ:(\\d+\\.?\\d*)");
                Matcher matcher2 = pattern2.matcher(msg);

                if (matcher2.find()) {
                    try {
                        String freqStr = matcher2.group(1);
                        tvFreq.setText(freqStr);
                    } catch (NumberFormatException e) {
                        //Log.e(TAG, "RSSI格式错误: " + message);
                    }
                }





            } else if (FMService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                //statusTextView.setText("状态: " + msg);
            }
        }
    };

    // --- Service 连接回调 (核心逻辑修改) ---
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            FMService.LocalBinder binder = (FMService.LocalBinder) service;
            fmService = binder.getService();
            isBound = true;
            //statusTextView.setText("状态: 服务已绑定");

            // 如果是点击“连接”按钮进来的，绑定成功后立即建立 Socket 连接
            if (shouldConnectOnBind) {
                if (fmService != null) {
                    fmService.connectFm();
                }
                shouldConnectOnBind = false; // 重置标记
            }

            // 2. 注册音量回调
            fmService.setOnVolumeChangeListener(new FMService.OnVolumeChangeListener() {
                @Override
                public void onVolumeChanged(int level) {
                    if (!isListenerActive) return; // 检查是否活跃

                    runOnUiThread(() -> {
                        try {
                            if (volumeMeterView != null) {
                                volumeMeterView.setLevel(level);
                            }
                        } catch (Exception e) {
                            // 忽略异常
                        }
                    });
                }
            });

        }



        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            fmService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFreq = findViewById(R.id.tvFreq);
        tvInfo = findViewById(R.id.tvInfo);
        btnPower = findViewById(R.id.btnPower);
        //btnSearch = findViewById(R.id.btnSearch);
        //btnToggleInfo = findViewById(R.id.btnToggleInfo);
        btnTuneDown = findViewById(R.id.btnTuneDown);
        btnTuneUp = findViewById(R.id.btnTuneUp);
        btnPre = findViewById(R.id.btnPre);
        btnNext = findViewById(R.id.btnNext);
        btnEdit = findViewById(R.id.btnEdit);
        flexboxLayoutButtons = findViewById(R.id.flexboxLayoutButtons);
        rssi_meter_wrap = findViewById(R.id.rssi_meter_wrap);
        rssi_meter = findViewById(R.id.rssi_meter);
        volumeMeterView = findViewById(R.id.volumeMeter);
        //quitButton = findViewById(R.id.quit_button);

        // 【修改点 1】：onCreate 中不再直接调用 startFMService()
        // 界面打开时，服务默认为空，等待用户操作
        //statusTextView.setText("状态: 等待连接...");

        // SharedPreferences 全局保存
        myapp = (MyFmApp) getApplicationContext();
        myapp.saveBoolean("btnPower", true);

        isFmServiceRunning = myapp.getBoolean("running",false);

        freq = myapp.getString("freq","");

        // 检查并请求权限
        checkAndRequestPermissions();



        // --- 设置按钮点击事件 ---

        btnPower.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                toggleFmService();

            }
        });


        //-----------btn Edit------------------
        btnEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent EditIntent = new Intent(MainActivity.this, FileEdit.class);
                startActivity(EditIntent);
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
                myapp.saveString("freq", freq);
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
                myapp.saveString("freq", freq);
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
            }
        });





    }

    /**
     * 启动并绑定服务（只有点击按钮时才调用）
     */
    private void startAndBindService() {
        Intent intent = new Intent(this, FMService.class);

        // 1. 启动前台服务 (保证后台存活)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        // 2. 绑定服务 (为了通信)
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    /**
     * 【修改点 3】：onStart 生命周期处理
     * 如果用户退出了 App 但服务还在跑，回来时我们要重新绑定，显示日志
     * 但如果服务没跑，我们不要启动它。
     */
    @Override
    protected void onStart() {
        super.onStart();

        // 注册广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(FMService.ACTION_LOG_UPDATE);
        filter.addAction(FMService.ACTION_STATUS_UPDATE);
        registerReceiver(fmReceiver, filter);

        // 尝试绑定现有的服务（如果服务正在运行）
        if (!isBound) {
            Intent intent = new Intent(this, FMService.class);
            // 关键：使用 flag 0 而不是 BIND_AUTO_CREATE
            // 如果服务已在运行，bindService 返回 true 并绑定
            // 如果服务未运行，bindService 返回 false，且不启动服务
            boolean serviceRunning = bindService(intent, connection, 0);
            if (serviceRunning) {
                //statusTextView.setText("状态: 恢复后台连接...");
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(fmReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isListenerActive = false;

        // 界面销毁时解绑，但服务依然在后台运行（因为之前调用了 startService）
        if (isBound) {

            // 解除监听器防止 Service 尝试回调已销毁的 Activity
            if (fmService != null) {
                fmService.setOnVolumeChangeListener(null);
            }

            unbindService(connection);
            isBound = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAppInForeground = true;

        tvFreq.setText(myapp.getString("freq",""));
        isFmServiceRunning = myapp.getBoolean("running", false);
        if(isFmServiceRunning){
            btnPower.setText("Stop");
            btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(color1)));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAppInForeground = false;

    }

    //----------------------------------------------------------------------------


    //---------rssi meter---------------
    //updateRSSIView(rssi_meter_wrap, rssi_meter, sinr2);
    private void updateRSSIView(final LinearLayout progressContainer, final View progressView,
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
        if (isFmServiceRunning) {
            // --- 停止逻辑 ---
            if (fmService != null) {
                fmService.sendFmCommand("QUIT");
            }

            // 核心修复 1: 必须解除绑定，否则 stopService 可能无效
            if (isBound) {
                fmService.setOnVolumeChangeListener(null);
                unbindService(connection);
                isBound = false;
                fmService = null;
            }

            // 停止前台服务
            stopService(new Intent(MainActivity.this, FMService.class));

            // 更新 UI 和状态
            isFmServiceRunning = false;
            myapp.saveBoolean("running",false);
            btnPower.setText("ON");
            tvInfo.setText("FM Stopped");
            volumeMeterView.setLevel(0);
            btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#e5e5e5")));
            updateRSSIView(rssi_meter_wrap, rssi_meter, 0);

        } else {
            // --- 启动 ---
            tvInfo.setText("FM Service Starting...");
            btnPower.setText("Stop");
            btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(color1)));


            shouldConnectOnBind = true;

            // 重新启动并绑定
            startAndBindService();



            isFmServiceRunning = true;
            myapp.saveBoolean("running",true);
        }
    }


    // ----------------电台列表按钮 存储------------------------
    private void loadAndSetupButtons() {
        MyFmApp application = (MyFmApp) getApplication();
        List<RadioStation> stations;

        // 1. 检查 Application 中是否已缓存数据
        if (application.areStationsLoaded()) {
            stations = application.getRadioStations();
            Log.d("MainActivity", "Using cached radio stations.");
        } else {
            // 2. 如果没有缓存，则从文件加载
            Log.d("MainActivity", "Loading radio stations from file.");
            String content = loadRadioStationsFromFile();
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
            button.setLayoutParams(lp);   // 放在创建 lp 之后

            // 为两行文字设置不同颜色
            String line1 = station.getNumber();
            String line2 = station.getName();
            String text = line1 + "\n" + line2;
            SpannableString spannable = new SpannableString(text);
            spannable.setSpan(
                    new ForegroundColorSpan(Color.parseColor("#555555")),
                    0,
                    line1.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            spannable.setSpan(
                    new ForegroundColorSpan(Color.parseColor("#aaaaaa")),
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
                if (fmService == null) {
                    return;
                }

                tvFreq.setText(station.getNumber());
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
                            myapp.saveString("freq",freqStr);
                            freq = freqStr;
                            fmService.sendFmCommand("TUNE "+freqStr);
                            //解决有时候声音小,已经在首次启动服务时设置了
                            //fmService.sendFmCommand("UNMUTE");
                            isFmServiceRunning = true;
                            myapp.saveBoolean("running",true);
                            btnPower.setText("Stop");
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





    // --------------获取 内置存储卡里的电台列表 ----------------
    private File getRadioStationsFile() {
        // 获取内置存储的根目录 (注意：这个方法在API 29+已被弃用，但在API 26仍可用)
        File rootDir = Environment.getExternalStorageDirectory();
        File myfmDir = new File(rootDir, "myapp");
        return new File(myfmDir, "myfm.txt");
    }

    private String loadRadioStationsFromFile() {
        File radioFile = getRadioStationsFile();
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
        } else {
            // 所有权限都已授予
            //Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            loadAndSetupButtons();
        }
    }







}
