#!/system/bin/sh
: <<'EOF'
因为 `LocationManagerService` 对 “谁在请求定位” 做了一层包名归属校验，而 `app_process` 拿到的系统 Context 的包名永远固定为 `android` 所以要切换到 uid 1000 读不了 `/data/data/com.termux/
`su 1000` 切换到的 system 身份无法进入该目录，
脚本可以留在 `$HOME`，但 jar 必须放到 /data/local/tmp

参数	含义	默认

-1	拿到一个 fix 就打印并退出（等价 -c 1）	不限
-c N	收满 N 个 fix 后退出	一直跑到超时
-t MS	总超时，毫秒	60000
-p NAME	指定 provider	gps
-n	同时打印 GNSS HAL 吐上来的原始 NMEA 语句	关
-v	打印卫星明细（SVID、星座、C/N0、方位角、高度角）	关

EOF

JAR=/data/local/tmp/gpscli.jar
if [ ! -r "$JAR" ]; then
  su -c 'cp $HOME/gpscli.jar /data/local/tmp/gpscli.jar; chmod 644 /data/local/tmp/gpscli.jar'
  echo "jar not found: $JAR (已经执行: su -c 'cp $HOME/gpscli.jar /data/local/tmp/gpscli.jar; chmod 644 /data/local/tmp/gpscli.jar') 如果不行请手动运行命令."
  exit 1
fi
exec su -c "su 1000 -c \"app_process -Djava.class.path=$JAR /system/bin GpsCli $*\""
