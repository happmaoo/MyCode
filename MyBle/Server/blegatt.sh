#!/system/bin/sh

: <<'COMMENT'

 用法以 BLEGattd.java 内为准

参数分类清单
① 广播 / 设备识别

参数	默认值	作用
--name <str>	r4a	广播名/设备名(--name 显式给出才会改蓝牙适配器名)
--adv-mode <m> / --mode <m>	low_power	广播模式:low_power(≈1s) / balanced(≈250ms) / low_latency(≈100ms)。两写法等价
--adv-tx <p> / --tx <p>	medium	发射功率:ultra_low / low / medium / high。两写法等价
--timeout <sec>	0	广播自动停止秒数,0=一直播
--non-connectable	关	改为发不可连接广播
--adv-only	关	只广播、不起 GATT server
--keep-adv	关(默认连上即停)	连上后继续广播;断开自动重启广播
--no-name-in-adv	关(默认带名字)	广播包不含设备名
--no-txpower-in-adv	关(默认带)	广播包不含 TX power
--no-svcuuid-in-adv	关	广播包不含服务 UUID
② 广播载荷 / 厂商数据

参数	默认值	作用
--mfg <id:hex>	0xABCD:无	厂商数据,如 fff0:0102aabb
--mfg-data <hex>	无	仅厂商数据(不带 id)
--adv-svc <uuid>	无	把某服务 UUID 放进广播(标准 16 位,如 180f)
--adv-text <模板>	无	广播包厂商数据载荷模板,占位符 {b}电量 {v}电压 {t}温度 {c}连接数 {u}运行秒 {n}名字
--sr-text <模板>	无	Scan Response 的独立 31 字节载荷
--refresh <sec>	0	>0 时按模板自动刷新广播内容(不掉已连客户端)
③ GATT 服务 / 特征 UUID

参数	默认值	作用
--svc <uuid>	8e7a1f01-2b3c-4d5e-6f70-8192a3b4c5d6	主(cmd)服务 UUID,支持 4 位简写如 abcd
--char-write <uuid>	8e7a1f03-...	CMD(写命令)特征 UUID
--char-notify <uuid>	8e7a1f04-...	RES(结果通知)特征 UUID
--char-auth <uuid>	8e7a1f05-...	AUTH(鉴权)特征 UUID
--battery-notify <s>	60	Battery Level 轮询秒数,变化才推;0=关闭
--batt-first	关	先注册 Battery 服务(排查"只映射第一个服务"缺陷)
--split-servers	关	Battery 用独立 GATT server 实例(同上排查)
④ 命令执行 / 鉴权

参数	默认值	作用
--psk <key>	无	预共享密钥;非空则命令通道强制先 AUTH 后 CMD
--exec	关	写 CMD 特征的内容直接当 shell 执行(无鉴权,危险,仅测试)
--on-connect <cmd>	无	客户端一连上就自动执行该命令,结果排队等其订阅通知
--max-out <bytes>	10240(10KB)	单条命令输出上限,超出截断
--cmd-timeout <sec>	10	单条命令最长执行时间(超时强杀)
--log-out <bytes>	1024	设备日志打印 exec 结果的上限,0=不打印
⑤ 传输节奏 / 链路调优

参数	默认值	作用
--send-timeout <ms>	2000	等 onNotificationSent 回调的超时,超时判定丢回调并自愈
--pace <ms>	50	判定栈不上报回调后的节流片间隔
--tx-pace <ms>	40	传输帧最小间隔下限(片间至少 40ms,连发易丢包)
⑥ 调试 / 其他

参数	默认值	作用
--echo	关	写特征内容原样弹回通知(验证通知链路)
--sub-dump	关	pushBattery 时打印订阅表诊断
--uid <n>	保持 root	进程内 setuid 后再跑(默认保持 root)
-h / --help	—	打印帮助并退出

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
