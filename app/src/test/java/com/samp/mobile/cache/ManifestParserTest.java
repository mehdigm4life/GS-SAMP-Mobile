package com.samp.mobile.cache;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Rules of the two JSON documents, tested exactly as they ship. */
public class ManifestParserTest {

    private static final String CONFIG_NESTED = "{\"client_config\":{\"version_code\":42,"
            + "\"url_launcher\":\"https://host/launcher.apk\","
            + "\"url_cache_files\":\"https://host/files.json\"}}";

    private static final String CONFIG_FLAT = "{\"version_code\":7,"
            + "\"url_cache_files\":\"files.json\"}";

    @Test
    public void parsesNestedClientConfig() throws Exception {
        ManifestParser.ClientConfig c = ManifestParser.parseClientConfig(CONFIG_NESTED);
        assertEquals(42, c.versionCode);
        assertEquals("https://host/launcher.apk", c.urlLauncher);
        assertEquals("https://host/files.json", c.urlCacheFiles);
    }

    @Test
    public void parsesFlatClientConfig() throws Exception {
        ManifestParser.ClientConfig c = ManifestParser.parseClientConfig(CONFIG_FLAT);
        assertEquals(7, c.versionCode);
        assertEquals("files.json", c.urlCacheFiles);
    }

    @Test
    public void rejectsConfigWithoutFileList() {
        try {
            ManifestParser.parseClientConfig("{\"client_config\":{\"version_code\":1}}");
            fail("expected JSONException");
        } catch (JSONException expected) {
            // ok
        }
    }

    @Test
    public void parsesObjectFormFileList() throws Exception {
        String json = "{\"files\":["
                + "{\"name\":\"gta3.img\",\"size\":123,\"path\":\"models/gta3.img\","
                + "\"url\":\"https://host/files/models/gta3.img\"},"
                + "{\"name\":\"a.txt\",\"size\":5,\"path\":\"data/a.txt\"}"
                + "]}";

        List<RemoteFile> files = ManifestParser.parseFileList(json, "https://host/files.json");
        assertEquals(2, files.size());

        assertEquals("models/gta3.img", files.get(0).getPath());
        assertEquals(123, files.get(0).getSize());
        assertEquals("https://host/files/models/gta3.img", files.get(0).getUrl());
        assertFalse(files.get(0).hasHash());

        // No url in the manifest: derived from the manifest location.
        assertEquals("https://host/data/a.txt", files.get(1).getUrl());
    }

    @Test
    public void parsesArrayFormFileList() throws Exception {
        String json = "[{\"path\":\"TEXT/AMERICAN.GXT\",\"size\":10}]";
        List<RemoteFile> files = ManifestParser.parseFileList(json, "https://host/dir/list.json");
        assertEquals(1, files.size());
        assertEquals("https://host/dir/TEXT/AMERICAN.GXT", files.get(0).getUrl());
    }

    @Test
    public void readsChecksums() throws Exception {
        String json = "{\"files\":["
                + "{\"path\":\"a\",\"size\":1,\"md5\":\"d41d8cd98f00b204e9800998ecf8427e\"},"
                + "{\"path\":\"b\",\"size\":1,\"sha1\":\"da39a3ee5e6b4b0d3255bfef95601890afd80709\"},"
                + "{\"path\":\"c\",\"size\":1,\"hash\":\"ABC\",\"hash_type\":\"crc32\"}"
                + "]}";
        List<RemoteFile> files = ManifestParser.parseFileList(json, "https://host/f.json");

        assertEquals(RemoteFile.HashType.MD5, files.get(0).getHashType());
        assertTrue(files.get(0).hasHash());
        assertEquals(RemoteFile.HashType.SHA1, files.get(1).getHashType());
        assertEquals(RemoteFile.HashType.CRC32, files.get(2).getHashType());
    }

    @Test
    public void normalisesUnsafePaths() {
        assertEquals("data/file.bin", RemoteFile.normalizePath("/data/file.bin"));
        assertEquals("etc/passwd", RemoteFile.normalizePath("../../etc/passwd"));
        assertEquals("a/b", RemoteFile.normalizePath("a//b"));
        assertNotNull(RemoteFile.normalizePath(null));
    }

