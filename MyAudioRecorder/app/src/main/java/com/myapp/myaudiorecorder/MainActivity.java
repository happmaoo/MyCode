package com.myapp.myaudiorecorder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.lifecycle.Observer;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Intent serviceIntent;
    private Observer<Pair<String, String>> messageObserver; // 添加观察者变量
    TextView textView_log,textView_local,textView_net;
    EditText editText_net,editText_local;

    Button btn_start;

    private RadioGroup radioGroup;
    RadioButton radioButton_loc,radioButton_net;
    LinearLayout layout_local,layout_net;

    MyApp myapp;
    String local;
    String net;

    // 颜色定义
    private final String COLOR_ACCENT = "#167c80";
    private final String COLOR_DEFAULT = "#e5e5e5";


    private StateManager stateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        myapp = (MyApp) getApplicationContext();
        stateManager = new StateManager(this, myapp);


        MyState currentState = stateManager.getCurrentState();
        if (currentState == MyState.STOPPED) {


        }

        serviceIntent = new Intent(this, MyAudioRecorderService.class);

        if (checkAndRequestPermissions()) {
            startAudioService();
        }



        layout_local = findViewById(R.id.layout_local);
        layout_net = findViewById(R.id.layout_net);

        textView_log = findViewById(R.id.textView_log);
        textView_local = findViewById(R.id.textView_local);
        textView_net = findViewById(R.id.textView_net);

        editText_net = findViewById(R.id.editText_net);
        editText_local = findViewById(R.id.editText_local);


        btn_start  = findViewById(R.id.btn_start);

        radioGroup  = findViewById(R.id.RadioGroup);
        radioButton_loc  = findViewById(R.id.radioButton_loc);
        radioButton_net  = findViewById(R.id.radioButton_net);




        if (Build.VERSION.SDK_INT >= 29) {
            // 获取应用私有外部存储目录（不需要任何存储权限）
            File appPrivateDir = getExternalFilesDir(null);
            local = myapp.getString("local",appPrivateDir.getAbsolutePath());
        } else {
            local = myapp.getString("local",Environment.getExternalStorageDirectory().getAbsolutePath() + "/myapp/MyAudioRecorder");
        }


//        Shell.Result result;
//        result = Shell.cmd("ls").exec();
//        List<String> out = result.getOut();
//        Log.i("TAG", "onCreate: "+out);


        myapp.setString("local",local);
        net = myapp.getString("net","192.168.0.1:7777");
        myapp.setString("net",net);

        if("loc".equals(myapp.getString("type", "loc"))){
            radioButton_loc.setChecked(true);
            layout_local.setVisibility(View.VISIBLE);
            layout_net.setVisibility(View.GONE);
        }else{
            radioButton_net.setChecked(true);
            layout_local.setVisibility(View.GONE);
            layout_net.setVisibility(View.VISIBLE);

        }

        editText_local.setText(local);
        editText_net.setText(net);


        //-----------Button send----------------
        btn_start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MyState currentState = stateManager.getCurrentState();

                if (messageObserver != null) {

                    if("loc".equals(myapp.getString("type", "loc"))){
                        if(currentState == MyState.RECORDDING){
                            DataManager.getInstance().sendMessage("Activity", "stopRecording");
                            stateManager.setState(MyState.STOPPED);
                        }else {
                            DataManager.getInstance().sendMessage("Activity", "startRecording");
                            stateManager.setState(MyState.RECORDDING);
                        }
                    }else{
                        if(currentState == MyState.RECORDDING) {
                            DataManager.getInstance().sendMessage("Activity", "stopNetRecording");
                            stateManager.setState(MyState.STOPPED);
                        }else {
                            DataManager.getInstance().sendMessage("Activity", "startNetRecording");
                            stateManager.setState(MyState.RECORDDING);

                        }
                    }

                }
            }
        });


        radioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.radioButton_loc) {
                    Log.d("RadioButton", "选择了：本地");
                    myapp.setString("type", "loc");
                    layout_net.setVisibility(View.GONE);
                    layout_local.setVisibility(View.VISIBLE);
                } else if (checkedId == R.id.radioButton_net) {
                    Log.d("RadioButton", "选择了：网络");
                    myapp.setString("type", "net");
                    layout_local.setVisibility(View.GONE);
                    layout_net.setVisibility(View.VISIBLE);
                }

                textView_log.setText("");
            }
        });





        editText_net.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            @Override
            public void afterTextChanged(Editable s) {
                String text = s.toString();
                myapp.setString("net", text);
            }
        });
        editText_local.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
            @Override
            public void afterTextChanged(Editable s) {
                String text = s.toString();
                myapp.setString("local", text);
            }
        });













    }

    /**
     * 检查并申请权限
     */
    private boolean checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // 录音权限 (必须)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }

        // Android 14+ 前台服务麦克风类型权限
        if (Build.VERSION.SDK_INT >= 34) { // API 34
            String micPermission = "android.permission.FOREGROUND_SERVICE_MICROPHONE";
            if (ContextCompat.checkSelfPermission(this, micPermission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(micPermission);
            }
        }

        // 存储权限调整
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 只需要读取权限（如果需要访问媒体文件）
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                // 检查是否需要存储权限：只有当应用需要访问媒体文件时才请求
                // 如果只是保存到应用私有目录（getExternalFilesDir），则不需要此权限
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        } else {
            // Android 9 及以下版本需要写权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), 888);
            return false;
        }
        return true;
    }

    /**
     * 处理权限申请回调
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 888) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startAudioService();
            } else {
                Toast.makeText(this, "需要录音权限才能运行", Toast.LENGTH_SHORT).show();
                // 引导用户去设置页面或关闭应用
            }
        }
    }

    private void startAudioService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }


    // 设置消息观察者
    private void setupMessageObserver() {
        messageObserver = new Observer<Pair<String, String>>() {
            @Override
            public void onChanged(Pair<String, String> pair) {
                if (pair != null) {
                    String from = pair.first;
                    String content = pair.second;
                    Log.i("TAG", "来自 " + from + " 的消息: " + content);

                    if ("Service".equals(from)||"AudioStreamer".equals(from)) {
                        handleServiceMessage(content);
                        if("连接失败".equals(content)){stateManager.setState(MyState.STOPPED);}
                    }
                }
            }
        };

        // 使用observeForever而不是observe
        DataManager.getInstance().getLiveDataMessage().observeForever(messageObserver);
    }

    // 处理来自Service的消息
    private void handleServiceMessage(String content) {
        textView_log.setText(content);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setupMessageObserver();


        // 因为需要再次更新UI，不然不会更新
        stateManager.setState(stateManager.getCurrentState());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (messageObserver != null) {
            DataManager.getInstance().getLiveDataMessage().removeObserver(messageObserver);
        }
    }

    @Override
    protected void onDestroy() {

        if (messageObserver != null) {
            DataManager.getInstance().sendMessage("Activity", "stopNetRecording");
        }


        if (serviceIntent != null) {
            stopService(serviceIntent);
        }
        super.onDestroy();
    }
}