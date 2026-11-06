#!/system/bin/sh

: <<'COMMENT'

 用法以 BLEGattd.java 内为准

--name 
--adv-mode 广播模式:low_power(≈1s) / balanced(≈250ms) / low_latency(≈100ms)。两写法等价
--tx  发射功率
--battery-notify 60
--split-servers
--psk 密码
--on-connect 执行的命令
--max-out
--cmd-timeout
--tx-pace

-----------------------我的红米n9 5安卓12要带参数--split-servers 的说明------------------------------
无论安卓几都可能有这个问题，如果客户端连不上，或者连上后无法读取数据就去掉或者添加--split-servers参数.
它针对的是一个怀疑存在的 Android BLE 协议栈缺陷:一个 GATT server 上注册了多个 primary 服务时,客户端可能只发现/正确映射了第一个服务,导致另一个服务(无论是 cmd 还是 Battery)在客户端那端"看不见"或句柄错乱,在 Android 里,你每调一次 BluetoothManager.openGattServer(...),就会从蓝牙协议栈拿到一个 BluetoothGattServer 对象——这就是一个"server 实例"。它是对外提供服务的载体,你往上面 addService(...) 注册 GATT 服务、用它 notifyCharacteristicChanged(...) 推送数据。

普通模式:只 openGattServer 一次,拿到一个实例,把 cmd 服务和 Battery 服务都 add 进这同一个实例。
--split-servers 模式:openGattServer 两次,拿到两个实例。cmd 服务加进第一个,Battery 服务加进第二个(代码里的 server 和 serverBatt)。
拆开是纯安卓框架内部的账本划分,目的是绕开"一个实例挂多个服务时,栈只正确映射第一个服务"的疑似缺陷(上一轮说过)。

COMMENT


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
  "$@"
