#!/bin/bash
date=date +"%Y-%m-%d %H:%M:%S"
xfce4-terminal -e 'bash -c "git add .;git commit -m "备份";git push;bash"' -T "Run and ready"
