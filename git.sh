#!/bin/bash


#xfce4-terminal -e 'bash -c "git add .;git commit -m "备份";git push;bash"' -T "Run and ready"

xfce4-terminal -e "bash -c 'git add .;git commit -m \"备份 $(date +'%Y-%m-%d %H:%M:%S')\";git push; bash'"
