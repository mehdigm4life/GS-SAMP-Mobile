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
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end test of the whole installer against a fake cache server:
 * client_config.json -> file list -> device filter -> verify -> download only
 * what is broken -> verify again -> COMPLETE.
 *
 * It also proves the two promises made to the user:
 *   - a corrupted file of the correct length is detected and replaced,
 *   - a second run downloads nothing when everything already matches.
 */
public class CacheManagerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private FakeServer server;
    private File root;

    private final Map<String, byte[]> bodies = new LinkedHashMap<String, byte[]>();

    @Before
    public void setUp() throws Exception {
        root = temp.newFolder("GTA");

        server = new FakeServer(new FakeServer.Handler() {
            @Override
            public void handle(FakeServer.Request request, OutputStream out) throws IOException {
                if ("/client_config.json".equals(request.path)) {
                    FakeServer.sendBody(out, 200, ("{\"client_config\":{\"version_code\":1,"
                            + "\"url_cache_files\":\"files.json\"}}").getBytes("UTF-8"));
                    return;
                }
                byte[] body = bodies.get(request.path);
                if (body == null) {
                    FakeServer.sendNotFound(out);
                    return;
                }
                FakeServer.sendBody(out, 200, body);
            }
        });
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------

    private static byte[] payload(int size, byte seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 17 + seed) & 0xFF);
        }
        return data;
    }

    private static String md5(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HashUtil.toHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String entry(String path, byte[] body) {
        return "{\"name\":\"" + path + "\",\"path\":\"" + path + "\",\"size\":"
                + body.length + ",\"url\":\"/files/" + path + "\",\"md5\":\""
                + md5(body) + "\"}";
    }

    /**
     * Three usable files plus one texture variant that must be skipped on a DXT
     * device.
     */
    private void publishManifest() throws IOException {
        byte[] a = payload(2048, (byte) 1);
        byte[] b = payload(3072, (byte) 2);
        byte[] c = payload(1024, (byte) 3);
        byte[] etc = payload(4096, (byte) 4);

        bodies.clear();
        bodies.put("/files/data/a.dat", a);
        bodies.put("/files/data/b.dat", b);
        bodies.put("/files/models/c.dat", c);
        bodies.put("/files/texdb/x.etc.dat", etc);

        String list = "{\"files\":["
                + entry("data/a.dat", a) + ","
                + entry("data/b.dat", b) + ","
                + entry("models/c.dat", c) + ","
                + entry("texdb/x.etc.dat", etc)
                + "]}";
        bodies.put("/files.json", list.getBytes("UTF-8"));
    }

    private String configUrl() {
        return server.url("/client_config.json");
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        assertTrue(parent == null || parent.isDirectory() || parent.mkdirs());
        FileOutputStream out = new FileOutputStream(file);
        out.write(data);
        out.close();
    }

    private static boolean awaitStage(CacheManager manager, CacheManager.Stage wanted,
                                      long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (manager.getStage() == wanted) {
                return true;
            }
            Thread.sleep(50);
        }
        return manager.getStage() == wanted;
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
    public void installsMissingFilesRepairsCorruptOnesAndSkipsOtherGpuPacks() throws Exception {
        publishManifest();

        // Already correct.
        writeFile(new File(root, "data/a.dat"), bodies.get("/files/data/a.dat"));

        // Same length, wrong bytes: only the checksum can catch this.
        byte[] corrupt = bodies.get("/files/data/b.dat").clone();
        corrupt[0] ^= 0xFF;
        writeFile(new File(root, "data/b.dat"), corrupt);

        // models/c.dat is missing entirely.

        CacheManager manager = new CacheManager(root, configUrl(), null, GpuDetector.DXT);
        manager.start();

        assertTrue("installer never reported COMPLETE",
                awaitStage(manager, CacheManager.Stage.COMPLETE, 90000));

        // Everything the device needs is now byte for byte identical.
        assertArrayEqualsFile(bodies.get("/files/data/a.dat"), new File(root, "data/a.dat"));
        assertArrayEqualsFile(bodies.get("/files/data/b.dat"), new File(root, "data/b.dat"));
        assertArrayEqualsFile(bodies.get("/files/models/c.dat"), new File(root, "models/c.dat"));

        // The texture pack for the other GPU families must not be downloaded.
        assertFalse("wrong GPU texture pack was downloaded",
                new File(root, "texdb/x.etc.dat").exists());

        // The intact file was never fetched; the broken two were, once each.
        assertEquals("intact file must not be downloaded again",
                0, server.hits("/files/data/a.dat"));
        assertEquals(1, server.hits("/files/data/b.dat"));
        assertEquals(1, server.hits("/files/models/c.dat"));
        assertTrue("the whole manifest was fetched", server.hits("/files.json") >= 1);
        assertFalse("partial files left behind", new File(root, "data/b.dat.part").exists());
    }

    @Test
    public void secondRunDownloadsNothingWhenCacheIsIntact() throws Exception {
        publishManifest();

        writeFile(new File(root, "data/a.dat"), bodies.get("/files/data/a.dat"));
        writeFile(new File(root, "data/b.dat"), bodies.get("/files/data/b.dat"));
        writeFile(new File(root, "models/c.dat"), bodies.get("/files/models/c.dat"));

        CacheManager manager = new CacheManager(root, configUrl(), null, GpuDetector.DXT);
        manager.start();
        assertTrue(awaitStage(manager, CacheManager.Stage.COMPLETE, 60000));

        assertEquals(0, server.hits("/files/data/a.dat"));
        assertEquals(0, server.hits("/files/data/b.dat"));
        assertEquals(0, server.hits("/files/models/c.dat"));
        assertEquals(0, server.hits("/files/texdb/x.etc.dat"));
    }

    /**
     * The reported bug: the page sat on "idle" after "Install files" until the
     * user found the retry button. Pressing the button must move the install
     * out of IDLE immediately, without waiting for the worker thread.
     */
    @Test
    public void startLeavesIdleImmediately() throws Exception {
        CacheManager manager = new CacheManager(root,
                "http://127.0.0.1:1/client_config.json", null, GpuDetector.DXT);

        assertEquals(CacheManager.Stage.IDLE, manager.getStage());
        manager.start();
        assertNotEquals("the page must not look idle after the start button",
                CacheManager.Stage.IDLE, manager.getStage());

        manager.cancel();
    }

    /**
     * A cold start often loses the first connection (radio waking up, DNS not
     * ready). That used to end in FAILED, so the user had to press retry by
     * hand; it must now recover on its own.
     */
    @Test
    public void transientServerErrorsAreRetriedWithoutUserAction() throws Exception {
        publishManifest();

        final AtomicInteger configCalls = new AtomicInteger();
        server.close();
        server = new FakeServer(new FakeServer.Handler() {
            @Override
            public void handle(FakeServer.Request request, OutputStream out) throws IOException {
                if ("/client_config.json".equals(request.path)) {
                    if (configCalls.incrementAndGet() <= 2) {
                        FakeServer.sendBody(out, 500, "boom".getBytes("UTF-8"));
                        return;
                    }
                    FakeServer.sendBody(out, 200, ("{\"client_config\":{\"version_code\":1,"
                            + "\"url_cache_files\":\"files.json\"}}").getBytes("UTF-8"));
                    return;
                }
                byte[] body = bodies.get(request.path);
                if (body == null) {
                    FakeServer.sendNotFound(out);
                    return;
                }
                FakeServer.sendBody(out, 200, body);
            }
        });

        CacheManager manager = new CacheManager(root, configUrl(), null, GpuDetector.DXT);
        manager.start();

        assertTrue("the installer must recover from a transient failure by itself",
                awaitStage(manager, CacheManager.Stage.COMPLETE, 90000));
        assertEquals("two failures then a success", 3, configCalls.get());
    }

    @Test
    public void unreachableServerIsReportedAsFailureNotAsSuccess() throws Exception {
        // A port with no listener: the installer must fail loudly instead of
        // letting the game start with a cache it never verified.
        CacheManager manager = new CacheManager(root,
                "http://127.0.0.1:1/client_config.json", null, GpuDetector.DXT);
        manager.start();

        assertTrue("an unreachable server must end in FAILED",
                awaitStage(manager, CacheManager.Stage.FAILED, 60000));
    }
}
