#!/system/bin/sh
# MyBleClientCli —— 命令行 BLE 客户端：连服务端、发命令、收结果
#
# 用法:
#   su -c './MyBleClientCli.sh --server Rolex --psk pwd --cmd "dumpsys deviceidle"'
#   su -c './MyBleClientCli.sh --server 04:D1:3A:56:0E:5F --psk pwd --cmd "id; uname -r"'
#
# 结果走 stdout，日志走 stderr。脚本内取结果示例:
#   R=$(su -c './MyBleClientCli.sh --server Rolex --psk pwd --cmd "date"')
#   echo "服务端时间: $R"
#
# 退出码: 0=成功 1=超时/参数错 2=服务端错误帧 3=连接失败/断开

DIR=$(dirname "$0")
export CLASSPATH=$DIR/MyBleClientCli.jar
exec app_process /system/bin MyBleClientCli "$@"
