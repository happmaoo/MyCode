#!/bin/bash

cmd2=$(cat <<EOF
git add .;git commit -m "备份 $(date +'%Y-%m-%d %H:%M:%S')";git push;bash
EOF
)

cmd=$(cat <<EOF
bash -c "$cmd2"
EOF
)

xfce4-terminal -e "$cmd" -T "Run and ready"

#xfce4-terminal -e 'bash -c "git add .;git commit -m "备份";git push;bash"' -T "Run and ready"
