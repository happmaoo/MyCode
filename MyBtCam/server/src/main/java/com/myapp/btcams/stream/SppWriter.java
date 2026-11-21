package com.myapp.btcams.stream;

import android.util.Log;

import com.myapp.btcam.common.BtCamProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 单条 SPP 链路的写出器。
 *
 * 设计要点：
 * 1. 所有报文只由本类的一个写线程发出，保证报文不会交错（SPP 没有消息边界）。
 * 2. 控制报文（状态/握手/配置）走独立队列且优先发送，避免视频积压把控制报文堵住。
 * 3. 视频队列很浅（6 帧）。队列满说明链路已经跟不上编码速度，此时直接丢弃整段，
 *    并进入「只等下一个 IDR」状态：丢掉非关键帧会让参考链断裂，客户端花屏一整段，
 *    所以要么全发，要么从下一个 IDR 重新开始。
 */
public class SppWriter {

    private static final String TAG = "SppWriter";

    public interface Listener {
        void onWriterError(Exception e);
    }

    private static class Item {
        final int type;
        final int flags;
        final byte[] payload;
        final int seq;
        final int pts;

        Item(int type, int flags, byte[] payload, int seq, int pts) {
            this.type = type;
            this.flags = flags;
            this.payload = payload;
            this.seq = seq;
            this.pts = pts;
        }
    }

    private final OutputStream out;
    private final Listener listener;
    private final ArrayBlockingQueue<Item> controlQueue = new ArrayBlockingQueue<>(24);
    private final ArrayBlockingQueue<Item> videoQueue = new ArrayBlockingQueue<>(6);

    private volatile boolean running = true;
    private volatile boolean dropUntilIdr = false;
    private Thread thread;

    public SppWriter(OutputStream out, Listener listener) {
        this.out = out;
        this.listener = listener;
    }

