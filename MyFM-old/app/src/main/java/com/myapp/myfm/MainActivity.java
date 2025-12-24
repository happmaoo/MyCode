package com.myapp.myfm;


import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import android.os.Environment;

import com.google.android.flexbox.FlexboxLayout;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.widget.CompoundButton;
import android.widget.ToggleButton;
import android.text.TextUtils;


public class MainActivity extends AppCompatActivity {


    private BroadcastReceiver responseReceiver;

    private Button btnPower,btnPre,btnNext,btnTuneDown,btnTuneUp,btnEdit;
    private boolean isFmServiceRunning = false;
    private TextView tvInfo;
    private TextView tvFreq;
    private ToggleButton btnToggleInfo;


    private TextView textViewDisplay;
    private FlexboxLayout flexboxLayoutButtons;
    private static final int PERMISSION_REQUEST_CODE = 10;
    private String response;


    private LinearLayout rssi_meter_wrap;
    private View rssi_meter;

    private LinearLayout vol_meter_wrap;
    private View vol_meter;
    private int lastLevel = -1;

    private String info="";
    private String frequency="87";
    private String rssi="0";

    private FmStateManager fmState;

    Button selectedButton = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnPower = findViewById(R.id.btnPower);
        btnToggleInfo = findViewById(R.id.btnToggleInfo);
        btnTuneDown = findViewById(R.id.btnTuneDown);
        btnTuneUp = findViewById(R.id.btnTuneUp);
        btnPre = findViewById(R.id.btnPre);
        btnNext = findViewById(R.id.btnNext);
        btnEdit = findViewById(R.id.btnEdit);

        tvInfo = findViewById(R.id.tvInfo);
        tvFreq = findViewById(R.id.tvFreq);


        textViewDisplay = findViewById(R.id.tvInfo);
        flexboxLayoutButtons = findViewById(R.id.flexboxLayoutButtons);

        rssi_meter_wrap = findViewById(R.id.rssi_meter_wrap);
        rssi_meter = findViewById(R.id.rssi_meter);

        vol_meter_wrap = findViewById(R.id.vol_meter_wrap);
        vol_meter = findViewById(R.id.vol_meter);

        // SharedPreferences 全局保存
        MyFmApp app = (MyFmApp) getApplicationContext();
        app.saveBoolean("btnPower", true);


        fmState = new FmStateManager(this);
        isFmServiceRunning = fmState.isRunning();

