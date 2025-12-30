cmake_minimum_required(VERSION 3.10.2)

# 1. 必须在 project 之前设置工具链和基本参数
set(ANDROID_NDK /home/happmaoo/Android/Sdk/ndk/20.0.5594570)
set(CMAKE_TOOLCHAIN_FILE ${ANDROID_NDK}/build/cmake/android.toolchain.cmake)
set(ANDROID_ABI arm64-v8a)
set(ANDROID_PLATFORM android-24)
set(ANDROID_STL c++_static)

project(test)

# 2. 删掉之前所有的 CMAKE_C_COMPILER 手动设置
# 3. 删掉所有 /usr/include 的引用

# 4. 强制指定 Sysroot（如果工具链没生效，这是最后的保险）
include_directories(${ANDROID_NDK}/sysroot/usr/include)

add_executable(test test.c)
target_link_libraries(test android log)
