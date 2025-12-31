#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/videodev2.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>

// === V4L2 定义保持不变 ===
#define V4L2_CID_PRV_BASE           0x8000000
#define V4L2_CID_PRV_STATE          (V4L2_CID_PRV_BASE + 4)
#define V4L2_CID_PRV_REGION         (V4L2_CID_PRV_BASE + 7)
#define V4L2_CID_PRV_CHAN_SPACING   (V4L2_CID_PRV_BASE + 14)
#define V4L2_CID_PRV_AUDIO_PATH     (V4L2_CID_PRV_BASE + 41)

enum fm_states { FM_OFF = 0, FM_RECV = 1 };
enum FM_AUDIO_PATH { AUDIO_DIGITAL_PATH = 0, AUDIO_ANALOG_PATH = 1 };
#define TUNE_MULT 16000
#define SOCKET_NAME "fm_service" // Socket 名称

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
        printf("Error setting %s: %s\n", name, strerror(errno));
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
        printf("[INIT] Running %s...\n", bin);
        snprintf(cmd, sizeof(cmd), "%s &", bin);
        run_cmd(cmd);
        for(int i=0; i<10; i++) { if(check_init_property()) return 0; usleep(200000); }
    }
    return enable_transport_layer() ? 0 : -1;
}

// 获取信号并返回字符串给客户端
void get_signal_info(int fd, char* output_buf, size_t size) {
    struct v4l2_tuner tuner;
    memset(&tuner, 0, sizeof(tuner));
    tuner.index = 0;
    
    if (ioctl(fd, VIDIOC_G_TUNER, &tuner) == 0) {
        snprintf(output_buf, size, "SIGNAL_RSSI:%d", tuner.signal);
    } else {
        snprintf(output_buf, size, "ERROR:Get_Signal_Failed");
    }
}

// === Socket 处理逻辑 ===

// 简单的响应发送函数
void send_response(int client_fd, const char* msg) {
    write(client_fd, msg, strlen(msg));
    write(client_fd, "\n", 1); // 添加换行符方便 Java readLine()
}

void handle_client_command(int radio_fd, int client_fd, char* cmd) {
    char response[256] = {0};
    float freq_mhz = 0;
    int val = 0;

    // 1. 调频指令: TUNE 98.7
    if (strncmp(cmd, "TUNE", 4) == 0) {
        if (sscanf(cmd + 5, "%f", &freq_mhz) == 1) {
            struct v4l2_frequency freq = { 
                .tuner = 0, 
                .type = V4L2_TUNER_RADIO, 
                .frequency = (int)(freq_mhz * TUNE_MULT) 
            };
            if (ioctl(radio_fd, VIDIOC_S_FREQUENCY, &freq) < 0) {
                snprintf(response, sizeof(response), "ERROR:Tune_Failed");
            } else {
                // 等待锁定并返回信号
                usleep(300000); 
                char sig_buf[64];
                get_signal_info(radio_fd, sig_buf, sizeof(sig_buf));
                snprintf(response, sizeof(response), "OK:Tuned_%.1f|%s", freq_mhz, sig_buf);
            }
        } else {
            strcpy(response, "ERROR:Invalid_Freq_Format");
        }
    }
    // 2. 静音: MUTE
    else if (strncmp(cmd, "MUTE", 4) == 0) {
        set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");
        strcpy(response, "OK:Muted");
    }
    // 3. 取消静音: UNMUTE
    else if (strncmp(cmd, "UNMUTE", 6) == 0) {
        set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
        strcpy(response, "OK:Unmuted");
    }
    // 4. 设置区域: REGION 0
    else if (strncmp(cmd, "REGION", 6) == 0) {
        if (sscanf(cmd + 7, "%d", &val) == 1) {
            set_control(radio_fd, V4L2_CID_PRV_REGION, val, "REGION");
            snprintf(response, sizeof(response), "OK:Region_Set_%d", val);
        }
    }
    // 5. 设置步进: SPACING 1
    else if (strncmp(cmd, "SPACING", 7) == 0) {
        if (sscanf(cmd + 8, "%d", &val) == 1) {
            set_control(radio_fd, V4L2_CID_PRV_CHAN_SPACING, val, "SPACING");
            snprintf(response, sizeof(response), "OK:Spacing_Set_%d", val);
        }
    }
    // 6. 获取信号: SIGNAL
    else if (strncmp(cmd, "SIGNAL", 6) == 0) {
        get_signal_info(radio_fd, response, sizeof(response));
    }
    // 7. 关闭服务: QUIT (完全退出进程)
    else if (strncmp(cmd, "QUIT", 4) == 0) {
        strcpy(response, "OK:Stopping_Service");
        send_response(client_fd, response);
        exit(0); // 退出整个程序
    }
    else {
        strcpy(response, "ERROR:Unknown_Command");
    }

    send_response(client_fd, response);
}

