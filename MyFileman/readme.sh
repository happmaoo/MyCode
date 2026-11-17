exit
: <<'COMMENT'
多行注释
COMMENT


# 此文件为myfileman app的脚本编写帮助文件


# APP执行脚本时会自动加入以下几个环境变量，供脚本读取,可用env查看:

$MYFM_D	#目标文件所在目录	%d
$MYFM_F	#当前目标文件的绝对路径	%f
$MYFM_N	#文件名（含扩展名）	%n


# 引入 Termux 环境
TERMUX_ENV=/data/data/com.termux/files/home/apps/myfileman/loadtermuxenv.sh
source "$TERMUX_ENV"


SCRIPT_PATH=$(readlink -f "$0")

#打开调试后会显示读取到的命令
#echo "[mycmd:debug:1]"
LOG=/sdcard/Download/.mylog.log
#发送日志位置
echo "[mycmd:log]$LOG"
: > "$LOG"  # 只在开头清一次，且要在输出 [mycmd:log] 之前
# echo自动写入日志
exec >> "$LOG" 2>&1 &


#发送按钮方法
btn() {
    local name=$1
    shift
    echo "[mycmd:btn:$name]$*"
}

#或 echo "[mycmd:btn:Extact]su --mount-master -c '$SCRIPT_PATH 7zx \"$FILE_PATH\"'"
#btn Edit "am start -a android.intent.action.VIEW -n in.mfile/com.nmmedit.nmm.TextEditorActivity -d \"$FILE_PATH\""


#发送删除按钮
echo "[mycmd:btn:strings:0]"
#发送命令结束
echo "[mycmd:exit]"


# 定义按钮要执行的长命令,接收脚本$1参数
if [ "$1" = "runbtn1" ]; then
    #echo "runbtn1 ok."
    #env;
    echo -e "────────────────────────"

    du -sh "$MYFM_P"/* "$MYFM_P"/.[!.]* 2>/dev/null | sort -rh | head -5 | awk '{n=split($2,a,"/"); print a[n]"\t"$1}' | column -t
    echo "[mycmd:btn:list big file:0]"
    exit
fi

# 发送按钮要执行的长命令
# 按钮如果要读取环境，也要先加入source "$TERMUX_ENV";
echo "[mycmd:btn:find big file]source \"$TERMUX_ENV\";du -sh $1/* .[!.]* 2>/dev/null | sort -rh | head -5"


