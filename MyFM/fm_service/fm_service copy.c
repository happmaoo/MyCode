#define _POSIX_C_SOURCE 199309L
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

/* Search options */
enum search_t {
	SEEK,
	SCAN,
	SCAN_FOR_STRONG,
	SCAN_FOR_WEAK,
	RDS_SEEK_PTY,
	RDS_SCAN_PTY,
	RDS_SEEK_PI,
	RDS_AF_JUMP,
};

enum iris_region_t {
	IRIS_REGION_US,
	IRIS_REGION_EU,
	IRIS_REGION_JAPAN,
	IRIS_REGION_JAPAN_WIDE,
	IRIS_REGION_OTHER
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


#define REGION_US_EU_BAND_LOW              87500
#define REGION_US_EU_BAND_HIGH             108000


/*Search Station list */
#define PARAMS_PER_STATION 0x08
#define STN_NUM_OFFSET     0x01
#define STN_FREQ_OFFSET    0x02
#define KHZ_TO_MHZ         1000
#define GET_MSB(x)((x >> 8) & 0xFF)
#define GET_LSB(x)((x) & 0xFF)



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

// scan
volatile int scan_done = 0;
pthread_mutex_t scan_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t  scan_cond  = PTHREAD_COND_INITIALIZER;

// 用来存储各种客户端可以修改的参数 为 0 使用系统默认 !!! 最好不要改，因为改了容易搜台不稳定，频率漂移。!!!
int var_V4L2_CID_PRIVATE_INTF_LOW_THRESHOLD = 0; // 最好配合下面的 HIGH_THRESHOLD 不然100多就 Invalid argument
int var_V4L2_CID_PRIVATE_INTF_HIGH_THRESHOLD = 0;// 必须配合上面的LOW_THRESHOLD
int var_V4L2_CID_PRIVATE_SINR_THRESHOLD = 0;



// 定义扫描列表存储
#define MAX_SCAN_LIST 20
float scan_list[MAX_SCAN_LIST];
int scan_count = 0;
int scan_status = 0;
int seek_status = 0;


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

// 比较函数：用于升序排列
int compare_freq(const void *a, const void *b) {
    float fa = *(const float *)a;
    float fb = *(const float *)b;
    return (fa > fb) - (fa < fb);
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
    // 使用静态或堆分配缓冲区，确保读取 IRIS_BUF_SRCH_LIST 时安全
    unsigned char *mbuf = malloc(STD_BUF_SIZE); 
    if (!mbuf) return -1;

    memset(&buf, 0, sizeof(buf));
    buf.index = type;
    buf.type = V4L2_BUF_TYPE_PRIVATE;
    buf.memory = V4L2_MEMORY_USERPTR;
    buf.m.userptr = (unsigned long)mbuf;
    buf.length = STD_BUF_SIZE;

    if (ioctl(fd, VIDIOC_DQBUF, &buf) < 0) {
        free(mbuf);
        return -1;
    }

    switch(type) {
        case IRIS_BUF_EVENTS:
            if (buf.bytesused > 0) {
                unsigned char event_type = mbuf[0];
                if (event_type < sizeof(iris_event_names)/sizeof(char*))
                    LOGI("收到事件: %s (0x%02X)\n", iris_event_names[event_type], event_type);

                if (event_type == IRIS_EVT_NEW_SRCH_LIST) {

                    if(scan_status == 1){
                        LOGI("检测到搜台完成，正在请求提取列表缓冲区...\n");
                        // 递归调用自身去拉取 SRCH_LIST 缓冲区的数据
                        // 这会进入本函数的 case IRIS_BUF_SRCH_LIST 分支
                        get_events(fd, IRIS_BUF_SRCH_LIST);
                        scan_status = 0;
                        // 2. 解锁阻塞在 scan() 的主线程
                        pthread_mutex_lock(&scan_mutex);
                        scan_done = 1;
                        pthread_cond_signal(&scan_cond);
                        pthread_mutex_unlock(&scan_mutex);
                    }
                }

                if (event_type == IRIS_EVT_SEEK_COMPLETE) {

                    // IRIS_EVT_SEEK_COMPLETE 的时候根本没有搜索完成，不要用，坑。

                    // LOGI("收到 IRIS_EVT_SEEK_COMPLETE，搜索完成\n");
                    // pthread_mutex_lock(&seek_mutex);
                    // seek_done = 1;
                    // pthread_cond_signal(&seek_cond);
                    // pthread_mutex_unlock(&seek_mutex);

                }
                if (event_type == IRIS_EVT_TUNE_SUCC) {

                    //是在搜索状态时的 IRIS_EVT_TUNE_SUCC
                    if(seek_status == 1){
                    LOGI("收到 IRIS_EVT_TUNE_SUCC\n");
                    seek_status = 0;
                    pthread_mutex_lock(&seek_mutex);
                    seek_done = 1;
                    pthread_cond_signal(&seek_cond);
                    pthread_mutex_unlock(&seek_mutex);
                    }
                }
            }
            break;

        case IRIS_BUF_SRCH_LIST:
            if (buf.bytesused > 0) {
                unsigned char *data = (unsigned char *)mbuf;
                int num_found = data[0]; 
                int valid_count = 0;
                float temp_list[MAX_SCAN_LIST];

                for (int i = 0; i < num_found && valid_count < MAX_SCAN_LIST; i++) {
                    int raw_h = data[1 + i * 2];
                    int raw_l = data[2 + i * 2];
                    int f_index = (raw_h << 8) | raw_l;

                    // 过滤无效索引 (如 0 或超出频段的索引)
                    if (f_index <= 0 || f_index > 410) continue; 

                    float freq = (87500 + (f_index * 50)) / 1000.0f;

                    // 简单的去重检查
                    int is_duplicate = 0;
                    for (int j = 0; j < valid_count; j++) {
                        if (temp_list[j] == freq) {
                            is_duplicate = 1;
                            break;
                        }
                    }

                    if (!is_duplicate) {
                        temp_list[valid_count++] = freq;
                    }
                }

                // 执行排序：让电台按 87.5 -> 108.0 排列
                qsort(temp_list, valid_count, sizeof(float), compare_freq);

                // 更新到全局列表并打印
                scan_count = valid_count;
                if(scan_count==0){LOGI("电台列表为空.");}
                for (int i = 0; i < scan_count; i++) {
                    scan_list[i] = temp_list[i];
                    LOGI("  - [电台 %d]: %.2f MHz\n", i + 1, scan_list[i]);
                }
            }
            break;
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

   
        } else {
            // 获取失败，可能是没有数据
            usleep(100000);
        }

        usleep(50000); // 50ms 添加延时，避免过快循环
    }
    return NULL;
}

