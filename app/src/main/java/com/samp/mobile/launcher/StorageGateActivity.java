package com.samp.mobile.launcher;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import com.samp.mobile.R;
import com.samp.mobile.cache.CacheDownloadActivity;
import com.samp.mobile.cache.GpuDetector;

/**
 * Launcher entry point that guarantees the game can reach its data before the
 * game activity is started, and that keeps a readable log on the shared storage.
 *
 * Why the storage gate exists
 * ---------------------------
 * The APK targets SDK 34, but the shared cache the game reads lives in
 * /storage/emulated/0/GTA/. From Android 11 (API 30) onwards that path is
 * outside the app sandbox and cannot be reached with READ/WRITE_EXTERNAL_STORAGE
 * any more: those permissions only give access to media collections, and
 * android:requestLegacyExternalStorage is ignored once targetSdk >= 30.
 *
 * Access to the whole shared volume now requires the special
 * MANAGE_EXTERNAL_STORAGE permission ("All files access"), which can only be
 * granted by the user from system settings. Previously the game activity was
 * the launcher and the only code that asked for permissions lived in
 * SplashActivity, which is never started. So the game launched with no access
 * to its own files and closed immediately.
 *
 * Why the log lives in /storage/emulated/0/GS-SAMP
 * -----------------------------------------------
 * The log used to be written to Android/data/com.samp.mobile/files/, which on
 * Android 11+ cannot be opened from a file manager, from MTP, or over USB
 * without ADB: the whole Android/data tree is hidden from other apps and from
 * the system picker. A crash report that the user cannot reach is useless, so
 * every diagnostic line is now appended to:
 *
 *     /storage/emulated/0/GS-SAMP/logcat.txt
 *
 * The native side (FLog in libsamp.so) writes to the same file, so one file
 * contains the Java lifecycle, the uncaught exception and the game's own log.
 */
public class StorageGateActivity extends AppCompatActivity {

    private static final String TAG = "StorageGate";

    /** Documented cache location, see README. */
    private static final String GAME_ROOT = "/storage/emulated/0/GTA";

    /**
     * Shared, user-accessible diagnostics folder. Deliberately outside
     * Android/data so it can be opened by any file manager.
     */
    private static final String LOG_DIR = "/storage/emulated/0/GS-SAMP";

    /** Below this the kernel starts killing the largest process; the game is it. */
    private static final long MEMORY_HEADROOM_BYTES = 350L * 1024 * 1024;

    private static final String LOG_FILE = "logcat.txt";

    /** Rotate the log once it grows past this size. */
    private static final long LOG_MAX_BYTES = 4L * 1024L * 1024L;

    private static final String GAME_ACTIVITY = "com.samp.mobile.game.SAMP";

    /**
     * The cache is installed by the app itself (see CacheDownloadActivity).
     * There is deliberately no external download link any more: a browser
     * round-trip through a file host left the user with a ZIP they had to
     * unpack by hand, and nothing verified that the result was complete.
     */
    private static final int REQUEST_INSTALL_CACHE = 1002;

    private static final int REQUEST_LEGACY_STORAGE = 1001;

    /** Guards against showing the dialog again on every onResume. */
    private boolean dialogShowing = false;

    /** True while CacheDownloadActivity is on screen. */
    private boolean installerOpen = false;

    /** True once the launcher has been opened; stops onResume from running the gate again. */
    private boolean launching = false;

    /**
     * Lines recorded before storage access is granted. On Android 11+ nothing
     * may be written to /storage/emulated/0 until the user approves "All files
     * access", so early lines are held here and flushed as soon as the folder
     * becomes writable.
     */
    private static final List<String> PENDING = new ArrayList<>();

    private static final int PENDING_MAX = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        installCrashRecorder();

