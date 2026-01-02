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
#include <math.h>

#include <sys/stat.h>
#include <android/log.h>
#include <signal.h>
#include <pthread.h>
#include <dlfcn.h> // 用于 dlopen/dlsym


enum v4l2_cid_private_iris_t {
	V4L2_CID_PRIVATE_IRIS_SRCHMODE = (0x08000000 + 1),
	V4L2_CID_PRIVATE_IRIS_SCANDWELL,
	V4L2_CID_PRIVATE_IRIS_SRCHON,
	V4L2_CID_PRIVATE_IRIS_STATE,
	V4L2_CID_PRIVATE_IRIS_TRANSMIT_MODE,
	V4L2_CID_PRIVATE_IRIS_RDSGROUP_MASK,
	V4L2_CID_PRIVATE_IRIS_REGION,
	V4L2_CID_PRIVATE_IRIS_SIGNAL_TH,
	V4L2_CID_PRIVATE_IRIS_SRCH_PTY,
	V4L2_CID_PRIVATE_IRIS_SRCH_PI,
	V4L2_CID_PRIVATE_IRIS_SRCH_CNT,
	V4L2_CID_PRIVATE_IRIS_EMPHASIS,
	V4L2_CID_PRIVATE_IRIS_RDS_STD,
	V4L2_CID_PRIVATE_IRIS_SPACING,
	V4L2_CID_PRIVATE_IRIS_RDSON,
	V4L2_CID_PRIVATE_IRIS_RDSGROUP_PROC,
	V4L2_CID_PRIVATE_IRIS_LP_MODE,
	V4L2_CID_PRIVATE_IRIS_ANTENNA,
	V4L2_CID_PRIVATE_IRIS_RDSD_BUF,
	V4L2_CID_PRIVATE_IRIS_PSALL,  /*0x8000014*/

	/*v4l2 Tx controls*/
	V4L2_CID_PRIVATE_IRIS_TX_SETPSREPEATCOUNT,
	V4L2_CID_PRIVATE_IRIS_STOP_RDS_TX_PS_NAME,
	V4L2_CID_PRIVATE_IRIS_STOP_RDS_TX_RT,
	V4L2_CID_PRIVATE_IRIS_IOVERC,
	V4L2_CID_PRIVATE_IRIS_INTDET,
	V4L2_CID_PRIVATE_IRIS_MPX_DCC,
	V4L2_CID_PRIVATE_IRIS_AF_JUMP,
	V4L2_CID_PRIVATE_IRIS_RSSI_DELTA,
	V4L2_CID_PRIVATE_IRIS_HLSI, /*0x800001d*/

	/*Diagnostic commands*/
	V4L2_CID_PRIVATE_IRIS_SOFT_MUTE,
	V4L2_CID_PRIVATE_IRIS_RIVA_ACCS_ADDR,
	V4L2_CID_PRIVATE_IRIS_RIVA_ACCS_LEN,
	V4L2_CID_PRIVATE_IRIS_RIVA_PEEK,
	V4L2_CID_PRIVATE_IRIS_RIVA_POKE,
	V4L2_CID_PRIVATE_IRIS_SSBI_ACCS_ADDR,
	V4L2_CID_PRIVATE_IRIS_SSBI_PEEK,
	V4L2_CID_PRIVATE_IRIS_SSBI_POKE,
	V4L2_CID_PRIVATE_IRIS_TX_TONE,
	V4L2_CID_PRIVATE_IRIS_RDS_GRP_COUNTERS,
	V4L2_CID_PRIVATE_IRIS_SET_NOTCH_FILTER, /* 0x8000028 */
	V4L2_CID_PRIVATE_IRIS_SET_AUDIO_PATH, /* TAVARUA specific command */
	V4L2_CID_PRIVATE_IRIS_DO_CALIBRATION,
	V4L2_CID_PRIVATE_IRIS_SRCH_ALGORITHM, /* TAVARUA specific command */
	V4L2_CID_PRIVATE_IRIS_GET_SINR,
	V4L2_CID_PRIVATE_INTF_LOW_THRESHOLD,
	V4L2_CID_PRIVATE_INTF_HIGH_THRESHOLD,
	V4L2_CID_PRIVATE_SINR_THRESHOLD,
	V4L2_CID_PRIVATE_SINR_SAMPLES,
	V4L2_CID_PRIVATE_SPUR_FREQ,
	V4L2_CID_PRIVATE_SPUR_FREQ_RMSSI,
	V4L2_CID_PRIVATE_SPUR_SELECTION,
	V4L2_CID_PRIVATE_UPDATE_SPUR_TABLE,
	V4L2_CID_PRIVATE_VALID_CHANNEL,
	V4L2_CID_PRIVATE_AF_RMSSI_TH,
	V4L2_CID_PRIVATE_AF_RMSSI_SAMPLES,
	V4L2_CID_PRIVATE_GOOD_CH_RMSSI_TH,
	V4L2_CID_PRIVATE_SRCHALGOTYPE,
	V4L2_CID_PRIVATE_CF0TH12,
	V4L2_CID_PRIVATE_SINRFIRSTSTAGE,
	V4L2_CID_PRIVATE_RMSSIFIRSTSTAGE,
	V4L2_CID_PRIVATE_RXREPEATCOUNT,

