package com.samp.mobile.launcher;

import android.Manifest;
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
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Launcher entry point that guarantees the game can reach its data before the
 * game activity is started.
 *
 * Why this exists
 * ---------------
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
 * This activity asks for exactly that, then hands over to the game.
 */
public class StorageGateActivity extends AppCompatActivity {

    private static final String TAG = "StorageGate";

    /** Documented cache location, see README. */
    private static final String GAME_ROOT = "/storage/emulated/0/GTA";

    private static final String GAME_ACTIVITY = "com.samp.mobile.game.SAMP";

    private static final int REQUEST_LEGACY_STORAGE = 1001;

    /** Guards against showing the dialog again on every onResume. */
    private boolean dialogShowing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        installCrashRecorder();

        // Simple placeholder so the user sees something while the system
        // settings screen is on screen. Built in code to avoid depending on
        // any layout resource.
        TextView tv = new TextView(this);
        tv.setText("SA-MP Mobile\n\nChecking storage access...");
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);

        Log.i(TAG, "onCreate; sdk=" + Build.VERSION.SDK_INT
                + " storageManager=" + hasStorageAccess());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStorageAccess()) {
            dialogShowing = false;
            startGameIfPossible();
        } else if (!dialogShowing) {
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
                + "On the next screen enable \"All files access\" / \"Permit access to manage all files\" for SA-MP Mobile, then come back.\n\n"
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
            Toast.makeText(this, "Open Settings > Apps > SA-MP Mobile > Permissions manually.",
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
        if (granted) {
            startGameIfPossible();
        } else if (!dialogShowing) {
            askForStorageAccess();
        }
    }

    // ------------------------------------------------------------------
    // Hand-over to the game
    // ------------------------------------------------------------------

    private void startGameIfPossible() {
        File root = new File(GAME_ROOT);
        if (!root.isDirectory()) {
            Log.w(TAG, "game root missing: " + GAME_ROOT);
            new AlertDialog.Builder(this)
                    .setTitle("Game data not found")
                    .setMessage("Storage access is granted, but this folder does not exist yet:\n\n"
                            + GAME_ROOT
                            + "\n\nExtract the GTA cache there (it must contain the \"data\" folder), then reopen the app.")
                    .setCancelable(false)
                    .setPositiveButton("Continue anyway", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            launchGame();
                        }
                    })
                    .setNegativeButton("Exit", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            finish();
                        }
                    })
                    .show();
            return;
        }
        launchGame();
    }

    private void launchGame() {
        try {
            // Resolved by name so the game class (and therefore its static
            // System.loadLibrary() block) is not touched until the intent is
            // actually handled.
            Intent intent = new Intent();
            intent.setClassName(this, GAME_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            finish();
        } catch (Throwable t) {
            Log.e(TAG, "cannot launch game", t);
            Toast.makeText(this, "Cannot start the game: " + t, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // Crash recording
    // ------------------------------------------------------------------

    /**
     * Writes any uncaught Java exception to
     * /storage/emulated/0/Android/data/com.samp.mobile/files/last_crash.txt,
     * which is readable over USB without any permission. Native crashes are
     * handled separately by the signal handlers installed in libsamp.so, which
     * append to /storage/emulated/0/GTA/samp_log.txt.
     */
    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(@NonNull Thread thread, @NonNull Throwable error) {
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir == null) {
                        dir = new File("/storage/emulated/0/Android/data/" + getPackageName() + "/files");
                    }
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    File out = new File(dir, "last_crash.txt");

                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("time   : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date()));
                    pw.println("thread : " + thread.getName());
                    pw.println("sdk    : " + Build.VERSION.SDK_INT);
                    pw.println("device : " + Build.MANUFACTURER + " " + Build.MODEL);
                    pw.println("abi64  : " + (Build.SUPPORTED_64_BIT_ABIS.length > 0
                            ? Build.SUPPORTED_64_BIT_ABIS[0] : "none"));
                    pw.println();
                    error.printStackTrace(pw);
                    pw.flush();

                    FileWriter fw = new FileWriter(out, true);
                    fw.write(sw.toString());
                    fw.write("\n-----------------------------\n");
                    fw.close();

                    Log.e(TAG, "crash written to " + out.getAbsolutePath());
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
