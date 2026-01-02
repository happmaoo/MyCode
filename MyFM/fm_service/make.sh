#!/bin/bash

# 将命令写入临时脚本
cmd_script=$(mktemp)
cat > "$cmd_script" << 'EOF'
#!/bin/bash
rm fm_service
make
sleep 10
EOF

chmod +x "$cmd_script"

# 执行命令
xfce4-terminal -e "bash $cmd_script" -T "make"



