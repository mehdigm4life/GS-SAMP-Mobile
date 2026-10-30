package com.samp.mobile.cache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Keeps the last device-filtered manifest on the phone.
 *
 * Why this exists: the cache must be verified against the server before the game
 * starts, but the player may be offline (in a car, on a plane, or simply without
 * a signal). Without a local copy of the manifest an offline launch could only
 * either refuse to start or start blindly. With it, the app can still compare the
 * tree with the exact list of files and checksums it downloaded last time and
 * start the game only when nothing is missing.
 *
 * The document reuses the server schema, so it is parsed by the same
 * {@link ManifestParser} code that parses the real thing.
 */
public final class ManifestCache {

    private final File file;

    public ManifestCache(File file) {
        this.file = file;
    }

    /** @return the cached manifest, or null when there is none or it is unreadable */
    public List<RemoteFile> load() {
        if (file == null || !file.isFile()) {
            return null;
        }
        RandomAccessFile in = null;
        try {
            in = new RandomAccessFile(file, "r");
            byte[] data = new byte[(int) Math.min(in.length(), 32L * 1024L * 1024L)];
            in.readFully(data);
            List<RemoteFile> files = ManifestParser.parseFileList(
                    new String(data, "UTF-8"), "https://samp-mobile.shop/files.json");
            return files.isEmpty() ? null : files;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void save(List<RemoteFile> files) {
        if (file == null || files == null || files.isEmpty()) {
            return;
        }
        JSONArray array = new JSONArray();
        for (RemoteFile f : files) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("path", f.getPath());
                o.put("size", f.getSize());
                o.put("url", f.getUrl());
                if (f.hasHash()) {
                    o.put("hash", f.getHash());
                    o.put("hash_type", f.getHashType().name());
                }
                array.put(o);
            } catch (Exception ignored) {
            }
        }
        try {
            JSONObject root = new JSONObject();
            root.put("files", array);

            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                    && !parent.isDirectory()) {
                return;
            }
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (file.exists() && !file.delete()) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (Throwable ignored) {
        }
    }
}
