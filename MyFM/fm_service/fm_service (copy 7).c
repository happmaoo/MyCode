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

//这个设置搜索阈值好像不支持，没有使用
#define V4L2_CID_PRIVATE_IRIS_SIGNAL_TH (V4L2_CID_PRV_BASE + 8)
#define V4L2_CID_PRIVATE_IRIS_SRCHON          (V4L2_CID_PRV_BASE + 3)



enum fm_states { FM_OFF = 0, FM_RECV = 1 };
enum FM_AUDIO_PATH { AUDIO_DIGITAL_PATH = 0, AUDIO_ANALOG_PATH = 1 };
#define TUNE_MULT 16000
#define SOCKET_NAME "fm_service" // Socket 名称

struct v4l2_hw_freq_seek v4l_seek;
struct v4l2_frequency    v4l_freq = {0};
float curr_freq_mhz = 0.0;
int dir;
int push_enabled = 0;




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


// =========== seek =========
int seek(int fd,int dir) {
    push_enabled = 0;
    // 1. 获取并保存原始频率
    struct v4l2_frequency orig_freq = {0};
    orig_freq.tuner = 0;
    orig_freq.type = V4L2_TUNER_RADIO;

    if (ioctl(fd, VIDIOC_G_FREQUENCY, &orig_freq) < 0) {
        perror("Failed to get original frequency");
        close(fd);
        return EXIT_FAILURE;
    }

    double orig_mhz = orig_freq.frequency/16 / 1000.0;
    LOGI("Original frequency: %.2f MHz\n", orig_mhz);

    int ret = 0;
    struct v4l2_hw_freq_seek v4l_seek; // 标准v4l2函数
    LOGI ("seek dir: %d", dir);

    // 初始化 v4l_seek
    memset(&v4l_seek, 0, sizeof(v4l_seek)); // 最好先清零
    v4l_seek.tuner = 0;
    v4l_seek.type = V4L2_TUNER_RADIO;
    v4l_seek.wrap_around = 1;
    v4l_seek.seek_upward = (dir != 0); // 确保是 0 或 1
    v4l_seek.spacing = 0; // 使用默认步进

    ret = ioctl(fd, VIDIOC_S_HW_FREQ_SEEK, &v4l_seek);

    if (ret < 0) {
        LOGI("VIDIOC_S_HW_FREQ_SEEK error: %d", ret);
        return -1;
    }

    LOGI ("VIDIOC_S_HW_FREQ_SEEK success");


    // 4. 轮询检测频率是否变化
    struct v4l2_frequency curr_freq = {0};
    curr_freq.tuner = 0;
    curr_freq.type = V4L2_TUNER_RADIO;

    const int max_attempts = 20;   // 最多尝试
    const int delay_ms = 300;      // 每次间隔

    int found = 0;
    for (int i = 0; i < max_attempts; i++) {
        usleep(delay_ms * 1000);

        if (ioctl(fd, VIDIOC_G_FREQUENCY, &curr_freq) < 0) {
            perror("Failed to get current frequency during polling");
            break;
        }

        // 如果频率发生变化，说明搜索已停在一个新台
        if (curr_freq.frequency != orig_freq.frequency) {
            found = 1;
            break;
        }
    }

    // 5. 输出结果
    curr_freq_mhz = curr_freq.frequency / 16 / 1000.0;
    if (found) {
        LOGI("✅ Seek succeeded! Tuned to:  %.2f MHz\n", curr_freq_mhz);
    } else {
        LOGI("⚠️ Search timeout or no station found. Still at:  %.2f MHz\n", curr_freq_mhz);
    }
    push_enabled = 1;
    return curr_freq_mhz;
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

// 获取信号信息
void get_signal_info(int radio_fd, char* buffer, int size) {
    struct v4l2_tuner tuner;
    struct v4l2_frequency freq;
    memset(&tuner, 0, sizeof(tuner));
    memset(&freq, 0, sizeof(freq));
    tuner.index = 0;
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    
    // 获取频率信息
    if (ioctl(radio_fd, VIDIOC_G_FREQUENCY, &freq) == 0) {
        if (ioctl(radio_fd, VIDIOC_G_TUNER, &tuner) == 0) {
            double frequency_mhz = (double)freq.frequency / 16 / 1000.0;
            
            snprintf(buffer, size, "FREQ:%.1fMHz|RSSI:%d",
                    frequency_mhz,
                    tuner.signal-139);
        } else {
            // 仅获取频率成功，但获取信号信息失败
            double frequency_mhz = (double)freq.frequency / 16 / 1000.0;
            snprintf(buffer, size, "FREQ:%.1fMHz|ERROR:无法获取信号信息",
                    frequency_mhz);
        }
    } else {
        // 无法获取频率信息
        snprintf(buffer, size, "PUSH|ERROR:无法获取频率和信号");
    }
}
// 处理客户端（带实时推送）
void handle_client(int radio_fd, int client_fd) {
    char cmd[1024];
    long long last_push_time = get_time_ms();
    // 初始化为 0，意味着程序刚启动时处于静默状态，直到收到第一条指令
    long long last_command_time = 0; 


    float freq_mhz = 0;
    int dir = 0;
    
    LOGI("FM Service handle_client: 准备就绪，等待指令以激活推送\n");
    
    // 设置非阻塞，确保 read 不会卡住整个循环
    set_nonblock(client_fd);
    
    // 发送欢迎消息给客户端
    write(client_fd, "FM_SERVICE|STATE:READY\n", 40);
    
    while (1) {
        long long now = get_time_ms();
        
        // === 1. 检查并处理客户端发来的指令 ===
        memset(cmd, 0, sizeof(cmd));
        int len = read(client_fd, cmd, sizeof(cmd) - 1);
        
        if (len > 0) {
            cmd[len] = '\0';
            // 移除换行符
            char *nl = strchr(cmd, '\n');
            if (nl) *nl = '\0';
            char *cr = strchr(cmd, '\r');
            if (cr) *cr = '\0';

            if (strlen(cmd) > 0) {


                // --- 指令处理开始 ---
                if (strcmp(cmd, "QUIT") == 0) {
                    write(client_fd, "OK|SHUTDOWN\n", 12);
                    break;
                }
                else if (strncmp(cmd, "PUSH", 4) == 0) {
                    int val = 0;
                    if (sscanf(cmd + 4, "%d", &val) == 1) {
                        push_enabled = (val == 1);
                        LOGI("PUSH: %s\n", push_enabled ? "ON" : "OFF");
                        char* resp = push_enabled ? "OK|PUSH_ON\n" : "OK|PUSH_OFF\n";
                        write(client_fd, resp, strlen(resp));
                    }
                }
                else if (strncmp(cmd, "TUNE", 4) == 0) {
                    if (sscanf(cmd + 5, "%f", &freq_mhz) == 1) {
                        struct v4l2_frequency freq = { 
                            .tuner = 0, 
                            .type = V4L2_TUNER_RADIO, 
                            .frequency = (int)(freq_mhz * TUNE_MULT) 
                        };
                        ioctl(radio_fd, VIDIOC_S_FREQUENCY, &freq);
                        set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
                        write(client_fd, "OK|TUNED\n", 9);
                    }
                }
                else if (strcmp(cmd, "MUTE") == 0) {
                    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");
                    write(client_fd, "OK|MUTED\n", 9);
                }
                else if (strcmp(cmd, "UNMUTE") == 0) {
                    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
                    write(client_fd, "OK|UNMUTED\n", 11);
                }
                else if (strncmp(cmd, "SEEK", 4) == 0) {
                    if (sscanf(cmd + 5, "%d", &dir) == 1) {
                        float new_freq = seek(radio_fd, dir);
                        char buf[32];
                        snprintf(buf, sizeof(buf), "OK|SEEK:%.2f\n", new_freq);
                        write(client_fd, buf, strlen(buf));
                    }
                }
                // --- 指令处理结束 ---
            }
        } else if (len == 0) {
            LOGI("客户端断开连接\n");
            break;
        } else {
            // len < 0 (EAGAIN), 无数据，继续执行推送逻辑
        }


        long long elapsed_since_last_push = now - last_push_time;

        if (elapsed_since_last_push >= PUSH_INTERVAL_MS) {
            if (push_enabled) {
                char signal_msg[256];
                get_signal_info(radio_fd, signal_msg, sizeof(signal_msg));
                
                char push_msg[300];
                int push_len = snprintf(push_msg, sizeof(push_msg), "%s\n", signal_msg);
                
                if (write(client_fd, push_msg, push_len) < 0) {
                    if (errno == EPIPE || errno == ECONNRESET) {
                        break;
                    }
                } else {
                    LOGI("📡 实时推送: %s\n", signal_msg);
                }
            }
            last_push_time = now;
        }

        // === 3. 休眠，防止空转导致 CPU 使用率过高 ===
        // 10ms 的精度足够处理 1s 一次的推送和交互
        usleep(10000); 
    }
    
    close(client_fd);
    LOGI("handle_client 退出\n");
}

// 主函数
int main() {
    signal(SIGPIPE, SIG_IGN);
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
    //set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");
    
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
