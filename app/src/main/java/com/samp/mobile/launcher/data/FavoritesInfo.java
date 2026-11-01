package com.samp.mobile.launcher.data;

import android.content.Context;
import android.util.Log;

import com.samp.mobile.launcher.util.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;

/** Stores favorite servers in app-private storage, erased with app data/uninstall. */
public class FavoritesInfo {
    private static final String TAG = "FavoritesInfo";
    private static final String RELATIVE_PATH = "SAMP/favorites.json";

    private static boolean bLoaded = false;
    private static final ArrayList<FavoriteServerData> serverList = new ArrayList<>();

    private static File favoritesFile(Context context) {
        return new File(context.getFilesDir(), RELATIVE_PATH);
    }

    public static synchronized void Load(Context context) {
        if (bLoaded) return;

        serverList.clear();
        File file = favoritesFile(context);
        if (!file.isFile()) {
            bLoaded = true;
            return;
        }

        try (FileInputStream input = new FileInputStream(file)) {
            String contents = Util.InputStreamToString(input);
            if (!contents.isEmpty()) {
                JSONArray servers = new JSONObject(contents).getJSONArray("servers");
                for (int i = 0; i < servers.length(); i++) {
                    JSONObject item = servers.getJSONObject(i);
                    int id = item.getInt("id");
                    int serverId = item.getInt("serverid");
                    String ip = item.isNull("ip") ? "" : item.getString("ip");
                    int port = item.isNull("port") ? 7777 : item.getInt("port");
                    serverList.add(new FavoriteServerData(id, serverId, ip, port));
                }
            }
            bLoaded = true;
        } catch (Exception e) {
            Log.e(TAG, "Could not read private favorites file; starting with an empty list", e);
            serverList.clear();
            bLoaded = true;
            Save(context);
        }
    }

    public static synchronized void Save(Context context) {
        File file = favoritesFile(context);
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            Log.e(TAG, "Could not create private favorites directory for " + file);
            return;
        }

        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            JSONArray servers = new JSONArray();
            for (FavoriteServerData server : serverList) {
                JSONObject item = new JSONObject();
                item.put("id", server.id);
                item.put("serverid", server.serverid);
                item.put("ip", server.ip);
                item.put("port", server.port);
                servers.put(item);
            }
            root.put("servers", servers);

            try (FileOutputStream stream = new FileOutputStream(temporary, false);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                writer.write(root.toString());
                writer.flush();
                stream.getFD().sync();
            }

            try {
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            bLoaded = true;
        } catch (Exception e) {
            temporary.delete();
            Log.e(TAG, "Could not save private favorites file " + file, e);
        }
    }

    public static synchronized boolean AddServer(Context context, int id, int serverId, String ip, int port) {
        if (!bLoaded) Load(context);
        for (FavoriteServerData favorite : serverList) {
            if (favorite.ip.equals(ip) && favorite.port == port) return false;
        }
        serverList.add(new FavoriteServerData(id, serverId, ip, port));
        return true;
    }

    public static synchronized boolean RemoveServer(Context context, String ip, int port) {
        if (!bLoaded) Load(context);
        Iterator<FavoriteServerData> iterator = serverList.iterator();
        while (iterator.hasNext()) {
            FavoriteServerData favorite = iterator.next();
            if (favorite.ip.equals(ip) && favorite.port == port) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean IsServerExists(Context context, int id, int serverId,
                                                       String ip, int port, boolean markQueried) {
        if (!bLoaded) Load(context);
        for (FavoriteServerData favorite : serverList) {
            if (favorite.id == id && favorite.serverid == serverId
                    && id != 0 && serverId != 0 && favorite.ip.isEmpty()) {
                if (markQueried) favorite.queried = true;
                favorite.ip = ip;
                favorite.port = port;
                Save(context);
                return true;
            }
            if (favorite.ip.equals(ip) && favorite.port == port) {
                if (markQueried) favorite.queried = true;
                return true;
            }
        }
        return false;
    }

    public static synchronized FavoriteServerData GetServerByIpAndPort(Context context, String ip, int port) {
        if (!bLoaded) Load(context);
        for (FavoriteServerData favorite : serverList) {
            if (favorite.ip.equals(ip) && favorite.port == port) return favorite;
        }
        return null;
    }

    public static synchronized ArrayList<FavoriteServerData> getServerList(Context context) {
        if (!bLoaded) Load(context);
        return new ArrayList<>(serverList);
    }

    static synchronized void ClearFavorites() {
        serverList.clear();
        bLoaded = true;
    }
}
