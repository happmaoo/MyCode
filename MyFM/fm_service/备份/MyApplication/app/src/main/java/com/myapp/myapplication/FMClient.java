package com.myapp.myapplication;


import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FMClient: 用于连接和通信的客户端类。
 * 使用 LocalSocket 连接 UNIX 抽象命名空间套接字。
 */
public class FMClient {

    private static final String TAG = "FMClient";
    // 必须和 C 代码中的 SOCKET_NAME 保持一致
    private static final String SOCKET_NAME = "fm_service";

    private LocalSocket socket = null;
    private PrintWriter writer = null;
    private BufferedReader reader = null;
    private Thread receiveThread = null;
    private final MessageCallback callback;


    // 线程池现在可以重新赋值
    private ExecutorService executor;

    /**
     * 接口：定义消息接收的回调方法
     */
    public interface MessageCallback {
        void onMessageReceived(String message);
        void onStatusChanged(String status);
    }

    public FMClient(MessageCallback callback) {
        this.callback = callback;
        // 在构造函数中初始化线程池
        initExecutor();
    }

    // 新增：初始化/重新初始化线程池的方法
    private synchronized void initExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
            Log.d(TAG, "线程池已重新初始化。");
        }
    }

    /**
     * 建立连接并启动接收循环。
     * 在后台线程中执行连接操作。
     */
    public void connect() {

        initExecutor();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                LocalSocket localSocket = null;
                try {
                    // 1. 创建 LocalSocket
                    localSocket = new LocalSocket();

                    // 2. 创建抽象命名空间的地址
                    LocalSocketAddress address = new LocalSocketAddress(
                            SOCKET_NAME,
                            LocalSocketAddress.Namespace.ABSTRACT
                    );

                    callback.onStatusChanged("尝试连接到 FM 服务端...");

                    // 3. 连接 - 阻塞操作，服务器不存在时会抛出 IOException
                    localSocket.connect(address);

                    // 4. 连接成功，设置资源并启动接收循环
                    socket = localSocket;
                    writer = new PrintWriter(new OutputStreamWriter(localSocket.getOutputStream()), true);
                    reader = new BufferedReader(new InputStreamReader(localSocket.getInputStream()));

                    Log.i(TAG, "已成功连接到 FM 服务端");
                    callback.onStatusChanged("✅ 已连接");

                    startReceiveLoop();

                } catch (IOException e) {
                    // 捕获连接失败的异常 (服务器不存在或权限问题)
                    Log.e(TAG, "连接失败: " + e.getMessage(), e);
                    callback.onStatusChanged("连接失败: 无法连接到服务 (" + e.getMessage() + ")");

                    // 确保即使连接失败，也清理掉可能已创建但未成功设置的 Socket 实例
                    if (localSocket != null) {
                        try {
                            localSocket.close();
                        } catch (IOException closeEx) {
                            Log.e(TAG, "关闭失败的 socket 异常", closeEx);
                        }
                    }

                    // 注意：这里不再调用 FMClient 级别的 close()，
                    // 因为 close() 会关闭线程池，影响下次连接。
                    // 只需要更新状态并让当前任务安全退出。

                } catch (Exception e) {
                    // 捕获所有其他意外的运行时异常，防止线程崩溃
                    Log.e(TAG, "连接过程中发生意外错误", e);
                    callback.onStatusChanged("连接过程中发生意外错误: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 持续从 Socket 中读取消息的循环。
     * 在独立的线程中运行，并阻塞等待消息。
     */
    private void startReceiveLoop() {
        // 创建并启动消息接收线程
        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    // 循环读取每一行数据
                    while (socket != null && socket.isConnected() && (line = reader.readLine()) != null) {
                        callback.onMessageReceived(line + "\n");
                    }
                } catch (IOException e) {
                    // 接收结束（例如服务端关闭连接）
                    Log.w(TAG, "接收循环结束", e);
                } finally {
                    callback.onStatusChanged("连接已断开");
                    close(); // 确保关闭资源
                }
            }
        });
        receiveThread.start();
    }

    /**
     * 向服务端发送命令（例如 "TUNE 98.7" 或 "QUIT"）。
     * @param command 要发送的字符串命令。
     */
    public void sendCommand(final String command) {
        initExecutor();
        // 在执行器线程中发送命令
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (writer != null) {
                    writer.println(command); // println 会自动添加换行符 '\n'
                    Log.d(TAG, "已发送命令: " + command);
                    callback.onStatusChanged("已发送: " + command);
                } else {
                    callback.onStatusChanged("未连接，无法发送命令");
                }
            }
        });
    }

    /**
     * 关闭所有连接资源。
     */
    public void close() {
        if (socket != null) {
            try {
                // 尝试中断接收线程
                if (receiveThread != null) {
                    receiveThread.interrupt();
                }
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭连接失败", e);
            } finally {
                socket = null;
                writer = null;
                reader = null;
                receiveThread = null;
            }
        }

        // 修改：安全关闭线程池
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        executor = null; // 设置为 null，允许重新连接时重建

        Log.i(TAG, "FMClient 连接和线程池已关闭。");
        // 关闭执行器
        executor.shutdownNow();
    }
}