    public void start() {
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "BtCamWriter");
        thread.start();
    }

    /** 视频帧入口：满了就丢帧，丢掉后只发下一段 IDR 起的帧。 */
    public void submit(Frame frame) {
        if (!running || frame == null) {
            return;
        }
        if (dropUntilIdr) {
            if (!frame.idr) {
                return;
            }
            dropUntilIdr = false;
        }
        if (!videoQueue.offer(new Item(BtCamProtocol.T_VIDEO,
                frame.idr ? BtCamProtocol.FLAG_IDR : 0, frame.data, frame.seq, frame.pts))) {
            // 链路跟不上：清掉积压，从下一个关键帧重新对齐
            int dropped = videoQueue.size();
            videoQueue.clear();
            if (frame.idr) {
                videoQueue.offer(new Item(BtCamProtocol.T_VIDEO,
                        BtCamProtocol.FLAG_IDR, frame.data, frame.seq, frame.pts));
            } else {
                dropUntilIdr = true;
            }
            Log.w(TAG, "link too slow, dropped " + dropped + " frames, wait next IDR");
        }
    }

    /**
     * 链路是否已经跟不上：队列里还有积压的帧。
     *
     * 注意不要用 dropUntilIdr 判断：等 IDR 时如果把编码也停掉，
     * 编码器就永远不会产出那个 IDR，会死锁成一片黑屏。
     */
    public boolean congested() {
        return videoQueue.size() >= 2;
    }

    /** 待发视频帧数（调试用） */
    public int queueSize() {
        return videoQueue.size();
    }

    /** 参数切换前调用：丢掉旧码流的积压帧，并从下一个 IDR 重新开始。 */
    public void resetVideo() {
        videoQueue.clear();
        dropUntilIdr = true;
    }

    public void sendStatus(String text, int level) {
        controlQueue.offer(new Item(BtCamProtocol.T_STATUS, level,
                BtCamProtocol.statusPayload(text), 0, 0));
    }

    public void sendAck(int result, String reason) {
        controlQueue.offer(new Item(BtCamProtocol.T_HELLO_ACK, 0,
                BtCamProtocol.ackPayload(result, reason), 0, 0));
    }

    public void sendConfig(int width, int height, byte[] sps, byte[] pps) {
        controlQueue.offer(new Item(BtCamProtocol.T_CONFIG, 0,
                BtCamProtocol.configPayload(width, height, sps, pps), 0, 0));
    }

    public void sendPong(int clientTs) {
        // 回显客户端的 ping 时刻 + 服务端收到时刻，客户端据此估算两机时钟偏差
        controlQueue.offer(new Item(BtCamProtocol.T_PONG, 0,
                BtCamProtocol.pongPayload(clientTs,
                        android.os.SystemClock.elapsedRealtime()), 0, 0));
    }

    private void loop() {
        while (running) {
            try {
                Item item = controlQueue.poll();
                if (item == null) {
                    item = videoQueue.poll(150, TimeUnit.MILLISECONDS);
                }
                if (item == null) {
                    continue;
                }
                write(item);
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                Log.w(TAG, "write failed: " + e);
                if (listener != null) {
                    listener.onWriterError(e);
                }
                break;
            }
        }
        running = false;
    }

    private void write(Item item) throws IOException {
        if (item.type == BtCamProtocol.T_VIDEO) {
            // 一帧一次性写出去：头 + seq + 负载合成一个数组。
            // 以前是 3 次 write（9 字节头 / 4 字节 seq / 负载），RFCOMM 每写一次都要单独
            // 组帧并走一次流控，小包会把 990 字节的 MTU 浪费掉一大半，实测直接拖掉吞吐。
            int len = 8 + item.payload.length;
            byte[] packet = new byte[BtCamProtocol.HEADER_SIZE + len];
            packet[0] = (byte) (BtCamProtocol.MAGIC >> 8);
            packet[1] = (byte) BtCamProtocol.MAGIC;
            packet[2] = BtCamProtocol.VERSION;
            packet[3] = (byte) item.type;
            packet[4] = (byte) item.flags;
            packet[5] = (byte) (len >>> 24);
            packet[6] = (byte) (len >>> 16);
            packet[7] = (byte) (len >>> 8);
            packet[8] = (byte) len;
            packet[9] = (byte) (item.seq >>> 24);
            packet[10] = (byte) (item.seq >>> 16);
            packet[11] = (byte) (item.seq >>> 8);
            packet[12] = (byte) item.seq;
            int pts = item.pts;
            packet[13] = (byte) (pts >>> 24);
            packet[14] = (byte) (pts >>> 16);
            packet[15] = (byte) (pts >>> 8);
            packet[16] = (byte) pts;
            System.arraycopy(item.payload, 0, packet, 17, item.payload.length);
            out.write(packet);
        } else {
            BtCamProtocol.writeMessage(out, item.type, item.flags, item.payload);
        }
        out.flush();
        countSent(item);
    }

    private long sentBytes;
    private long sentFrames;
    private long statAt;

    private void countSent(Item item) {
        sentBytes += BtCamProtocol.HEADER_SIZE + (item.payload == null ? 0 : item.payload.length)
                + (item.type == BtCamProtocol.T_VIDEO ? 8 : 0);
        if (item.type == BtCamProtocol.T_VIDEO) {
            sentFrames++;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (statAt == 0) {
            statAt = now;
            return;
        }
        if (now - statAt >= 2000) {
            int kbs = (int) (sentBytes * 1000 / (now - statAt) / 1024);
            int fps = (int) (sentFrames * 1000 / (now - statAt));
            Log.i(TAG, "link up " + kbs + " KB/s (" + fps + " fps, avg "
                    + (sentFrames > 0 ? sentBytes / sentFrames : 0) + " B/frame)");
            sentBytes = 0;
            sentFrames = 0;
            statAt = now;
        }
    }

    /** 关闭写线程；socket 关闭后阻塞中的 write 会抛异常退出。 */
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
