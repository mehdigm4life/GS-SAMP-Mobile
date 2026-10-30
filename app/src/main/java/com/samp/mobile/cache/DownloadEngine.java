package com.samp.mobile.cache;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Resumable, pausable cache downloader.
 *
 * This class is deliberately free of Android imports: the whole transfer state
 * machine can therefore be exercised by plain JVM tests against a local HTTP
 * server, which is the only way to prove that pause/resume/cancel and the
 * resume-by-Range logic actually work before shipping an APK.
 *
 * Behaviour that matters for a multi-gigabyte cache on a phone:
 *
 *  - <b>Resume</b> - bytes already on disk stay in {@code <name>.part} and the
 *    next attempt continues with an HTTP {@code Range} request. When the server
 *    ignores the range (HTTP 200 instead of 206) the file restarts from zero
 *    instead of producing a corrupt result.
 *  - <b>Pause</b> - returns to a wait loop between chunks, keeping the
 *    {@code .part} file, so resuming continues from the exact byte reached and
 *    no socket is left hanging.
 *  - <b>Cancel</b> - stops the worker and deletes the partial file.
 *  - <b>Retries</b> - a failed request is retried with linear backoff before the
 *    file is reported as failed.
 *
 * Callbacks are dispatched through the {@link Executor} passed to the
 * constructor, so the Android UI can marshal them onto the main thread.
 */
public class DownloadEngine {

    public enum State { IDLE, RUNNING, PAUSED, CANCELLED, FINISHED }

    /** Aggregate result of one run. */
    public static final class Summary {
        public final int succeeded;
        public final int failed;
        public final long bytesWritten;

        Summary(int succeeded, int failed, long bytesWritten) {
            this.succeeded = succeeded;
            this.failed = failed;
            this.bytesWritten = bytesWritten;
        }

        public boolean isSuccess() {
            return failed == 0;
        }
    }

    public interface Listener {
        void onQueuePrepared(int totalFiles, long totalBytes);

        void onFileStarted(int index, RemoteFile file);

        /**
         * @param fileTotal    size of the current file, may be -1 when unknown
         * @param overallBytes bytes completed across the whole queue so far
         */
        void onProgress(int index, long fileBytes, long fileTotal,
                        long overallBytes, long overallTotal);

        void onFileFinished(int index, RemoteFile file, boolean success, String error);

        void onStateChanged(State state);

        void onFinished(Summary summary);
    }

    /** Internal outcome of one attempt at one file. */
    private enum Outcome { SUCCESS, PAUSED, CANCELLED, ERROR }

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1500L;
    private static final long PROGRESS_INTERVAL_MS = 120L;

    private final File root;
    private final Executor callbackExecutor;

    private volatile State state = State.IDLE;
    private volatile boolean cancelRequested = false;
    private volatile boolean pauseRequested = false;

    /** Bytes of the whole queue, including files that were already correct. */
    private volatile long totalBytes = 0L;
    /** Bytes considered done: pre-verified files plus finished files of this run. */
    private volatile long doneBytes = 0L;

    private volatile String lastError = null;

    private Thread worker;
    private Listener listener;

    /** Runs callbacks on the calling thread. Convenient for tests. */
    public DownloadEngine(File root) {
        this(root, null);
    }

