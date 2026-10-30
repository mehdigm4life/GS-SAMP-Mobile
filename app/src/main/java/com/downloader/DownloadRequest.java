package com.downloader;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single download, configured through {@link DownloadRequestBuilder}.
 *
 * Downloads run on a dedicated background thread; every callback is posted back
 * to the main thread so callers can safely touch UI state, matching the
 * behaviour of the original library.
 */
public class DownloadRequest {

    /** Live connections, keyed by download id, so they can be cancelled. */
    private static final Map<Integer, HttpURLConnection> ACTIVE =
            new ConcurrentHashMap<Integer, HttpURLConnection>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int nextId = 1;

    private final String url;
    private final String dirPath;
    private final String fileName;

    private OnStartOrResumeListener onStartOrResumeListener;
    private OnPauseListener onPauseListener;
    private OnCancelListener onCancelListener;
    private OnProgressListener onProgressListener;

    DownloadRequest(String url, String dirPath, String fileName) {
        this.url = url;
        this.dirPath = dirPath;
        this.fileName = fileName;
    }

    public DownloadRequest setOnStartOrResumeListener(OnStartOrResumeListener listener) {
        this.onStartOrResumeListener = listener;
        return this;
    }

    public DownloadRequest setOnPauseListener(OnPauseListener listener) {
        this.onPauseListener = listener;
        return this;
    }

    public DownloadRequest setOnCancelListener(OnCancelListener listener) {
        this.onCancelListener = listener;
        return this;
    }

    public DownloadRequest setOnProgressListener(OnProgressListener listener) {
        this.onProgressListener = listener;
        return this;
    }

    /**
     * Starts the download and returns its id immediately.
     */
    public int start(OnDownloadListener listener) {
        final int id;
        synchronized (DownloadRequest.class) {
            id = nextId++;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                performDownload(id, listener);
            }
        }, "PRDownloader-" + id);
        worker.setDaemon(true);
        worker.start();
        return id;
    }

    private void performDownload(int id, final OnDownloadListener listener) {
        HttpURLConnection connection = null;
        File target = null;
        File partial = null;

        try {
            File dir = new File(dirPath);
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                throw new IOException("Cannot create directory " + dir);
            }

            target = new File(dir, fileName);
            partial = new File(dir, fileName + ".part");

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(PRDownloader.getConfig().getConnectTimeout());
            connection.setReadTimeout(PRDownloader.getConfig().getReadTimeout());
            connection.setRequestProperty("User-Agent", "PRDownloader");
            connection.connect();

            ACTIVE.put(id, connection);

            final int responseCode = connection.getResponseCode();
            if (responseCode >= 400) {
                throw new IOException("Server returned HTTP " + responseCode);
            }

            final long totalBytes = connection.getContentLengthLong();

            if (onStartOrResumeListener != null) {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        onStartOrResumeListener.onStartOrResume();
                    }
                });
            }

            InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(partial);

            long currentBytes = 0L;
            long lastNotifiedAt = 0L;
            byte[] buffer = new byte[16 * 1024];
            int read;

            try {
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    currentBytes += read;

                    long now = System.currentTimeMillis();
                    if (now - lastNotifiedAt >= 100L) {
                        lastNotifiedAt = now;
                        notifyProgress(currentBytes, totalBytes);
                    }
                }
                output.flush();
            } finally {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }

            notifyProgress(currentBytes, totalBytes);

            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot overwrite " + target);
            }
            if (!partial.renameTo(target)) {
                throw new IOException("Cannot move download into place: " + target);
            }
            partial = null;

            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onDownloadComplete();
                }
            });

        } catch (final Exception e) {
            // Leave nothing half-written behind.
            if (partial != null && partial.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
            }
            final Error error = new Error(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onError(error);
                }
            });
        } finally {
            ACTIVE.remove(id);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void notifyProgress(final long currentBytes, final long totalBytes) {
        if (onProgressListener == null) {
            return;
        }
        final Progress progress = new Progress(currentBytes, totalBytes);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                onProgressListener.onProgress(progress);
            }
        });
    }

    static void cancelDownload(int downloadId) {
        HttpURLConnection connection = ACTIVE.remove(downloadId);
        if (connection != null) {
            connection.disconnect();
        }
    }

    static void cancelAllDownloads() {
        for (Integer id : ACTIVE.keySet()) {
            cancelDownload(id);
        }
    }
}
