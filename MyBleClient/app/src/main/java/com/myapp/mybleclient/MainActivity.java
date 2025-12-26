package com.myapp.mybleclient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private GattService.DataPlane mDataPlane;
    private EditText editText_send;
    private TextView textView_rec,textView_bt,textView_bat,textView_sig;
    private Button btn_send,btn_stop;
    private boolean mIsBound = false;
    private CheckBox checkBox_autoconn,checkBox_autopan,checkBox_pan,checkBox_wifi;
    private Switch switch_wifi;

    private ImageView imageView_bt,imageView_bat,imageView_sig;
    private String color1="#FF2DBB38";
    private String color2="#aaaaaa";
    private String textcolor1="#555555";
    private String textcolor2="#cccccc";

    private BroadcastReceiver dataReceiver;
    private boolean isReceiverRegistered = false;
    MyApp app;
    Boolean conncted = false;
    String addr = "";
    int bat = 0;


    // 2. 定义服务连接器
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // 获取来自 GattService 的 Binder
            mDataPlane = (GattService.DataPlane) service;

            mIsBound = true;
            Log.i(TAG, "Service Bound");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mDataPlane = null;
            mIsBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textView_rec = findViewById(R.id.textView_rec);
        editText_send = findViewById(R.id.editText_send);
        btn_send = findViewById(R.id.btn_send);
        btn_stop = findViewById(R.id.btn_stop);
        checkBox_autoconn = findViewById(R.id.checkBox_autoconn);
        checkBox_autopan = findViewById(R.id.checkBox_autopan);
        checkBox_pan = findViewById(R.id.checkBox_pan);
        checkBox_wifi = findViewById(R.id.checkBox_wifi);

        imageView_bt = findViewById(R.id.imageView_bt);
        imageView_bat = findViewById(R.id.imageView_bat);
        imageView_sig = findViewById(R.id.imageView_sig);
        textView_bt = findViewById(R.id.textView_bt);
        textView_bat = findViewById(R.id.textView_bat);
        textView_sig = findViewById(R.id.textView_sig);





        // SharedPreferences
        app = (MyApp) getApplicationContext();
        //app.saveString("username", "JohnDoe");
        boolean sp_pan = app.getBoolean("autopan", false);
        if(sp_pan){
            checkBox_autopan.setChecked(true);
        }
        textView_bt.setText(app.getString("addr", ""));
        textView_bat.setText(String.valueOf(app.getInt("bat", 0)));

        Intent intent = new Intent(this, GattService.class);
        intent.setAction(GattService.DATA_PLANE_ACTION);
        setupBroadcastReceiver();

        boolean autoconn = app.getBoolean("autoconn", false);

        if (autoconn) {
            checkBox_autoconn.setChecked(true);
            startService(intent);
            bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }

        //-----------Button send----------------
        btn_send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            String inputText = editText_send.getText().toString().trim();
                if (mDataPlane != null) {
                    mDataPlane.send(inputText);
                }
            }
        });

        // checkBox_autoconn
        checkBox_autoconn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                app.setBoolean("autoconn", true);
                Toast.makeText(MainActivity.this, "自动连接开启", Toast.LENGTH_SHORT).show();
            }else{
                app.setBoolean("autoconn", false);
            }
        });

        // checkBox_pan
        checkBox_autopan.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                app.setBoolean("autopan", true);
                Toast.makeText(MainActivity.this, "pan 自动连接开启", Toast.LENGTH_SHORT).show();
            }else{
                app.setBoolean("autopan", false);
            }
        });

        // btn_stop
        btn_stop.setOnClickListener((View view) -> {
            boolean running = isServiceRunning(GattService.class);
            if (running) {
                if (mIsBound) {
                    unbindService(mServiceConnection);
                    mIsBound = false;
                }
                stopService(intent);


                btn_stop.setText("Start");
                checkBox_wifi.setEnabled(false);
                checkBox_pan.setEnabled(false);
                btn_send.setEnabled(false);
                editText_send.setEnabled(false);

                app.setBoolean("connected", false);

                app.setpan(false);
                app.setwifi(false);
                checkBox_pan.setChecked(false);
                checkBox_wifi.setChecked(false);

                textView_bat.setText("0");
                app.setInt("bat", 0);


                refreshicon();
            } else {
                startService(intent);
                bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);


                btn_stop.setText("Stop");
                checkBox_wifi.setEnabled(true);
                checkBox_pan.setEnabled(true);
                btn_send.setEnabled(true);
                editText_send.setEnabled(true);

                app.setBoolean("connected", true);
            }
        });


        // pan 开关
        checkBox_pan.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (mDataPlane != null) {
                    mDataPlane.send("enable_pan");
                    app.setpan(true);
                }
            } else {
                if (mDataPlane != null) {
                    mDataPlane.send("disable_pan");
                    app.setpan(false);
                }
            }
        });

        // wifi 开关
        checkBox_wifi.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (mDataPlane != null) {
                    mDataPlane.send("enable_wifi");
                    app.setwifi(true);
                }
            } else {
                if (mDataPlane != null) {
                    mDataPlane.send("disable_wifi");
                    app.setwifi(false);
                }
            }
        });


    }





    private void setupBroadcastReceiver() {
        dataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (GattService.ACTION_DATA_RECEIVED.equals(intent.getAction())) {
                    String data = intent.getStringExtra(GattService.EXTRA_DATA_VALUE);

                    // 处理接收到的数据
                    Log.d("MainActivity", "Received: " + data);
                    textView_rec.append(data);

                    // textView_rec 滚动到底部
                    textView_rec.post(new Runnable() {
                        @Override
                        public void run() {
                            int scrollAmount = textView_rec.getLayout().getLineTop(textView_rec.getLineCount())
                                    - textView_rec.getHeight();
                            if (scrollAmount > 0) {
                                textView_rec.scrollTo(0, scrollAmount);
                            } else {
                                textView_rec.scrollTo(0, 0);
                            }
                        }
                    });


                    // 提取 server 发来的内容
                    Pattern pattern = Pattern.compile("(\\d{2}:\\d{2}:\\d{2}):([a-zA-Z]+):");
                    Matcher matcher = pattern.matcher(data);

                    if (matcher.find()) {
                        String time = matcher.group(1);      // "15:42:00"
                        String name = matcher.group(2);    // "connected"

                        System.out.println("时间: " + time);
                        System.out.println("Name: " + name);


                        if ("connected".equals(name)){
                            //
                        }
                    } else {
                        //System.out.println("格式不匹配");
                    }
                }

                refreshicon();
            }
        };
        if (!isReceiverRegistered) {
        // 注册本地广播接收器
        LocalBroadcastManager.getInstance(this)
        .registerReceiver(dataReceiver, new IntentFilter(GattService.ACTION_DATA_RECEIVED));
        isReceiverRegistered = true;}
    }





    @Override
    protected void onStart() {
        super.onStart();
        // 只要服务正在运行，我们就去绑定它
        if (isServiceRunning(GattService.class)) {
            bindMyService();
        }
    }
    private void bindMyService() {
        if (!mIsBound) {
            Intent intent = new Intent(this, GattService.class);
            intent.setAction(GattService.DATA_PLANE_ACTION);
            // Context.BIND_AUTO_CREATE 会在服务没启动时自动启动它
            bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        textView_rec.setText(app.getString("log", ""));

        // 检查服务是否运行
        if (isServiceRunning(GattService.class)) {
            btn_stop.setText("Stop");

            checkBox_wifi.setEnabled(true);
            checkBox_pan.setEnabled(true);
            btn_send.setEnabled(true);
            editText_send.setEnabled(true);

            if(app.getpan()==true){checkBox_pan.setChecked(true);}
            if(app.getwifi()==true){checkBox_wifi.setChecked(true);}

        } else {
            btn_stop.setText("Start");
        }
        refreshicon();
    }


    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 统一在 onDestroy 中取消注册
        if (dataReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }

        // 4. 解绑服务防止内存泄漏
        if (mIsBound) {
            unbindService(mServiceConnection);
            mIsBound = false;
        }
    }



    public void refreshicon(){
        conncted = app.getBoolean("connected",false);

        addr  = app.getString("addr","");
        bat  = app.getInt("bat",0);
        if(conncted){
            imageView_bt.setColorFilter(Color.parseColor(color1));
            textView_bt.setText(addr);
            textView_bt.setTextColor(Color.parseColor(textcolor1));

            imageView_bat.setColorFilter(Color.parseColor(color1));
            textView_bat.setText(String.valueOf(bat));
            textView_bat.setTextColor(Color.parseColor(textcolor1));
        }else{
            imageView_bt.setColorFilter(Color.parseColor(color2));
            textView_bt.setTextColor(Color.parseColor(textcolor2));

            imageView_bat.setColorFilter(Color.parseColor(color2));
            textView_bat.setTextColor(Color.parseColor(textcolor2));
        }
    }















    public String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }


    // 检查服务是否运行的简单方法
    private boolean isServiceRunning(Class<?> cls) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(100)) {
            if (cls.getName().equals(s.service.getClassName())) {
                return true;
            }
        }
        return false;
    }


}