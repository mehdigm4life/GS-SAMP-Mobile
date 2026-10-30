package com.downloader;

import android.content.Context;

/**
 * Minimal, dependency-free replacement for the PRDownloader library.
 *
 * The original artifact ({@code com.github.amitshekhariitbhu:PRDownloader:1.0.1})
 * was published on jcenter, which has been shut down; it is not available on
 * JitPack or Maven Central under any coordinate, so the build could not resolve
 * it. This package re-implements the exact subset of the API this app uses.
 */
public class PRDownloader {

    private static PRDownloaderConfig config = PRDownloaderConfig.newBuilder().build();

    private PRDownloader() {
    }

    public static void initialize(Context context, PRDownloaderConfig config) {
        if (config != null) {
            PRDownloader.config = config;
        }
    }

    public static PRDownloaderConfig getConfig() {
        return config;
    }

    public static DownloadRequestBuilder download(String url, String dirPath, String fileName) {
        return new DownloadRequestBuilder(url, dirPath, fileName);
    }

    public static void cancel(int downloadId) {
        DownloadRequest.cancelDownload(downloadId);
    }

    public static void cancelAll() {
        DownloadRequest.cancelAllDownloads();
    }
}
