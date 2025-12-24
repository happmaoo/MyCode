#!/bin/bash

#xfce4-terminal -e 'bash -c "git add .;git commit -m "备份";git push;bash"' -T "Run and ready"
# 说明，先复制config文件到.git目录覆盖，然后再运行本脚本

xfce4-terminal -e "bash -c 'git add .;printf \"%b\n\" \"$(git status --short)\";git commit -m \"备份 $(date +'%Y-%m-%d %H:%M:%S')\";git push;echo "完成.";sleep 5'"





