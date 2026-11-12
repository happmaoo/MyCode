#!/usr/bin/env bash
# 手工编译 Android APK（零 Gradle / 零第三方依赖）
# 用法: ./build.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
export JAVA_HOME="${JAVA_HOME:-/root/.sdkman/candidates/java/current}"

# 编译中间产物放到工程目录外（/tmp），避免污染 /workspace 的「产物」面板
BUILD="/tmp/adb_filemanager_build"
# 成果物统一输出到 /workspace 根目录，与 WorkBuddy「产物」面板展示的文件保持一致
OUT="$HERE/FileManager.apk"

# 自增构建号：每次构建 +1，让 versionCode / versionName 单调递增。
# 目的：避免「重装同版本号」导致系统拒绝更新、界面看起来「没变」「没菜单」。
# 版本名形如 2.50.0001（base.minor.构建号），versionCode = 2500000 + 构建号。
BN_FILE="$HERE/.build_no"
BN=1
if [ -f "$BN_FILE" ]; then BN=$(( $(cat "$BN_FILE") + 1 )); fi
echo "$BN" > "$BN_FILE"
VER_BASE="2.50"
VER_NAME="${VER_BASE}.$(printf '%04d' "$BN")"   # 例如 2.50.0001
VER_CODE=$(( 2500000 + BN ))                     # 例如 2500001

echo "==> 清理"
rm -rf "$BUILD"
mkdir -p "$BUILD/flat" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> [1/7] aapt2 编译资源"
"$AAPT2" compile --dir "$HERE/res" -o "$BUILD/res.zip"

echo "==> [2/7] aapt2 链接，生成基础 APK 与 R.java"
# 本环境 aapt2 的 --version-code/--version-name 不会覆盖 manifest，
# 因此先 sed 出一个带自增版本号的临时 manifest 再链接（不动源文件）。
MAN="$BUILD/manifest.xml"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VER_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER_NAME\"/" \
    "$HERE/AndroidManifest.xml" > "$MAN"
grep -E "android:versionCode|android:versionName" "$MAN"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$AJ" \
  --manifest "$MAN" \
  --java "$BUILD/gen" \
  -A "$HERE/assets" \
  --min-sdk-version 24 --target-sdk-version 34 \
  "$BUILD/res.zip"

echo "==> [2.5/7] aidl 编译插件契约"
FRAME="$SDK/platforms/android-34/framework.aidl"
for f in IPlugin IArchiveProvider IViewer; do
  "$BT/aidl" -I "$HERE/src" -p "$FRAME" -o "$BUILD/gen" \
    "$HERE/src/com/myapp/myfilemanager/plugin/$f.aidl" || exit 1
done

echo "==> [3/7] javac 编译 Java 源码"
find "$HERE/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVA_HOME/bin/javac" \
  -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> [4/7] d8 生成 classes.dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$D8" --lib "$AJ" --min-api 24 --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "==> [5/7] 打包 dex 进 APK"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/unsigned.apk" classes.dex)

echo "==> [6/7] zipalign 对齐"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> [7/7] 签名"
# 签名密钥固定在工程目录，避免清理 build 后重签导致无法覆盖安装
KS="$HERE/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=MyFileman,O=MyFileman,C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$BUILD/aligned.apk"

echo "==> 验证"
"$APKSIGNER" verify --verbose "$OUT" > "$BUILD/verify.txt" 2>&1
cat "$BUILD/verify.txt"
"$AAPT2" dump badging "$OUT" > "$BUILD/badging.txt" 2>&1
grep -E "^(package:|sdkVersion|targetSdkVersion|application-label|launchable-activity|uses-permission|providers)" "$BUILD/badging.txt"

echo
echo "本次版本: versionName=$VER_NAME  versionCode=$VER_CODE  (构建号 #$BN)"
echo "APK 已生成: $OUT"
ls -lh "$OUT"
