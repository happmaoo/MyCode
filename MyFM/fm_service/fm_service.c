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


//FM STATES
typedef enum {
    FM_OFF = 0,
    FM_ON,
    FM_ON_IN_PROGRESS,
    FM_OFF_IN_PROGRESS,
    FM_TUNE_IN_PROGRESS,
    SEEK_IN_PROGRESS,
    SCAN_IN_PROGRESS
} fm_state_t;

static fm_state_t cur_fm_state = FM_OFF;




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


enum FM_AUDIO_PATH { AUDIO_DIGITAL_PATH = 0, AUDIO_ANALOG_PATH = 1 };
#define TUNE_MULT 16000
#define SOCKET_NAME "fm_service" // Socket 名称

struct v4l2_hw_freq_seek v4l_seek;
struct v4l2_frequency    v4l_freq = {0};
float curr_freq_mhz = 0.0f;
int dir;
volatile int push_enabled = 0;
volatile int event_enabled = 1;


// seek
volatile int seek_done = 0;
pthread_mutex_t seek_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t  seek_cond  = PTHREAD_COND_INITIALIZER;


// 用于暂停一会跳过按下seek的一瞬间发出的假的 IRIS_EVT_SEEK_COMPLETE 信号
//volatile int seek_pause = 0;


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

// 全局退出标志
volatile sig_atomic_t should_exit = 0;

int get_events(int fd, int type);

//-----------------------------------------------------------------

void signal_handler(int sig) {
    should_exit = 1;
}

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

// 获取频率信息 返回值 double
double get_freq() {
    double frequency_mhz;
    struct v4l2_frequency freq;
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    usleep(50000); //50ms

    if (ioctl(radio_fd, VIDIOC_G_FREQUENCY, &freq) == 0) {
        frequency_mhz = (double)freq.frequency / 16 / 1000.0f;
    } else {
        frequency_mhz = 0.0f;
    }
    return frequency_mhz;
}



