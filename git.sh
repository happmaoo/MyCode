#!/bin/bash

#xfce4-terminal -e 'bash -c "git add .;git commit -m "备份";git push;bash"' -T "Run and ready"
# 说明，先复制config文件到.git目录覆盖，然后再运行本脚本


echo "清理 .gitignore 文件中的 /build 行"
find . -name ".gitignore" -exec sed -i '/^\/build$/d' {} \;
echo "完成"


echo "apk 改名"
find /home/happmaoo/MyCode -name "*.apk" -type f | while read file; do
    dir=$(dirname "$file")
    # 提取项目名（MyCode目录下的第一级子目录名）
    project_name=$(echo "$file" | sed 's|/home/happmaoo/MyCode/||' | cut -d'/' -f1)
    # 提取原文件名（不带路径和扩展名）
    old_name=$(basename "$file" .apk)
    # 如果原文件名不是项目名，则重命名
    if [ "$old_name" != "$project_name" ]; then
        mv "$file" "$dir/${project_name}.apk"
        echo "重命名: $old_name.apk -> $project_name.apk"
    else
        echo "跳过: $project_name.apk (文件名已正确)"
    fi
done




mdate=$(date +'%Y-%m-%d-%H-%M-%S')

# 使用 head -n 10 限制输出行数，避免参数超长
xfce4-terminal -e "bash -c 'git add .; echo \"部分变更列表:\"; git status --short | head -n 10; git commit -m \"备份 $mdate\"; git tag $mdate; git push -f; git push --tags; echo \"完成.\"; sleep 5'"