int main(int argc, char **argv) {
    int radio_fd;
    int socket_fd, client_fd;
    struct sockaddr_un server_addr;

    printf("=== FM Server Service V1.0 ===\n");
    
    // 1. 初始化 FM 硬件
    radio_fd = open("/dev/radio0", O_RDWR);
    if (radio_fd < 0) { perror("Open Radio"); return -1; }

    init_firmware(radio_fd);

    if (set_control(radio_fd, V4L2_CID_PRV_STATE, FM_RECV, "FM_RECV") < 0) {
        close(radio_fd); return -1;
    }
    
    printf("Hardware stabilizing...\n");
    sleep(1);

    // 默认设置
    set_control(radio_fd, V4L2_CID_PRV_AUDIO_PATH, AUDIO_DIGITAL_PATH, "DIGITAL_PATH");
    set_control(radio_fd, V4L2_CID_PRV_REGION, 0, "REGION");
    set_control(radio_fd, V4L2_CID_PRV_CHAN_SPACING, 1, "SPACING");
    // 默认先静音，等待指令
    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE"); 

    // 2. 创建 Socket
    socket_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (socket_fd < 0) { perror("Socket"); return -1; }

    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sun_family = AF_UNIX;
    // 关键点：抽象命名空间，第一个字节是 \0
    server_addr.sun_path[0] = 0;
    strncpy(server_addr.sun_path + 1, SOCKET_NAME, sizeof(server_addr.sun_path) - 2);

    if (bind(socket_fd, (struct sockaddr*)&server_addr, sizeof(sa_family_t) + strlen(SOCKET_NAME) + 1) < 0) {
        perror("Bind"); return -1;
    }

    if (listen(socket_fd, 5) < 0) { perror("Listen"); return -1; }

    printf("Listening on abstract socket: %s\n", SOCKET_NAME);

    // 3. 主循环：接受连接并处理
    while (1) {
        client_fd = accept(socket_fd, NULL, NULL);
        if (client_fd < 0) {
            perror("Accept");
            continue;
        }

        // 读取命令
        char buf[1024];
        int len;
        // 注意：这里简单处理，假设一次 read 包含完整指令。
        // 如果需要长连接多次交互，可以在这里加个 while 循环
        memset(buf, 0, sizeof(buf));
        while ((len = read(client_fd, buf, sizeof(buf) - 1)) > 0) {
            buf[len] = '\0';
            // 移除换行符
            char *pos;
            if ((pos = strchr(buf, '\n')) != NULL) *pos = '\0';
            if ((pos = strchr(buf, '\r')) != NULL) *pos = '\0';

           if (strlen(buf) > 0) {

    			// 打印原始内容
    			printf("[CMD] Received (raw): \"%s\"\n", buf);
			
    			// 以HEX方式打印，便于确认格式
    			printf("[CMD] HEX: ");
    			for (int i = 0; buf[i] != '\0'; i++) {
        			printf("%02X ", (unsigned char)buf[i]);
    			}
    			printf("\n");
			
    			handle_client_command(radio_fd, client_fd, buf);
			}

            // 处理完一条指令后，如果 App 保持连接，可以继续 read
            // 这里我们假设短连接或者 App 自己维护流
            // 清空 buffer 准备下一次
            memset(buf, 0, sizeof(buf));
        }
        
        close(client_fd);
    }

    // 清理（一般走不到这，除非 QUIT 指令触发 exit）
    close(socket_fd);
    set_control(radio_fd, V4L2_CID_PRV_STATE, FM_OFF, "FM_OFF");
    close(radio_fd);
    return 0;
}
