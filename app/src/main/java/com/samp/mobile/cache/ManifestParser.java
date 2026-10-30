package com.samp.mobile.cache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Parses the two JSON documents the cache server exposes.
 *
 * Kept free of Android and of networking so the rules can be unit tested
 * exactly as they ship. The manifest schema is tolerated in several shapes
 * because the server is operated independently of this client:
 *
 *   client_config.json
 *     { "client_config": { "version_code": 105,
 *                          "url_cache_files": "https://host/files.json" } }
 *   ... or the same keys at the top level.
 *
 *   files.json
 *     { "files": [ { "name", "size", "path", "url", "md5?" }, ... ] }
 *   ... or a bare array.
 */
public final class ManifestParser {

    /** Left out of the cache on purpose: they are written by the client itself. */
    private static final String[] IGNORED_NAMES = {
            "samp_log.txt", "svlog.txt", "gtasatelem.set",
            "gtasamp10.b", ".htaccess", "gta_sa.set", "settings.ini"
    };

    private ManifestParser() {
    }

    // ------------------------------------------------------------------
    // client_config.json
    // ------------------------------------------------------------------

    /** Result of reading client_config.json. */
    public static final class ClientConfig {
        public final int versionCode;
        public final String urlLauncher;
        public final String urlCacheFiles;

        ClientConfig(int versionCode, String urlLauncher, String urlCacheFiles) {
            this.versionCode = versionCode;
            this.urlLauncher = urlLauncher;
            this.urlCacheFiles = urlCacheFiles;
        }
    }

    public static ClientConfig parseClientConfig(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            throw new JSONException("empty client config");
        }
        JSONObject root = new JSONObject(json);
        JSONObject config = root.optJSONObject("client_config");
        if (config == null) {
            config = root;
        }

        int versionCode = config.optInt("version_code", config.optInt("versionCode", 0));
        String launcher = optString(config, "url_launcher", "urlLauncher");
        String cache = optString(config, "url_cache_files", "urlCacheFiles", "files_url", "filesUrl");

