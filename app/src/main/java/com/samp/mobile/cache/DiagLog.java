package com.samp.mobile.cache;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Appends cache-installer events to the same diagnostics file the launcher and
 * the native library use, so one file always explains what happened:
 *
 * <pre>/storage/emulated/0/GS-SAMP/logcat.txt</pre>
 */
public final class DiagLog {

    private static final String TAG = "CacheDiag";

    public static final String DIR = "/storage/emulated/0/GS-SAMP";
    public static final String FILE = "logcat.txt";

    private static final long MAX_BYTES = 4L * 1024L * 1024L;

    private DiagLog() {
    }

    public static File file() {
        return new File(DIR, FILE);
    }

    public static synchronized void line(String message) {
        Log.i(TAG, message);

        String text = "[" + timestamp() + "] [cache] " + message + "\n";
        File out = file();
        try {
            File dir = new File(DIR);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return;
            }
            rotateIfNeeded(out);

            FileOutputStream fos = new FileOutputStream(out, true);
            try {
                fos.write(text.getBytes("UTF-8"));
                fos.flush();
            } finally {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
            // Storage access may not be granted yet; logcat keeps the line.
        }
    }

    private static void rotateIfNeeded(File file) {
        try {
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getParentFile(), FILE + ".old");
                if (old.exists() && !old.delete()) {
                    return;
                }
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
