package com.samp.mobile.cache;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the real download engine over real HTTP against a local server:
 * plain download, resume with a Range request, recovery when the server ignores
 * the Range header, pause / resume and cancel.
 *
 * These are the behaviours the phone depends on for a multi-gigabyte cache, and
 * they are impossible to check by reading the code.
 */
public class DownloadEngineTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private HttpServer server;
    private int port;
    private File root;

    @Before
    public void setUp() throws Exception {
        root = temp.newFolder("GTA");
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------
    // Local HTTP server helpers
    // ------------------------------------------------------------------

    private void serve(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** Serves the map, honouring "Range: bytes=N-" with a 206 response. */
    private static HttpHandler rangeAware(final Map<String, byte[]> files,
                                          final List<String> rangesSeen) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                byte[] body = files.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }

                String range = exchange.getRequestHeaders().getFirst("Range");
                int start = 0;
                if (range != null) {
                    rangesSeen.add(range);
                    int dash = range.indexOf('-');
                    String digits = range.substring(range.indexOf('=') + 1,
                            dash > 0 ? dash : range.length()).trim();
                    try {
                        start = Integer.parseInt(digits);
                    } catch (NumberFormatException ignored) {
                        start = 0;
                    }
                }

                if (start > 0) {
                    int rest = body.length - start;
                    exchange.getResponseHeaders().add("Content-Range",
                            "bytes " + start + "-" + (body.length - 1) + "/" + body.length);
                    exchange.sendResponseHeaders(206, rest);
                    OutputStream out = exchange.getResponseBody();
                    out.write(body, start, rest);
                    out.close();
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    OutputStream out = exchange.getResponseBody();
                    out.write(body);
                    out.close();
                }
                exchange.close();
            }
        };
    }

    /** Always answers 200 with the whole body, like a server without Range. */
    private static HttpHandler ignoringRange(final Map<String, byte[]> files) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = files.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                out.close();
                exchange.close();
            }
        };
    }

    /** Trickles the body out so a pause has something to interrupt. */
    private static HttpHandler slow(final byte[] body, final int chunk, final long sleepMs) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                try {
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
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                    exchange.close();
                }
            }
        };
    }

    private static byte[] payload(int size, byte seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        return data;
    }

    private RemoteFile file(String path, int size) {
        return new RemoteFile(path, path, size, url("/files/" + path),
                RemoteFile.HashType.NONE, null);
    }

    private static boolean awaitState(DownloadEngine engine, DownloadEngine.State wanted,
                                      long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (engine.getState() == wanted) {
                return true;
            }
            Thread.sleep(25);
        }
        return engine.getState() == wanted;
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    public void downloadsEveryFileAndLeavesNoPartFiles() throws Exception {
        final Map<String, byte[]> bodies = new HashMap<>();
        bodies.put("/files/data/main.scm", payload(4096, (byte) 1));
        bodies.put("/files/TEXT/AMERICAN.GXT", payload(8192, (byte) 2));
        bodies.put("/files/models/empty.dat", new byte[0]);

        final AtomicInteger hits = new AtomicInteger();
        serve(new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                ignoringRange(bodies).handle(exchange);
            }
        });

        List<RemoteFile> files = Arrays.asList(
                file("data/main.scm", 4096),
                file("TEXT/AMERICAN.GXT", 8192),
                file("models/empty.dat", 0));

        final List<String> finished = Collections.synchronizedList(new ArrayList<String>());
        DownloadEngine engine = new DownloadEngine(root);
        engine.setListener(new DownloadEngine.Listener() {
            @Override
            public void onQueuePrepared(int totalFiles, long totalBytes) {
            }

            @Override
            public void onFileStarted(int index, RemoteFile f) {
            }

            @Override
            public void onProgress(int index, long fileBytes, long fileTotal,
                                   long overallBytes, long overallTotal) {
            }

            @Override
            public void onFileFinished(int index, RemoteFile f, boolean success, String error) {
                finished.add(f.getPath() + ":" + success + ":" + error);
            }

            @Override
            public void onStateChanged(DownloadEngine.State state) {
            }

            @Override
            public void onFinished(DownloadEngine.Summary summary) {
            }
        });

        engine.start(files, 0);
        assertTrue("engine did not finish", awaitState(engine, DownloadEngine.State.FINISHED, 30000));
        engine.join(5000);

        assertTrue("all three files must be reported finished", finished.size() == 3);
        for (String line : finished) {
            assertTrue("unexpected failure: " + line, line.contains(":true:"));
        }

        assertArrayEqualsFile(payload(4096, (byte) 1), new File(root, "data/main.scm"));
        assertArrayEqualsFile(payload(8192, (byte) 2), new File(root, "TEXT/AMERICAN.GXT"));
        assertEquals(0, new File(root, "models/empty.dat").length());

        assertFalse("partial file left behind", new File(root, "data/main.scm.part").exists());
        assertEquals("every file fetched exactly once", 3, hits.get());
        assertEquals(12288, engine.getTotalBytes());
    }

    @Test
    public void resumesFromExistingPartFileWithRangeRequest() throws Exception {
        final byte[] body = payload(10000, (byte) 7);
        final Map<String, byte[]> bodies = new HashMap<>();
        bodies.put("/files/models/gta3.img", body);
        final List<String> ranges = Collections.synchronizedList(new ArrayList<String>());
        serve(rangeAware(bodies, ranges));

        // Simulate a download that was interrupted after 4000 bytes.
        File part = new File(root, "models/gta3.img.part");
        assertTrue(part.getParentFile().isDirectory() || part.getParentFile().mkdirs());
        FileOutputStream out = new FileOutputStream(part);
        out.write(body, 0, 4000);
        out.close();

        DownloadEngine engine = new DownloadEngine(root);
        engine.start(Collections.singletonList(file("models/gta3.img", body.length)), 0);
        assertTrue(awaitState(engine, DownloadEngine.State.FINISHED, 30000));
        engine.join(5000);

        assertEquals("the engine must ask for the remaining bytes only",
                Collections.singletonList("bytes=4000-"), ranges);
        assertArrayEqualsFile(body, new File(root, "models/gta3.img"));
        assertFalse(part.exists());
    }

    @Test
    public void restartsCleanlyWhenServerIgnoresRange() throws Exception {
        final byte[] body = payload(6000, (byte) 9);
        final Map<String, byte[]> bodies = new HashMap<>();
        bodies.put("/files/data/handling.cfg", body);
        serve(ignoringRange(bodies));

        // A stale part file with the wrong bytes must not be appended to.
        File part = new File(root, "data/handling.cfg.part");
        assertTrue(part.getParentFile().isDirectory() || part.getParentFile().mkdirs());
        FileOutputStream out = new FileOutputStream(part);
        out.write(payload(1500, (byte) 66));
        out.close();

        DownloadEngine engine = new DownloadEngine(root);
        engine.start(Collections.singletonList(file("data/handling.cfg", body.length)), 0);
        assertTrue(awaitState(engine, DownloadEngine.State.FINISHED, 30000));
        engine.join(5000);

        assertArrayEqualsFile(body, new File(root, "data/handling.cfg"));
    }

    @Test
    public void pauseKeepsThePartialFileAndResumeCompletesIt() throws Exception {
        final byte[] body = payload(200000, (byte) 3);
        serve(slow(body, 2000, 12));

        DownloadEngine engine = new DownloadEngine(root);
        engine.start(Collections.singletonList(file("data/big.bin", body.length)), 0);

        File part = new File(root, "data/big.bin.part");
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && (!part.exists() || part.length() < 8000)) {
            Thread.sleep(20);
        }
        assertTrue("download never started", part.exists() && part.length() > 0);

        engine.pause();
        assertTrue(awaitState(engine, DownloadEngine.State.PAUSED, 5000));

        Thread.sleep(300);
        long pausedAt = part.length();
        assertTrue("pause must keep the partial data", part.exists());
        assertTrue("pause must not lose progress", pausedAt > 0);
        assertTrue("pause must stop before the end", pausedAt < body.length);

        engine.resume();
        assertTrue("resume did not finish", awaitState(engine, DownloadEngine.State.FINISHED, 60000));
        engine.join(5000);

        assertArrayEqualsFile(body, new File(root, "data/big.bin"));
        assertFalse(part.exists());
    }

    @Test
    public void cancelStopsAndDeletesThePartialFile() throws Exception {
        final byte[] body = payload(400000, (byte) 5);
        serve(slow(body, 4000, 12));

        DownloadEngine engine = new DownloadEngine(root);
        engine.start(Collections.singletonList(file("data/cancel.bin", body.length)), 0);

        File part = new File(root, "data/cancel.bin.part");
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && (!part.exists() || part.length() == 0)) {
            Thread.sleep(20);
        }
        assertTrue("download never started", part.exists());

        engine.cancel();
        assertTrue(awaitState(engine, DownloadEngine.State.CANCELLED, 10000));
        engine.join(10000);

        assertFalse("cancel must clean up the .part file", part.exists());
        assertFalse(new File(root, "data/cancel.bin").exists());
        assertEquals(DownloadEngine.State.CANCELLED, engine.getState());
    }

    @Test
    public void wrongChecksumIsReportedAsFailure() throws Exception {
        final byte[] body = payload(512, (byte) 4);
        final Map<String, byte[]> bodies = new HashMap<>();
        bodies.put("/files/data/bad.dat", body);
        serve(ignoringRange(bodies));

        RemoteFile bad = new RemoteFile("data/bad.dat", "data/bad.dat", body.length,
                url("/files/data/bad.dat"), RemoteFile.HashType.MD5,
                "00000000000000000000000000000000");

        final boolean[] success = new boolean[]{true};
        final String[] error = new String[]{null};

        DownloadEngine engine = new DownloadEngine(root);
        engine.setListener(new DownloadEngine.Listener() {
            @Override
            public void onQueuePrepared(int totalFiles, long totalBytes) {
            }

            @Override
            public void onFileStarted(int index, RemoteFile f) {
            }

            @Override
            public void onProgress(int index, long fileBytes, long fileTotal,
                                   long overallBytes, long overallTotal) {
            }

            @Override
            public void onFileFinished(int index, RemoteFile f, boolean ok, String message) {
                success[0] = ok;
                error[0] = message;
            }

            @Override
            public void onStateChanged(DownloadEngine.State state) {
            }

            @Override
            public void onFinished(DownloadEngine.Summary summary) {
            }
        });

        engine.start(Collections.singletonList(bad), 0);
        assertTrue(awaitState(engine, DownloadEngine.State.FINISHED, 60000));
        engine.join(5000);

        assertFalse("a file with a wrong checksum must not be accepted", success[0]);
        assertNotNull(error[0]);
        assertFalse("the corrupt file must not be left in place",
                new File(root, "data/bad.dat").exists());
    }

    // ------------------------------------------------------------------

    private static void assertArrayEqualsFile(byte[] expected, File actual) throws IOException {
        assertTrue("missing file: " + actual, actual.isFile());
        byte[] got = Files.readAllBytes(actual.toPath());
        assertEquals("length of " + actual.getName(), expected.length, got.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != got[i]) {
                assertEquals("content differs at byte " + i, expected[i], got[i]);
            }
        }
    }
}
