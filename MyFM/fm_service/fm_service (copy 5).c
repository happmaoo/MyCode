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
#include <android/log.h>
#include <signal.h>

#include <dlfcn.h> // 用于 dlopen/dlsym
#include <stdio.h>
#include <string.h>

// 定义日志标签
#define LOG_TAG "MyFM-Log"
//#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); \
    fprintf(stdout, "[INFO] %s: ", LOG_TAG); \
    fprintf(stdout, __VA_ARGS__); \
    fflush(stdout); \
} while(0)

int radio_fd  = -1;
int socket_fd = -1;
int client_fd;

// 信号处理函数
void cleanup(int sig) {
    if (radio_fd >= 0) {
        close(radio_fd);
        LOGI("Radio device closed.\n");
    }
    // 这里可以添加其他清理代码
    LOGI("Cleanup done. Exiting...\n");
    exit(0);  // 退出程序
}



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

struct v4l2_hw_freq_seek v4l_seek;
struct v4l2_frequency    v4l_freq = {0};
int curr_freq = 0;
int dir;

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


// ----------------------------- 客户端获取 -----------------------------------------------
// 获取信号并返回字符串给客户端
void get_signal_info(int fd, char* output_buf, size_t size) {
    struct v4l2_tuner tuner;
    memset(&tuner, 0, sizeof(tuner));
    tuner.index = 0;
    
    if (ioctl(fd, VIDIOC_G_TUNER, &tuner) == 0) {
        snprintf(output_buf, size, "RSSI:%d", tuner.signal);
    } else {
        snprintf(output_buf, size, "ERROR:Get_Signal_Failed");
    }
}

// === 辅助函数：获取 SINR 值 ===
int get_sinr(int fd) {
    struct v4l2_control ctrl = { .id = V4L2_CID_PRIVATE_IRIS_GET_SINR };

    if (ioctl(fd, VIDIOC_G_CTRL, &ctrl) < 0) {
        LOGI("Error getting SINR: %s\n", strerror(errno));
        return -1; // 返回错误代码
    }
    return ctrl.value;
}

//========== 获取频率 ===========
int freq_get(int fd) {
  int ret = 0;
  int freq = 0;

  v4l_freq.tuner = 0; // Tuner index = 0
  v4l_freq.type = V4L2_TUNER_RADIO;
  memset(v4l_freq.reserved, 0, sizeof(v4l_freq.reserved));
  ret = ioctl(fd, VIDIOC_G_FREQUENCY, &v4l_freq);

  if (ret < 0) {
    LOGI("chip_imp_freq_get VIDIOC_G_FREQUENCY errno: %d", errno);
    return -1;
  }

  freq = v4l_freq.frequency / 16;
  curr_freq = freq;

  LOGI ("freq_get VIDIOC_G_FREQUENCY success: %d", freq);

  return freq;
}

// =========== seek =========
int seek(int fd,int dir) {
  int ret = 0;
  // 假设 v4l_seek 已经被正确定义为 struct v4l2_hw_freq_seek
  struct v4l2_hw_freq_seek v4l_seek; // 建议在函数内定义

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
    LOGI("chip_imp_seek_start VIDIOC_S_HW_FREQ_SEEK error: %d", ret);
    return -1;
  }

  LOGI ("chip_imp_seek_start VIDIOC_S_HW_FREQ_SEEK success");

  // *** 改进的等待和轮询逻辑 ***
  // 理想情况下，应该使用 V4L2 事件或 v4l2_tuner 状态来判断完成。
  // 如果必须使用轮询频率变化的方法，应该设置更合理的超时和等待时间。

  int orig_freq = freq_get(fd);
  int new_freq = orig_freq;
  int ctr;
  
  // 初始等待，确保硬件有时间启动操作
  usleep(100000); // 100ms

  for (ctr = 0; ctr < 50; ctr++) { // 100ms * 50 = 5秒总超时（取决于你的硬件）
      // 检查频率是否已经变化
      new_freq = freq_get(fd);
      
      if (new_freq != orig_freq) {
          // 频率已变化，认为搜索完成
          LOGI("Frequency changed from %d to %d", orig_freq, new_freq);
          break;
      }
      
      // 如果频率未变化，继续等待
      usleep(100000); // 100ms
  }
  
  // 搜索可能仍在进行中，但我们已经等待了足够的轮询时间
  LOGI("chip_imp_seek_start complete after %d iterations. new_freq: %d", ctr, new_freq);

  // 最终获取到的频率，无论是新频率还是旧频率（如果超时）
  return freq_get(fd); 
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
    // 定义一个变量来存储 SINR 值
    int sinr_val = -1;