    @Test
    public void filtersIgnoredNamesButKeepsEveryTextureFamily() throws Exception {
        String json = "{\"files\":["
                + "{\"path\":\"data/main.scm\",\"size\":1},"
                + "{\"path\":\"samp_log.txt\",\"size\":1},"
                + "{\"path\":\"texdb/a.dxt.dat\",\"size\":1},"
                + "{\"path\":\"texdb/a.etc.dat\",\"size\":1},"
                + "{\"path\":\"GTA/data/extra.scm\",\"size\":1}"
                + "]}";
        List<RemoteFile> all = ManifestParser.parseFileList(json, "https://host/f.json");

        // The engine picks the family itself, so every family is installed.
        List<RemoteFile> dxt = ManifestParser.filterForDevice(all, GpuDetector.DXT);
        assertEquals(4, dxt.size());
        assertEquals("data/main.scm", dxt.get(0).getPath());
        assertEquals("texdb/a.dxt.dat", dxt.get(1).getPath());
        assertEquals("texdb/a.etc.dat", dxt.get(2).getPath());
        // The redundant "GTA/" root segment is stripped so files land in the
        // same folder the game reads.
        assertEquals("data/extra.scm", dxt.get(3).getPath());

        List<RemoteFile> etc = ManifestParser.filterForDevice(all, GpuDetector.ETC);
        assertEquals(4, etc.size());
    }

    @Test
    public void gpuMappingMatchesRendererFamilies() {
        assertEquals(GpuDetector.DXT, GpuDetector.fromRenderer("Adreno (TM) 610"));
        assertEquals(GpuDetector.ETC, GpuDetector.fromRenderer("Mali-G52 MC2"));
        assertEquals(GpuDetector.PVR, GpuDetector.fromRenderer("PowerVR Rogue GE8320"));
        assertEquals(GpuDetector.DXT, GpuDetector.fromRenderer(null));
        assertEquals(GpuDetector.DXT, GpuDetector.fromRenderer(""));
    }

    /**
     * The crash from the device log: a PowerVR Rogue GE8320 was told to download
     * the PVR index, while the game - which probes GL extensions - opened
     * mobile.dxt.tmb and died with SIGSEGV when it was not there.
     */
    @Test
    public void videoCardVendorDoesNotOverrideExtensionProbe() {
        // Exactly what this device advertises: a PowerVR name plus S3TC support.
        String extensions = "GL_EXT_texture_compression_s3tc GL_IMG_texture_compression_pvrtc "
                + "GL_OES_compressed_ETC1_RGB8_texture GL_EXT_texture_filter_anisotropic";
        assertEquals("the game follows the extensions, so must we",
                GpuDetector.DXT,
                GpuDetector.decide("PowerVR Rogue GE8320", extensions));
        assertEquals("dxt", GpuDetector.formatSuffixFor(
                GpuDetector.decide("PowerVR Rogue GE8320", extensions)));
    }

    @Test
    public void extensionProbeOrderIsS3tcThenPvrtcThenEtc() {
        assertEquals(GpuDetector.DXT, GpuDetector.fromExtensions(EXT_S3TC_ONLY));
        assertEquals(GpuDetector.PVR, GpuDetector.fromExtensions(EXT_PVRTC_ONLY));
        assertEquals(GpuDetector.ETC, GpuDetector.fromExtensions(EXT_ATC_ONLY));
        // Nothing but the compulsory ETC1: .etc is the only usable family.
        assertEquals(GpuDetector.ETC, GpuDetector.fromExtensions("GL_OES_compressed_ETC1_RGB8_texture"));
        // DXT1 counts as S3TC.
        assertEquals(GpuDetector.DXT, GpuDetector.fromExtensions("GL_EXT_texture_compression_dxt1"));
    }

    private static final String EXT_S3TC_ONLY = "GL_EXT_texture_compression_s3tc";
    private static final String EXT_PVRTC_ONLY = "GL_IMG_texture_compression_pvrtc";
    private static final String EXT_ATC_ONLY = "GL_AMD_compressed_ATC_texture";