        if (cache == null || cache.trim().isEmpty()) {
            throw new JSONException("url_cache_files is missing from client_config.json");
        }
        return new ClientConfig(versionCode, launcher, cache.trim());
    }

    private static String optString(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.trim().isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // files.json
    // ------------------------------------------------------------------

    public static List<RemoteFile> parseFileList(String json, String baseUrl) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            throw new JSONException("empty file list");
        }

        String trimmed = json.trim();
        JSONArray array;
        if (trimmed.startsWith("[")) {
            array = new JSONArray(trimmed);
        } else {
            JSONObject root = new JSONObject(trimmed);
            array = root.optJSONArray("files");
            if (array == null) {
                array = root.optJSONArray("data");
            }
            if (array == null) {
                throw new JSONException("file list has no \"files\" array");
            }
        }

        List<RemoteFile> out = new ArrayList<>(array.length());
        String resolvedBase = baseUrl == null ? "" : baseUrl;
        // Relative file URLs are relative to the folder the manifest lives in,
        // not to the manifest file itself: https://host/dir/list.json means
        // https://host/dir/<file>.
        String baseDir = directoryOf(resolvedBase);

        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }

            String path = o.optString("path", null);
            if (path == null || path.trim().isEmpty()) {
                // Some manifests only carry the name.
                path = o.optString("name", null);
            }
            if (path == null || path.trim().isEmpty()) {
                continue;
            }

            long size = o.optLong("size", o.optLong("bytes", -1L));

            String url = optString(o, "url", "download_url", "downloadUrl");
            if (url == null || url.trim().isEmpty()) {
                // No URL at all: the file sits next to the manifest.
                url = baseDir + RemoteFile.normalizePath(path);
            } else if (url.startsWith("/")) {
                // Absolute path on the manifest host.
                url = hostOf(resolvedBase) + url;
            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                // Relative path.
                url = baseDir + url;
            }

            RemoteFile.HashType hashType = RemoteFile.HashType.NONE;
            String hash = null;
            String declaredType = optString(o, "hash_type", "hashType", "algo");
            String genericHash = optString(o, "hash", "checksum");
            if (genericHash != null && declaredType != null) {
                hashType = hashTypeOf(declaredType);
                hash = genericHash;
            } else if (genericHash == null) {
                String md5 = optString(o, "md5");
                String sha1 = optString(o, "sha1", "sha-1");
                if (md5 != null) {
                    hashType = RemoteFile.HashType.MD5;
                    hash = md5;
                } else if (sha1 != null) {
                    hashType = RemoteFile.HashType.SHA1;
                    hash = sha1;
                }
            }

            RemoteFile file = new RemoteFile(
                    o.optString("name", RemoteFile.lastSegment(path)),
                    path, size, url, hashType, hash);
            out.add(file);
        }
        return Collections.unmodifiableList(out);
    }

    private static RemoteFile.HashType hashTypeOf(String raw) {
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.contains("sha1") || s.contains("sha-1")) {
            return RemoteFile.HashType.SHA1;
        }
        if (s.contains("md5")) {
            return RemoteFile.HashType.MD5;
        }
        if (s.contains("crc")) {
            return RemoteFile.HashType.CRC32;
        }
        return RemoteFile.HashType.NONE;
    }

    /** Folder part of a URL, always ending with a slash. */
    private static String directoryOf(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        int slash = url.lastIndexOf('/');
        if (slash < 0 || (schemeEnd >= 0 && slash <= schemeEnd + 2)) {
            return url.endsWith("/") ? url : url + "/";
        }
        return url.substring(0, slash + 1);
    }

    private static String hostOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            return u.getProtocol() + "://" + u.getHost() + port;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Filtering
    // ------------------------------------------------------------------

    public static boolean isIgnoredName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        for (String ignored : IGNORED_NAMES) {
            if (lower.equals(ignored)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cache ships texture variants for three GPU families
     * (.dxt. / .etc. / .pvr.). Downloading all three would triple the size, so
     * only the variant matching this device is kept.
     *
     * Files under player/, playerhi/, menu/ or samp/ exist as a single copy and
     * are always kept, mirroring the original client.
     *
     * @param gpuType 1 = DXT, 2 = ETC, 3 = PVR; any other value keeps everything
     */
    public static boolean matchesGpu(String path, int gpuType) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);

        boolean shared = lower.contains("player") || lower.contains("playerhi")
                || lower.contains("menu") || lower.contains("samp");
        if (shared || gpuType <= 0) {
            return true;
        }
        if (lower.contains(".dxt.")) {
            return gpuType == 1;
        }
        if (lower.contains(".etc.")) {
            return gpuType == 2;
        }
        if (lower.contains(".pvr.")) {
            return gpuType == 3;
        }
        return true;
    }

    /**
     * The cache root on the device is /storage/emulated/0/GTA, so a manifest
     * that prefixes every path with "GTA/" would create /GTA/GTA/... Strip the
     * redundant segment.
     */
    public static String stripCacheRoot(String path) {
        if (path == null) {
            return "";
        }
        String p = RemoteFile.normalizePath(path);
        String lower = p.toLowerCase(Locale.US);
        if (lower.equals("gta")) {
            return "";
        }
        if (lower.startsWith("gta/")) {
            return p.substring(4);
        }
        return p;
    }

    /** Applies the name filter and the GPU filter in one pass. */
    public static List<RemoteFile> filterForDevice(List<RemoteFile> all, int gpuType) {
        List<RemoteFile> out = new ArrayList<>(all.size());
        for (RemoteFile f : all) {
            if (!f.isSafePath()) {
                continue;
            }
            if (isIgnoredName(f.getName())) {
                continue;
            }
            if (f.getUrl() == null || f.getUrl().trim().isEmpty()) {
                continue;
            }
            if (!matchesGpu(f.getPath(), gpuType)) {
                continue;
            }

            String stripped = stripCacheRoot(f.getPath());
            if (stripped.isEmpty()) {
                continue;
            }
            out.add(stripped.equals(f.getPath())
                    ? f
                    : new RemoteFile(f.getName(), stripped, f.getSize(), f.getUrl(),
                            f.getHashType(), f.getHash()));
        }
        return out;
    }
}
