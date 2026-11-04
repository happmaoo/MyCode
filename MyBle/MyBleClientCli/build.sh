#!/usr/bin/env bash
# 构建 MyBleClientCli.jar —— 里面只有一个 classes.dex，靠 CLASSPATH + app_process 直载
# 教训：每次都用全新 obj 目录，javac 非零立刻终止（管道 grep 会吞掉编译错误，产出脏 jar）
set -euo pipefail
cd "$(dirname "$0")"

SDK=/home/happmaoo/Android/Sdk
PLATFORM=${PLATFORM:-$SDK/platforms/android-28/android.jar}
D8=${D8:-$SDK/build-tools/29.0.3/d8}
OBJ="obj.$$"

[ -f "$PLATFORM" ] || { echo "缺 android.jar: $PLATFORM"; exit 1; }
rm -rf "$OBJ" build.dex MyBleClientCli.jar.new
mkdir -p "$OBJ"

echo "[1/3] javac"
javac -nowarn -Xlint:-options -source 1.8 -target 1.8 -bootclasspath "$PLATFORM" \
      -d "$OBJ" MyBleClientCli.java

# 关键 class 必须存在，否则说明编译其实没成功
[ -f "$OBJ/MyBleClientCli.class" ] || { echo "编译产物缺失 MyBleClientCli.class"; exit 1; }
echo "      class 数: $(find "$OBJ" -name '*.class' | wc -l)"

echo "[2/3] d8 -> classes.dex (min-api 21)"
mkdir -p build.dex
"$D8" --lib "$PLATFORM" --min-api 21 --output build.dex "$OBJ"/*.class
[ -f build.dex/classes.dex ] || { echo "d8 没产出 classes.dex"; exit 1; }

echo "[3/3] 打包 jar"
cp build.dex/classes.dex "$OBJ/classes.dex"
( cd "$OBJ" && zip -X -q ../MyBleClientCli.jar.new classes.dex )
mv MyBleClientCli.jar.new MyBleClientCli.jar
rm -rf "$OBJ" build.dex

unzip -l MyBleClientCli.jar
echo "OK -> $(pwd)/MyBleClientCli.jar ($(stat -c%s MyBleClientCli.jar) 字节)"