int set_freq(float mhz_freq) {
    struct v4l2_frequency freq;
    memset(&freq, 0, sizeof(freq));
    freq.tuner = 0;
    freq.type = V4L2_TUNER_RADIO;
    freq.frequency = (unsigned int)(mhz_freq * TUNE_MULT);

    if (ioctl(radio_fd, VIDIOC_S_FREQUENCY, &freq) < 0) {
        perror("VIDIOC_S_FREQUENCY failed");
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



//-------------------- handle_event 处理事件 ------------------------------

void handle_NEW_SRCH_LIST_event(){
    if(cur_fm_state == SCAN_IN_PROGRESS){
        LOGI("检测到搜台完成，正在请求提取列表缓冲区...\n");
        // 递归调用自身去拉取 SRCH_LIST 缓冲区的数据
        // 这会进入本函数的 case IRIS_BUF_SRCH_LIST 分支
        get_events(radio_fd, IRIS_BUF_SRCH_LIST);
        scan_status = 0;
        // 2. 解锁阻塞在 scan() 的主线程
        pthread_mutex_lock(&scan_mutex);
        cur_fm_state = FM_ON;
        pthread_cond_signal(&scan_cond);
        pthread_mutex_unlock(&scan_mutex);
    }
}

void handle_SEEK_COMPLETE_event(){


    if (cur_fm_state == SEEK_IN_PROGRESS) {
        pthread_mutex_lock(&seek_mutex);
        cur_fm_state = FM_ON;
        pthread_cond_signal(&seek_cond);
        pthread_mutex_unlock(&seek_mutex);
    }
}
void handle_TUNE_SUCC_event(){
    //是在搜索状态时的 IRIS_EVT_TUNE_SUCC
    //if(get_fm_state() == SEEK_IN_PROGRESS){
    //LOGI("收到 IRIS_EVT_TUNE_SUCC\n");
    //  seek_status = 0;
    //  pthread_mutex_lock(&seek_mutex);
    //  seek_done = 1;
    //  pthread_cond_signal(&seek_cond);
    //   pthread_mutex_unlock(&seek_mutex);
    //}
}
//--------------------------------------------------

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
                    LOGI(" - Event: %s -\n", iris_event_names[event_type]);

                switch (event_type) {
                    case IRIS_EVT_NEW_SRCH_LIST:
                        handle_NEW_SRCH_LIST_event();
                        break;
                    case IRIS_EVT_SEEK_COMPLETE:
                        handle_SEEK_COMPLETE_event();
                        break;
                    case IRIS_EVT_TUNE_SUCC:
                        handle_TUNE_SUCC_event();
                        break;
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

        // if (seek_pause) {
        //     usleep(10); // 暂停10us 跳过
        //     seek_pause = 0;
        //     continue;
        // }
        
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

// =========== seek =========
float seek(int fd, int dir) {
    push_enabled = 0;
    cur_fm_state = SEEK_IN_PROGRESS;


    //adjust_seek_start(fd,dir);

    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");

    // 1. 关键：先强制停止任何可能存在的搜索任务，重置状态机
    // set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
    // usleep(100000); // 给驱动一点反应时间

    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCH_CNT, 1, "RESET_SRCH_CNT"); // 重置为1

    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHMODE, SEEK, "SRCHMODE_SEEK");




    // 2. 重置同步变量
    pthread_mutex_lock(&seek_mutex);
    pthread_mutex_unlock(&seek_mutex);

    // 3. 配置 SEEK 参数
    struct v4l2_hw_freq_seek v4l_seek;
    memset(&v4l_seek, 0, sizeof(v4l_seek));
    v4l_seek.tuner = 0;
    v4l_seek.type = V4L2_TUNER_RADIO;
    v4l_seek.wrap_around = 1;
    v4l_seek.seek_upward = (dir != 0);

    // 必须参数设置完才能开始搜索
     //set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 1, "SRCH_ON");

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
    if (cur_fm_state == SEEK_IN_PROGRESS) { // 双重检查，防止信号早于等待发生
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
    curr_freq_mhz = get_freq();
    LOGI("---SEEK 完成 %.2f ---\n", curr_freq_mhz);
    
    //seek_pause = 1;

    // 其实不用这些方法，因为当时我参数设置乱了，所以很不稳定.参数乱了，不稳定，记得重启手机
    // 邻频择优（±100kHz）
    //curr_freq_mhz = refine_freq(fd, curr_freq_mhz);
    //LOGI("----------校正后 %.2f ----------\n", curr_freq_mhz);
    
    //set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
    push_enabled = 1;
    cur_fm_state = FM_ON;
    return curr_freq_mhz;
}




int scan(int fd) {

    // 保存当前频率
    double currfreq = get_freq();

    // scan 时从最低频率开始
    struct v4l2_frequency freq = { 
    .tuner = 0, 
    .type = V4L2_TUNER_RADIO, 
    .frequency = (int)(87.5f * TUNE_MULT) 
    };
    ioctl(fd, VIDIOC_S_FREQUENCY, &freq);



    // 1. 强制先关闭一次搜索，确保状态机复位
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");
    usleep(50000);
    LOGI("开始扫描 (SCAN)...\n");
    push_enabled = 0;
    scan_count = 0;
    cur_fm_state = SCAN_IN_PROGRESS;
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
    while (cur_fm_state == SCAN_IN_PROGRESS) {
        if (pthread_cond_timedwait(&scan_cond, &scan_mutex, &ts) == ETIMEDOUT) {
            LOGI("扫描超时\n");
            break;
        }
    }
    pthread_mutex_unlock(&scan_mutex);
    set_control(fd, V4L2_CID_PRIVATE_IRIS_SRCHON, 0, "SRCH_OFF");


    // 恢复scan前频率
    set_freq(currfreq);


    set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");

    // 5. 扫描结束后，需要通过 VIDIOC_DQBUF 获取 IRIS_BUF_SRCH_LIST
    // 这部分逻辑建议放在 loop_event 里的获取数据部分
    
    push_enabled = 1;
    LOGI("扫描结束\n");
    return 0;
}


//-----------------------------------------------------------------



void get_signal_info(int radio_fd, char* buffer, int size) {
    struct v4l2_tuner tuner;
    memset(buffer, 0, size);
    memset(&tuner, 0, sizeof(tuner));
    tuner.index = 0;

    struct v4l2_control sinr = { .id = V4L2_CID_PRIVATE_IRIS_GET_SINR };
    
    // 获取信号信息
    if (ioctl(radio_fd, VIDIOC_G_TUNER, &tuner) == 0) {
        if (ioctl(radio_fd, VIDIOC_G_CTRL, &sinr) == 0) {
            snprintf(buffer, size, "RSSI:%d,SINR:%d",tuner.signal-139,sinr.value);
        }        
    } else {
        snprintf(buffer, size, "get_signal_info error.");
    }

    

}


// 分离命令处理逻辑
void process_command(int radio_fd, int client_fd, const char* cmd) {
    float freq_mhz = 0.0f;
    int dir = 0;
    char response[256];
    
    if (strcmp(cmd, "QUIT") == 0) {
        LOGI("QUIT退出程序\n");
        event_enabled = 0;
        usleep(100000);
        if (radio_fd >= 0) {
            set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");
            set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_STATE, FM_OFF, "FM_OFF");
            usleep(100000);
            close(radio_fd);
            radio_fd = -1;
        }
        if (client_fd >= 0) {
            close(client_fd);
            client_fd = -1;
        }
        pthread_mutex_destroy(&seek_mutex);
        pthread_cond_destroy(&seek_cond);
        pthread_mutex_destroy(&scan_mutex);
        pthread_cond_destroy(&scan_cond);
        _exit(0);
    }
    else if (strncmp(cmd, "PUSH", 4) == 0) {
        int val = 0;
        if (sscanf(cmd + 4, "%d", &val) == 1) {
            push_enabled = (val == 1);
            LOGI("PUSH: %s\n", push_enabled ? "ON" : "OFF");
            char* resp = push_enabled ? "PUSH_ON\n" : "PUSH_OFF\n";
            write(client_fd, resp, strlen(resp));
        }
    }
    else if (strncmp(cmd, "TUNE", 4) == 0) {
        push_enabled = 0;
        if (sscanf(cmd + 5, "%f", &freq_mhz) == 1) {
            struct v4l2_frequency freq = { 
                .tuner = 0, 
                .type = V4L2_TUNER_RADIO, 
                .frequency = (int)(freq_mhz * TUNE_MULT) 
            };
            if (ioctl(radio_fd, VIDIOC_S_FREQUENCY, &freq) == 0) {
                set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
                int len = snprintf(response, sizeof(response), "TUNED,FREQ:%.1f\n", freq_mhz);
                write(client_fd, response, len);
            } else {
                LOGI("TUNE ERROR\n");
                snprintf(response, sizeof(response), "ERROR|TUNE_FAILED:%s\n", strerror(errno));
                write(client_fd, response, strlen(response));
            }
        }
        push_enabled = 1;
    }
    else if (strcmp(cmd, "MUTE") == 0) {
        set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 1, "MUTE");
        write(client_fd, "MUTED\n", 6);
    }
    else if (strcmp(cmd, "UNMUTE") == 0) {
        set_control(radio_fd, V4L2_CID_AUDIO_MUTE, 0, "UNMUTE");
        write(client_fd, "UNMUTED\n", 8);
    }
    else if (strncmp(cmd, "SEEK", 4) == 0) {
        if (sscanf(cmd + 5, "%d", &dir) == 1) {
            float new_freq = seek(radio_fd, dir);
            char buf[32];
            snprintf(buf, sizeof(buf), "SEEKED,FREQ:%.1f\n", new_freq);
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



// 处理客户端（带实时推送）
void handle_client(int radio_fd, int client_fd) {
    char cmd[1024];
    long long last_push_time = get_time_ms();
    fd_set read_fds;
    struct timeval timeout;
    
    LOGI("FM Service handle_client: 准备就绪\n");
    
    // 设置非阻塞
    set_nonblock(client_fd);
    
    // 发送欢迎消息
    write(client_fd, "FM_SERVICE|STATE:READY\n", 40);
    
    while (1) {
        long long now = get_time_ms();
        
        // === 使用 select 监听多个事件 ===
        FD_ZERO(&read_fds);
        FD_SET(client_fd, &read_fds);
        
        // 设置超时（100ms），这样不会阻塞推送
        timeout.tv_sec = 0;
        timeout.tv_usec = 100000; // 100ms
        
        int ret = select(client_fd + 1, &read_fds, NULL, NULL, &timeout);
        
        if (ret < 0) {
            if (errno == EINTR) continue; // 被信号中断
            LOGI("select error: %s\n", strerror(errno));
            break;
        }
        
        // === 1. 处理客户端命令 ===
        if (ret > 0 && FD_ISSET(client_fd, &read_fds)) {
            memset(cmd, 0, sizeof(cmd));
            int n = read(client_fd, cmd, sizeof(cmd) - 1); // 留一个字节给 \0

            if (n <= 0) {
                // n = 0 代表客户端正常关闭 Socket
                // n < 0 代表发生了严重错误
                LOGI("客户端断开连接或读取异常 (n=%d)，释放硬件资源并退出...\n", n);
                
                // 关键操作：设置退出标志并关闭连接
                should_exit = 1; 
                close(client_fd);
                // 注意：如果你在 main 函数最后有统一的 close(radio_fd)，这里可以只 return
                return; 
            }

            // 走到这里说明读到了 n 个字节
            cmd[n] = '\0';
            
            // 移除换行符
            char *nl = strchr(cmd, '\n');
            if (nl) *nl = '\0';
            char *cr = strchr(cmd, '\r');
            if (cr) *cr = '\0';
            
            if (strlen(cmd) > 0) {
                LOGI("收到命令: %s\n", cmd);
                process_command(radio_fd, client_fd, cmd);
                
                // 如果收到 QUIT 命令，也设置 should_exit
                if (strcmp(cmd, "QUIT") == 0) {
                    LOGI("收到 QUIT 指令，准备退出...\n");
                    should_exit = 1;
                    return;
                }
            }
        }
        // === 2. 处理推送 ===
        long long elapsed_since_last_push = now - last_push_time;
        
        if (push_enabled && (now - last_push_time >= PUSH_INTERVAL_MS)) {
            fd_set write_fds;
            struct timeval tv_nowait = {0, 0}; // 立即返回
            FD_ZERO(&write_fds);
            FD_SET(client_fd, &write_fds);

            // 检查 Socket 是否可写（缓冲区是否有空间）
            int can_write = select(client_fd + 1, NULL, &write_fds, NULL, &tv_nowait);
            if (can_write > 0) {
                char signal_msg[256] = {0};
                get_signal_info(radio_fd, signal_msg, sizeof(signal_msg));
                char push_msg[300] = {0};
                int push_len = snprintf(push_msg, sizeof(push_msg), "%s\n", signal_msg);
                write(client_fd, push_msg, push_len);
            } else {
                LOGI("发送缓冲区满，跳过本次推送，防止阻塞主循环\n");
            }
            last_push_time = now;
        }
        
        // === 3. 检查线程事件 ===
        // 这里可以添加对其他线程状态的检查
    }
    
    close(client_fd);
    LOGI("handle_client 退出\n");
}

// 主函数
int main() {
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGPIPE, SIG_IGN);
    
   // 1. 初始化 FM 硬件
    radio_fd = open("/dev/radio0", O_RDWR);
    if (radio_fd < 0) { perror("Open Radio"); return -1; }

    set_nonblock(radio_fd);
    init_firmware(radio_fd);

    if (set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_STATE, FM_ON, "FM_RECV") < 0) {
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
    
    
    // 使用带有超时的 accept
    struct timeval tv;
    tv.tv_sec = 5;  // 1秒超时
    tv.tv_usec = 0;
    setsockopt(server_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    
    LOGI("等待FM App连接（5秒超时）...\n");
    
    while (!should_exit) {
        int client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // 超时，检查是否需要退出
                continue;
            } else if (errno == EINTR) {
                // 被信号中断
                continue;
            } else {
                perror("接受连接失败");
                break;
            }
        }
        
        LOGI("✅ 客户端已连接\n");
        handle_client(radio_fd, client_fd);
        
        if (should_exit) break;
    }
    
    // 清理代码
    LOGI("服务关闭，开始清理...\n");
    
    // 等待线程退出
    // pthread_join(tid, NULL);
    
    // 销毁互斥锁和条件变量
    pthread_mutex_destroy(&seek_mutex);
    pthread_cond_destroy(&seek_cond);
    pthread_mutex_destroy(&scan_mutex);
    pthread_cond_destroy(&scan_cond);
    

    if (radio_fd >= 0) {
        set_control(radio_fd, V4L2_CID_PRIVATE_IRIS_STATE, FM_OFF,"FM_OFF."); 
        close(radio_fd);
    }
    if (server_fd >= 0) {
        close(server_fd);
    }
    return 0;
    
    LOGI("服务完全退出\n");
    return 0;
}
