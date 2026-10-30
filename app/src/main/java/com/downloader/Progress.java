package com.downloader;

/**
 * Progress snapshot handed to {@link OnProgressListener}.
 *
 * The field names and their public visibility are part of the original
 * PRDownloader contract (callers read {@code progress.currentBytes} directly).
 */
public class Progress {
    public long currentBytes;
    public long totalBytes;

    public Progress() {
    }

    public Progress(long currentBytes, long totalBytes) {
        this.currentBytes = currentBytes;
        this.totalBytes = totalBytes;
    }
}
