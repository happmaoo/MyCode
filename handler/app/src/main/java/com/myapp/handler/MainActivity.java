package com.myapp.handler;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.TextView;

import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity {
    private TextView tvDisplay;
    private Messenger mServiceMessenger = null; // 服务的地址
    private boolean isBound = false;

    // --- 核心 A：定义一个安全的收件 Handler ---
    // 主界面的邮递员：处理来自 Service 的信件
    // 1. 定义一个静态内部类，避免持有外部 Activity 的隐式引用 ,使用弱引用包裹 Activity
    // Handler 就是管家：外人只能把信件（Message）交给管家

    private static class UiHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        UiHandler(MainActivity activity) {
            // 2. 指定在主线程处理消息 将 Activity 的引用存入“弱引用容器”
            super(Looper.getMainLooper());
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            // 4. 从容器里尝试取出 Activity
            // 关键判断：如果 Activity 还没被销毁  此时可以安全操作
            MainActivity activity = mActivity.get();

            if (activity != null) {
                activity.tvDisplay.setText("服务说: " + msg.obj);
            } else {

            }
        }
    }

    // --- 核心 B：把安全的收件 Handler包装成 Messenger 地址 ---
    private final Messenger uiMessenger = new Messenger(new UiHandler(this));

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {

            // 2. 拿到服务的 Messenger 拿到服务端的“投递入口”
            // 这里的参数 "service" (IBinder 类型)，就是服务 onBind 时，return serviceMessenger.getBinder();传过来的东西

            mServiceMessenger = new Messenger(service);
            isBound = true;
            tvDisplay.setText("已连接到服务");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceMessenger = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvDisplay = findViewById(R.id.textView_log);

        // 绑定并启动服务
        Intent intent = new Intent(this, MyForegroundService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);


        // 3. 按钮点击：给服务发信
        findViewById(R.id.btn_start).setOnClickListener(v -> {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            // --- 核心 C：发信 ---

            if (isBound && mServiceMessenger != null) {
                try {
                    Message msg = Message.obtain();
                    msg.obj = "你好 Service，我是 Activity！";

                    // 【关键点】告诉服务：如果你要回信，请发给这个 Messenger
                    msg.replyTo = uiMessenger;

                    mServiceMessenger.send(msg);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) unbindService(connection);
    }
}






/*


-------------------------------------------------------

写代码步骤：

先写 Service 的 onBind，再写 Activity 的 onServiceConnected。
因为 Service 是提供“服务”的一方，你必须先定义好邮局的窗口（Binder），Activity 才能（Bind）。


第1步：招聘管家与配置基站 (Service 逻辑)
先写服务端，因为它决定了通信的“频道”。
定义 Handler (收件箱)：创建一个 Handler（管家），重写 handleMessage 来决定收到 Activity 消息后做什么。
创建 Messenger (基站)：用这个 Handler 创建一个 Messenger 对象。
前台化 (Notification)：在 onStartCommand 中调用 startForeground，让服务变成前台服务（显示通知）。
暴露接口 (onBind)：在 onBind 方法中返回 messenger.getBinder()。

第2步：安装专线并开始通话 (Activity 逻辑)
再写客户端，连接并发送消息。
安全 Handler (静态内部类)：写一个静态的 IncomingHandler，用 WeakReference 弱引用 Activity，防止内存泄漏。
准备对讲机 (Messenger)：在 Activity 里初始化自己的 Messenger（用于接收回信）。
发起连接 (bindService)：调用 bindService 建立连接。
接通电话 (onServiceConnected)：在回调中，利用系统给的钥匙创建出指向服务的 mServiceMessenger。
发送第一封信：调用 mServiceMessenger.send(msg)。


-------------------------------------------------------



第一阶段：建立连接（初始化）
这是在程序刚运行，你点击“启动”按钮时发生的。

顺序	执行的方法	所在位置	发生了什么（注解）

1	onCreate()	Activity	主界面开门。此时会实例化 UiHandler（联络员）和 UiMessenger（自己的对讲机）。
2	bindService()	Activity	发出建交请求。Activity 告诉系统：“我想连接到那个邮局服务。”
3	onBind()	Service	邮局准许连接。Service 被唤醒，返回 serviceMessenger.getBinder()。这就是把自己的“频率”发出去。
4	onServiceConnected()	Activity	双方连通。Activity 接到了 Service 发来的 IBinder 钥匙，并据此创建了 mServiceMessenger。此时，Activity 终于可以给 Service 发信了。


第二阶段：发信过程（Activity -> Service）
当你点击按钮发送消息时。

5	onClick()	Activity	用户触发。点击按钮，开始准备信件。
6	Message.obtain()	Activity	拿信纸。从信纸池里拿一张干净的纸，比 new Message() 更省电省内存。
7	msg.replyTo = ...	Activity	贴回邮地址。把 Activity 自己的对讲机地址塞进信封，否则 Service 没法回信。
8	mServiceMessenger.send()	Activity	投递。点击发送。这封信会进入系统的“传送带”（MessageQueue）。


第三阶段：处理与回信（Service 处理中）
9	handleMessage()	Service	系统自动触发（回信点 1）。Service 的 Looper 发现有信，自动调用这个方法。Service 拆开信，看到了你的内容。
10	replyTo.send()	Service	寄出回信。Service 看到信封上的 replyTo 盖章，按照这个地址把回复发回去。


第四阶段：阅读回信（Service -> Activity）
11	handleMessage()	Activity	系统自动触发（回信点 2）。Activity 里的 UiHandler 发现有回信了，系统自动跳进这个方法。
12	activity.tvDisplay.setText()	Activity	更新界面。通过 WeakReference 确认主人还在家，然后把回信内容写在屏幕上。

第五阶段：销毁连接
当你退出 App 或关闭服务时。

13	onDestroy()	Activity	撤退。Activity 准备关门。
14	unbindService()	Activity	断开连接。Activity 告诉系统：“我不跟邮局联络了，收回那部对讲机吧。”



-------------------------------------------------------


// --- 核心 A：定义一个安全的收件 Handler ---
private static class ClientHandler extends Handler {
    private final WeakReference<MainActivity> mActivity;

    ClientHandler(MainActivity activity) {
        super(Looper.getMainLooper()); // 保证在 UI 线程处理
        mActivity = new WeakReference<>(activity);
    }

    // 这里就是“阅读信件”的地方
    @Override
    public void handleMessage(Message msg) {
        MainActivity activity = mActivity.get();
        if (activity != null) {
            activity.tvDisplay.setText("收到服务回信: " + msg.obj);
        }
    }
}

// --- 核心 B：包装成 Messenger 地址 ---
private final Messenger clientMessenger = new Messenger(new ClientHandler(this));

// --- 核心 C：发信逻辑 ---
private void sendMessage() {
    if (mServiceMessenger != null) {
        Message msg = Message.obtain();
        msg.obj = "你好 Service！";

        // 【最关键一步】：告诉 Service 往哪回信 (附带回信地址)
        msg.replyTo = clientMessenger;

        try {
            mServiceMessenger.send(msg); // 投递信件
        } catch (RemoteException e) { e.printStackTrace(); }
    }
}




// --- 核心 A：服务端收件箱 ---
private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {
    @Override
    public void handleMessage(Message msgFromActivity) {
        // 1. 拆信
        String content = (String) msgFromActivity.obj;

        // 2. 找到“回邮地址” (从信封里取出 Activity 传来的 Messenger)
        Messenger replyToMessenger = msgFromActivity.replyTo;

        if (replyToMessenger != null) {
            // 3. 准备回信
            Message replyMsg = Message.obtain();
            replyMsg.obj = "我已经收到你的消息啦！";
            try {
                // 4. 寄出回信
                replyToMessenger.send(replyMsg);
            } catch (RemoteException e) { e.printStackTrace(); }
        }
    }
};

// --- 核心 B：对外窗口 ---
private final Messenger serviceMessenger = new Messenger(serviceHandler);

@Override
public IBinder onBind(Intent intent) {
    // 绑定时把自己的窗口地址交给 Activity
    return serviceMessenger.getBinder();
}

@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    // 启动前台服务的核心：必须显示 Notification
    startForeground(1, myNotification);
    return START_STICKY;
}





-------------------------------------------------------








 */