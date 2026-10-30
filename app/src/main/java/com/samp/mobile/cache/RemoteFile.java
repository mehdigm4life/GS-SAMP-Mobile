package com.samp.mobile.cache;

import java.util.Locale;

/**
 * One file as described by the remote cache manifest.
 *
 * The server publishes (see client_config.json -> url_cache_files):
 *
 * <pre>
 * {
 *   "files": [
 *     { "name": "gta3.img", "size": 906297344, "path": "models/gta3.img", "url": "..." }
 *   ]
 * }
 * </pre>
 *
 * Only {@code path} and {@code size} are strictly required. A checksum is used
 * when the server provides one, which lets the verifier detect a file that was
 * truncated or overwritten instead of merely checking its length.
 */
public class RemoteFile {

    /** Optional integrity hint; the manifest may not include any. */
    public enum HashType { NONE, MD5, SHA1, CRC32 }

    private final String name;
    private final String path;
    private final long size;
    private final String url;
    private final HashType hashType;
    private final String hash;

    public RemoteFile(String name, String path, long size, String url,
                      HashType hashType, String hash) {
        this.name = name;
        this.path = normalizePath(path);
        this.size = size;
        this.url = url;
        this.hashType = hashType == null ? HashType.NONE : hashType;
        this.hash = hash;
    }

    /**
     * Normalises a manifest path: forward slashes only, no leading slash, no
     * "..", no empty segments. This is defence against a malformed or hostile
     * manifest pointing outside the cache folder.
     */
    public static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String p = raw.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        StringBuilder out = new StringBuilder(p.length());
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(segment);
        }
        return out.toString();
    }

    /** True when the path is safe to resolve under the cache root. */
    public boolean isSafePath() {
        return !path.isEmpty() && !path.contains("..");
    }

    public String getName() {
        return name != null ? name : lastSegment(path);
    }

    public String getPath() {
        return path;
    }

    public long getSize() {
        return size;
    }

    public String getUrl() {
        return url;
    }

    public HashType getHashType() {
        return hashType;
    }

    public String getHash() {
        return hash;
    }

    public boolean hasHash() {
        return hashType != HashType.NONE && hash != null && !hash.trim().isEmpty();
    }

    public static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "RemoteFile{%s, %d bytes}", path, size);
    }
}
