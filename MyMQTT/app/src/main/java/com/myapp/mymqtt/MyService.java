package com.myapp.mymqtt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;
import androidx.lifecycle.Observer;

import androidx.core.app.NotificationCompat;


import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
//import org.eclipse.pales.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MyService extends Service {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "my_channel";
    private static final String TAG = "Service";





    private Observer<Pair<String, String>> messageObserver; // 添加观察者变量


    MyMQTT myapp;
    MyMQTT application;

    private MqttClient mqttClient;

    private ConnectivityManager.NetworkCallback networkCallback;

    String mqqt_server_url;
    String mqqt_server_addr;
    int mqqt_server_port;
    int mqqt_server_keepalive;
    String mqqt_server_username;
    String mqqt_server_password;
    String mqqt_topic;
    String topic_request;

    int retries = 0;

    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());



    @Override
    public void onCreate() {
        setupMessageObserver();
        super.onCreate();

        // SharedPreferences 全局保存
        myapp = (MyMQTT) getApplicationContext();

        mqqt_server_url = myapp.getString("mqqt_server_url","");
        mqqt_server_port = myapp.getInt("mqqt_server_port",0);
        mqqt_server_keepalive = myapp.getInt("mqqt_server_keepalive",60);
        mqqt_server_username = myapp.getString("mqqt_server_username","");
        mqqt_server_password = myapp.getString("mqqt_server_password","");
        mqqt_topic = myapp.getString("mqqt_topic","");


        Log.i(TAG, "onCreate: ");
        topic_request = "cmd/request";
    }



    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }


    @Override
    public void onDestroy() {
        // A. 立即截断所有 Handler 延时任务，防止 5 秒后 Service "诈尸" 执行逻辑
        mHandler.removeCallbacksAndMessages(null);

        // B. 移除消息观察者
        if (messageObserver != null) {
            DataManager.getInstance().getLiveDataMessage().removeObserver(messageObserver);
        }

        // C. 注销网络监听
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(networkCallback);
                } catch (Exception e) {
                    // 防止重复注销崩溃
                }
            }
        }

        // D. 同步关闭 MQTT (移除 Thread)
        // 在 onDestroy 中，为了确保关闭指令发出，建议直接同步操作或设极短超时
        if (mqttClient != null) {
            try {
                mqttClient.setCallback(null); // 1. 关掉回调，防止断开时又跑进 connectionLost
                if (mqttClient.isConnected()) {
                    mqttClient.disconnectForcibly(500); // 2. 强制断开，最多等 500ms
                }
                mqttClient.close();
            } catch (Exception e) {
                Log.e(TAG, "MQTT Close Error: " + e.getMessage());
            }
        }

        // E. 停止前台服务
        stopForeground(true);
        myapp.isRunning = false;

        Log.i(TAG, "MyService 已彻底销毁");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        myapp.isRunning=true;

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MQTT")
                .setContentText("")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // 发送数据
        //DataManager.getInstance().sendMessage("Service","我是 Service.");
        //initMqtt();


        getServerAddr();
        //setupNetworkMonitoring();


        return START_STICKY;
    }



    private  void getServerAddr(){
        new Thread(() -> {

            Log.i(TAG, "getServerAddr()");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            String currentTime = sdf.format(Calendar.getInstance().getTime());


            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(mqqt_server_url + "?t=" + currentTime)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String result = response.body().string();
                Log.i(TAG, "gist text:"+result);


                Pattern pattern_addr = Pattern.compile("addr=([0-9a-fA-F:]+)");
                Matcher matcher_addr = pattern_addr.matcher(result);

                if (matcher_addr.find()) {
                    mqqt_server_addr = matcher_addr.group(1);
                    DataManager.getInstance().sendMessage("Service","Get Server Address:\n"+mqqt_server_addr);
                    initMqtt();
                }else{
                    DataManager.getInstance().sendMessage("Service","Get Server Address Error.");
                }



            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    //mosquitto_pub -p 28002 -u mymqtt -P happmaoo123 -t "cmd/request" -m "ls"



    private void initMqtt() {

        // 【安全清理逻辑】
        try {
            if (mqttClient != null) {
                // 先解绑回调，防止旧客户端的消息干扰新逻辑
                mqttClient.setCallback(null);
                if (mqttClient.isConnected()) {
                    mqttClient.disconnectForcibly(); // 强制断开
                }
                mqttClient.close();
                mqttClient = null;
                Log.i(TAG, "旧的 MQTT 客户端已清理");
            }
        } catch (MqttException e) {
            Log.e(TAG, "清理旧客户端出错: " + e.getMessage());
        }


        String formattedAddr = mqqt_server_addr.contains(":") ? "[" + mqqt_server_addr + "]" : mqqt_server_addr;
        String brokerUrl = "tcp://" + formattedAddr + ":" + mqqt_server_port;
        Log.i(TAG, "initMqtt: "+brokerUrl);
        String clientId = MqttClient.generateClientId();

        try {
            // 1. 创建客户端
            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            // 2. 设置回调
            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    // 自动重连成功后，必须重新订阅
                    Log.i(TAG, "连接成功/重连成功: " + serverURI);
                    DataManager.getInstance().sendMessage("Service", "MQTT 已连接");
                    updateNotification("MQTT", "MQTT 已连接");
                    subscribeToTopic(mqqt_topic);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.e(TAG, "连接断开: " + (cause != null ? cause.getMessage() : "原因未知"));
                    DataManager.getInstance().sendMessage("Service", "连接已断开，正在监测重连状态...");
                    updateNotification("MQTT", "MQTT 连接断开");
                    // 不要用 new Handler().postDelayed，要用全局的 mHandler
                    mHandler.postDelayed(() -> {
                        if (mqttClient != null && !mqttClient.isConnected()) {
                            getServerAddr();
                        }
                    }, 5000);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    Log.d(TAG, "收到消息 [" + topic + "]: " + payload);
                    DataManager.getInstance().sendMessage("Service", payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            // 3. 配置连接参数
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(mqqt_server_username);
            options.setPassword(mqqt_server_password.toCharArray());
            options.setAutomaticReconnect(true);  // 开启自动重连
            options.setCleanSession(true);
            options.setConnectionTimeout(10);     // 关键：10秒超时
            options.setKeepAliveInterval(mqqt_server_keepalive);     // 心跳间隔

            // 4. 发起连接 (Paho 的 connect 是阻塞的，建议在子线程执行)
            new Thread(() -> {
                try {
                    Log.i(TAG, "开始连接 Broker: " + brokerUrl);
                    DataManager.getInstance().sendMessage("Service", "开始连接 Broker: \n" + brokerUrl);
                    mqttClient.connect(options);
                } catch (MqttException e) {
                    handleMqttError(e);
                }
            }).start();

        } catch (MqttException e) {
            handleMqttError(e);
        }
    }

    private void handleMqttError(MqttException e) {
        String errorInfo;
        switch (e.getReasonCode()) {
            case MqttException.REASON_CODE_CLIENT_TIMEOUT:
                errorInfo = "连接超时：请检查端口 " + mqqt_server_port + " 是否开放或网络是否支持 IPv6";
                break;
            case MqttException.REASON_CODE_FAILED_AUTHENTICATION:
                errorInfo = "认证失败：用户名或密码错误";
                break;
            case MqttException.REASON_CODE_BROKER_UNAVAILABLE:
                errorInfo = "服务器不可用 (Broker Offline)";
                break;
            default:
                errorInfo = "连接失败 [" + e.getReasonCode() + "]: " + e.getMessage();
                //连接失败可能是ipv6地址更新了，获取新地址
                if(retries<5){
                    getServerAddr();
                    retries++;
                }
                break;

        }
        Log.e(TAG, errorInfo);
        DataManager.getInstance().sendMessage("Service", errorInfo);
    }

    private void subscribeToTopic(String topic) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.subscribe(topic, 0);
                Log.i(TAG, "订阅成功: " + topic);
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    public void publish(String topic, String content) {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                MqttMessage message = new MqttMessage(content.getBytes());
                message.setQos(0);
                mqttClient.publish(topic, message);
            } catch (MqttException e) {
                Log.e(TAG, "发布失败: " + e.getMessage());
            }
        }
    }



    // Service: 设置消息观察者,需手动注册/注销
    private void setupMessageObserver() {
        messageObserver = new Observer<Pair<String, String>>() {
            @Override
            public void onChanged(Pair<String, String> pair) {
                if (pair != null) {
                    String from = pair.first;
                    String content = pair.second;
                    //Log.i(TAG, "messageObserver log: " + from + " 发来: " + content);

                    if ("Activity".equals(from)) {
                        // 处理来自Activity的消息
                        Log.i(TAG, "收到消息: " + content);
                        publish(topic_request,content);

                    }
                }
            }
        };
        // 使用observeForever
        DataManager.getInstance().getLiveDataMessage().observeForever(messageObserver);
    }



    private void setupNetworkMonitoring() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // 配置我们关心的网络能力：只要能上网就行
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // 当网络切换（比如 Wi-Fi 断开切换到 5G，或者重新连上 Wi-Fi）
                Log.i(TAG, "网络已就绪，正在获取 Gist 配置...");
                retries = 0;
                getServerAddr(); // 触发你的获取 IP 逻辑
            }

            @Override
            public void onLost(Network network) {
                Log.e(TAG, "网络丢失");
                DataManager.getInstance().sendMessage("Service", "网络连接中断");
                //  主动断开客户端，防止 Paho 在后台尝试 Automatic Reconnect
                new Thread(() -> {
                    try {
                        if (mqttClient != null) {
                            // 使用 disconnectForcibly 立即释放 Socket 资源
                            mqttClient.disconnectForcibly();
                            Log.i(TAG, "MQTT 客户端已强制停用");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "停止重连时出错: " + e.getMessage());
                    }
                }).start();
            }
        };

        if (cm != null) {
            cm.registerNetworkCallback(request, networkCallback);
        }
    }



    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "My Foreground Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel description");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }


    private void updateNotification(String title, String text) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // 构建新的通知内容，确保 ID 和 ChannelID 保持一致
        Notification updatedNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 必须有图标
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // 前台服务通知通常设为 ongoing
                .build();

        // 关键：使用创建时相同的 NOTIFICATION_ID
        notificationManager.notify(NOTIFICATION_ID, updatedNotification);
    }

}
