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
    public void filtersIgnoredNamesAndWrongGpu() throws Exception {
        String json = "{\"files\":["
                + "{\"path\":\"data/main.scm\",\"size\":1},"
                + "{\"path\":\"samp_log.txt\",\"size\":1},"
                + "{\"path\":\"texdb/a.dxt.dat\",\"size\":1},"
                + "{\"path\":\"texdb/a.etc.dat\",\"size\":1},"
                + "{\"path\":\"GTA/data/extra.scm\",\"size\":1}"
                + "]}";
        List<RemoteFile> all = ManifestParser.parseFileList(json, "https://host/f.json");

        List<RemoteFile> dxt = ManifestParser.filterForDevice(all, GpuDetector.DXT);
        assertEquals(3, dxt.size());
        assertEquals("data/main.scm", dxt.get(0).getPath());
        assertEquals("texdb/a.dxt.dat", dxt.get(1).getPath());
        // The redundant "GTA/" root segment is stripped so files land in the
        // same folder the game reads.
        assertEquals("data/extra.scm", dxt.get(2).getPath());

        List<RemoteFile> etc = ManifestParser.filterForDevice(all, GpuDetector.ETC);
        assertEquals("texdb/a.etc.dat", etc.get(1).getPath());
    }

    @Test
    public void gpuMappingMatchesRendererFamilies() {
        assertEquals(GpuDetector.DXT, GpuDetector.fromRenderer("Adreno (TM) 610"));
        assertEquals(GpuDetector.ETC, GpuDetector.fromRenderer("Mali-G52 MC2"));
        assertEquals(GpuDetector.PVR, GpuDetector.fromRenderer("PowerVR Rogue GE8320"));
        assertEquals(GpuDetector.DXT, GpuDetector.fromRenderer(null));
        assertEquals(GpuDetector.DXT, GpuDetector.fromRenderer(""));
    }

    @Test
    public void resolvesRelativeUrls() {
        assertEquals("https://host/x.json",
                HttpFetcher.resolve("https://host/files.json", "/x.json"));
        assertEquals("https://host/v2/f.json",
                HttpFetcher.resolve("https://host/dir/files.json", "v2/f.json"));
        assertEquals("https://cdn/x.json",
                HttpFetcher.resolve("https://host/files.json", "https://cdn/x.json"));
    }
}
