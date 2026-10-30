package com.samp.mobile.cache;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * they cannot be checked by reading the code.
 */
public class DownloadEngineTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private FakeServer server;
    private File root;

    @Before
    public void setUp() throws Exception {
        root = temp.newFolder("GTA");
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Serves the map; honours Range with 206 when asked to. */
    private FakeServer serveFiles(final Map<String, byte[]> files,
                                  final List<String> rangesSeen,
                                  final boolean honourRange) throws IOException {
        return new FakeServer(new FakeServer.Handler() {
            @Override
            public void handle(FakeServer.Request request, OutputStream out) throws IOException {
                byte[] body = files.get(request.path);
                if (body == null) {
                    FakeServer.sendNotFound(out);
                    return;
                }

                String rangeHeader = request.header("Range");
                if (rangeHeader != null && rangesSeen != null) {
                    rangesSeen.add(rangeHeader);
                }

                long start = honourRange ? request.rangeStart() : -1L;
                if (start > 0) {
                    int from = (int) start;
                    int rest = body.length - from;
                    byte[] slice = new byte[rest];
                    System.arraycopy(body, from, slice, 0, rest);

                    Map<String, String> extra = new LinkedHashMap<String, String>();
                    extra.put("Content-Range", "bytes " + from + "-" + (body.length - 1)
                            + "/" + body.length);
                    FakeServer.send(out, 206, slice, extra);
                } else {
                    FakeServer.sendBody(out, 200, body);
                }
            }
        });
    }

    private RemoteFile file(String path, int size) {
        return new RemoteFile(path, path, size, server.url("/files/" + path),
                RemoteFile.HashType.NONE, null);
    }

    private static byte[] payload(int size, byte seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        return data;
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        assertTrue(parent == null || parent.isDirectory() || parent.mkdirs());
        FileOutputStream out = new FileOutputStream(file);
        out.write(data);
        out.close();
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

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    public void downloadsEveryFileAndLeavesNoPartFiles() throws Exception {
        final Map<String, byte[]> bodies = new HashMap<String, byte[]>();
        bodies.put("/files/data/main.scm", payload(4096, (byte) 1));
        bodies.put("/files/TEXT/AMERICAN.GXT", payload(8192, (byte) 2));
        bodies.put("/files/models/empty.dat", new byte[0]);

        server = serveFiles(bodies, null, false);

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
        assertTrue("engine did not finish",
                awaitState(engine, DownloadEngine.State.FINISHED, 30000));
        engine.join(5000);

        assertEquals("all three files must be reported finished", 3, finished.size());
        for (String line : finished) {
            assertTrue("unexpected failure: " + line, line.contains(":true:"));
        }

        assertArrayEqualsFile(payload(4096, (byte) 1), new File(root, "data/main.scm"));
        assertArrayEqualsFile(payload(8192, (byte) 2), new File(root, "TEXT/AMERICAN.GXT"));
        assertEquals(0, new File(root, "models/empty.dat").length());

        assertFalse("partial file left behind", new File(root, "data/main.scm.part").exists());
        assertEquals("every file fetched exactly once", 3, server.hits("/files/data/main.scm")
                + server.hits("/files/TEXT/AMERICAN.GXT") + server.hits("/files/models/empty.dat"));
        assertEquals(12288, engine.getTotalBytes());
    }

    @Test
    public void resumesFromExistingPartFileWithRangeRequest() throws Exception {
        final byte[] body = payload(10000, (byte) 7);
        final Map<String, byte[]> bodies = new HashMap<String, byte[]>();
        bodies.put("/files/models/gta3.img", body);
        final List<String> ranges = Collections.synchronizedList(new ArrayList<String>());
        server = serveFiles(bodies, ranges, true);

        // Simulate a download that was interrupted after 4000 bytes.
        File part = new File(root, "models/gta3.img.part");
        writeFile(part, Arrays.copyOfRange(body, 0, 4000));

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
        final Map<String, byte[]> bodies = new HashMap<String, byte[]>();
        bodies.put("/files/data/handling.cfg", body);
        server = serveFiles(bodies, null, false);

        // A stale part file with the wrong bytes must never be appended to.
        File part = new File(root, "data/handling.cfg.part");
        writeFile(part, payload(1500, (byte) 66));

        DownloadEngine engine = new DownloadEngine(root);
        engine.start(Collections.singletonList(file("data/handling.cfg", body.length)), 0);
        assertTrue(awaitState(engine, DownloadEngine.State.FINISHED, 30000));
        engine.join(5000);

        assertArrayEqualsFile(body, new File(root, "data/handling.cfg"));
        assertFalse(part.exists());
    }

    @Test
    public void pauseKeepsThePartialFileAndResumeCompletesIt() throws Exception {
        final byte[] body = payload(200000, (byte) 3);
        server = new FakeServer(new FakeServer.Handler() {
            @Override
            public void handle(FakeServer.Request request, OutputStream out) throws IOException {
                FakeServer.sendSlowly(out, body, 2000, 12);
            }
        });

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
        assertTrue("resume did not finish",
                awaitState(engine, DownloadEngine.State.FINISHED, 60000));
        engine.join(5000);

        assertArrayEqualsFile(body, new File(root, "data/big.bin"));
        assertFalse(part.exists());
    }

    @Test
    public void cancelStopsAndDeletesThePartialFile() throws Exception {
        final byte[] body = payload(400000, (byte) 5);
        server = new FakeServer(new FakeServer.Handler() {
            @Override
            public void handle(FakeServer.Request request, OutputStream out) throws IOException {
                FakeServer.sendSlowly(out, body, 4000, 12);
            }
        });

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
        final Map<String, byte[]> bodies = new HashMap<String, byte[]>();
        bodies.put("/files/data/bad.dat", body);
        server = serveFiles(bodies, null, false);

        RemoteFile bad = new RemoteFile("data/bad.dat", "data/bad.dat", body.length,
                server.url("/files/data/bad.dat"), RemoteFile.HashType.MD5,
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
}