	/*using private CIDs under userclass*/
	V4L2_CID_PRIVATE_IRIS_READ_DEFAULT = 0x00980928,
	V4L2_CID_PRIVATE_IRIS_WRITE_DEFAULT,
	V4L2_CID_PRIVATE_IRIS_SET_CALIBRATION,
};

#define STD_BUF_SIZE (256)

enum iris_buf_t {
	IRIS_BUF_SRCH_LIST,
	IRIS_BUF_EVENTS,
	IRIS_BUF_RT_RDS,
	IRIS_BUF_PS_RDS,
	IRIS_BUF_RAW_RDS,
	IRIS_BUF_AF_LIST,
	IRIS_BUF_PEEK,
	IRIS_BUF_SSBI_PEEK,
	IRIS_BUF_RDS_CNTRS,
	IRIS_BUF_RD_DEFAULT,
	IRIS_BUF_CAL_DATA,
	IRIS_BUF_RT_PLUS,
	IRIS_BUF_ERT,
	IRIS_BUF_SPUR,
	IRIS_BUF_MAX,
};
enum iris_evt_t {
	IRIS_EVT_RADIO_READY,
	IRIS_EVT_TUNE_SUCC,
	IRIS_EVT_SEEK_COMPLETE,
	IRIS_EVT_SCAN_NEXT,
	IRIS_EVT_NEW_RAW_RDS,
	IRIS_EVT_NEW_RT_RDS,
	IRIS_EVT_NEW_PS_RDS,
	IRIS_EVT_ERROR,
	IRIS_EVT_BELOW_TH,
	IRIS_EVT_ABOVE_TH,
	IRIS_EVT_STEREO,
	IRIS_EVT_MONO,
	IRIS_EVT_RDS_AVAIL,
	IRIS_EVT_RDS_NOT_AVAIL,
	IRIS_EVT_NEW_SRCH_LIST,
	IRIS_EVT_NEW_AF_LIST,
	IRIS_EVT_TXRDSDAT,
	IRIS_EVT_TXRDSDONE,
	IRIS_EVT_RADIO_DISABLED,
	IRIS_EVT_NEW_ODA,
	IRIS_EVT_NEW_RT_PLUS,
	IRIS_EVT_NEW_ERT,
	IRIS_EVT_SPUR_TBL,
};

const char* iris_event_names[] = {
    "IRIS_EVT_RADIO_READY",    // 0x00
    "IRIS_EVT_TUNE_SUCC",      // 0x01
    "IRIS_EVT_SEEK_COMPLETE",  // 0x02
    "IRIS_EVT_SCAN_NEXT",      // 0x03
    "IRIS_EVT_NEW_RAW_RDS",    // 0x04
    "IRIS_EVT_NEW_RT_RDS",     // 0x05
    "IRIS_EVT_NEW_PS_RDS",     // 0x06
    "IRIS_EVT_ERROR",          // 0x07
    "IRIS_EVT_BELOW_TH",       // 0x08
    "IRIS_EVT_ABOVE_TH",       // 0x09
    "IRIS_EVT_RDS_AVAIL",      // 0x0A
    "IRIS_EVT_RDS_NOT_AVAIL",  // 0x0B
    "IRIS_EVT_NEW_SRCH_LIST",  // 0x0C
    "IRIS_EVT_NEW_AF_LIST",    // 0x0D
    "IRIS_EVT_STEREO",         // 0x0E
    "IRIS_EVT_MONO",           // 0x0F
    "IRIS_EVT_TXRDSDAT",       // 0x10
    "IRIS_EVT_TXRDSDONE",      // 0x11
    "IRIS_EVT_RADIO_DISABLED", // 0x12
    "IRIS_EVT_NEW_ODA",        // 0x13
    "IRIS_EVT_NEW_RT_PLUS",    // 0x14
    "IRIS_EVT_NEW_ERT",        // 0x15
    "IRIS_EVT_SPUR_TBL",       // 0x16
};

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

