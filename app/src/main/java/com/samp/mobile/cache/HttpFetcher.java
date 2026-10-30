package com.samp.mobile.cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Tiny HTTP text fetcher for the two small JSON documents of the cache
 * protocol. Kept free of Android imports so it can be unit tested on the JVM.
 */
public final class HttpFetcher {

    /** The JSON documents are a few kilobytes; refuse anything absurd. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Thrown for any condition that prevents a usable document. */
    public static final class FetchException extends IOException {
        public FetchException(String message) {
            super(message);
        }
    }

    private HttpFetcher() {
    }

    public static String get(String url, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new FetchException("empty URL");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url.trim()).openConnection();
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "GS-SAMP-Mobile");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            // Defeat an intermediate cache that would keep serving an old
            // manifest after the server has been updated.
            connection.setRequestProperty("Cache-Control", "no-cache");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new FetchException("HTTP " + code + " for " + url);
            }

            InputStream in = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
            try {
                byte[] chunk = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = in.read(chunk)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) {
                        throw new FetchException("response larger than " + MAX_BYTES + " bytes");
                    }
                    buffer.write(chunk, 0, read);
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }

            String body = new String(buffer.toByteArray(), UTF8);
            if (body.trim().isEmpty()) {
                throw new FetchException("empty response from " + url);
            }
            return body;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Resolves a possibly relative URL against a base URL.
     *
     * @param base e.g. https://host/dir/files.json
     * @param ref  e.g. v2/files.json, /x.json or an absolute URL
     */
    public static String resolve(String base, String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            return base;
        }
        String r = ref.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return r;
        }
        try {
            URL b = new URL(base);
            if (r.startsWith("/")) {
                return b.getProtocol() + "://" + b.getAuthority() + r;
            }
            String path = b.getPath();
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return b.getProtocol() + "://" + b.getAuthority() + dir + r;
        } catch (Exception e) {
            return r;
        }
    }
}
