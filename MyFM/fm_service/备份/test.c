#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <linux/videodev2.h>
#include <time.h>
#include <errno.h>

#include <sys/stat.h>
#include <android/log.h>
#include <signal.h>

#include <dlfcn.h> // 用于 dlopen/dlsym


#define SOCKET_NAME "fm_service"
#define PUSH_INTERVAL_MS 1000  // 1秒推送一次
// 定义日志标签
#define LOG_TAG "MyFM-Log"
//#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
// 同时输出到 Android 日志和标准输出
#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); \
    fprintf(stdout, "[INFO] %s: ", LOG_TAG); \
    fprintf(stdout, __VA_ARGS__); \
    fflush(stdout); \
} while(0)

int radio_fd  = -1;
int socket_fd = -1;
int client_fd;


// === V4L2 定义 来自 radio-iris-commands.h ===
#define V4L2_CID_PRV_BASE           0x8000000
#define V4L2_CID_PRV_STATE          (V4L2_CID_PRV_BASE + 4)
#define V4L2_CID_PRV_REGION         (V4L2_CID_PRV_BASE + 7)
#define V4L2_CID_PRV_CHAN_SPACING   (V4L2_CID_PRV_BASE + 14)
#define V4L2_CID_PRV_AUDIO_PATH     (V4L2_CID_PRV_BASE + 41)
#define V4L2_CID_PRIVATE_IRIS_GET_SINR    (V4L2_CID_PRIVATE_BASE + 0x2C)
#define V4L2_CID_PRIVATE_IRIS_ANTENNA   0x08000012 //天线必须为0



enum fm_states { FM_OFF = 0, FM_RECV = 1 };
enum FM_AUDIO_PATH { AUDIO_DIGITAL_PATH = 0, AUDIO_ANALOG_PATH = 1 };
#define TUNE_MULT 16000
#define SOCKET_NAME "fm_service" // Socket 名称


//-----------------------------------------------------------------

// === 辅助函数 ===
int run_cmd(const char *cmd) { return system(cmd); }
int file_exists(const char *path) { struct stat b; return (stat(path, &b) == 0); }

int enable_transport_layer() {
    int fd = open("/sys/module/radio_iris_transport/parameters/fmsmd_set", O_WRONLY);
    if (fd < 0) return 0;
    write(fd, "1", 1);
    close(fd);
    sleep(1);
    return 1;
}

int check_init_property() {
    FILE *fp = popen("getprop vendor.hw.fm.init", "r");
    char path[1035];
    int result = 0;
    if (fp && fgets(path, sizeof(path), fp)) 
        if (strstr(path, "1")) result = 1;
    if (fp) pclose(fp);
    return result;
}

// 修改 set_control 以便我们可以获取返回值状态
int set_control(int fd, int id, int value, const char* name) {
    struct v4l2_control ctrl = { .id = id, .value = value };
    if (ioctl(fd, VIDIOC_S_CTRL, &ctrl) < 0) {
        LOGI("Error setting %s: %s\n", name, strerror(errno));
        LOGI("Error setting %s: %s\n", name, strerror(errno));
        return -1;
    }
    return 0;
}

int init_firmware(int fd) {
    struct v4l2_capability cap;
    char cmd[256];
    const char *bins[] = {"/vendor/bin/fm_dl", "/system/bin/fm_dl", NULL};
    const char *bin = NULL;

    if (ioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) return -1;
    snprintf(cmd, sizeof(cmd), "setprop vendor.hw.fm.version %d", cap.version);
    run_cmd(cmd);
    run_cmd("setprop vendor.hw.fm.mode normal");
    run_cmd("setprop vendor.hw.fm.init 0");

    for (int i = 0; bins[i]; i++) if (file_exists(bins[i])) bin = bins[i];

    if (bin) {
        LOGI("[INIT] Running %s...\n", bin);
        LOGI("[INIT] Running %s...\n", bin);
        snprintf(cmd, sizeof(cmd), "%s &", bin);
        run_cmd(cmd);
        for(int i=0; i<10; i++) { if(check_init_property()) return 0; usleep(200000); }
    }
    return enable_transport_layer() ? 0 : -1;
}


//-----------------------------------------------------------------

// 设置非阻塞
void set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

// 获取当前时间（毫秒）
long long get_time_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

// 获取信号强度
void get_signal_strength(int radio_fd, char* buffer, int size) {
    struct v4l2_tuner tuner;
    memset(&tuner, 0, sizeof(tuner));
    tuner.index = 0;
    
    if (ioctl(radio_fd, VIDIOC_G_TUNER, &tuner) == 0) {
        snprintf(buffer, size, "PUSH|RSSI:%d|STEREO:%d|AUDIO:%s",
                tuner.signal,
                (tuner.rxsubchans & V4L2_TUNER_SUB_STEREO) ? 1 : 0,
                (tuner.audmode == V4L2_TUNER_MODE_STEREO) ? "STEREO" : "MONO");
    } else {
        snprintf(buffer, size, "PUSH|ERROR:无法获取信号");
    }
}

