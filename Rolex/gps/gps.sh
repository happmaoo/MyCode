#!/system/bin/sh
: <<'EOF'
因为 `LocationManagerService` 对 “谁在请求定位” 做了一层包名归属校验，而 `app_process` 拿到的系统 Context 的包名永远固定为 `android` 所以要切换到 uid 1000 读不了 `/data/data/com.termux/
`su 1000` 切换到的 system 身份无法进入该目录，
脚本可以留在 `$HOME`，但 jar 必须放到 /data/local/tmp
EOF

JAR=/data/local/tmp/gpscli.jar
if [ ! -r "$JAR" ]; then
  su -c 'cp $HOME/gpscli.jar /data/local/tmp/gpscli.jar; chmod 644 /data/local/tmp/gpscli.jar'
  echo "jar not found: $JAR (已经执行: su -c 'cp $HOME/gpscli.jar /data/local/tmp/gpscli.jar; chmod 644 /data/local/tmp/gpscli.jar') 如果不行请手动运行命令."
  exit 1
fi
exec su -c "su 1000 -c \"app_process -Djava.class.path=$JAR /system/bin GpsCli $*\""
