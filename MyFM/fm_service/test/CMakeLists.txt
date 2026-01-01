# 这行必须放在第一行
cmake_minimum_required(VERSION 3.10.2)

# 设置 Android 参数
#set(CMAKE_TOOLCHAIN_FILE ${ANDROID_NDK}/build/cmake/android.toolchain.cmake)
set(ANDROID_ABI armeabi-v8a)      # 或者 armeabi-v7a, x86, x86_64
set(ANDROID_PLATFORM android-25)



project(fm_service)
set(ANDROID_NDK /home/happmaoo/Android/Sdk/ndk/20.0.5594570)
include_directories(${ANDROID_NDK}/sysroot/usr/include)


# 使用NDK的toolchain
set(CMAKE_TOOLCHAIN_FILE ${ANDROID_NDK}/build/cmake/android.toolchain.cmake)

# 设置Android属性
set(ANDROID_ABI arm64-v8a)
set(ANDROID_PLATFORM android-24)
set(ANDROID_STL c++_static)

# 设置工具链
set(CMAKE_C_COMPILER ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang)
set(CMAKE_CXX_COMPILER ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++)

# 创建可执行文件
add_executable(fm_service fm_service.c)

# 链接 Android 日志库
target_link_libraries(fm_service android log)
