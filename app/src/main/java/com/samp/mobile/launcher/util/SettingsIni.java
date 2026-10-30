package com.samp.mobile.launcher.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.wardrumstudios.utils.WarMedia;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The one path the game actually reads its settings from.
 *
 * libsamp reads {@code <storage>/SAMP/settings.ini} - on this build
 * {@code /storage/emulated/0/GTA/SAMP/settings.ini} - and nothing else. The
 * server browser used to write {@code getExternalFilesDir()/SAMP/settings.ini}
 * instead, which is a folder the game never opens, so pressing "connect" on a
 * server edited a file nobody read and the client kept dialling the address it
 * had hardcoded. Picking a server from the list could therefore never work.
 *
 * Every reader and writer in the launcher now goes through {@link #resolve},
 * which hands back the game's file and, when it is missing, seeds it from the
 * old location or from the bundled asset so the file always exists.
 */
public final class SettingsIni {

    private static final String TAG = "SettingsIni";

    /** Fallback when the game root constant is unusable. */
    private static final String DEFAULT_GAME_ROOT = "/storage/emulated/0/GTA/";

    private static final String RELATIVE_PATH = "SAMP/settings.ini";
    private static final String ASSET_NAME = "settings.ini";

    private SettingsIni() {
    }

    /** The settings file the native client reads, created if it is missing. */
    public static File resolve(Context context) {
        File canonical = new File(gameRoot() + RELATIVE_PATH);

        if (canonical.isFile()) {
            return canonical;
        }

        File parent = canonical.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            Log.w(TAG, "cannot create " + parent + ", keeping the app-private settings file");
            return legacyFile(context);
        }

        // Start from what the player already has, then from the bundled defaults.
        File legacy = legacyFile(context);
        if (legacy.isFile() && copy(legacy, canonical)) {
            Log.i(TAG, "seeded " + canonical + " from " + legacy);
        } else if (!canonical.isFile() && copyAsset(context, canonical)) {
            Log.i(TAG, "created " + canonical + " from the bundled settings.ini");
        }

        return canonical.isFile() ? canonical : legacy;
    }

    private static String gameRoot() {
        try {
            String root = WarMedia.GAME_ROOT;
            if (root != null && root.length() > 0) {
                return root.endsWith("/") ? root : root + "/";
            }
        } catch (Throwable ignored) {
            // fall through to the constant below
        }
        return DEFAULT_GAME_ROOT;
    }

    private static File legacyFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        File legacy = new File(dir == null ? new File(DEFAULT_GAME_ROOT) : dir, RELATIVE_PATH);
        File parent = legacy.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        return legacy;
    }

    private static boolean copyAsset(Context context, File target) {
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(ASSET_NAME);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot copy the bundled settings.ini to " + target, t);
            return false;
        }
    }

    private static boolean copy(File source, File target) {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot copy " + source + " to " + target, t);
            return false;
        }
    }
}