enum fm_states { FM_OFF = 0, FM_RECV = 1 };
enum FM_AUDIO_PATH { AUDIO_DIGITAL_PATH = 0, AUDIO_ANALOG_PATH = 1 };
#define TUNE_MULT 16000
#define SOCKET_NAME "fm_service" // Socket 名称

struct v4l2_hw_freq_seek v4l_seek;
struct v4l2_frequency    v4l_freq = {0};
float curr_freq_mhz = 0.0;
int dir;
int push_enabled = 0;
int event_enabled = 1;

// seek
volatile int seek_done = 0;
pthread_mutex_t seek_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t  seek_cond  = PTHREAD_COND_INITIALIZER;

//-----------------------------------------------------------------

// === 辅助函数 ===
int run_cmd(const char *cmd) { return system(cmd); }
int file_exists(const char *path) { struct stat b; return (stat(path, &b) == 0); }

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
        snprintf(cmd, sizeof(cmd), "%s &", bin);
        run_cmd(cmd);
        for(int i=0; i<10; i++) { if(check_init_property()) return 0; usleep(200000); }
    }
    return enable_transport_layer() ? 0 : -1;
}



int get_events(int fd, int type) {
    struct v4l2_buffer buf;
    void *mbuf = malloc(128);
    
    if (!mbuf) {
        perror("malloc failed");
        return -1;
    }
    
    memset(&buf, 0, sizeof(buf));
    
    // 配置缓冲区参数
    buf.index = type;
    buf.type = V4L2_BUF_TYPE_PRIVATE;
    buf.memory = V4L2_MEMORY_USERPTR;
    buf.m.userptr = (unsigned long)mbuf;
    buf.length = 256;
    
    // 从驱动队列中取出数据
    int ret = ioctl(fd, VIDIOC_DQBUF, &buf);
    if (ret < 0) {
    if (errno == EAGAIN) {
        free(mbuf);
        return -1; // 没有事件，不是错误
    }
    perror("VIDIOC_DQBUF failed");
    }

    
    // 成功获取到数据
    LOGI("获取到 %d 字节数据 (类型: %d):\n", buf.bytesused, type);
    
    // 处理数据（根据类型）
    switch(type) {
        case IRIS_BUF_EVENTS:
            if (buf.bytesused > 0) {
                unsigned char event_type = *(unsigned char *)mbuf;
                LOGI("事件类型: 0x%02X\n", event_type);
                
               // 自动输出事件名
            if (event_type < sizeof(iris_event_names) / sizeof(iris_event_names[0])) {
                LOGI("事件类型: 0x%02X (%s)\n", event_type, iris_event_names[event_type]);
            } else {
                LOGI("事件类型: 0x%02X (未知事件)\n", event_type);
            }
            
            if (event_type == IRIS_EVT_SEEK_COMPLETE) {
                    pthread_mutex_lock(&seek_mutex);
                    seek_done = 1;
                    pthread_cond_signal(&seek_cond);
                    pthread_mutex_unlock(&seek_mutex);
                }
            }
            break;
                        
        default:
            // 非IRIS_BUF_EVENTS事件, 打印原始数据
            LOGI("非IRIS_BUF_EVENTS事件，原始数据: ");
            for (int i = 0; i < buf.bytesused && i < 16; i++) {
                LOGI("%02X ", ((unsigned char *)mbuf)[i]);
            }
            LOGI("\n");
    }
    
    free(mbuf);
    return 0;
}


void* loop_event(void *arg) {
    int radio_fd = *(int *)arg;
    // event 循环
    while (event_enabled) {
        
        // 获取事件
        if (get_events(radio_fd, IRIS_BUF_EVENTS) == 0) {
            // 成功获取事件，可以在这里根据事件类型获取详细信息

            
            //set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_RDSON, 1, "");
            //set_control(radio_fd, V4L2_CID_PRIVATE_INTF_LOW_THRESHOLD, 108, "other");//seek时 108 可以
            //set_control(radio_fd, V4L2_CID_PRIVATE_INTF_HIGH_THRESHOLD, 150, "other");
            //set_control(radio_fd, V4L2_CID_PRIVATE_SINR_THRESHOLD, 0, "SIGNAL_TH");
       
   
    } else {
            // 获取失败，可能是没有数据
            usleep(100000); // 等待100ms避免CPU占用过高
        }
        
        // 添加延时，避免过快循环
        usleep(50000); // 50ms
    }
    return NULL;
}

// ================================================================


