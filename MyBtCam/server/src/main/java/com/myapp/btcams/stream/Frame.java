package com.myapp.btcams.stream;

/**
 * 一帧 H.264 数据。
 * seq 由服务端对「每一次编码出帧」连续分配，与是否真正发出无关，
 * 客户端据此判断中间是否被丢弃过。
 */
public class Frame {
    public final int seq;
    public final boolean idr;
    public final byte[] data;
    /** 采集时刻（服务端 elapsedRealtime 毫秒的低 32 位），客户端据此算端到端延迟 */
    public final int pts;

    public Frame(int seq, boolean idr, byte[] data, int pts) {
        this.seq = seq;
        this.idr = idr;
        this.data = data;
        this.pts = pts;
    }
}
