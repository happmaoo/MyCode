#!/bin/bash

#xfce4-terminal -e 'bash -c "git add .;git commit -m "备份";git push;bash"' -T "Run and ready"
# 说明，先复制config文件到.git目录覆盖，然后再运行本脚本






mdate=$(date +'%Y-%m-%d-%H-%M-%S')

# 使用 head -n 10 限制输出行数，避免参数超长
xfce4-terminal -e "bash -c 'git add .; echo \"部分变更列表:\"; git status --short | head -n 10; git commit -m \"备份 $mdate\"; git tag $mdate; git push; git push --tags; echo \"完成.\"; sleep 5'"


