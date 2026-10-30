package com.samp.mobile.cache;

import org.json.JSONException;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Drives the whole cache lifecycle and owns the "loop until the local tree
 * matches the server exactly" rule.
 *
 * A pass is:
 *
 * <pre>
 *   client_config.json  ->  file list  ->  filter for this device
 *                      ->  verify every file on disk (size + checksum)
 *                      ->  download only what is missing or corrupt
 *                      ->  verify again, and repeat while anything is wrong
 * </pre>
 *
 * The activity never talks to the network or to the file system directly: it
 * renders {@link Stats} snapshots and calls {@link #pause()}, {@link #resume()},
 * {@link #cancel()} or {@link #retry()}.
 *
 * Like {@link DownloadEngine} this class has no Android imports, so the entire
 * orchestration can be tested on a plain JVM against a local HTTP server.
 */
public final class CacheManager {

    /** Default endpoint published by the project. */
    public static final String DEFAULT_CONFIG_URL = "https://samp-mobile.shop/client_config.json";

    /** How many verify/download rounds before giving up. */
    private static final int MAX_PASSES = 3;

    /** Attempts at the manifest before the installer reports a failure. */
    private static final int CONNECT_ATTEMPTS = 4;

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;

    public enum Stage { IDLE, CONNECTING, VERIFYING, DOWNLOADING, PAUSED, COMPLETE, FAILED, CANCELLED }

    /** Immutable snapshot handed to the UI. */
    public static final class Stats {
        public final Stage stage;
        public final int percent;
        public final long doneBytes;
        public final long totalBytes;
        public final double bytesPerSecond;
        public final long etaSeconds;
        public final int filesDone;
        public final int filesTotal;
        public final String currentFile;
        public final String message;
        public final String verifySummary;
        /** Progress of the file currently being fetched, 0..100. */
        public final int filePercent;

        Stats(Stage stage, int percent, long doneBytes, long totalBytes,
              double bytesPerSecond, long etaSeconds, int filesDone, int filesTotal,
              String currentFile, String message, String verifySummary, int filePercent) {
            this.stage = stage;
            this.percent = percent;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
            this.bytesPerSecond = bytesPerSecond;
            this.etaSeconds = etaSeconds;
            this.filesDone = filesDone;
            this.filesTotal = filesTotal;
            this.currentFile = currentFile;
            this.message = message;
            this.verifySummary = verifySummary;
            this.filePercent = filePercent;
        }
    }

    public interface Listener {
        /** Called whenever something the user can see has changed. */
        void onStats(Stats stats);

        /** One line for the on-screen diagnostics and the log file. */
        void onLog(String line);
    }

    private final File root;
    private final String configUrl;
    private final Executor callbacks;
    private final int gpuType;

    /** App-private folder for the manifest copy and the file receipts. */
    private final File stateDir;
    private ReceiptStore receipts;
    private ManifestCache manifestCache;

    /** True when the file list came from the local copy because the server was unreachable. */
    private volatile boolean offline = false;

    private volatile Listener listener;

    private volatile Stage stage = Stage.IDLE;
    private volatile long totalBytes;
    private volatile long doneBytes;
    private volatile double bytesPerSecond;
    private volatile long etaSeconds;
    private volatile int filesDone;
    private volatile int filesTotal;
    private volatile String currentFile = "";
    private volatile String message = "";
    private volatile String verifySummary = "";
    private volatile int filePercent = 0;

    private volatile DownloadEngine engine;
    private volatile Thread worker;

    /** Set by {@link #cancel()}, cleared by {@link #start()} / {@link #retry()}. */
    private volatile boolean cancelFlag = false;
    /** Set by {@link #pause()} so a pass that is still verifying does not start
     *  downloading behind the back of a closed screen. */
    private volatile boolean pauseFlag = false;

    private long lastSampleTime;
    private long lastSampleBytes;

    public CacheManager(File root, Executor callbacks, int gpuType) {
        this(root, DEFAULT_CONFIG_URL, callbacks, gpuType, null);
    }

    public CacheManager(File root, String configUrl, Executor callbacks, int gpuType) {
        this(root, configUrl, callbacks, gpuType, null);
    }

    /**
     * @param stateDir app-private folder used for the local manifest copy and
     *                 the per-file receipts; null disables both
     */
    public CacheManager(File root, String configUrl, Executor callbacks, int gpuType,
                        File stateDir) {
        this.root = root;
        this.configUrl = configUrl;
        this.callbacks = callbacks;
        this.gpuType = gpuType;
        this.stateDir = stateDir;
        if (stateDir != null) {
            this.receipts = new ReceiptStore(new File(stateDir, "receipts.json"));
            this.manifestCache = new ManifestCache(new File(stateDir, "manifest.json"));
        }
    }

    public boolean isOffline() {
        return offline;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        emit();
    }

    public Stage getStage() {
        return stage;
    }

    public File getRoot() {
        return root;
    }

    // ------------------------------------------------------------------
    // Public controls
    // ------------------------------------------------------------------

    /** Starts the first pass: connect, verify, then download what is missing. */
    public synchronized void start() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        cancelFlag = false;
        pauseFlag = false;
        resetCounters();
        // Switch the page out of "idle" before the worker exists, so pressing
        // the button always produces a visible reaction straight away.
        setStage(Stage.CONNECTING, "Contacting server...");
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runPasses(null);
            }
        }, "cache-manager");
        worker.setDaemon(true);
        worker.start();
    }

    /** Re-runs the whole pass, reusing the manifest when one is already known. */
    public synchronized void retry() {
        DownloadEngine current = engine;
        if (current != null && current.isActive()) {
            return;
        }
        // Only terminal states may restart; anything else means a pass is still
        // running. Checking the stage instead of Thread.isAlive() removes the
        // race where the worker has already published FAILED but has not been
        // reaped yet, which used to swallow the user's tap on retry.
        Stage snapshotStage = stage;
        if (snapshotStage != Stage.IDLE && snapshotStage != Stage.FAILED
                && snapshotStage != Stage.CANCELLED) {
            return;
        }
        cancelFlag = false;
        pauseFlag = false;
        setStage(Stage.CONNECTING, "Contacting server...");
        // After an offline pass the in-memory list came from the local copy;
        // retrying is the player asking for a fresh attempt, so go online again.
        final boolean reuse = !offline;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runPasses(reuse ? manifest : null);
            }
        }, "cache-manager");
        worker.setDaemon(true);
        worker.start();
    }

    public void pause() {
        pauseFlag = true;
        DownloadEngine e = engine;
        if (e != null) {
            e.pause();
        }
        if (stage == Stage.DOWNLOADING || stage == Stage.VERIFYING || stage == Stage.CONNECTING) {
            stage = Stage.PAUSED;
            emit();
        }
    }

    public void resume() {
        pauseFlag = false;
        DownloadEngine e = engine;
        if (e != null) {
            e.resume();
        }
        if (stage == Stage.PAUSED) {
            stage = Stage.DOWNLOADING;
            emit();
        }
    }

    public void cancel() {
        cancelFlag = true;
        pauseFlag = false;
        DownloadEngine e = engine;
        if (e != null) {
            e.cancel();
        }
        stage = Stage.CANCELLED;
        emit();
    }

    public boolean isBusy() {
        Stage s = stage;
        return s == Stage.CONNECTING || s == Stage.VERIFYING || s == Stage.DOWNLOADING || s == Stage.PAUSED;
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    private volatile List<RemoteFile> manifest;

    private void runPasses(List<RemoteFile> reuse) {
        try {
            List<RemoteFile> files = reuse != null ? reuse : connect();
            if (files == null) {
                return;
            }
            manifest = files;

            for (int pass = 0; pass < MAX_PASSES && !isCancelled(); pass++) {
                setStage(Stage.VERIFYING, "Checking local files...");
                FileVerifier.Result result = FileVerifier.verify(root, files, receipts, null);
                verifySummary = result.describeProblems();
                log(String.format(Locale.US, "pass %d: %d/%d verified (%s)",
                        pass + 1, result.okCount(), result.totalCount(), verifySummary));

                if (result.isComplete()) {
                    if (receipts != null) {
                        receipts.save();
                    }
                    doneBytes = totalBytesOf(files);
                    filesDone = files.size();
                    setStage(Stage.COMPLETE, "All files verified");
                    return;
                }

                if (offline) {
                    // The local copy of the manifest lets us verify offline, but
                    // it cannot conjure the missing bytes.
                    setStage(Stage.FAILED,
                            "No internet connection: " + verifySummary
                                    + ". Connect and try again.");
                    return;
                }

                // A file the verifier rejected must not survive into the
                // download pass: the engine skips anything whose length already
                // matches, which is exactly the case for a same-length damaged
                // file. Removing it here makes "verification said broken" and
                // "download will replace it" the same statement.
                for (FileVerifier.Entry entry : result.entries) {
                    if (entry.isOk() || entry.status == FileVerifier.Status.MISSING) {
                        continue;
                    }
                    File damaged = new File(root, entry.remote.getPath());
                    if (damaged.isFile() && damaged.delete()) {
                        log("removing damaged file (" + entry.status + "): "
                                + entry.remote.getPath());
                    }
                }

                long allBytes = totalBytesOf(files);
                long remaining = result.bytesToDownload;
                long already = Math.max(0, allBytes - remaining);

                filesTotal = result.incomplete.size();
                filesDone = 0;
                doneBytes = already;
                totalBytes = allBytes;
                currentFile = "";

                // A pause pressed while verifying parks the pass here, so the
                // screen never claims to be downloading while nothing moves.
                awaitResume();
                if (isCancelled()) {
                    setStage(Stage.CANCELLED, "Cancelled");
                    return;
                }

                setStage(Stage.DOWNLOADING,
                        result.incomplete.size() + " file(s) to download");

                boolean ok = download(result.incomplete, already);
                if (isCancelled()) {
                    setStage(Stage.CANCELLED, "Cancelled");
                    return;
                }
                if (!ok) {
                    setStage(Stage.FAILED, "Some files could not be downloaded");
                    return;
                }
            }

            setStage(Stage.FAILED, "Cache still does not match the server");
        } catch (Throwable t) {
            log("fatal: " + t);
            setStage(Stage.FAILED, describe(t));
        }
    }

    /**
     * Fetches the config and the file list, then filters for this device.
     *
     * A cold start is the worst moment for the network: the Wi-Fi association
     * may still be settling, DNS may not answer yet, or the radio just came out
     * of sleep. A single failure there used to leave the page on "failed" until
     * the user found the retry button, so transient errors are retried here with
     * a growing delay. A malformed document is never retried: it would fail the
     * same way every time.
     */
    private List<RemoteFile> connect() {
        Exception last = null;

        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            if (isCancelled()) {
                return null;
            }

            try {
                return fetchManifest();
            } catch (JSONException e) {
                log("manifest parse error: " + e.getMessage());
                setStage(Stage.FAILED, "Server response was not readable: " + e.getMessage());
                return null;
            } catch (Exception e) {
                last = e;
                log("attempt " + attempt + "/" + CONNECT_ATTEMPTS + " failed: " + e);
                if (attempt < CONNECT_ATTEMPTS) {
                    setStage(Stage.CONNECTING,
                            "Connection problem, retrying (" + attempt + "/" + CONNECT_ATTEMPTS + ")");
                    if (!waitUnlessCancelled(1500L * attempt)) {
                        return null;
                    }
                }
            }
        }

        // Every attempt failed. The local copy of the manifest still lets the
        // tree be verified, and a complete tree still lets the game start.
        if (manifestCache != null) {
            List<RemoteFile> cached = manifestCache.load();
            if (cached != null) {
                log("server unreachable (" + describe(last)
                        + "), verifying against the manifest saved on "
                        + java.text.DateFormat.getDateTimeInstance()
                        .format(new java.util.Date(manifestCacheFile().lastModified())));
                offline = true;
                setStage(Stage.VERIFYING, "Offline: checking against the saved file list");
                totalBytes = totalBytesOf(cached);
                filesTotal = cached.size();
                emit();
                return cached;
            }
        }

        setStage(Stage.FAILED, describe(last));
        return null;
    }

    private File manifestCacheFile() {
        return stateDir == null ? null : new File(stateDir, "manifest.json");
    }

    /** One full attempt: client_config.json, then the file list. */
    private List<RemoteFile> fetchManifest() throws Exception {
        String configJson = HttpFetcher.get(configUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        ManifestParser.ClientConfig config = ManifestParser.parseClientConfig(configJson);
        log("client_config: version=" + config.versionCode + " list=" + config.urlCacheFiles);

        String listUrl = HttpFetcher.resolve(configUrl, config.urlCacheFiles);
        String listJson = HttpFetcher.get(listUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);

        List<RemoteFile> all = ManifestParser.parseFileList(listJson, listUrl);
        List<RemoteFile> filtered = ManifestParser.filterForDevice(all, gpuType);
        int withHash = 0;
        for (RemoteFile f : filtered) {
            if (f.hasHash()) {
                withHash++;
            }
        }
        log("manifest: " + all.size() + " entries, " + filtered.size()
                + " for this device (gpuType=" + gpuType
                + ", texture format ." + GpuDetector.formatSuffixFor(gpuType) + ")");
        log("checksums published by the server: " + withHash + "/" + filtered.size()
                + (withHash < filtered.size()
                ? " (the rest is verified by length and by the receipt recorded when it was downloaded)"
                : ""));

        if (filtered.isEmpty()) {
            throw new IllegalStateException("The server returned an empty file list");
        }

        String suffix = GpuDetector.formatSuffixFor(gpuType);
        boolean hasIndex = false;
        for (RemoteFile f : filtered) {
            if (f.getPath().toLowerCase(Locale.US).endsWith("." + suffix + ".tmb")) {
                hasIndex = true;
                break;
            }
        }
        if (!hasIndex) {
            // The game opens texdb/<db>/<db>.<format>.tmb at startup; without any
            // index of this family it would crash inside SortEntries no matter
            // how complete the rest of the cache is.
            throw new IllegalStateException("The server has no ." + suffix
                    + " texture database for this GPU");
        }

        if (manifestCache != null) {
            manifestCache.save(filtered);
        }
        offline = false;

        totalBytes = totalBytesOf(filtered);
        filesTotal = filtered.size();
        doneBytes = 0;
        emit();
        return filtered;
    }

    /** Sleeps, but returns early (false) when the user cancels. */
    private boolean waitUnlessCancelled(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (isCancelled()) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCancelled();
    }

    /**
     * Runs the download engine over {@code toFetch} and waits for it.
     *
     * @return true when every file was written successfully
     */
    private boolean download(List<RemoteFile> toFetch, long alreadyDone) throws InterruptedException {
        awaitResume();
        if (isCancelled()) {
            return false;
        }

        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] success = new boolean[]{false};

        DownloadEngine e = new DownloadEngine(root, callbacks);
        e.setReceiptStore(receipts);
        engine = e;
        e.setListener(new DownloadEngine.Listener() {
            @Override
            public void onQueuePrepared(int total, long bytes) {
                filesTotal = total;
                totalBytes = bytes;
                emit();
            }

            @Override
            public void onFileStarted(int index, RemoteFile file) {
                currentFile = file.getPath();
                filePercent = 0;
                emit();
            }

            @Override
            public void onProgress(int index, long fileBytes, long fileTotal,
                                   long overallBytes, long overallTotal) {
                doneBytes = overallBytes;
                totalBytes = overallTotal > 0 ? overallTotal : totalBytes;
                if (fileTotal > 0) {
                    filePercent = (int) Math.min(100, (fileBytes * 100L) / fileTotal);
                }
                updateSpeed(overallBytes);
                emit();
            }

            @Override
            public void onFileFinished(int index, RemoteFile file, boolean ok, String error) {
                filesDone++;
                filePercent = ok ? 100 : 0;
                if (!ok) {
                    log("failed: " + file.getPath() + " (" + error + ")");
                }
                emit();
            }

            @Override
            public void onStateChanged(DownloadEngine.State state) {
                if (state == DownloadEngine.State.PAUSED) {
                    CacheManager.this.stage = Stage.PAUSED;
                    emit();
                } else if (state == DownloadEngine.State.RUNNING
                        && CacheManager.this.stage == Stage.PAUSED) {
                    CacheManager.this.stage = Stage.DOWNLOADING;
                    emit();
                }
            }

            @Override
            public void onFinished(DownloadEngine.Summary summary) {
                success[0] = summary.isSuccess();
                done.countDown();
            }
        });

        log("downloading " + toFetch.size() + " file(s), "
                + Fmt.bytes(totalBytes - alreadyDone) + " to fetch");
        lastSampleTime = System.currentTimeMillis();
        lastSampleBytes = doneBytes;

        e.start(toFetch, alreadyDone);

        // Wake up regularly so a cancel is noticed even if the engine is
        // waiting between retries.
        while (!done.await(200, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                e.cancel();
                return false;
            }
        }
        return success[0];
    }

    private void updateSpeed(long overallBytes) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastSampleTime;
        if (elapsed < 500) {
            return;
        }
        long delta = overallBytes - lastSampleBytes;
        if (delta < 0) {
            delta = 0;
        }
        double instant = (delta * 1000.0) / elapsed;
        bytesPerSecond = bytesPerSecond <= 0 ? instant : (bytesPerSecond * 0.6 + instant * 0.4);

        long remaining = Math.max(0, totalBytes - overallBytes);
        etaSeconds = bytesPerSecond > 1024 ? (long) (remaining / bytesPerSecond) : -1;

        lastSampleTime = now;
        lastSampleBytes = overallBytes;
    }

    private boolean isCancelled() {
        return cancelFlag;
    }

    /** Waits while the user has the installer paused. */
    private void awaitResume() {
        while (pauseFlag && !cancelFlag) {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void resetCounters() {
        totalBytes = 0;
        doneBytes = 0;
        bytesPerSecond = 0;
        etaSeconds = -1;
        filesDone = 0;
        filesTotal = 0;
        currentFile = "";
        message = "";
        verifySummary = "";
        filePercent = 0;
    }

    private long totalBytesOf(List<RemoteFile> files) {
        long sum = 0;
        for (RemoteFile f : files) {
            sum += Math.max(0, f.getSize());
        }
        return sum;
    }

    private void setStage(Stage next, String detail) {
        stage = next;
        message = detail == null ? "" : detail;
        log(next + ": " + message);
        emit();
    }

    /**
     * Sends one line to the listener.
     *
     * The callback is marshalled onto the supplied executor: the listener is a
     * view and appends to a TextView, and touching a view from this worker threw
     * {@code CalledFromWrongThreadException}. That exception used to be caught by
     * the connect() retry loop and reported as "connection problem", which hid
     * the real cause and cost an extra 1.5s on every install.
     */
    private void log(String line) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        final String text = line;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                l.onLog(text);
            }
        };
        if (callbacks != null) {
            try {
                callbacks.execute(r);
            } catch (Throwable ignored) {
                r.run();
            }
        } else {
            r.run();
        }
    }

    /** Builds a snapshot and hands it to the listener on the callback thread. */
    private void emit() {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        final Stats snapshot = snapshot();
        Runnable r = new Runnable() {
            @Override
            public void run() {
                l.onStats(snapshot);
            }
        };
        if (callbacks != null) {
            try {
                callbacks.execute(r);
            } catch (Throwable ignored) {
                r.run();
            }
        } else {
            r.run();
        }
    }

    private Stats snapshot() {
        long total = totalBytes;
        long done = doneBytes;
        int percent = 0;
        if (total > 0) {
            percent = (int) Math.min(100, (done * 100L) / total);
        } else if (stage == Stage.COMPLETE) {
            percent = 100;
        }
        return new Stats(stage, percent, done, total, bytesPerSecond, etaSeconds,
                filesDone, filesTotal, currentFile, message, verifySummary, filePercent);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        return m;
    }

    /** Small formatting helpers shared with the UI. */
    public static final class Fmt {
        private Fmt() {
        }

        public static String bytes(long bytes) {
            if (bytes < 0) {
                return "--";
            }
            if (bytes < 1024) {
                return bytes + " B";
            }
            double kb = bytes / 1024.0;
            if (kb < 1024) {
                return String.format(Locale.US, "%.0f KB", kb);
            }
            double mb = kb / 1024.0;
            if (mb < 1024) {
                return String.format(Locale.US, "%.1f MB", mb);
            }
            return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        }

        public static String speed(double bytesPerSecond) {
            if (bytesPerSecond <= 0) {
                return "--";
            }
            return bytes((long) bytesPerSecond) + "/s";
        }

        public static String duration(long seconds) {
            if (seconds < 0) {
                return "--";
            }
            if (seconds < 60) {
                return seconds + "s";
            }
            long minutes = seconds / 60;
            long rest = seconds % 60;
            if (minutes < 60) {
                return String.format(Locale.US, "%d:%02d", minutes, rest);
            }
            long hours = minutes / 60;
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60, rest);
        }
    }
}
