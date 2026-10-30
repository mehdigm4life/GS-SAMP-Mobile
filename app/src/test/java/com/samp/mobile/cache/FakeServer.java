package com.samp.mobile.cache;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deliberately tiny HTTP/1.1 server for the unit tests.
 *
 * {@code com.sun.net.httpserver} is not on the classpath when Android unit tests
 * are compiled (the Android SDK jar is used as the boot classpath), so the tests
 * speak HTTP over a plain {@link ServerSocket} instead. That also gives exact
 * control over the parts that matter here: whether {@code Range} is honoured,
 * whether the connection is throttled, and how many requests each file took.
 */
final class FakeServer implements Closeable {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Handed the parsed request and the raw socket output. */
    interface Handler {
        void handle(Request request, OutputStream out) throws IOException;
    }

    /** One parsed request. */
    static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;

        Request(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        /** First byte of a "bytes=N-" or "bytes=N-M" range, or -1. */
        long rangeStart() {
            String range = header("Range");
            if (range == null) {
                return -1L;
            }
            int eq = range.indexOf('=');
            int dash = range.indexOf('-', eq + 1);
            String digits = range.substring(eq + 1, dash < 0 ? range.length() : dash).trim();
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
    }

    private final ServerSocket serverSocket;
    private final Handler handler;
    private final List<Socket> open = new CopyOnWriteArrayList<Socket>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<String, AtomicInteger>();
    private final AtomicInteger connections = new AtomicInteger();

    FakeServer(Handler handler) throws IOException {
        this.handler = handler;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));

        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "fake-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    /** How many requests were made for this path. */
    int hits(String path) {
        AtomicInteger counter = hits.get(path);
        return counter == null ? 0 : counter.get();
    }

    int connections() {
        return connections.get();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setSoTimeout(30000);
                open.add(socket);
                connections.incrementAndGet();

                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        serve(socket);
                    }
                }, "fake-server-conn");
                worker.setDaemon(true);
                worker.start();

            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            Request request = readRequest(socket.getInputStream());
            if (request == null) {
                return;
            }
            recordHit(request.path);

            OutputStream out = socket.getOutputStream();
            handler.handle(request, out);
            out.flush();

        } catch (IOException ignored) {
            // Client went away (cancel test does exactly that).
        } finally {
            closeQuietly(socket);
            open.remove(socket);
        }
    }

    private void recordHit(String path) {
        synchronized (hits) {
            AtomicInteger counter = hits.get(path);
            if (counter == null) {
                counter = new AtomicInteger();
                hits.put(path, counter);
            }
            counter.incrementAndGet();
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            head.write(b);
            if ((matched == 0 || matched == 2) && b == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && b == '\n') {
                matched++;
            } else {
                matched = 0;
            }
        }

        String[] lines = new String(head.toByteArray(), UTF8).split("\r\n");
        if (lines.length == 0) {
            return null;
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            return null;
        }

        String path = requestLine[1];
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }

        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }
        return new Request(requestLine[0], path, headers);
    }

    // ------------------------------------------------------------------
    // Response helpers used by the tests
    // ------------------------------------------------------------------

    /** Writes a complete response. */
    static void send(OutputStream out, int status, byte[] body,
                     Map<String, String> extraHeaders) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ')
                .append(status == 200 ? "OK" : status == 206 ? "Partial Content" : "Not Found")
                .append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        head.append("\r\n");

        out.write(head.toString().getBytes(UTF8));
        out.write(body);
        out.flush();
    }

    static void sendBody(OutputStream out, int status, byte[] body) throws IOException {
        send(out, status, body, null);
    }

    static void sendNotFound(OutputStream out) throws IOException {
        sendBody(out, 404, "not found".getBytes(UTF8));
    }

    /** Streams a response body in chunks so a test can interrupt it. */
    static void sendSlowly(OutputStream out, byte[] body, int chunk, long sleepMs)
            throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(UTF8));
        for (int offset = 0; offset < body.length; offset += chunk) {
            int len = Math.min(chunk, body.length - offset);
            out.write(body, offset, len);
            out.flush();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        out.flush();
    }

    @Override
    public void close() {
        closeQuietly(serverSocket);
        for (Socket socket : open) {
            closeQuietly(socket);
        }
        open.clear();
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