        TextView tv = new TextView(this);
        tv.setText(getString(R.string.app_name) + "\n\nChecking storage access...");
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);

        logLine("========== session start ==========");
        logLine("sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " abi64=" + (Build.SUPPORTED_64_BIT_ABIS.length > 0
                        ? Build.SUPPORTED_64_BIT_ABIS[0] : "none")
                + " storageAccess=" + hasStorageAccess());
        Log.i(TAG, "onCreate; sdk=" + Build.VERSION.SDK_INT
                + " storageManager=" + hasStorageAccess());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (installerOpen || launching || dialogShowing) {
            // Coming back from the installer is handled in onActivityResult.
            // Re-running the flow here would reopen the installer in a loop, or
            // stack a second dialog on top of the current one.
            return;
        }
        if (hasStorageAccess()) {
            dialogShowing = false;
            ensureLogDir();
            logLine("storage access granted");
            verifyCacheBeforeLauncher();
        } else if (!dialogShowing) {
            logLine("storage access NOT granted");
            askForStorageAccess();
        }
    }

    // ------------------------------------------------------------------
    // Permission handling
    // ------------------------------------------------------------------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // API 30+
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                Log.w(TAG, "isExternalStorageManager failed", t);
                return false;
            }
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askForStorageAccess() {
        dialogShowing = true;

        String message = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? "The game needs access to \"" + GAME_ROOT + "\" to read its data files.\n\n"
                + "On the next screen enable \"All files access\" / \"Permit access to manage all files\" for "
                + getString(R.string.app_name) + ", then come back.\n\n"
                + "The game folder must already contain the GTA cache at:\n" + GAME_ROOT
                : "The game needs storage access to read its data files at:\n" + GAME_ROOT;

        new AlertDialog.Builder(this)
                .setTitle("Storage access required")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openStorageSettings();
                    }
                })
                .setNegativeButton("Exit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        finish();
                    }
                })
                .show();
    }

    private void openStorageSettings() {
        dialogShowing = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "per-app all-files intent unavailable, falling back", t);
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (Throwable t) {
                Log.w(TAG, "global all-files intent unavailable", t);
            }
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Throwable t) {
                Log.w(TAG, "app details intent unavailable", t);
            }
            Toast.makeText(this,
                    "Open Settings > Apps > " + getString(R.string.app_name) + " > Permissions manually.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQUEST_LEGACY_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LEGACY_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "legacy storage granted=" + granted);
        logLine("legacy storage granted=" + granted);
        if (granted) {
            verifyCacheBeforeLauncher();
        } else if (!dialogShowing) {
            askForStorageAccess();
        }
    }

    // ------------------------------------------------------------------
    // Hand-over to the game
    // ------------------------------------------------------------------

    /**
     * Returns a human readable summary of what is missing under GAME_ROOT, or
     * null when the cache looks usable.
     *
     * The list mirrors what the prebuilt libGTASA.so opens during startup; the
     * exact file that produced the last SIGSEGV was TEXT/AMERICAN.GXT, so it is
     * checked explicitly.
     */
    private String describeMissingData() {
        File root = new File(GAME_ROOT);
        if (!root.isDirectory()) {
            return GAME_ROOT + " (folder does not exist)";
        }

        StringBuilder missing = new StringBuilder();
        appendIfMissing(root, missing, "data");
        appendIfMissing(root, missing, "models");
        appendIfMissing(root, missing, "SAMP");
        appendIfMissing(root, missing, "TEXT");

        // The game reads the GXT at startup; a missing one is a guaranteed
        // nullptr dereference inside OS_FileRead(), so name it precisely.
        File text = new File(root, "TEXT");
        if (text.isDirectory() && !new File(text, "AMERICAN.GXT").exists()
                && !new File(text, "american.gxt").exists()) {
            appendEntry(missing, "TEXT/AMERICAN.GXT");
        }

        // libGTASA opens texdb/<db>/<db>.<format>.tmb as soon as RenderWare
        // initialises, where <format> follows its own GL-extension check. A
        // cache built for another GPU family therefore gets all the way to the
        // loading screen and then dies in TextureDatabaseRuntime::SortEntries
        // (SIGSEGV, fault address 0x6f1). Checking the exact file turns that
        // guaranteed crash into the "install files" dialog.
        String gpuMissing = describeMissingTextureDb(root);
        if (gpuMissing != null) {
            appendEntry(missing, gpuMissing);
        }

        return missing.length() == 0 ? null : missing.toString();
    }

    /**
     * @return null when the texture database the game will open exists,
     *         otherwise the path that is missing
     */
    private String describeMissingTextureDb(File root) {
        File texdb = new File(root, "texdb");
        if (!texdb.isDirectory()) {
            return "texdb/";
        }

        String suffix;
        try {
            suffix = GpuDetector.formatSuffix();
        } catch (Throwable t) {
            logLine("gpu detection failed: " + t);
            suffix = null;
        }

        File mobile = new File(texdb, "mobile");

        // The family this GPU should use.
        if (suffix != null && new File(mobile, "mobile." + suffix + ".tmb").exists()) {
            return null;
        }

        // Any other installed family is good enough: libsamp now resolves the
        // texture family from the files that are actually on disk
        // (TextureDatabaseRuntime::ResolveFormatForDevice), so a cache built for
        // another family still starts instead of dereferencing a null database.
        String[] families = {suffix, "pvr", "dxt", "etc", "unc", "360", "ps3"};
        for (String family : families) {
            if (family != null && new File(mobile, "mobile." + family + ".tmb").exists()) {
                if (suffix != null && !family.equals(suffix)) {
                    logLine("texture database " + family + " found while this GPU prefers "
                            + suffix + " - using the installed one");
                }
                return null;
            }
        }

        logLine("no texture database installed for this GPU: "
                + new File(mobile, "mobile." + (suffix == null ? "*" : suffix) + ".tmb").getAbsolutePath()
                + " renderer=[" + GpuDetector.lastRenderer() + "]");
        return "texdb/mobile/mobile." + (suffix == null ? "*" : suffix) + ".tmb";
    }

    private void appendIfMissing(File root, StringBuilder missing, String entry) {
        if (!new File(root, entry).exists()) {
            appendEntry(missing, entry + "/");
        }
    }

    private void appendEntry(StringBuilder missing, String entry) {
        if (missing.length() > 0) {
            missing.append(", ");
        }
        missing.append(entry);
    }

    /**
     * Creates the shared game-folder skeleton and seeds required game assets.
     * settings.ini is intentionally app-private and is managed by SettingsIni.
     */
    private void prepareGameFolder() {
        try {
            File root = new File(GAME_ROOT);
            File samp = new File(root, "SAMP");
            if (!samp.isDirectory() && !samp.mkdirs() && !samp.isDirectory()) {
                logLine("could not create " + samp);
                return;
            }

            // libsamp loads SAMP/fonts/arial_bold.ttf while the overlay starts up
            // and ImFontAtlas::AddFontFromFileTTF() aborts the process when the
            // file is missing. The server does not publish a main.font, so the
            // copy inside the APK seeds it once and the start no longer dies with
            // SIGABRT in UI::initialize().
            File fonts = new File(samp, "fonts");
            if (!fonts.isDirectory() && !fonts.mkdirs() && !fonts.isDirectory()) {
                logLine("could not create " + fonts);
                return;
            }
            seedAsset("SAMP/fonts/arial_bold.ttf", new File(fonts, "arial_bold.ttf"));
        } catch (Throwable t) {
            logLine("could not prepare game folder: " + t);
        }
    }

    /** Copies an asset to [target] when the target is missing or empty. */
    private void seedAsset(String assetName, File target) {
        try {
            if (target.isFile() && target.length() > 0) {
                return;
            }
            java.io.InputStream in = getAssets().open(assetName);
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            logLine("seeded " + target.getAbsolutePath() + " (" + target.length() + " bytes)");
        } catch (Throwable t) {
            logLine("could not seed " + assetName + ": " + t);
        }
    }

    /**
     * Refuses to start the game while the cache is missing.
     *
     * Launching anyway is not "just a warning": libGTASA.so dereferences the
     * nullptr returned by its file-open hook and dies with SIGSEGV (fault
     * address 0x10 inside OS_FileRead). Offering a "Continue anyway" button
     * therefore only produced a guaranteed crash loop.
     */
    /**
     * Entry point of the launch flow.
     *
     * The cache is now <b>always</b> verified against the server manifest first,
     * by the installer page: it compares every file's length and, where the
     * manifest publishes one, its checksum, and re-downloads whatever is missing
     * or altered. Starting the game straight away was a real bug - deleting one
     * file by hand left every path this method used to check in place, so the
     * game was launched with an incomplete cache and crashed during loading.
     *
     * A spot check of a handful of paths can only ever answer "the folder looks
     * non-empty", which is not the same question as "the tree matches the server".
     */
    private void verifyCacheBeforeLauncher() {
        prepareGameFolder();
        openInstaller(describeMissingData());
    }

    private void showMissingDataDialog(final String missing) {
        dialogShowing = true;

        String message = getString(R.string.gate_missing_message,
                missing, GAME_ROOT, LOG_DIR + "/" + LOG_FILE);

        new AlertDialog.Builder(this)
                .setTitle(R.string.gate_missing_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.gate_install, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        dialogShowing = false;
                        openInstaller(missing);
                    }
                })
                .setNeutralButton(R.string.gate_recheck, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        dialogShowing = false;
                        verifyCacheBeforeLauncher();
                    }
                })
                .setNegativeButton(R.string.gate_exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        finish();
                    }
                })
                .show();
    }

    /**
     * Opens the in-app installer. It downloads straight into GAME_ROOT, verifies
     * every file against the server manifest and only then returns RESULT_OK.
     */
    private void openInstaller(String missing) {
        logLine("opening in-app cache installer");
        installerOpen = true;
        try {
            Intent intent = new Intent(this, CacheDownloadActivity.class);
            intent.putExtra(CacheDownloadActivity.EXTRA_ROOT, GAME_ROOT);
            intent.putExtra(CacheDownloadActivity.EXTRA_MISSING, missing);
            startActivityForResult(intent, REQUEST_INSTALL_CACHE);
        } catch (Throwable t) {
            installerOpen = false;
            Log.e(TAG, "cannot open installer", t);
            logLine("cannot open installer: " + Log.getStackTraceString(t));
            dialogShowing = false;
            Toast.makeText(this, "Cannot open the installer: " + t, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL_CACHE) {
            return;
        }
        installerOpen = false;
        logLine("installer returned: " + resultCode);
        dialogShowing = false;

        if (resultCode == RESULT_OK) {
            // The installer only reports success after it compared the whole
            // tree with the manifest and verified every file. Keep the game
            // behind the launcher so the player can choose a server and adjust
            // settings before entering SA-MP.
            logLine("cache verified against the server manifest");
            openLauncher();
            return;
        }

        String missing = describeMissingData();
        logLine("installer did not complete: " + (missing == null ? "unknown" : missing));
        showMissingDataDialog(missing == null ? "verification failed" : missing);
    }

    private void openLauncher() {
        launching = true;
        ensureLogDir();
        logNativeSessionHistory();
        logLine("opening the full launcher before " + GAME_ACTIVITY);
        try {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            // Leave MainActivity at the top of the task. Starting the game
            // from a server card will place SAMP above it, so Back returns to
            // the launcher instead of restarting the cache gate.
            finish();
        } catch (Throwable t) {
            launching = false;
            Log.e(TAG, "cannot open launcher", t);
            logLine("cannot open launcher: " + Log.getStackTraceString(t));
            Toast.makeText(this, "Cannot open the launcher: " + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The native side records the startup stage it reached in
     * /storage/emulated/0/GS-SAMP/stage.txt and only overwrites it when the next
     * stage is reached, so whatever is still in that file when the launcher runs
     * is where the previous session stopped. A start that dies before the game
     * loop (crash or OOM kill) leaves a stage that is not "game loop running" or
     * "clean exit"; printing it here is what turns a silent exit into a named one.
     */
    private void logNativeSessionHistory() {
        try {
            File stage = new File(LOG_DIR, "stage.txt");
            if (!stage.isFile()) {
                logLine("previous native session: no stage recorded");
                return;
            }
            String value = readSmallText(stage).trim().replace("\r", "").replace("\n", " | ");
            logLine("previous native session ended at stage: "
                    + (value.isEmpty() ? "(empty)" : value));
        } catch (Throwable t) {
            logLine("cannot read previous stage: " + t);
        }
    }

    private static String readSmallText(File file) throws java.io.IOException {
        java.io.InputStream in = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4096) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * The game runs in this very process, so whatever the launcher and the cache
     * installer allocated is still resident while the game maps a gigabyte of
     * texture databases. The device this was reported on has 2 GB of RAM, and a
     * start that was killed during the world streaming phase left no signal
     * behind - the signature of a low-memory kill. Dropping what can be dropped
     * before the game starts is the cheapest way to widen that margin.
     */
    public static void releaseMemoryBeforeGame(android.content.Context context) {
        try {
            Runtime rt = Runtime.getRuntime();
            long beforeUsed = rt.totalMemory() - rt.freeMemory();

            // Nothing here is needed once the game starts.
            System.gc();
            System.runFinalization();

            long afterUsed = rt.totalMemory() - rt.freeMemory();
            logLine("memory: java heap " + (beforeUsed / (1024 * 1024)) + " MB used before launch, "
                    + (afterUsed / (1024 * 1024)) + " MB after gc, "
                    + (rt.maxMemory() / (1024 * 1024)) + " MB max");
        } catch (Throwable t) {
            logLine("memory cleanup failed: " + t);
        }

        waitForMemoryHeadroom(context);
    }

    /**
     * The game maps several hundred megabytes of textures as soon as the world
     * starts streaming. When the device is already tight, the kernel answers
     * that with a SIGKILL, and a kill leaves no crash report at all - exactly
     * the "the second start just closed" that no log could explain.
     *
     * So the launch is not started until the system reports a usable amount of
     * free memory: what can be dropped is dropped, and the check is repeated a
     * few times before giving the game its best chance.
     */
    private static void waitForMemoryHeadroom(android.content.Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) {
                logLine("memory: ActivityManager unavailable, skipping the headroom check");
                return;
            }
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            for (int attempt = 1; attempt <= 3; attempt++) {
                am.getMemoryInfo(info);
                logLine("memory: " + (info.availMem / (1024 * 1024)) + " MB available, lowMemory="
                        + info.lowMemory + " (attempt " + attempt + "/3)");
                if (info.availMem >= MEMORY_HEADROOM_BYTES) {
                    return;
                }
                System.gc();
                System.runFinalization();
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            am.getMemoryInfo(info);
            logLine("memory: still only " + (info.availMem / (1024 * 1024))
                    + " MB available, starting anyway");
        } catch (Throwable t) {
            logLine("memory headroom check failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics file: /storage/emulated/0/GS-SAMP/logcat.txt
    // ------------------------------------------------------------------

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static File logFile() {
        return new File(new File(LOG_DIR), LOG_FILE);
    }

    /** Creates /storage/emulated/0/GS-SAMP if storage access allows it. */
    private static void ensureLogDir() {
        try {
            File dir = new File(LOG_DIR);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                Log.w(TAG, "cannot create " + LOG_DIR);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot create " + LOG_DIR, t);
        }
    }

    /**
     * Appends one line to GS-SAMP/logcat.txt and mirrors it to logcat.
     *
     * Nothing can be written to shared storage before "All files access" is
     * granted, so lines are queued and replayed on the first successful write.
     */
    private static synchronized void logLine(String message) {
        Log.i(TAG, message);
        String line = "[" + timestamp() + "] " + message;

        File out = logFile();
        if (out.getParentFile() != null && !out.getParentFile().isDirectory()) {
            ensureLogDir();
        }

        // Flush the queued lines FIRST, so the file stays in chronological
        // order even though the earliest lines could not be written at the
        // time they happened.
        if (!PENDING.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String pendingLine : PENDING) {
                sb.append(pendingLine).append('\n');
            }
            if (!appendTo(out, sb.toString())) {
                if (PENDING.size() < PENDING_MAX) {
                    PENDING.add(line);
                }
                return;
            }
            PENDING.clear();
        }

        if (!appendTo(out, line + "\n")) {
            if (PENDING.size() < PENDING_MAX) {
                PENDING.add(line);
            }
        }
    }

    private static boolean appendTo(File file, String text) {
        FileOutputStream fos = null;
        try {
            rotateIfNeeded(file);
            fos = new FileOutputStream(file, true);
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Throwable t) {
            // Expected before the storage permission is granted.
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void rotateIfNeeded(File file) {
        try {
            if (file.length() > LOG_MAX_BYTES) {
                File old = new File(file.getParentFile(), LOG_FILE + ".old");
                if (old.exists() && !old.delete()) {
                    return;
                }
                if (!file.renameTo(old)) {
                    Log.w(TAG, "log rotation failed");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Crash recording
    // ------------------------------------------------------------------

    /**
     * Appends any uncaught Java exception to the same logcat.txt, so a single
     * file always holds the reason the app exited.
     *
     * Native crashes are handled separately by the signal handlers installed in
     * libsamp.so, which write to the same file through FLog().
     */
    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(@NonNull Thread thread, @NonNull Throwable error) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("========== FATAL JAVA EXCEPTION ==========");
                    pw.println("time   : " + timestamp());
                    pw.println("thread : " + thread.getName());
                    pw.println("sdk    : " + Build.VERSION.SDK_INT);
                    pw.println("device : " + Build.MANUFACTURER + " " + Build.MODEL);
                    pw.println("abi64  : " + (Build.SUPPORTED_64_BIT_ABIS.length > 0
                            ? Build.SUPPORTED_64_BIT_ABIS[0] : "none"));
                    pw.println();
                    error.printStackTrace(pw);
                    pw.println("==========================================");
                    pw.flush();

                    String report = sw.toString();

                    // Try GS-SAMP first; that is the file the user can open.
                    ensureLogDir();
                    if (!appendTo(logFile(), report)) {
                        // The process is already dying here, so a queued line
                        // would be lost. Keep a last-resort copy in app-specific
                        // storage, reachable with: adb shell run-as com.samp.mobile
                        File fallbackDir = getFilesDir();
                        File fallback = new File(fallbackDir, LOG_FILE);
                        if (appendTo(fallback, report)) {
                            Log.e(TAG, "crash written to private fallback: " + fallback);
                        }
                    } else {
                        Log.e(TAG, "crash written to " + logFile().getAbsolutePath());
                    }
                } catch (Throwable ignored) {
                    // Never mask the original crash.
                }

                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }
}