float measure_rssi(int fd, float mhz) {
    struct v4l2_frequency f = {
        .tuner = 0,
        .type = V4L2_TUNER_RADIO,
        .frequency = (int)(mhz * 16000)
    };
    ioctl(fd, VIDIOC_S_FREQUENCY, &f);
    usleep(100000); //100ms

    struct v4l2_tuner tuner = { .index = 0 };
    if (ioctl(fd, VIDIOC_G_TUNER, &tuner) == 0)
        return tuner.signal;

    return -1;
}


float refine_freq1(int fd, float base) {
    float candidates[] = {
        base - 0.1f,
        base,
        base + 0.1f
    };

    float best = base;
    float best_rssi = -1;

    for (int i = 0; i < 3; i++) {
        float rssi = measure_rssi(fd, candidates[i]);
        if (rssi > best_rssi) {
            best_rssi = rssi;
            best = candidates[i];
        }
    }
    return best;
}



static void adjust_seek_start(int fd, int dir) {
    struct v4l2_frequency f;
    memset(&f, 0, sizeof(f));
    f.tuner = 0;
    f.type = V4L2_TUNER_RADIO;

    if (ioctl(fd, VIDIOC_G_FREQUENCY, &f) < 0)
        return;

    int step = 16000 / 10; // 0.1 MHz

    if (dir == 0) {
        // 向左：先退一格，避免命中当前频点
        f.frequency -= step;
        LOGI("Adjust SEEK start: -0.1MHz\n");
    } else {
        // 向右：先进一格（可选，但建议对称）
        //f.frequency += step;
        LOGI("Adjust SEEK start: +0.1MHz\n");
    }

    ioctl(fd, VIDIOC_S_FREQUENCY, &f);
    usleep(40000); // 给 PLL 稳定时间
}


//---------------------------------------

// 简化的 densense 阈值表（可按需求增加）
struct bad_freq_t {
    float freq;    // MHz
    int rssi_th;   // 最小 RSSI 阈值
};

static struct bad_freq_t bad_freqs[] = {
    // {92.9f, 28},
    // {93.1f, 28},

};

// 判断是否严重干扰
static int is_severe_densense(float freq, int rssi) {
    for (int i = 0; i < sizeof(bad_freqs)/sizeof(bad_freqs[0]); i++) {
        if (fabsf(freq - bad_freqs[i].freq) < 0.05f && rssi < bad_freqs[i].rssi_th) {
            LOGI("Severe densense: %.2fMHz RSSI=%d\n", freq, rssi);
            return 1;
        }
    }
    return 0;
}

// refine 基频
float refine_freq(int fd, float base) {
    float points[5];
    int rssi[5];
    struct v4l2_frequency f_cmd = { .tuner = 0, .type = V4L2_TUNER_RADIO };
    struct v4l2_tuner tuner = { .index = 0 };

    // 1. 采集 ±200kHz 范围内的 5 个点
    for (int i = 0; i < 5; i++) {
        points[i] = base + (i - 2) * 0.1f;
        f_cmd.frequency = (int)(points[i] * 16000);
        ioctl(fd, VIDIOC_S_FREQUENCY, &f_cmd);
        
        // 增加稳定延时：如果 93.1 抢台，可能是上一个频点的残余信号
        usleep(120000); 

        if (ioctl(fd, VIDIOC_G_TUNER, &tuner) == 0) {
            rssi[i] = (int)tuner.signal - 139;
        } else {
            rssi[i] = -139;
        }
        LOGI("  Scan [%.1f MHz] RSSI: %d\n", points[i], rssi[i]);
    }

    // 2. 模拟厂家 FMR_DensenseDetect 逻辑进行过滤
    // 定义逻辑：如果 92.9 和 93.1 的 RSSI 都很高，且 93.0 并不比它们弱超过 6dB
    // 那么 93.0 绝对是中心。
    
    int final_idx = 2; // 默认锁定 93.0 (base)

    // 计算斜率
    int slope_left = rssi[2] - rssi[1];  // 93.0 - 92.9
    int slope_right = rssi[2] - rssi[3]; // 93.0 - 93.1

    LOGI("  分析: 左斜率=%d, 右斜率=%d\n", slope_left, slope_right);

    // --- 核心算法：整数频率保护 ---
    // 如果发现左右两边都有强信号 (类似波峰)，即便 93.0 稍微弱一点，也判定为 93.0
    if (rssi[1] > -50 && rssi[3] > -50) { 
        LOGI("  检测到双侧强信号，强制判定为中心台 93.0\n");
        final_idx = 2;
    }
    // 如果 93.1 极强 (比 93.0 强 12dB 以上)，才考虑是偏移台
    else if (rssi[3] > rssi[2] + 12) {
        final_idx = 3;
        LOGI("  右侧信号具有绝对优势，判定为 93.1\n");
    }
    // 同理判断 92.9
    else if (rssi[1] > rssi[2] + 12) {
        final_idx = 1;
        LOGI("  左侧信号具有绝对优势，判定为 92.9\n");
    }
    else {
        LOGI("  信号分布平缓，回归整数频点\n");
        final_idx = 2;
    }

    // 3. 预防厂家代码中提到的“严重干扰 (SevereDensense)”
    // 强制修正已知干扰频点
    float result_f = points[final_idx];
    if (fabsf(result_f - 93.1f) < 0.01f || fabsf(result_f - 92.9f) < 0.01f) {
        if (rssi[final_idx] < 35) { // 信号不够强时，不允许停在非整数位
            LOGI("  非整数位信号强度不足，回弹至 93.0\n");
            result_f = base;
        }
    }

    // 4. 最终设置并返回
    f_cmd.frequency = (int)(result_f * 16000);
    ioctl(fd, VIDIOC_S_FREQUENCY, &f_cmd);
    
    LOGI("🎯 最终决定: %.1f MHz\n", result_f);
    return result_f;
}


