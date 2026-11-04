#!/system/bin/sh
# 标准 BLE 外设：Battery Service 0x180F（0x1A19 电量）+ 自定义命令通道（PSK 门控）
# 广播是静态的（名字 + 16 位服务 UUID + TX Power），因此不再需要 --refresh


# ── 启动前结束已存在的 BLEGattd 实例，避免双实例互抢广播/回调重复 ──
/system/bin/pkill -9 -f BLEGattd 2>/dev/null
i=0
while [ $i -lt 20 ]; do                    # 最多等 2 秒让旧进程真正退出
    pgrep -f BLEGattd >/dev/null 2>&1 || break
    sleep 0.1
    i=$((i+1))
done




PSK="pwd"

DIR="$(cd "$(dirname "$0")" && pwd)"
export CLASSPATH="$DIR/blegatt.jar"
exec app_process /system/bin BLEGattd \
  --name Rolex \
  --adv-svc 180f \
  --battery-notify 60 \
  --split-servers \
  --psk "$PSK" \
  --on-connect 'echo "this is connected command."' \
  "$@"