// ================================================================


/*

float measure_rssi(int fd, float mhz) {
    struct v4l2_frequency f = {
        .tuner = 0,
        .type = V4L2_TUNER_RADIO,
        .frequency = (int)(mhz * 16000)
    };
    ioctl(fd, VIDIOC_S_FREQUENCY, &f);
    usleep(200000); //100ms

    float myf = f.frequency / 16 / 1000.0;

    struct v4l2_tuner tuner = { .index = 0 };
    if (ioctl(fd, VIDIOC_G_TUNER, &tuner) == 0)
        LOGI("refine_freq: ===%.2f = %d===\n", myf, tuner.signal-139);
        return tuner.signal;

    return -1;
}


float refine_freq1(int fd, float base) {
    float candidates[] = {
    base - 0.1f,
    base,
    base + 0.1f,
    base + 0.2f
    };

    float best = base;
    float best_rssi = -1;

    for (int i = 0; i < 4; i++) {
        float rssi = measure_rssi(fd, candidates[i]);
        if (rssi > best_rssi) {
            best_rssi = rssi;
            best = candidates[i];
        }
    }
    return best;
}

// ----------------------------------------------------------------------

// 定义一个结构体来存储测量结果
struct signal_metrics {
    int rssi;
    int sinr;
    float freq;
};

// 测量函数：现在同时获取 RSSI 和 SINR
struct signal_metrics measure_metrics(int fd, float mhz) {
    struct signal_metrics metrics = { .rssi = 0, .sinr = 0, .freq = mhz };

    // 1. 设置频率
    struct v4l2_frequency f = {
        .tuner = 0,
        .type = V4L2_TUNER_RADIO,
        .frequency = (int)(mhz * 16000)
    };
    ioctl(fd, VIDIOC_S_FREQUENCY, &f);
    
    // 等待硬件稳定 (200ms)
    usleep(200000); 

    // 2. 获取 RSSI
    struct v4l2_tuner tuner = { .index = 0 };
    if (ioctl(fd, VIDIOC_G_TUNER, &tuner) == 0) {
        metrics.rssi = tuner.signal - 139;
    }

    // 3. 获取 SINR
    struct v4l2_control ctrl = { .id = V4L2_CID_PRIVATE_IRIS_GET_SINR };
    if (ioctl(fd, VIDIOC_G_CTRL, &ctrl) == 0) {
        metrics.sinr = ctrl.value;
    }

    LOGI("Measure Freq: %.2f | RSSI: %d | SINR: %d\n", mhz, metrics.rssi, metrics.sinr);
    return metrics;
}

// 优化频率函数：比较多个候选点
float refine_freq(int fd, float base) {
    float candidates[] = { base - 0.1f, base, base + 0.1f, base + 0.2f };
    
    struct signal_metrics best_metrics = { .rssi = 0, .sinr = 0, .freq = base };

    for (int i = 0; i < 4; i++) {
        struct signal_metrics current = measure_metrics(fd, candidates[i]);

        // 比较逻辑：
        // 优先判断 SINR（信噪比是音频质量的关键）
        // 如果 SINR 更大，或者 SINR 相等但 RSSI 更强，则更新
        if (current.sinr > best_metrics.sinr || 
           (current.sinr == best_metrics.sinr && current.rssi > best_metrics.rssi)) {
            
            best_metrics = current;
        }
    }

    //LOGI("Refine Result -> Best Freq: %.2f (SINR: %d, RSSI: %d)\n", 
          //best_metrics.freq, best_metrics.sinr, best_metrics.rssi);

    // 强制对齐到 0.1 MHz
    best_metrics.freq = roundf(best_metrics.freq * 10.0f) / 10.0f;
    return best_metrics.freq;
}


// ----------------------------------------------------------------------









//---------------------------------------

// 简化的 densense 阈值表（可按需求增加）
struct bad_freq_t {
    float freq;    // MHz
    int rssi_th;   // 最小 RSSI 阈值
};

static struct bad_freq_t bad_freqs[] = {
    // {92.9f, 28},
    // {93.1f, 28},
    // 可以继续添加
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

*/


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
        f.frequency += step;
        LOGI("Adjust SEEK start: +0.1MHz\n");
    }

    ioctl(fd, VIDIOC_S_FREQUENCY, &f);
    usleep(50000); // 给 PLL 稳定时间
}