// ----------------------------- 客户端发来的参数 -----------------------------------------------
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
                // === 临时测试：调用获取 SINR 值 ===
                sinr_val = get_sinr(radio_fd);
                
                // 将 RSSI 和 SINR 信息都包含在响应中
                if (sinr_val >= 0) {
                    snprintf(response, sizeof(response), "FREQ:%.1f|%s|SINR:%d", freq_mhz, sig_buf, sinr_val);
                    LOGI("Tuned_%.1f|%s|SINR:%d", freq_mhz, sig_buf, sinr_val);
                } else {
                    // 如果获取 SINR 失败，则只返回 RSSI
                    snprintf(response, sizeof(response), "FREQ:%.1f|%s|SINR_ERR", freq_mhz, sig_buf);
                    LOGI("FREQ:%.1f|%s|SINR_ERR", freq_mhz, sig_buf);
                }
            }
        } else {
            strcpy(response, "ERROR:Invalid_Freq_Format");
            LOGI("Invalid_Freq_Format");
        }
    
    }
    // 2. 静音: MUTE
    else if (strncmp(cmd, "MUTE", 4) == 0) {
        set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");
        strcpy(response, "Muted");
    }
    // 3. 取消静音: UNMUTE
    else if (strncmp(cmd, "UNMUTE", 6) == 0) {
        set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
        strcpy(response, "Unmuted");
        LOGI("Unmuted");
    }
    // 4. 获取信号: SIGNAL
    else if (strncmp(cmd, "SIGNAL", 6) == 0) {
        get_signal_info(radio_fd, response, sizeof(response));
    }
    
    // 5. 获取 SINR: SINR
    else if (strncmp(cmd, "SINR", 4) == 0) {
        int sinr_val = get_sinr(radio_fd);
        if (sinr_val >= 0) {
            snprintf(response, sizeof(response), "SINR:%d", sinr_val);
            LOGI("SINR:%d", sinr_val);
        } else {
            strcpy(response, "ERROR:Get_SINR_Failed");
        }
    }

    // 9. seek
    else if (strncmp(cmd, "SEEK", 4) == 0) {
            if (sscanf(cmd + 5, "%d", &dir) == 1) {
                
                if(dir ==1){
                    curr_freq = seek(radio_fd,1);
                }else{
                    curr_freq = seek(radio_fd,0);
                }


                
                
            } else {
                write(client_fd, "ERROR:Invalid_SEEK_Format", 25);
                LOGI("Invalid_SEEK_Format");
            }
            snprintf(response, sizeof(response), "FREQ:%.1f", (float)curr_freq/1000);
    }

    // 10. 关闭服务: QUIT (完全退出进程)
    else if (strncmp(cmd, "QUIT", 4) == 0) {
        strcpy(response, "Stopping_Service");
        LOGI("Stopping_Service");
        send_response(client_fd, response);
        exit(0); // 退出整个程序
    }
    else {
        strcpy(response, "ERROR:Unknown_Command");
        LOGI("Unknown_Command");
    }

    send_response(client_fd, response);
}

int main(int argc, char **argv) {


    struct sockaddr_un server_addr;


    // 注册信号处理函数
    signal(SIGTERM, cleanup);  // 捕捉 SIGTERM 信号（killall 会发送该信号）
    signal(SIGINT,  cleanup);  // Ctrl + C 这个不行

    
    
    
    LOGI("=== FM Server Service V1.0 ===\n");
    
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

    //int antenna = get_antenna(radio_fd);
    //LOGI("ANTENNA: %d\n", antenna);

    // int myseek = seek(radio_fd,1);
    // int sinr1 = get_sinr(radio_fd);
    // LOGI("sinr2: %d\n", sinr1);
    // sleep(5);

    // int myseek2 = seek(radio_fd,1);
    // int sinr2 = get_sinr(radio_fd);
    // LOGI("sinr2: %d\n", sinr2);
    // sleep(5);

    // int myseek3 = seek(radio_fd,1);
    // int sinr3 = get_sinr(radio_fd);
    // LOGI("sinr2: %d\n", sinr3);
    // sleep(5);



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

    LOGI("Listening on abstract socket: %s\n", SOCKET_NAME);

    // 3. 主循环：接受连接并处理
    while (1) {
        client_fd = accept(socket_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) {
                LOGI("accept interrupted by signal, exiting loop");
                break;   // ← 退出 while(1)
            }
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
    			LOGI("[CMD] Received (raw): \"%s\"\n", buf);
			
    			// 以HEX方式打印，便于确认格式
    			LOGI("[CMD] HEX: ");
    			for (int i = 0; buf[i] != '\0'; i++) {
        			LOGI("%02X ", (unsigned char)buf[i]);
    			}
    			LOGI("\n");
			
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
