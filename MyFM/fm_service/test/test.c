#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>

#define SMD_DEVICE "/dev/smd3"

int main() {
    int fd = open(SMD_DEVICE, O_RDWR | O_NOCTTY);
    if (fd < 0) return -1;

    // 1. 发送标准的 HCI Reset
    unsigned char hci_reset[] = { 0x01, 0x03, 0x0C, 0x00 };
    write(fd, hci_reset, sizeof(hci_reset));
    usleep(100000);

    // 2. 修正后的 Set Scan Parameters
    // 注意：我们将长度设为 7，并严格按照高通建议的 10ms (0x0010 * 0.625ms) 窗口
    unsigned char scan_params[] = {
        0x01,               // Packet Type: Command
        0x0B, 0x20,         // Opcode: 0x200B
        0x07,               // Length: 7
        0x00,               // Passive Scanning
        0x10, 0x20,         // Interval: 10ms
        0x10, 0x00,         // Window: 10ms
        0x00,               // Own Address Type: Public
        0x00                // Filter Policy: Any
    };
    write(fd, scan_params, sizeof(scan_params));
    usleep(100000);

    // 3. 修正后的 Set Scan Enable
    // 某些高通固件如果 Filter_Duplicates 设为 0x01 会报 0x12 错误，改为 0x00 试试
    unsigned char scan_enable[] = {
        0x01,               // Packet Type: Command
        0x0C, 0x20,         // Opcode: 0x200C
        0x02,               // Length: 2
        0x01,               // Enable: True
        0x00                // Filter Duplicates: False (重要：尝试改为 00)
    };
    write(fd, scan_enable, sizeof(scan_enable));

    printf("指令已下发，正在监听 /dev/smd3 ...\n");

    unsigned char buf[1024];
    while (1) {
        int len = read(fd, buf, sizeof(buf));
        if (len > 0) {
            // 打印所有返回数据，寻找 04 3E (LE Meta Event)
            printf("RECV[%d]: ", len);
            for(int i=0; i<len; i++) printf("%02X ", buf[i]);
            printf("\n");

            // 如果看到 04 3E 02，那就是扫描到设备了
            if (buf[0] == 0x04 && buf[1] == 0x3E) {
                printf("!!! 发现 BLE 广播包 !!!\n");
            }
        }
    }
    return 0;
}
