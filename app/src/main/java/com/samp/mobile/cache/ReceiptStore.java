package com.samp.mobile.cache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers what every cached file looked like at the moment it was written.
 *
 * The server's file list is not guaranteed to publish a checksum, and in that
 * case a size comparison alone cannot tell a healthy file from one that was
 * overwritten with the same number of bytes. So whenever a file is downloaded
 * this store records its length, its modification time and its MD5.
 *
 * A later verification then costs one {@code stat()} per file: if length and
 * modification time are unchanged the content cannot have changed either, and
 * if they did change the MD5 is recomputed and compared with the recorded one,
 * which detects the difference instead of trusting the length.
 *
 * The store lives in app-private storage, so an ordinary user can neither read
 * nor forge it, and uninstalling the app simply drops it.
 */
public final class ReceiptStore {

    /** What one file looked like when it was written. */
    public static final class Entry {
        public final long size;
        public final long mtime;
        public final String md5;

        Entry(long size, long mtime, String md5) {
            this.size = size;
            this.mtime = mtime;
            this.md5 = md5;
        }
    }

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private boolean dirty = false;

    public ReceiptStore(File file) {
        this.file = file;
        load();
    }

    public synchronized Entry get(String path) {
        return entries.get(path);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void put(String path, long size, long mtime, String md5) {
        if (path == null || md5 == null) {
            return;
        }
        Entry previous = entries.get(path);
        if (previous != null && previous.size == size && previous.mtime == mtime
                && md5.equals(previous.md5)) {
            return;
        }
        entries.put(path, new Entry(size, mtime, md5));
        dirty = true;
    }

    public synchronized void remove(String path) {
        if (entries.remove(path) != null) {
            dirty = true;
        }
    }

    /** Writes the store back to disk, best effort. */
    public synchronized void save() {
        if (!dirty || file == null) {
            return;
        }
        JSONArray array = new JSONArray();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            try {
                JSONObject o = new JSONObject();
                o.put("path", e.getKey());
                o.put("size", e.getValue().size);
                o.put("mtime", e.getValue().mtime);
                o.put("md5", e.getValue().md5);
                array.put(o);
            } catch (Exception ignored) {
            }
        }

        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                    && !parent.isDirectory()) {
                return;
            }
            // Write to a temporary name, then rename: a half-written store must
            // never be mistaken for a valid one.
            File tmp = new File(file.getPath() + ".tmp");
            out = new FileOutputStream(tmp);
            out.write(array.toString().getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;

            if (file.exists() && !file.delete()) {
                return;
            }
            if (tmp.renameTo(file)) {
                dirty = false;
            }
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private synchronized void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        RandomAccessFile in = null;
        try {
            in = new RandomAccessFile(file, "r");
            byte[] data = new byte[(int) Math.min(in.length(), 16L * 1024L * 1024L)];
            in.readFully(data);
            JSONArray array = new JSONArray(new String(data, "UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String path = o.optString("path", null);
                String md5 = o.optString("md5", null);
                if (path == null || md5 == null) {
                    continue;
                }
                entries.put(path, new Entry(o.optLong("size", -1),
                        o.optLong("mtime", -1), md5));
            }
        } catch (Throwable ignored) {
            // A damaged store simply means "no receipts": verification then
            // falls back to the size comparison.
            entries.clear();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
