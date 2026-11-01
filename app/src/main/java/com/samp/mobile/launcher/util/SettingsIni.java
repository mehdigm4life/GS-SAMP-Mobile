package com.samp.mobile.launcher.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * The app-private settings file used by both the launcher and native client.
 *
 * It lives in {@link Context#getFilesDir()}, not in the shared game folder or
 * any external/cache directory. Android removes it when the app is uninstalled
 * or its data is cleared, after which the bundled defaults are seeded again.
 */
public final class SettingsIni {

    private static final String TAG = "SettingsIni";
    private static final String FILE_NAME = "settings.ini";
    private static final String ASSET_NAME = "settings.ini";

    private SettingsIni() {
    }

    /** Returns the canonical app-private file and initializes it from APK defaults if needed. */
    public static synchronized File resolve(Context context) {
        File settings = new File(context.getFilesDir(), FILE_NAME);
        if (settings.isFile() && settings.length() > 0) {
            return settings;
        }

        File parent = settings.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            Log.e(TAG, "cannot create private files directory " + parent);
            return settings;
        }

        if (copyBundledDefaults(context.getAssets(), settings)) {
            Log.i(TAG, "initialized app-private settings.ini from APK defaults: " + settings);
        }
        return settings;
    }

    private static boolean copyBundledDefaults(AssetManager assets, File target) {
        File parent = target.getParentFile();
        if (parent == null) {
            Log.e(TAG, "private settings file has no parent directory: " + target);
            return false;
        }
        File temporary = new File(parent, FILE_NAME + ".tmp");
        try (InputStream in = assets.open(ASSET_NAME);
             FileOutputStream out = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        } catch (Throwable t) {
            Log.e(TAG, "cannot prepare private settings defaults at " + temporary, t);
            temporary.delete();
            return false;
        }

        // Publish only a complete file. Both paths are in filesDir, so rename
        // stays on the same filesystem and avoids exposing a half-copied INI.
        if (target.exists() && !target.delete()) {
            Log.e(TAG, "cannot replace empty private settings file " + target);
            temporary.delete();
            return false;
        }
        if (!temporary.renameTo(target)) {
            Log.e(TAG, "cannot publish private settings file " + target);
            temporary.delete();
            return false;
        }
        return true;
    }
}
