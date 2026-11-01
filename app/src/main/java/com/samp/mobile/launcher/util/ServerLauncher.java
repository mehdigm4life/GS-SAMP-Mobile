package com.samp.mobile.launcher.util;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.samp.mobile.game.SAMP;
import com.samp.mobile.launcher.StorageGateActivity;

import org.ini4j.Wini;

import java.io.File;
import java.io.IOException;

/** Starts a selected SA-MP server from the launcher without manual INI edits. */
public final class ServerLauncher {

    private ServerLauncher() {
    }

    /**
     * Saves the selected endpoint to the settings.ini consumed by the game.
     * The saved-server collection remains separate, so each favorite keeps its
     * own address and selecting another server simply replaces this active one.
     */
    public static boolean saveServer(Activity activity, String host, int port) {
        String address = host == null ? "" : host.trim();
        if (address.isEmpty() || port < 1 || port > 65535) {
            Toast.makeText(activity, "The selected server address is invalid.", Toast.LENGTH_LONG).show();
            return false;
        }

        File settings = SettingsIni.resolve(activity);
        if (settings == null || !settings.isFile()) {
            Toast.makeText(activity, "Could not access the game settings file.", Toast.LENGTH_LONG).show();
            return false;
        }

        try {
            Wini ini = new Wini(settings);
            ini.put("client", "host", address);
            ini.put("client", "port", port);
            ini.store();
            return true;
        } catch (IOException | RuntimeException e) {
            Toast.makeText(activity, "Could not save the selected server: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Starts the game above the launcher; do not finish the launcher Activity. */
    public static boolean startGame(Activity activity) {
        try {
            StorageGateActivity.releaseMemoryBeforeGame(activity);
            activity.startActivity(new Intent(activity, SAMP.class));
            return true;
        } catch (RuntimeException e) {
            Toast.makeText(activity, "Could not start the game: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
