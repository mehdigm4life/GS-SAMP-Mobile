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
     * A manifest shaped like the real one: three shared files plus the texture
     * database in all three GPU flavours. The installer must keep only the
     * family this device renders and must never fetch the other two.
     */
    private void publishManifest() throws IOException {
        byte[] a = payload(2048, (byte) 1);
        byte[] b = payload(3072, (byte) 2);
        byte[] c = payload(1024, (byte) 3);

        byte[] dxt = payload(4096, (byte) 4);
        byte[] dxtTmb = payload(512, (byte) 5);
        byte[] pvr = payload(4096, (byte) 6);
        byte[] pvrTmb = payload(512, (byte) 7);
        byte[] etc = payload(4096, (byte) 8);
        byte[] etcTmb = payload(512, (byte) 9);
        byte[] uncTmb = payload(256, (byte) 10);

        bodies.clear();
        bodies.put("/files/data/a.dat", a);
        bodies.put("/files/data/b.dat", b);
        bodies.put("/files/models/c.dat", c);

        bodies.put("/files/texdb/mobile/mobile.dxt", dxt);
        bodies.put("/files/texdb/mobile/mobile.dxt.tmb", dxtTmb);
        bodies.put("/files/texdb/mobile/mobile.pvr", pvr);
        bodies.put("/files/texdb/mobile/mobile.pvr.tmb", pvrTmb);
        bodies.put("/files/texdb/mobile/mobile.etc", etc);
        bodies.put("/files/texdb/mobile/mobile.etc.tmb", etcTmb);
        bodies.put("/files/texdb/mobile/mobile.unc.tmb", uncTmb);

        String list = "{\"files\":["
                + entry("data/a.dat", a) + ","
                + entry("data/b.dat", b) + ","
                + entry("models/c.dat", c) + ","
                + entry("texdb/mobile/mobile.dxt", dxt) + ","
                + entry("texdb/mobile/mobile.dxt.tmb", dxtTmb) + ","
                + entry("texdb/mobile/mobile.pvr", pvr) + ","
                + entry("texdb/mobile/mobile.pvr.tmb", pvrTmb) + ","
                + entry("texdb/mobile/mobile.etc", etc) + ","
                + entry("texdb/mobile/mobile.etc.tmb", etcTmb) + ","
                + entry("texdb/mobile/mobile.unc.tmb", uncTmb)
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

        // models/c.dat is missing entirely, and so are the DXT texture files.

        CacheManager manager = new CacheManager(root, configUrl(), null, GpuDetector.DXT);
        manager.start();

        assertTrue("installer never reported COMPLETE",
                awaitStage(manager, CacheManager.Stage.COMPLETE, 90000));

        // Everything the device needs is now byte for byte identical.
        assertArrayEqualsFile(bodies.get("/files/data/a.dat"), new File(root, "data/a.dat"));
        assertArrayEqualsFile(bodies.get("/files/data/b.dat"), new File(root, "data/b.dat"));
        assertArrayEqualsFile(bodies.get("/files/models/c.dat"), new File(root, "models/c.dat"));

        // The DXT family is the one this device renders, so it had to be fetched.
        assertArrayEqualsFile(bodies.get("/files/texdb/mobile/mobile.dxt"),
                new File(root, "texdb/mobile/mobile.dxt"));
        assertArrayEqualsFile(bodies.get("/files/texdb/mobile/mobile.dxt.tmb"),
                new File(root, "texdb/mobile/mobile.dxt.tmb"));

        // The PVR and ETC copies exist on the server but must not be downloaded.
        assertFalse("wrong GPU texture pack was downloaded",
                new File(root, "texdb/mobile/mobile.pvr").exists());
        assertFalse("wrong GPU texture pack was downloaded",
                new File(root, "texdb/mobile/mobile.etc").exists());
        assertEquals(0, server.hits("/files/texdb/mobile/mobile.pvr"));
        assertEquals(0, server.hits("/files/texdb/mobile/mobile.etc"));

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

        // Everything the DXT device needs is already on disk.
        for (Map.Entry<String, byte[]> e : bodies.entrySet()) {
            if (e.getKey().startsWith("/files/")) {
                String path = e.getKey().substring("/files/".length());
                if (path.endsWith(".pvr") || path.endsWith(".pvr.tmb")
                        || path.endsWith(".etc") || path.endsWith(".etc.tmb")) {
                    continue;  // another GPU family: not part of this cache
                }
                writeFile(new File(root, path), e.getValue());
            }
        }

        CacheManager manager = new CacheManager(root, configUrl(), null, GpuDetector.DXT);
        manager.start();
        assertTrue(awaitStage(manager, CacheManager.Stage.COMPLETE, 60000));

        assertEquals(0, server.hits("/files/data/a.dat"));
        assertEquals(0, server.hits("/files/data/b.dat"));
        assertEquals(0, server.hits("/files/models/c.dat"));
        assertEquals(0, server.hits("/files/texdb/mobile/mobile.dxt"));
        assertEquals(0, server.hits("/files/texdb/mobile/mobile.dxt.tmb"));
        assertEquals(0, server.hits("/files/texdb/mobile/mobile.pvr"));
        assertEquals(0, server.hits("/files/texdb/mobile/mobile.etc"));
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

    // ------------------------------------------------------------------
    // The reported scenario: a file is removed by hand, then the app starts
    // ------------------------------------------------------------------

    /** Same manifest, but without checksums - like the live server today. */
    private void publishManifestWithoutChecksums() throws IOException {
        publishManifest();

        StringBuilder list = new StringBuilder("{\"files\":[");
        boolean first = true;
        for (Map.Entry<String, byte[]> e : bodies.entrySet()) {
            if (!e.getKey().startsWith("/files/")) {
                continue;
            }
            String path = e.getKey().substring("/files/".length());
            if (!first) {
                list.append(',');
            }
            first = false;
            list.append("{\"name\":\"").append(path)
                    .append("\",\"path\":\"").append(path)
                    .append("\",\"size\":").append(e.getValue().length)
                    .append(",\"url\":\"/files/").append(path).append("\"}");
        }
        list.append("]}");
        bodies.put("/files.json", list.toString().getBytes("UTF-8"));
    }

    private File state() throws IOException {
        return temp.newFolder("cache-state-" + System.nanoTime());
    }

    /** A manager exactly as the app builds it: with receipts and a manifest copy. */
    private CacheManager newManager(File stateDir) {
        return new CacheManager(root, configUrl(), null, GpuDetector.DXT, stateDir);
    }

    @Test
    public void aFileDeletedByHandIsDownloadedAgainOnTheNextLaunch() throws Exception {
        publishManifest();
        File stateDir = state();

        CacheManager first = newManager(stateDir);
        first.start();
        assertTrue(awaitStage(first, CacheManager.Stage.COMPLETE, 90000));

        File victim = new File(root, "texdb/mobile/mobile.dxt.tmb");
        assertTrue("the file must exist after a successful install", victim.isFile());
        assertTrue("cannot delete the test file", victim.delete());

        int victimHitsBefore = server.hits("/files/texdb/mobile/mobile.dxt.tmb");
        int healthyHitsBefore = server.hits("/files/data/a.dat");

        // A fresh manager is what the launcher creates on the next app start.
        CacheManager second = newManager(stateDir);
        second.start();
        assertTrue("the relaunch must notice the missing file and repair it",
                awaitStage(second, CacheManager.Stage.COMPLETE, 90000));

        assertTrue("the deleted file must be back", victim.isFile());
        assertArrayEqualsFile(bodies.get("/files/texdb/mobile/mobile.dxt.tmb"), victim);
        assertEquals("it must be fetched exactly once more",
                victimHitsBefore + 1, server.hits("/files/texdb/mobile/mobile.dxt.tmb"));
        // Nothing else had to move.
        assertEquals("a healthy file must not be downloaded again",
                healthyHitsBefore, server.hits("/files/data/a.dat"));
    }

    @Test
    public void contentChangedWithoutChangingTheLengthIsDetectedAndReplaced() throws Exception {
        // No checksums from the server: only the receipt can catch this.
        publishManifestWithoutChecksums();
        File stateDir = state();

        CacheManager first = newManager(stateDir);
        first.start();
        assertTrue(awaitStage(first, CacheManager.Stage.COMPLETE, 90000));

        File victim = new File(root, "data/b.dat");
        int victimHitsBefore = server.hits("/files/data/b.dat");
        int healthyHitsBefore = server.hits("/files/data/a.dat");
        byte[] expected = bodies.get("/files/data/b.dat");
        byte[] tampered = expected.clone();
        tampered[10] ^= 0x55;          // same length, different content
        writeFile(victim, tampered);
        assertTrue(victim.setLastModified(System.currentTimeMillis() + 60000L));

        CacheManager second = newManager(stateDir);
        second.start();
        assertTrue(awaitStage(second, CacheManager.Stage.COMPLETE, 90000));

        assertArrayEqualsFile(expected, victim);
        assertEquals("the tampered file must be fetched again",
                victimHitsBefore + 1, server.hits("/files/data/b.dat"));
        assertEquals("healthy files must not be touched",
                healthyHitsBefore, server.hits("/files/data/a.dat"));
    }

    @Test
    public void withNoNetworkACompleteTreeStillVerifiesAndAllowsTheGame() throws Exception {
        publishManifest();
        File stateDir = state();

        CacheManager first = newManager(stateDir);
        first.start();
        assertTrue(awaitStage(first, CacheManager.Stage.COMPLETE, 90000));

        // The player goes offline; the saved manifest must still drive a real
        // verification, so a healthy tree is not blocked by a dead server.
        server.close();
        server = new FakeServer(new FakeServer.Handler() {
            @Override
            public void handle(FakeServer.Request request, OutputStream out) throws IOException {
                FakeServer.sendNotFound(out);
            }
        });

        CacheManager second = newManager(stateDir);
        second.start();
        assertTrue(awaitStage(second, CacheManager.Stage.COMPLETE, 180000));
        assertTrue("the pass must report that it ran offline", second.isOffline());
    }

    @Test
    public void withNoNetworkAMissingFileIsReportedInsteadOfStartingTheGame() throws Exception {
        publishManifest();
        File stateDir = state();

        CacheManager first = newManager(stateDir);
        first.start();
        assertTrue(awaitStage(first, CacheManager.Stage.COMPLETE, 90000));

        assertTrue(new File(root, "data/b.dat").delete());

        server.close();
        server = new FakeServer(new FakeServer.Handler() {
            @Override
            public void handle(FakeServer.Request request, OutputStream out) throws IOException {
                FakeServer.sendNotFound(out);
            }
        });

        CacheManager second = newManager(stateDir);
        second.start();
        assertTrue("a missing file with no network must not report success",
                awaitStage(second, CacheManager.Stage.FAILED, 180000));
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