// =========== seek =========
int seek(int fd, int dir) {
    push_enabled = 0;
    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");

    struct v4l2_hw_freq_seek v4l_seek;
    memset(&v4l_seek, 0, sizeof(v4l_seek));

    v4l_seek.tuner = 0;
    v4l_seek.type = V4L2_TUNER_RADIO;
    v4l_seek.wrap_around = 1;
    v4l_seek.seek_upward = (dir != 0);
    v4l_seek.spacing = 0;

    // 清除旧状态
    pthread_mutex_lock(&seek_mutex);
    seek_done = 0;
    pthread_mutex_unlock(&seek_mutex);

    LOGI("开始 SEEK, dir=%d\n", dir);
    adjust_seek_start(fd, dir);

    if (ioctl(fd, VIDIOC_S_HW_FREQ_SEEK, &v4l_seek) < 0) {
        LOGI("VIDIOC_S_HW_FREQ_SEEK failed: %s\n", strerror(errno));
        push_enabled = 1;
        return -1;
    }

    // ===== 等待 SEEK_COMPLETE 事件 =====
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 5;   // 最多等 5 秒，防死锁

    pthread_mutex_lock(&seek_mutex);
    while (!seek_done) {
        if (pthread_cond_timedwait(&seek_cond, &seek_mutex, &ts) == ETIMEDOUT) {
            LOGI("⚠️ SEEK 超时\n");
            pthread_mutex_unlock(&seek_mutex);
            push_enabled = 1;
            return -1;
        }
    }
    pthread_mutex_unlock(&seek_mutex);

    // ===== SEEK 完成，读取新频率 =====
    struct v4l2_frequency freq;
    memset(&freq, 0, sizeof(freq));
    freq.tuner = 0;
    freq.type  = V4L2_TUNER_RADIO;

    usleep(100000); //100ms

    if (ioctl(fd, VIDIOC_G_FREQUENCY, &freq) < 0) {
        LOGI("获取频率失败\n");
        push_enabled = 1;
        return -1;
    }


    curr_freq_mhz = freq.frequency / 16 / 1000.0;
    LOGI("✅ SEEK 完成，当前频率 %.2f MHz\n", curr_freq_mhz);

    // 邻频择优（±100kHz）
    curr_freq_mhz = refine_freq(fd, curr_freq_mhz);
    LOGI("🎯 校正后频率 %.2f MHz\n", curr_freq_mhz);

    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
    push_enabled = 1;
    return curr_freq_mhz;
}





//-----------------------------------------------------------------


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

    set_nonblock(radio_fd);
    init_firmware(radio_fd);

    if (set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_STATE, FM_RECV, "FM_RECV") < 0) {
        close(radio_fd); return -1;
    }
    
    LOGI("Hardware stabilizing...\n");
    usleep(100000); 
    // 默认设置
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_SET_AUDIO_PATH, AUDIO_DIGITAL_PATH, "DIGITAL_PATH");
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_REGION, 0, "REGION");
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_SPACING, 1, "SPACING");


    //set_control(radio_fd, V4L2_CID_PRIVATE_INTF_LOW_THRESHOLD, 110, "LOW_THRESHOLD");//seek时 108 可以
    //set_control(radio_fd, V4L2_CID_PRIVATE_INTF_HIGH_THRESHOLD, 150, "HIGH_THRESHOLD");
    set_control(radio_fd, V4L2_CID_PRIVATE_SINR_THRESHOLD, 0, "SINR_THRESHOLD");

    pthread_t tid;
    pthread_create(&tid, NULL, loop_event, &radio_fd);

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