    /**
     * @param callbackExecutor where listener callbacks are delivered; null
     *                         delivers them on the worker thread
     */
    public DownloadEngine(File root, Executor callbackExecutor) {
        this.root = root;
        this.callbackExecutor = callbackExecutor;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getDoneBytes() {
        return doneBytes;
    }

    public boolean isActive() {
        return state == State.RUNNING || state == State.PAUSED;
    }

    /**
     * Starts or resumes the given files.
     *
     * @param alreadyDoneBytes bytes of the cache that were already verified and
     *                         are not part of {@code files}; folded into the
     *                         overall progress so the bar reflects the whole
     *                         cache rather than only what is being fetched
     */
    public synchronized void start(final List<RemoteFile> files, final long alreadyDoneBytes) {
        if (state == State.RUNNING || state == State.PAUSED) {
            return;
        }
        cancelRequested = false;
        pauseRequested = false;

        long sum = 0;
        for (RemoteFile f : files) {
            sum += Math.max(0, f.getSize());
        }
        totalBytes = sum + Math.max(0, alreadyDoneBytes);
        doneBytes = Math.max(0, alreadyDoneBytes);

        setState(State.RUNNING);
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onQueuePrepared(files.size(), totalBytes);
                }
            }
        });

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runQueue(files);
            }
        }, "cache-downloader");
        worker.setDaemon(true);
        worker.start();
    }

    public void pause() {
        if (state != State.RUNNING) {
            return;
        }
        pauseRequested = true;
        setState(State.PAUSED);
    }

    public void resume() {
        if (state != State.PAUSED) {
            return;
        }
        pauseRequested = false;
        setState(State.RUNNING);
    }

    public void cancel() {
        if (state == State.FINISHED || state == State.CANCELLED) {
            return;
        }
        cancelRequested = true;
        pauseRequested = false;
        setState(State.CANCELLED);
    }

    /** Blocks until the worker thread has finished, if one is running. */
    public void join(long timeoutMs) {
        Thread t = worker;
        if (t == null) {
            return;
        }
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    private void runQueue(List<RemoteFile> files) {
        int succeeded = 0;
        int failed = 0;
        long written = 0;

        for (int i = 0; i < files.size(); i++) {
            if (cancelRequested) {
                break;
            }

            final RemoteFile file = files.get(i);
            final int index = i;

            post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onFileStarted(index, file);
                    }
                }
            });

            File target = new File(root, file.getPath());
            File part = new File(target.getPath() + ".part");

            Outcome outcome = Outcome.ERROR;
            String error = null;
            int retries = 0;

            // Keep working on this file until it is done, cancelled, or out of
            // retries. A pause simply parks the loop; the .part file stays.
            while (true) {
                if (cancelRequested) {
                    outcome = Outcome.CANCELLED;
                    break;
                }
                if (pauseRequested) {
                    if (!waitWhilePaused()) {
                        outcome = Outcome.CANCELLED;
                        break;
                    }
                    continue;
                }

                outcome = attemptFile(file, target, part);

                if (outcome == Outcome.SUCCESS || outcome == Outcome.CANCELLED) {
                    break;
                }
                if (outcome == Outcome.PAUSED) {
                    // Paused mid-transfer: wait, then continue from .part.
                    continue;
                }

                error = lastError;
                retries++;
                if (retries > MAX_RETRIES) {
                    break;
                }
                if (!sleepInterruptibly(RETRY_DELAY_MS * retries)) {
                    if (cancelRequested) {
                        outcome = Outcome.CANCELLED;
                        break;
                    }
                    // Paused during the backoff: keep the outcome and loop.
                    outcome = Outcome.PAUSED;
                    continue;
                }
            }

            if (outcome == Outcome.CANCELLED) {
                if (part.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                setState(State.CANCELLED);
                return;
            }

            final boolean ok = outcome == Outcome.SUCCESS;
            if (ok) {
                succeeded++;
                doneBytes += Math.max(0, file.getSize());
                written += Math.max(0, file.getSize());
            } else {
                failed++;
            }

            final String reason = ok ? null : (error == null ? "unknown error" : error);
            final long overall = doneBytes;
            post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onFileFinished(index, file, ok, reason);
                        listener.onProgress(index, file.getSize(), file.getSize(),
                                overall, totalBytes);
                    }
                }
            });
        }

        final Summary summary = new Summary(succeeded, failed, written);

        if (cancelRequested) {
            setState(State.CANCELLED);
            return;
        }

        setState(State.FINISHED);
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onFinished(summary);
                }
            }
        });
    }

    /**
     * Blocks the worker while the user has paused the download.
     *
     * @return false when the download was cancelled instead of resumed
     */
    private boolean waitWhilePaused() {
        while (pauseRequested) {
            if (cancelRequested) {
                return false;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !cancelRequested;
    }

    private boolean sleepInterruptibly(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (cancelRequested || pauseRequested) {
                return false;
            }
            try {
                Thread.sleep(Math.min(100L, Math.max(1L, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static boolean isAlreadyCorrect(RemoteFile file, File target) {
        if (!target.isFile()) {
            return false;
        }
        if (file.getSize() >= 0 && target.length() != file.getSize()) {
            return false;
        }
        if (file.hasHash()) {
            String actual = HashUtil.compute(target, file.getHashType());
            return actual != null && actual.equalsIgnoreCase(file.getHash().trim());
        }
        return file.getSize() >= 0;
    }

    private Outcome attemptFile(RemoteFile file, File target, File part) {
        // Already correct: nothing to do.
        //
        // The length alone is not enough. A file can be truncated *and* padded,
        // or simply overwritten with the same number of bytes, so whenever the
        // manifest publishes a checksum it is the checksum that decides.
        if (target.exists() && isAlreadyCorrect(file, target)) {
            if (part.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            return Outcome.SUCCESS;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            lastError = "cannot create " + parent.getName();
            return Outcome.ERROR;
        }

        HttpURLConnection connection = null;
        try {
            long have = part.exists() ? part.length() : 0L;
            if (have > 0 && file.getSize() > 0 && have > file.getSize()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                have = 0L;
            }

            connection = (HttpURLConnection) new URL(file.getUrl()).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "GS-SAMP-Mobile");
            // Byte ranges must refer to the raw file, never to a gzip stream.
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (have > 0) {
                connection.setRequestProperty("Range", "bytes=" + have + "-");
            }

            int code = connection.getResponseCode();

            boolean append = false;
            if (have > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
            } else if (have > 0 && code == HttpURLConnection.HTTP_OK) {
                // Range ignored: the body starts at byte 0, start over cleanly.
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                have = 0L;
            } else if (code >= 400) {
                lastError = "HTTP " + code;
                return Outcome.ERROR;
            }

            long contentLength = connection.getContentLengthLong();
            long expectedTotal = file.getSize() > 0
                    ? file.getSize()
                    : (have + Math.max(0L, contentLength));

            InputStream in = connection.getInputStream();
            FileOutputStream out = new FileOutputStream(part, append);

            long received = have;
            long lastProgress = 0L;
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;

            try {
                while ((read = in.read(buffer)) != -1) {
                    if (cancelRequested) {
                        return Outcome.CANCELLED;
                    }
                    if (pauseRequested) {
                        // Everything written so far is flushed, so resuming
                        // continues from this exact byte.
                        out.flush();
                        return Outcome.PAUSED;
                    }

                    out.write(buffer, 0, read);
                    received += read;

                    long now = System.currentTimeMillis();
                    if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                        lastProgress = now;
                        postProgress(received, expectedTotal);
                    }
                }
                out.flush();
            } finally {
                closeQuietly(out);
                closeQuietly(in);
            }

            long partLength = part.length();
            if (file.getSize() > 0 && partLength != file.getSize()) {
                lastError = String.format(Locale.US, "incomplete (%d/%d bytes)",
                        partLength, file.getSize());
                return Outcome.ERROR;
            }

            if (target.exists() && !target.delete()) {
                lastError = "cannot replace " + target.getName();
                return Outcome.ERROR;
            }
            if (!part.renameTo(target)) {
                lastError = "cannot move into place";
                return Outcome.ERROR;
            }

            if (file.hasHash()) {
                String actual = HashUtil.compute(target, file.getHashType());
                if (actual == null || !actual.equalsIgnoreCase(file.getHash().trim())) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    lastError = "checksum mismatch";
                    return Outcome.ERROR;
                }
            }

            return Outcome.SUCCESS;

        } catch (IOException e) {
            lastError = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return Outcome.ERROR;
        } catch (Exception e) {
            lastError = String.valueOf(e);
            return Outcome.ERROR;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void postProgress(final long fileBytes, final long fileTotal) {
        final long overall = doneBytes + fileBytes;
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onProgress(-1, fileBytes, fileTotal, overall, totalBytes);
                }
            }
        });
    }

    private void setState(final State next) {
        state = next;
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onStateChanged(next);
                }
            }
        });
    }

    private void post(Runnable r) {
        Executor executor = callbackExecutor;
        if (executor == null) {
            r.run();
        } else {
            executor.execute(r);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
