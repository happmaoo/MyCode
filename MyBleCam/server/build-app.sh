#!/bin/bash
# MyBleCamS 服务端构建：aapt2 + javac + d8 + zipalign + apksigner（无 Gradle / 无 AndroidX）
set -e

JDK=/root/.sdkman/candidates/java/current/bin
SDK=/root/sdk/android-14
BT=$SDK
PLAT=/root/sdk/android-11/android.jar
APP=/root/work/cam_project/server
B=/tmp/camserverbuild
MIN=25
TGT=30
PKG=com/myapp/myblecams

for f in "$BT/aapt2" "$BT/apksigner" "$BT/zipalign" "$BT/lib/d8.jar" "$PLAT" "$APP/AndroidManifest.xml"; do
  [ -e "$f" ] || { echo "!! 缺少: $f"; exit 1; }
done

STAMP=$(date +%s%N)
OBJ="$B/obj-$STAMP"; DEXDIR="$B/dex-$STAMP"
rm -rf "$OBJ" "$DEXDIR"; mkdir -p "$OBJ" "$DEXDIR" "$B/gen"

NOW=$(date '+%Y%m%d%H%M')
VC=$(date '+%s')
echo "[*] MyBleCamS 版本: 1.0.$NOW (versionCode=$VC)"
python3 - "$APP/AndroidManifest.xml" "$B/AndroidManifest.xml" "$VC" "1.0.$NOW" <<'PYEOF'
import sys
src, dst, vc, vn = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
s = open(src, encoding='utf-8').read()
s = s.replace('android:versionCode="1"', 'android:versionCode="%s"' % vc)
s = s.replace('android:versionName="1.0.000000000000"', 'android:versionName="%s"' % vn)
open(dst, 'w', encoding='utf-8').write(s)
PYEOF

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$B/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$B/base.apk" -I "$PLAT" --manifest "$B/AndroidManifest.xml" \
  --java "$B/gen" --min-sdk-version $MIN --target-sdk-version $TGT "$B/res.zip"

echo "[3/6] javac"
SRCS=$(find "$APP/src" -name '*.java')
if ! "$JDK/javac" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLAT" \
      -d "$OBJ" "$B/gen/$PKG/R.java" $SRCS > "$B/javac.log" 2>&1; then
  echo "!! javac 失败："; grep -E "error:" -A3 "$B/javac.log" | head -40; exit 1
fi
for need in MainActivity CamServer; do
  [ -f "$OBJ/$PKG/$need.class" ] || { echo "!! 缺少 $need.class"; exit 1; }
done
echo "  产物: $(find "$OBJ" -name '*.class' | wc -l) 个 class"

echo "[4/6] d8"
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api $MIN --lib "$PLAT" \
  --output "$DEXDIR" $(find "$OBJ" -name '*.class')

echo "[5/6] 合包 dex"
python3 - "$B/base.apk" "$DEXDIR/classes.dex" "$B/unsigned.apk" <<'PYEOF'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base); zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist(): zout.writestr(it, zin.read(it.filename))
zout.write(dex, 'classes.dex'); zout.close(); zin.close()
PYEOF
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "[6/6] 签名 -> /workspace/MyBleCamS.apk"
KS=/tmp/blebuild/debug.jks2; KS_ALIAS=d2
[ -f "$KS" ] || { echo "!! 缺少 keystore $KS"; exit 1; }
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass pass:android --key-pass pass:android \
  --out /workspace/MyBleCamS.apk "$B/aligned.apk"
"$BT/apksigner" verify --print-certs /workspace/MyBleCamS.apk | grep -i "SHA-256" | head -1
"$BT/aapt" dump badging /workspace/MyBleCamS.apk 2>/dev/null | grep -o "versionName='[^']*'" || true
ls -lh /workspace/MyBleCamS.apk
