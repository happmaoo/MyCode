package com.myapp.myfm;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FMClient {

    private static final String TAG = "FMClient";
    // 请确保这个名称与 C 端定义的 SOCKET_NAME 完全一致
    private static final String SOCKET_NAME = "fm_service";

    private LocalSocket socket = null;
    private PrintWriter writer = null;
    private BufferedReader reader = null;

    // 接收线程
    private Thread receiveThread = null;

    private final MessageCallback callback;
    private ExecutorService executor;
    // 回调接口
    public interface MessageCallback {
        void onMessageReceived(String message);
        void onStatusChanged(String status);
    }

    public FMClient(MessageCallback callback) {
        this.callback = callback;
        initExecutor();
    }

    // 初始化线程池
    private synchronized void initExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
    }

    /**
     * 连接到服务端
     */
    public void connect() {
        initExecutor();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 如果已经连接，先关闭旧连接
                if (socket != null && socket.isConnected()) {
                    callback.onStatusChanged("已连接，请勿重复连接");
                    return;
                }

                LocalSocket localSocket = null;
                try {
                    callback.onStatusChanged("正在连接 FM 服务...");

                    localSocket = new LocalSocket();
                    LocalSocketAddress address = new LocalSocketAddress(
                            SOCKET_NAME,
                            LocalSocketAddress.Namespace.ABSTRACT
                    );

                    localSocket.connect(address);

                    socket = localSocket;
                    writer = new PrintWriter(new OutputStreamWriter(localSocket.getOutputStream()), true);
                    reader = new BufferedReader(new InputStreamReader(localSocket.getInputStream()));

                    Log.i(TAG, "连接成功");
                    callback.onStatusChanged("✅ 连接成功");

                    // 启动接收循环
                    startReceiveLoop();

                } catch (IOException e) {
                    Log.e(TAG, "连接失败: " + e.getMessage());
                    callback.onStatusChanged("连接失败: " + e.getMessage());
                    // 如果连接失败，尝试关闭这个临时的 socket 对象
                    if (localSocket != null) {
                        try { localSocket.close(); } catch (IOException ignored) {}
                    }
                }
            }
        });
    }

    /**
     * 接收消息循环 (修复了 Crash 问题)
     */
    private void startReceiveLoop() {
        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    // 【修复点】：去掉了 socket.isClosed() 检查，避免 UnsupportedOperationException
                    // readLine() 在 socket 关闭时会抛出异常或返回 null，这就足够了
                    while (socket != null && (line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            callback.onMessageReceived(line + "\n");
                        }
                    }
                } catch (IOException e) {
                    // 这种情况通常是 socket 被关闭（正常断开或异常断开）
                    Log.w(TAG, "读取循环结束: " + e.getMessage());
                } finally {
                    callback.onStatusChanged("连接已断开");
                    // 确保资源被清理，但不销毁线程池，允许重连
                    closeSocketResources();
                }
            }
        });
        receiveThread.start();
    }

    /**
     * 发送命令
     */
    public void sendCommand(final String command) {
        initExecutor();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // --- 核心修改：自动重连逻辑 ---
                if (!isConnected()) {
                    Log.w(TAG, "检测到连接断开，尝试自动重连并发送: " + command);
                    // 1. 同步执行连接过程（因为已经在 executor 线程池里了）
                    performConnectSync();

                    // 2. 连接后再次检查
                    if (!isConnected()) {
                        callback.onStatusChanged("自动重连失败，指令丢失");
                        return;
                    }
                }

                // --- 原有的发送逻辑 ---
                try {
                    writer.println(command);
                    if (writer.checkError()) {
                        Log.e(TAG, "发送失败，清理资源");
                        closeSocketResources();
                    } else {
                        Log.d(TAG, "已发送: " + command);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "发送异常", e);
                    closeSocketResources();
                }
            }
        });
    }

    /**
     * 提取一个同步连接的方法，供内部调用
     */
    private void performConnectSync() {
        LocalSocket localSocket = null;
        try {
            localSocket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress(
                    SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT);
            localSocket.connect(address);

            socket = localSocket;
            writer = new PrintWriter(new OutputStreamWriter(localSocket.getOutputStream()), true);
            reader = new BufferedReader(new InputStreamReader(localSocket.getInputStream()));

            startReceiveLoop(); // 重启接收监听
            Log.i(TAG, "自动重连成功");
        } catch (IOException e) {
            Log.e(TAG, "自动重连失败: " + e.getMessage());
        }
    }

    /**
     * 仅关闭 Socket 相关资源
     */
    private synchronized void closeSocketResources() {
        if (socket != null) {
            try {
                // 关闭 Socket 会导致 readLine() 抛出异常从而退出循环
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭 Socket 异常", e);
            } finally {
                socket = null;
                writer = null;
                reader = null;
            }
        }
    }

    public boolean isConnected() {
        // 增加 try-catch 保护，防止某些固件上 isClosed() 崩溃
        try {
            return socket != null && socket.isConnected() && !socket.isClosed();
        } catch (Exception e) {
            return socket != null && socket.isConnected();
        }
    }

    /**
     * 彻底关闭（退出 App 时调用）
     */
    public void close() {
        closeSocketResources();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        executor = null;
    }
}