// =========== seek =========
int seek(int fd, int dir) {
    push_enabled = 0;
    seek_status = 1;


    //adjust_seek_start(fd,dir);

    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");

    // 1. 关键：先强制停止任何可能存在的搜索任务，重置状态机
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
    usleep(100000); // 给驱动一点反应时间

    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCH_CNT, 1, "RESET_SRCH_CNT"); // 重置为1

    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHMODE, SEEK, "SRCHMODE_SEEK");




    // 2. 重置同步变量
    pthread_mutex_lock(&seek_mutex);
    seek_done = 0;
    pthread_mutex_unlock(&seek_mutex);

    // 3. 配置 SEEK 参数
    struct v4l2_hw_freq_seek v4l_seek;
    memset(&v4l_seek, 0, sizeof(v4l_seek));
    v4l_seek.tuner = 0;
    v4l_seek.type = V4L2_TUNER_RADIO;
    v4l_seek.wrap_around = 1;
    v4l_seek.seek_upward = (dir == 0) ? 0 : 1;

    // 必须参数设置完才能开始搜索
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 1, "SRCH_ON");

    LOGI("开始执行 VIDIOC_S_HW_FREQ_SEEK...\n");
    if (ioctl(fd, VIDIOC_S_HW_FREQ_SEEK, &v4l_seek) < 0) {
        LOGI("SEEK ioctl 失败: %s\n", strerror(errno));
        push_enabled = 1;
        return -1;
    }

    // 4. 等待信号
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 10; 

    pthread_mutex_lock(&seek_mutex);
    if (!seek_done) { // 双重检查，防止信号早于等待发生
        int ret = pthread_cond_timedwait(&seek_cond, &seek_mutex, &ts);
        if (ret == ETIMEDOUT) {
            LOGI("SEEK TIMEDOUT\n");
            pthread_mutex_unlock(&seek_mutex);
            // 超时也要关闭搜索状态
            set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
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

    // 其实不用这些方法，因为当时我参数设置乱了，所以很不稳定.参数乱了，不稳定，记得重启手机
    // 强制避免出现 93.05 这种频率
    //curr_freq_mhz = floorf(curr_freq_mhz * 10.0f + 0.5f) / 10.0f;

    LOGI("---SEEK 完成 %.2f ---\n", curr_freq_mhz);

    // 其实不用这些方法，因为当时我参数设置乱了，所以很不稳定.参数乱了，不稳定，记得重启手机
    // 邻频择优（±100kHz）
    //curr_freq_mhz = refine_freq(fd, curr_freq_mhz);
    //LOGI("----------校正后 %.2f ----------\n", curr_freq_mhz);
    
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
    push_enabled = 1;
    seek_status = 0;
    return curr_freq_mhz;
}




int scan(int fd) {


    // scan 时从最低频率开始
    struct v4l2_frequency freq = { 
    .tuner = 0, 
    .type = V4L2_TUNER_RADIO, 
    .frequency = (int)(87.5 * TUNE_MULT) 
    };
    ioctl(fd, VIDIOC_S_FREQUENCY, &freq);



    // 1. 强制先关闭一次搜索，确保状态机复位
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
    usleep(50000);
    LOGI("开始扫描 (SCAN)...\n");
    push_enabled = 0;
    scan_count = 0;
    scan_done = 0;
    memset(scan_list, 0, sizeof(scan_list));

    scan_status = 1;


    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHMODE, SCAN_FOR_STRONG, "SRCH_MODE"); 
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCH_CNT, MAX_SCAN_LIST, "SRCH_CNT");



    // 启动扫描
    if (set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 1, "SRCH_ON") < 0) {
        LOGI("开启 SRCHO 失败\n");
        push_enabled = 1;
        return -1;
    }

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 60; // 扫描时间s

    pthread_mutex_lock(&scan_mutex);
    while (!scan_done) {
        if (pthread_cond_timedwait(&scan_cond, &scan_mutex, &ts) == ETIMEDOUT) {
            LOGI("扫描超时\n");
            break;
        }
    }
    pthread_mutex_unlock(&scan_mutex);
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");

    // 5. 扫描结束后，需要通过 VIDIOC_DQBUF 获取 IRIS_BUF_SRCH_LIST
    // 这部分逻辑建议放在 loop_event 里的获取数据部分
    
    push_enabled = 1;
    LOGI("扫描结束\n");
    return 0;
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
                else if (strncmp(cmd, "SCAN", 4) == 0) {
                    int ret = scan(radio_fd);

                    if (ret != 0) {
                        //LOGI("MyFM: 扫描异常退出\n");
                    }else {
                        char res_buf[256];
                        memset(res_buf, 0, sizeof(res_buf));
                        int pos = snprintf(res_buf, sizeof(res_buf), "SCANED:");
                        
                        for (int i = 0; i < scan_count; i++) {
                            if (pos < (int)sizeof(res_buf) - 15) {
                                pos += snprintf(res_buf + pos, sizeof(res_buf) - pos, "%.1f%s", 
                                                scan_list[i], (i == scan_count - 1 ? "" : ","));
                            }
                        }
                        strncat(res_buf, "\n", sizeof(res_buf) - strlen(res_buf) - 1);
                        write(client_fd, res_buf, strlen(res_buf));
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
                    LOGI("PUSH: %s\n", signal_msg);
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
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_SPACING, 1, "SPACING");
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_EMPHASIS, 1,"EMPHASIS");  // 50μs去加重（中国）
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_REGION, IRIS_REGION_OTHER,"REGION_OTHER");  // 其他区域


    if(var_V4L2_CID_PRIVATE_INTF_LOW_THRESHOLD != 0){
        set_control(radio_fd, V4L2_CID_PRIVATE_INTF_LOW_THRESHOLD, var_V4L2_CID_PRIVATE_INTF_LOW_THRESHOLD, "LOW_THRESHOLD");
    }
    if(var_V4L2_CID_PRIVATE_INTF_HIGH_THRESHOLD != 0){
        set_control(radio_fd, V4L2_CID_PRIVATE_INTF_HIGH_THRESHOLD, var_V4L2_CID_PRIVATE_INTF_HIGH_THRESHOLD, "HIGH_THRESHOLD");
    }
    if(var_V4L2_CID_PRIVATE_SINR_THRESHOLD != 0){
        set_control(radio_fd, V4L2_CID_PRIVATE_SINR_THRESHOLD, var_V4L2_CID_PRIVATE_SINR_THRESHOLD, "SINR_THRESHOLD");
    }

    pthread_t tid;
    pthread_create(&tid, NULL, loop_event, &radio_fd);


    // sleep(2);
    // set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");

    /*

     // 4. 关闭收音机
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_STATE, FM_OFF, "FM_OFF");
    usleep(200000);  // 200ms
    
    // 5. 重新打开收音机
    set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_STATE, FM_RECV, "FM_RECV");
    usleep(600000);  // 300ms 等待硬件初始化


    //  重置到默认状态（IRIS特有）
    struct v4l2_control ctrl = { .id = V4L2_CID_PRIVATE_IRIS_READ_DEFAULT };
    if (ioctl(radio_fd, VIDIOC_G_CTRL, &ctrl) == 0) {
        LOGI("读取默认配置完成\n");
    }else{
        LOGI("读取默认配置  error\n");
    }

    */







    
    // scan(radio_fd);
    // close(radio_fd);
    // return 0;



    // struct v4l2_frequency freq = { 
    // .tuner = 0, 
    // .type = V4L2_TUNER_RADIO, 
    // .frequency = (int)(108 * TUNE_MULT) 
    // };
    // ioctl(radio_fd, VIDIOC_S_FREQUENCY, &freq);

    // seek(radio_fd,0);
    // sleep(2);
    // seek(radio_fd,0);
    // sleep(2);
    // seek(radio_fd,0);
    // sleep(2);
    // seek(radio_fd,0);
    // sleep(2);
    // seek(radio_fd,0);
    // sleep(2);

    // close(radio_fd);
    // return 0;




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
    
    LOGI("服务关闭\n");
    return 0;
}
