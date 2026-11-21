#!/bin/bash
# 不用 Gradle / AndroidX，直接 aapt2 + javac + d8 + zipalign + apksigner 出 APK
# 关键：javac 失败必须立刻终止；每次构建用全新 obj/dex 目录，绝不复用旧产物
set -e

# ---- 工具链路径（沙箱实测）----
JDK=/root/.sdkman/candidates/java/current/bin
SDK=/root/sdk/android-14
BT=$SDK                       # aapt2 / apksigner / zipalign / lib/d8.jar 都在 android-14 下
PLAT=/root/sdk/android-11/android.jar
APP=/root/work/app_project/app
B=/tmp/blebuild
MIN=21
TGT=30

# ---- 前置检查 ----
for f in "$BT/aapt2" "$BT/apksigner" "$BT/zipalign" "$BT/lib/d8.jar" "$PLAT" "$APP/AndroidManifest.xml"; do
  [ -e "$f" ] || { echo "!! 缺少工具/文件: $f"; exit 1; }
done

STAMP=$(date +%s%N)
OBJ="$B/obj-$STAMP"
DEXDIR="$B/dex-$STAMP"
mkdir -p "$OBJ" "$DEXDIR" "$B/gen"

# ---- 版本号注入：manifest versionName=2.2.YYYYMMDDHHMM，versionCode=Unix秒；源码 __BUILD_DATE__ 占位符同上 ----
NOW=$(date '+%Y%m%d%H%M')
VC=$(date '+%s')
echo "[*] 版本: 2.2.$NOW (versionCode=$VC)"
python3 - "$APP/AndroidManifest.xml" "$B/AndroidManifest.xml" "$VC" "2.2.$NOW" <<'PYEOF'
import sys
src, dst, vc, vn = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
s = open(src, encoding='utf-8').read()
s = s.replace('android:versionCode="1"', 'android:versionCode="%s"' % vc)
s = s.replace('android:versionName="2.2.000000000000"', 'android:versionName="%s"' % vn)
open(dst, 'w', encoding='utf-8').write(s)
PYEOF
rm -rf "$B/src"; cp -r "$APP/src" "$B/src"
sed -i "s#__BUILD_DATE__#$NOW#g" $(find "$B/src" -name '*.java')

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$B/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$B/base.apk" -I "$PLAT" --manifest "$B/AndroidManifest.xml" \
  --java "$B/gen" --min-sdk-version $MIN --target-sdk-version $TGT "$B/res.zip"

echo "[3/6] javac"
SRCS=$(find "$B/src" -name '*.java')
if ! "$JDK/javac" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLAT" \
      -d "$OBJ" "$B/gen/com/myapp/mybleclient/R.java" $SRCS > "$B/javac.log" 2>&1; then
  echo "!! javac 失败，终止构建："
  grep -E "error:" -A3 "$B/javac.log" | head -30
  exit 1
fi
for need in MainActivity BleService P Settings; do
  if [ ! -f "$OBJ/com/myapp/mybleclient/$need.class" ]; then
    echo "!! 产物缺少 $need.class，终止构建"; exit 1
  fi
done
echo "  编译产物：$(find "$OBJ" -name '*.class' | wc -l) 个 class（全新目录 $OBJ，无历史残留）"

echo "[4/6] d8"
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api $MIN --lib "$PLAT" \
  --output "$DEXDIR" $(find "$OBJ" -name '*.class')

echo "[5/6] 合包 classes.dex"
python3 - "$B/base.apk" "$DEXDIR/classes.dex" "$B/unsigned.apk" <<'PYEOF'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zout.writestr(it, zin.read(it.filename))
zout.write(dex, 'classes.dex')      # dex 未压缩存储，利于 ART 直接 mmap
zout.close(); zin.close()
PYEOF
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "[6/6] 签名 -> /workspace/MyBleClient.apk"
# 必须用覆盖安装用的旧 keystore（jks2 / alias d2），否则签名不匹配、无法无缝升级
KS=/tmp/blebuild/debug.jks2
KS_ALIAS=d2
if [ ! -f "$KS" ]; then
  echo "!! 缺少覆盖安装 keystore: $KS（找不到就无法无缝覆盖已装版本）"; exit 1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass pass:android --key-pass pass:android \
  --out /workspace/MyBleClient.apk "$B/aligned.apk"
"$BT/apksigner" verify --print-certs /workspace/MyBleClient.apk | grep -i "SHA-256" | head -1
ls -lh /workspace/MyBleClient.apk