        if (isFmServiceRunning) {
            btnPower.setText("Stop");
            btnPower.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#C0FFB0"))
            );
        } else {
            btnPower.setText("ON");
            btnPower.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#e5e5e5"))
            );
        }

        frequency = fmState.getFrequency();
        tvFreq.setText(frequency);





        // 检查并请求权限
        checkAndRequestPermissions();


        if (isFmServiceRunning) {
            btnPower.setText("Stop");
            btnPower.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#C0FFB0"))
            );
        } else {
            btnPower.setText("ON");
            btnPower.setBackgroundTintList(
                    ColorStateList.valueOf(Color.parseColor("#e5e5e5"))
            );
        }


        //-----------btn Power------------------
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
                //Toast.makeText(MainActivity.this, frequency, Toast.LENGTH_SHORT).show();
                float freq = Float.parseFloat(frequency)-0.1f;
                Intent serviceIntent = new Intent(MainActivity.this, FMService.class);
                serviceIntent.putExtra("freq", freq); // 传递频率
                startService(serviceIntent);
            }
        });

        //-----------Button TuneUp----------------
        btnTuneUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                //Toast.makeText(MainActivity.this, frequency, Toast.LENGTH_SHORT).show();
                float freq = Float.parseFloat(frequency)+0.1f;
                Intent serviceIntent = new Intent(MainActivity.this, FMService.class);
                serviceIntent.putExtra("freq", freq); // 传递频率
                startService(serviceIntent);
            }
        });


        //-----------Button Pre----------------
        btnPre.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                //Toast.makeText(MainActivity.this, frequency, Toast.LENGTH_SHORT).show();
                Intent serviceIntent = new Intent(MainActivity.this, FMService.class);
                serviceIntent.putExtra("seek", 0);
                startService(serviceIntent);
            }
        });

        //-----------Button Next----------------
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                //Toast.makeText(MainActivity.this, frequency, Toast.LENGTH_SHORT).show();
                Intent serviceIntent = new Intent(MainActivity.this, FMService.class);
                serviceIntent.putExtra("seek", 1);
                startService(serviceIntent);
            }
        });



        // //-----------Toggle Button info 刷新开关----------------
        btnToggleInfo.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    Intent serviceIntent = new Intent(MainActivity.this, FMService.class);
                    serviceIntent.putExtra("liveinfo", 1);
                    //serviceIntent.putExtra("station_name", station.getName());

                    startService(serviceIntent); // 启动服务来改变频率

                } else {

                }
            }
        });

       // 接收来自服务器的响应数据
        responseReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {

                if (intent.getAction().equals("com.myapp.myfm.SERVER_RESPONSE")) {
                    response = intent.getStringExtra("response_data");
                    //responseTextView.setText("FM Server Response: " + response);
                    updateUIText(response);
                }
            }
        };

    }


    private void updateUIText(String text) {
        String frequencyRec = extractValueByKey(text, "FREQ");
        String rssiRec = extractValueByKey(text, "RSSI");
        String sinrRec = extractValueByKey(text, "SINR");

        if (frequencyRec != null && !frequencyRec.trim().isEmpty()) {
            frequency = frequencyRec;
            tvFreq.setText(frequency);
            fmState.setFrequency(frequency);
        }
        if (rssiRec != null && !rssiRec.trim().isEmpty()) {
            rssi = String.valueOf(Integer.parseInt(rssiRec) - 139);
        }
        //无信号时为139 所以要减去.  139 - 255 = -116db

        if (sinrRec != null) {
            int sinr2 = Integer.parseInt(sinrRec);
            // 超过200就是不准确的
            if(sinr2<200){
            updateProgressView(rssi_meter_wrap, rssi_meter, sinr2);
            }
        }

        //tvInfo.setText(text.replace("|", "\n"));
        tvInfo.setText("FREQ:" + frequency + "\nRSSI:" + rssi +"\nSINR:" + sinrRec);
    }

    /**
     * 切换FM服务的启动和停止状态。
     */
    private void toggleFmService() {
        Intent serviceIntent = new Intent(MainActivity.this, FMService.class);

        if (isFmServiceRunning) {
            // 当前正在运行 -> 执行停止操作
            stopService(serviceIntent);

            // 更新状态和UI
            isFmServiceRunning = false;
            fmState.setRunning(false);
            btnPower.setText("ON");
            tvInfo.setText("FM Stopped");


            btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#e5e5e5")));
            updateProgressView(rssi_meter_wrap, rssi_meter, 0);
            updateVolumeView(vol_meter_wrap, vol_meter, 0);

        } else {
            // 当前未运行 -> 执行启动操作
            tvInfo.setText("FM Service Starting...");
            btnPower.setText("Stop");
            btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#C0FFB0")));

            serviceIntent.putExtra("freq", frequency);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // 更新状态和UI
            isFmServiceRunning = true;
            fmState.setRunning(true);
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


   //-------------界面显示部分-----------------


    //---------rssi meter---------------
    private void updateProgressView(final LinearLayout progressContainer, final View progressView,
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

    // --------- Volume Meter---------------
    private void updateVolumeView(final LinearLayout volumeContainer,
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
                textViewDisplay.setText(station.getDisplayText());
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
                        float freq = Float.parseFloat(freqStr);
                        if (freq > 0) {
                            // 只传递新的频率给 FMService
                            Intent serviceIntent = new Intent(MainActivity.this, FMService.class);
                            serviceIntent.putExtra("freq", freq); // 传递频率
                            serviceIntent.putExtra("station_name", station.getName()); // ⭐ 传递电台名称

                            startService(serviceIntent); // 启动服务来改变频率
                            tvInfo.setText("Frequency: " + freq);
                            isFmServiceRunning = true;
                            fmState.setRunning(true);
                            btnPower.setText("Stop");
                            btnPower.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#C0FFB0")));


                        }else {
                            Toast.makeText(MainActivity.this, "Invalid frequency value", Toast.LENGTH_SHORT).show();
                        }
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


    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.myapp.myfm.SERVER_RESPONSE");
        filter.addAction(FMService.ACTION_VOLUME_UPDATE);

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(responseReceiver, filter);

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(volumeReceiver,
                        new IntentFilter(FMService.ACTION_VOLUME_UPDATE));
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(responseReceiver);
        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(volumeReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 恢复按钮状态
        //使用 SharedPreferences 恢复按钮状态：
    }


    /**
     * 从键值对字符串中提取指定 Key 的值。
     * 格式示例: FREQ:90.6|SIGNAL_RSSI:152|SINR:0
     *
     * @param InfoString 待解析的实时信息字符串 (例如: "FREQ:90.6|...")
     * @param targetKey      要查找的键 (例如: "SINR" 或 "FREQ")
     * @return 对应的字符串值 (例如: "0" 或 "90.6")；如果未找到或字符串无效，则返回 null。
     */
    public static String extractValueByKey(String InfoString, String targetKey) {

        if (InfoString == null || InfoString.isEmpty() || targetKey == null || targetKey.isEmpty()) {
            return null;
        }

        String searchPattern = targetKey.trim() + ":";
        int patternLength = searchPattern.length();
        int startIndex = InfoString.indexOf(searchPattern);

        if (startIndex == -1) {
            return null;
        }
        int valueStart = startIndex + patternLength;
        int valueEnd = InfoString.indexOf('|', valueStart);

        if (valueEnd == -1) {
            valueEnd = InfoString.length();
        }

        if (valueStart < valueEnd) {
            String extractedValue = InfoString.substring(valueStart, valueEnd).trim();
            return extractedValue.isEmpty() ? null : extractedValue;
        }

        return null;
    }

    public interface VolumeListener {
        void onVolume(int level); // 0 ~ 100
    }


    // --------------volume meter Receiver----------------------
    private BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isFmServiceRunning) {
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




}


