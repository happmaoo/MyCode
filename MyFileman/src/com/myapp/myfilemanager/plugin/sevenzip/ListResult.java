package com.myapp.myfilemanager.plugin.sevenzip;

import java.util.List;

public final class ListResult {
    final List<SevenZipEntry> entries;
    final int code;
    final String stderr;

    ListResult(List<SevenZipEntry> entries, int code, String stderr) {
        this.entries = entries;
        this.code = code;
        this.stderr = stderr;
    }

    public List<SevenZipEntry> getEntries() { return entries; }
    public int getCode() { return code; }
    public String getStderr() { return stderr; }
}
