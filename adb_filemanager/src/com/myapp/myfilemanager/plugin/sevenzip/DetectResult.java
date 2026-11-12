package com.myapp.myfilemanager.plugin.sevenzip;

/** 探测结果：找到的二进制（可为 null）+ 完整诊断报告 */
public final class DetectResult {
    final String bin;
    final String report;

    DetectResult(String bin, String report) {
        this.bin = bin;
        this.report = report;
    }

    public String getBin() { return bin; }
    public String getReport() { return report; }
}
