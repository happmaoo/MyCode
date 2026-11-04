#!/system/bin/sh
# MyBleClientCli —— 命令行 BLE 客户端：连服务端、发命令、收结果
#
# 用法:
#   su -c './MyBleClientCli.sh --server Rolex --psk pwd --strip-rc --cmd "dumpsys deviceidle"'
#   su -c './MyBleClientCli.sh --server Cannon --psk pwd --strip-rc --cmd "id"'                     # 按名字扫
#   su -c './MyBleClientCli.sh --server 04:D1:3A:56:0E:5F --psk pwd --strip-rc --cmd "id; uname -r"' # MAC 直连
#
# 扫描注意：Android 的 native ScanManager 在息屏时会挂起「不带 filter 的扫描」
#   （logcat 里那行 Cannot start unfiltered scan in screen-off），应用侧表现就是一个广播都收不到。
#   本工具默认在扫描前发一次 KEYCODE_WAKEUP 点亮屏幕；不想动屏幕加 --no-wake，
#   无人值守的正道是直接给 MAC（--server <MAC>），连接路径不看屏幕状态。
#
# 取结果：正文走 stdout，日志走 stderr，所以 $() 只会拿到正文。
# 正文首行是服务端加的「#rc=<对端命令退出码>」，想要纯输出加 --strip-rc（rc 转记到 stderr）。
#   R=$(su -c '/data/data/com.termux/files/home/MyBleClientCli.sh --server Cannon --psk pwd --strip-rc --cmd "date"')
#   echo "服务端时间: $R"
# 判定成败看退出码，别拿字符串判空（命令本身可能就没输出）。
#
# 从宿主机取结果时不要嵌套双引号 —— adb shell -> su -c 会吃掉一层，
# 结果变成「客户端只收到 id，后面半截在本机跑了」。写成设备端脚本再调它：
#   adb -s <rolex> shell su -c /data/local/tmp/clitest.sh
#
# PAN（连对端蓝牙共享，本机当 PANU 客户端）：
#   su -c '/data/data/com.termux/files/home/MyBleClientCli.sh --server Cannon --pan'
#   su -c '/data/data/com.termux/files/home/MyBleClientCli.sh --server Cannon --pan-disconnect'
# stdout 给「网卡名 地址」，例如 `bt-pan 192.168.44.210/24`；DHCP/DNS/路由由安卓自己做好。
# --pan 已经连着时再跑一次算成功（幂等，方便看门狗）；--pan 与 --cmd 同时给 = 先连 PAN 再发命令。
# 前提：装好 PanCli.apk（工程 ~/MyCode/MyBle/PanApk/，bash build.sh && adb install -r PanCli.apk）、
# 两边已配对、对端「蓝牙网络共享」开着。注意安卓会把 PAN 抬成默认网络，网关上行的蜂窝会切到对端。
#
# 退出码: 0=成功 1=超时/参数错 2=服务端错误帧 3=连接失败/断开

DIR=$(dirname "$0")
export CLASSPATH=$DIR/MyBleClientCli.jar
exec /system/bin/app_process /system/bin MyBleClientCli "$@"
