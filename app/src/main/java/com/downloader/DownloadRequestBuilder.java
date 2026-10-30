package com.downloader;

/** Entry point returned by {@link PRDownloader#download}. */
public class DownloadRequestBuilder {
    private final String url;
    private final String dirPath;
    private final String fileName;

    DownloadRequestBuilder(String url, String dirPath, String fileName) {
        this.url = url;
        this.dirPath = dirPath;
        this.fileName = fileName;
    }

    public DownloadRequest build() {
        return new DownloadRequest(url, dirPath, fileName);
    }
}
