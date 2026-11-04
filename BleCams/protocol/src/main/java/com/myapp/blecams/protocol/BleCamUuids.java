package com.myapp.blecams.protocol;

import java.util.UUID;

/**
 * BleCam 使用的 GATT 服务 / 特征定义（服务端注册，客户端按此订阅）。
 *
 * <p>广播里只放 {@link #SERVICE}（128bit，占 16 字节），设备名放在 scan response 中，
 * 这样 adv 包刚好能装进 31 字节。
 */
public final class BleCamUuids {

    private BleCamUuids() {
    }

    /** 视频流服务。 */
    public static final UUID SERVICE = UUID.fromString("c8a2f101-1c3e-4f5a-9b7d-6e0a1b2c3d40");

    /** 视频流通道：notify（服务端 -> 客户端），载荷见 {@link StreamProtocol}。 */
    public static final UUID CHAR_VIDEO_STREAM = UUID.fromString("c8a2f102-1c3e-4f5a-9b7d-6e0a1b2c3d40");

    /** 控制通道：write / write-no-response（客户端 -> 服务端），载荷见 {@link StreamProtocol} 命令表。 */
    public static final UUID CHAR_CONTROL = UUID.fromString("c8a2f103-1c3e-4f5a-9b7d-6e0a1b2c3d40");

    /** 状态通道：read，返回 {@link StatusInfo} 序列化的 key=value 文本。 */
    public static final UUID CHAR_STATUS = UUID.fromString("c8a2f104-1c3e-4f5a-9b7d-6e0a1b2c3d40");

    /** 标准 CCCD 描述符，客户端必须写入 ENABLE_NOTIFY 才会开始收流。 */
    public static final UUID DESC_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** adv / gatt server 使用的名称。 */
    public static final String DEVICE_NAME = "BleCam";
}