    @Test
    public void textureDatabaseFilesAreFilteredByRealExtension() {
        // The data file has no trailing dot: ".dxt." used to miss it entirely.
        assertTrue(ManifestParser.hasFormatToken("texdb/mobile/mobile.dxt", "dxt"));
        assertTrue(ManifestParser.hasFormatToken("texdb/mobile/mobile.dxt.tmb", "dxt"));
        assertTrue(ManifestParser.hasFormatToken("texdb/mobile/mobile.pvr", "pvr"));
        assertFalse("dxt1 is a different token",
                ManifestParser.hasFormatToken("texdb/mobile/mobile.dxt1", "dxt"));
        assertFalse(ManifestParser.hasFormatToken("texdb/mobile/mobile.pvr", "dxt"));

        // No family is dropped any more: Load() ignores the format it is given
        // and opens the one it selected itself, so the cache must contain all of
        // them or the run ends in SortEntries.
        for (int gpu : new int[]{GpuDetector.DXT, GpuDetector.ETC, GpuDetector.PVR}) {
            assertTrue(ManifestParser.matchesGpu("texdb/mobile/mobile.dxt", gpu));
            assertTrue(ManifestParser.matchesGpu("texdb/mobile/mobile.dxt.tmb", gpu));
            assertTrue(ManifestParser.matchesGpu("texdb/mobile/mobile.pvr.tmb", gpu));
            assertTrue(ManifestParser.matchesGpu("texdb/mobile/mobile.unc.tmb", gpu));
            assertTrue(ManifestParser.matchesGpu("data/main.scm", gpu));
        }
    }

    /**
     * The device log that motivated 0.8.2.9: the launcher asked the engine for
     * the PVR family and the engine still opened texdb/player/player.dxt.tmb.
     * Everything the server publishes has to be installed, whatever GPU the
     * probe reports.
     */
    @Test
    public void everyPublishedFamilyIsInstalledWhateverTheGpu() throws Exception {
        String json = "{\"files\":["
                + "{\"path\":\"texdb/mobile/mobile.dxt\",\"size\":1},"
                + "{\"path\":\"texdb/mobile/mobile.dxt.tmb\",\"size\":1},"
                + "{\"path\":\"texdb/mobile/mobile.pvr\",\"size\":1},"
                + "{\"path\":\"texdb/mobile/mobile.pvr.tmb\",\"size\":1},"
                + "{\"path\":\"texdb/mobile/mobile.etc\",\"size\":1},"
                + "{\"path\":\"texdb/mobile/mobile.unc.tmb\",\"size\":1}"
                + "]}";
        List<RemoteFile> all = ManifestParser.parseFileList(json, "https://host/f.json");

        for (int gpu : new int[]{GpuDetector.DXT, GpuDetector.ETC, GpuDetector.PVR}) {
            java.util.Set<String> kept = new java.util.HashSet<>();
            for (RemoteFile f : ManifestParser.filterForDevice(all, gpu)) {
                kept.add(f.getPath());
            }

            assertTrue("the index libGTASA opens must be installed",
                    kept.contains("texdb/mobile/mobile.dxt.tmb"));
            assertTrue(kept.contains("texdb/mobile/mobile.dxt"));
            assertTrue(kept.contains("texdb/mobile/mobile.pvr.tmb"));
            assertTrue(kept.contains("texdb/mobile/mobile.etc"));
            assertTrue(kept.contains("texdb/mobile/mobile.unc.tmb"));
            assertEquals("nothing the server published is dropped", 6, kept.size());
        }
    }

    @Test
    public void resolvesRelativeUrls() {
        assertEquals("https://host/x.json",
                HttpFetcher.resolve("https://host/files.json", "/x.json"));
        assertEquals("https://host/dir/v2/f.json",
                HttpFetcher.resolve("https://host/dir/files.json", "v2/f.json"));
        assertEquals("https://cdn/x.json",
                HttpFetcher.resolve("https://host/files.json", "https://cdn/x.json"));
    }
}
