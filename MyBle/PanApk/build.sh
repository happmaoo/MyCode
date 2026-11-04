#!/usr/bin/env bash
# 构建 PanCli.apk —— 无 Gradle：aapt2 link → javac → d8 → zip 塞 classes.dex → zipalign → apksigner
# 踩过的坑都避开了：每次全新 obj/dex 目录、javac 非零即终止（不拿管道吞错误）、keystore 用 JKS
set -euo pipefail
cd "$(dirname "$0")"

SDK=/home/happmaoo/Android/Sdk
PLATFORM=$SDK/platforms/android-28/android.jar
AAPT2=$SDK/build-tools/34.0.0/aapt2
D8=$SDK/build-tools/29.0.3/d8            # 34 的 d8 要 Java 11，本机默认是 JDK8
ZIPALIGN=$SDK/build-tools/34.0.0/zipalign
APKSIGNER=$SDK/build-tools/34.0.0/apksigner
KS=pan.jks
KS_ALIAS=pan
KS_PASS=pan12345

OBJ=obj
DEX=dex
rm -rf $OBJ $DEX build
mkdir -p $OBJ $DEX build

echo "[1/6] aapt2 link"
"$AAPT2" link -o build/base.apk --manifest AndroidManifest.xml -I "$PLATFORM"

echo "[2/6] javac"
javac -nowarn -Xlint:-options -source 1.8 -target 1.8 -bootclasspath "$PLATFORM" \
      -d $OBJ $(find src -name '*.java')
[ -f "$OBJ/com/qw/pancli/PanRcv.class" ] && [ -f "$OBJ/com/qw/pancli/Pan.class" ] \
      || { echo "编译没产出 PanRcv/Pan.class"; exit 1; }
echo "      class 数: $(find $OBJ -name '*.class' | wc -l)"

echo "[3/6] d8 → classes.dex"
"$D8" --lib "$PLATFORM" --min-api 21 --output $DEX $(find $OBJ -name '*.class')
[ -f "$DEX/classes.dex" ] || { echo "d8 没产出 classes.dex"; exit 1; }

echo "[4/6] 把 classes.dex 塞进 apk"
cp build/base.apk build/unaligned.apk
( cd $DEX && zip -q ../build/unaligned.apk classes.dex )

echo "[5/6] zipalign"
"$ZIPALIGN" -f 4 build/unaligned.apk build/aligned.apk

echo "[6/6] 签名（JKS，别用 PKCS12，apksigner on JDK8 读不了）"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storetype JKS -alias "$KS_ALIAS" \
          -keyalg RSA -keysize 2048 -validity 10000 \
          -storepass "$KS_PASS" -keypass "$KS_PASS" \
          -dname "CN=PanCli, OU=dev, O=local, L=local, S=local, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" \
                  --key-pass "pass:$KS_PASS" --out PanCli.apk build/aligned.apk
"$APKSIGNER" verify --print-certs PanCli.apk | head -3

rm -rf $OBJ $DEX build
echo "OK -> $(pwd)/PanCli.apk ($(stat -c%s PanCli.apk) 字节)"
