#!/usr/bin/env bash
# 手工编译 Android APK（RecyclerView 版：内置 androidx 依赖，零 Gradle）
# 用法: ./build_rv.sh   （需 ANDROID_HOME / JAVA_HOME=JDK11+，且 JDK 在 PATH 前）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

BUILD="/tmp/adb_filemanager_rv_build"
OUT="$HERE/FileManager.apk"

BN_FILE="$HERE/.build_no"
BN=1
if [ -f "$BN_FILE" ]; then BN=$(( $(cat "$BN_FILE") + 1 )); fi
echo "$BN" > "$BN_FILE"
VER_NAME="2.51.$(printf '%04d' "$BN")"
VER_CODE=$(( 2510000 + BN ))

echo "==> 清理"; rm -rf "$BUILD"; mkdir -p "$BUILD/flat" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> [1] aapt2 编译资源（app + androidx lib res）"
"$AAPT2" compile --dir "$HERE/res" -o "$BUILD/res.zip"
LIBRES_ZIPS=()
for d in "$HERE"/libs/res/*/; do
  [ -d "$d" ] || continue
  name="$(basename "$d")"
  "$AAPT2" compile --dir "$d" -o "$BUILD/libres_$name.zip"
  LIBRES_ZIPS+=("$BUILD/libres_$name.zip")
done

echo "==> [2] aapt2 链接"
MAN="$BUILD/manifest.xml"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VER_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER_NAME\"/" \
    "$HERE/AndroidManifest.xml" > "$MAN"
"$AAPT2" link -o "$BUILD/base.apk" -I "$AJ" --manifest "$MAN" --java "$BUILD/gen" \
  -A "$HERE/assets" --min-sdk-version 24 --target-sdk-version 34 \
  --extra-packages androidx.recyclerview:androidx.core:androidx.customview:androidx.lifecycle:androidx.versionedparcelable:androidx.collection:androidx.annotation:androidx.arch.core \
  "$BUILD/res.zip" "${LIBRES_ZIPS[@]}"

echo "==> [2.5] aidl"
FRAME="$SDK/platforms/android-34/framework.aidl"
for f in IPlugin IArchiveProvider IViewer; do
  "$BT/aidl" -I "$HERE/src" -p "$FRAME" -o "$BUILD/gen" \
    "$HERE/src/com/myapp/myfilemanager/plugin/$f.aidl" || exit 1
done

# androidx classpath
LIBCP=""
for j in "$HERE"/libs/jar/*.jar; do LIBCP="$LIBCP:$j"; done

echo "==> [3] javac"
find "$HERE/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$AJ" -classpath "$AJ$LIBCP" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> [4] d8（app classes + androidx jars）"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$D8" --lib "$AJ" --min-api 24 --output "$BUILD/dex" @"$BUILD/classes.txt" "$HERE"/libs/jar/*.jar

echo "==> [5] 打包"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/unsigned.apk" classes.dex)

echo "==> [6] zipalign"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> [7] 签名"
KS="$HERE/debug.keystore"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --out "$OUT" "$BUILD/aligned.apk"

echo "==> 验证"
"$APKSIGNER" verify "$OUT" >/dev/null 2>&1 && echo "signature OK"
echo "本次版本: $VER_NAME ($VER_CODE)  构建号 #$BN"
echo "APK: $OUT"; ls -lh "$OUT"