// 处理客户端（带实时推送）
void handle_client(int radio_fd, int client_fd) {
    char buf[1024];
    long long last_push_time = get_time_ms();
    long long last_command_time = get_time_ms();

    float freq_mhz = 0;
    int sinr_val = -1;
    
    LOGI("live_push\n");
    
    // 设置为非阻塞
    set_nonblock(client_fd);
    
    // 发送初始状态
    write(client_fd, "FM_SERVICE|MODE:LIVE_PUSH|INTERVAL:1000\n", 44);
    
    while (1) {
        long long now = get_time_ms();
        
        // === 1. 检查并处理客户端命令（非阻塞） ===
        memset(buf, 0, sizeof(buf));
        int len = read(client_fd, buf, sizeof(buf)-1);
        
        if (len > 0) {
            // 收到命令
            buf[len] = '\0';
            
            // 移除换行符
            char *nl = strchr(buf, '\n');
            if (nl) *nl = '\0';
            
            LOGI("📨 命令: %s\n", buf);
            last_command_time = now;
            
            // 处理命令
            if (strcmp(buf, "QUIT") == 0) {
                write(client_fd, "OK|SHUTDOWN\n", 12);
                break;
            }
            
            if (strcmp(buf, "PUSH_STOP") == 0) {
                write(client_fd, "OK|PUSH_STOPPED\n", 16);
                // 可以改为不推送的模式，这里简单处理
                continue;
            }
            
            if (strcmp(buf, "PUSH_START") == 0) {
                write(client_fd, "OK|PUSH_STARTED\n", 16);
                last_push_time = now;  // 重置推送时间
                continue;
            }
            
            // 其他FM命令
            // 1. 调频指令: TUNE 98.7
            if (strncmp(buf, "TUNE", 4) == 0) {
                if (sscanf(buf + 5, "%f", &freq_mhz) == 1) {
                    struct v4l2_frequency freq = { 
                        .tuner = 0, 
                        .type = V4L2_TUNER_RADIO, 
                        .frequency = (int)(freq_mhz * TUNE_MULT) 
                    };
                    if (ioctl(radio_fd, VIDIOC_S_FREQUENCY, &freq) < 0) {
                        write(client_fd, "tune ok", 10);
                        //snprintf(response, sizeof(response), "ERROR:Tune_Failed");
                    } else {
                        // 等待锁定并返回信号
                        usleep(300000);         
                    }
                } else {
                    write(client_fd, "ERROR:Invalid_Freq_Format", 25);
                    LOGI("Invalid_Freq_Format");
                }
            }
        } 
        else if (len == 0) {
            // 客户端主动断开
            LOGI("客户端断开连接\n");
            break;
        }
        // len < 0 且 errno == EAGAIN 表示没数据，继续
        
        // === 2. 检查是否需要推送信号强度 ===
        long long elapsed = now - last_push_time;
        if (elapsed >= PUSH_INTERVAL_MS) {
            // 获取信号强度
            char signal_msg[256];
            get_signal_strength(radio_fd, signal_msg, sizeof(signal_msg));
            
            // 添加时间戳
            char push_msg[300];
            snprintf(push_msg, sizeof(push_msg), "%s|TIME:%lld\n", 
                    signal_msg, now);
            
            // 发送给客户端
            int written = write(client_fd, push_msg, strlen(push_msg));
            
            if (written < 0) {
                if (errno == EPIPE || errno == ECONNRESET) {
                    LOGI("写入失败，客户端可能已断开\n");
                    break;
                }
                // 其他错误忽略，继续尝试
            } else {
                LOGI("📡 推送: %s\n", signal_msg);
            }
            
            last_push_time = now;
        }
        
        // === 3. 智能休眠（关键优化！） ===
        // 计算到下次推送还需等待多久
        long long next_push_in = PUSH_INTERVAL_MS - (now - last_push_time);
        
        if (next_push_in > 0) {
            // 计算合理的休眠时间
            // 最小10ms，最大 next_push_in
            long long sleep_ms = next_push_in > 10 ? 10 : next_push_in;
            
            // 如果有命令刚处理完，减少休眠以便快速响应
            if (now - last_command_time < 100) {  // 最近100ms内有命令
                sleep_ms = sleep_ms > 5 ? 5 : sleep_ms;  // 更短休眠
            }
            
            usleep(sleep_ms * 1000);  // 转换为微秒
        }
        // 如果 next_push_in <= 0，表示立即需要推送，不休眠
    }
    
    close(client_fd);
    LOGI("服务结束\n");
}

// 主函数
int main() {
    // 1. 初始化 FM 硬件
    radio_fd = open("/dev/radio0", O_RDWR);
    if (radio_fd < 0) { perror("Open Radio"); return -1; }

    init_firmware(radio_fd);

    if (set_control(radio_fd, V4L2_CID_PRV_STATE, FM_RECV, "FM_RECV") < 0) {
        close(radio_fd); return -1;
    }
    
    LOGI("Hardware stabilizing...\n");
    sleep(1);

    // 默认设置
    set_control(radio_fd, V4L2_CID_PRV_AUDIO_PATH, AUDIO_DIGITAL_PATH, "DIGITAL_PATH");
    set_control(radio_fd, V4L2_CID_PRV_REGION, 0, "REGION");
    set_control(radio_fd, V4L2_CID_PRV_CHAN_SPACING, 1, "SPACING");



    // 默认先静音，等待指令
    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE"); 
    
    // 2. 创建UNIX Socket
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("创建socket失败");
        return -1;
    }
    
    // 绑定到抽象命名空间
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = 0;
    strncpy(addr.sun_path + 1, SOCKET_NAME, sizeof(addr.sun_path)-2);
    
    if (bind(server_fd, (struct sockaddr*)&addr, 
             sizeof(addr.sun_family) + strlen(SOCKET_NAME) + 1) < 0) {
        perror("绑定socket失败");
        return -1;
    }
    
    listen(server_fd, 1);  // 只允许1个客户端等待
    
    LOGI("等待FM App连接...\n");
    
    // 3. 接受客户端连接
    int client_fd = accept(server_fd, NULL, NULL);
    if (client_fd < 0) {
        perror("接受连接失败");
        return -1;
    }
    
    LOGI("✅ 客户端已连接\n");
    
    // 4. 处理客户端（带实时推送）
    handle_client(radio_fd, client_fd);
    
    // 5. 清理
    close(server_fd);
    close(radio_fd);
    
    LOGI("👋 服务关闭\n");
    return 0;
}
