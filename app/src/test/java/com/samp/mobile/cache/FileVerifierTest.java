package com.samp.mobile.cache;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The verifier is what decides whether the game may start, so every failure
 * mode is pinned down here: an absent file, a truncated file, a file of the
 * right length but with different bytes, and a perfectly fine cache.
 */
public class FileVerifierTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File root;

    @Before
    public void setUp() throws Exception {
        root = temp.newFolder("GTA");
    }

    private File write(String path, String content) throws Exception {
        File f = new File(root, path);
        File parent = f.getParentFile();
        if (parent != null) {
            assertTrue(parent.isDirectory() || parent.mkdirs());
        }
        FileOutputStream out = new FileOutputStream(f);
        out.write(content.getBytes("UTF-8"));
        out.close();
        return f;
    }

    private static RemoteFile entry(String path, String content) {
        return new RemoteFile(path, path, content.getBytes().length, "https://host/" + path,
                RemoteFile.HashType.NONE, null);
    }

    private static RemoteFile entryWithMd5(String path, int size, String md5) {
        return new RemoteFile(path, path, size, "https://host/" + path,
                RemoteFile.HashType.MD5, md5);
    }

    @Test
    public void completeCacheNeedsNoDownload() throws Exception {
        write("data/main.scm", "abc");
        write("TEXT/AMERICAN.GXT", "hello");

        List<RemoteFile> manifest = Arrays.asList(
                entry("data/main.scm", "abc"),
                entry("TEXT/AMERICAN.GXT", "hello"));

        FileVerifier.Result r = FileVerifier.verify(root, manifest, null);
        assertTrue(r.isComplete());
        assertEquals(2, r.okCount());
        assertEquals(0, r.bytesToDownload);
        assertEquals("all files verified", r.describeProblems());
    }

    @Test
    public void missingFileIsReportedAndCounted() throws Exception {
        write("data/main.scm", "abc");

        List<RemoteFile> manifest = Arrays.asList(
                entry("data/main.scm", "abc"),
                entry("models/gta3.img", "0123456789"));

        FileVerifier.Result r = FileVerifier.verify(root, manifest, null);
        assertFalse(r.isComplete());
        assertEquals(1, r.incomplete.size());
        assertEquals("models/gta3.img", r.incomplete.get(0).getPath());
        assertEquals(10, r.bytesToDownload);
        assertEquals(FileVerifier.Status.MISSING, r.entries.get(1).status);
        assertTrue(r.describeProblems().contains("missing"));
    }

    @Test
    public void truncatedFileIsSizeMismatch() throws Exception {
        write("models/gta3.img", "abc");   // 3 bytes, manifest says 10

        FileVerifier.Result r = FileVerifier.verify(root,
                new ArrayList<>(Arrays.asList(entry("models/gta3.img", "0123456789"))), null);

        assertEquals(FileVerifier.Status.SIZE_MISMATCH, r.entries.get(0).status);
        assertEquals(1, r.incomplete.size());
    }

    @Test
    public void alteredFileOfSameLengthIsHashMismatch() throws Exception {
        // Same length, wrong bytes: only a checksum can catch this.
        write("TEXT/AMERICAN.GXT", "abd");

        // md5("abc") - deliberately not the digest of what is on disk
        RemoteFile file = entryWithMd5("TEXT/AMERICAN.GXT", 3,
                "900150983cd24fb0d6963f7d28e17f72");

        FileVerifier.Result r = FileVerifier.verify(root,
                new ArrayList<>(Arrays.asList(file)), null);

        assertEquals(FileVerifier.Status.HASH_MISMATCH, r.entries.get(0).status);
        assertEquals(1, r.incomplete.size());
        assertTrue(r.describeProblems().contains("corrupted"));
    }

    @Test
    public void matchingChecksumIsAccepted() throws Exception {
        write("TEXT/AMERICAN.GXT", "abc");
        RemoteFile file = entryWithMd5("TEXT/AMERICAN.GXT", 3,
                "900150983CD24FB0D6963F7D28E17F72"); // upper case on purpose

        FileVerifier.Result r = FileVerifier.verify(root,
                new ArrayList<>(Arrays.asList(file)), null);
        assertTrue(r.isComplete());
    }

    @Test
    public void directoryWhereFileExpectedIsUnreadable() throws Exception {
        File dir = new File(root, "models/gta3.img");
        assertTrue(dir.mkdirs());

        FileVerifier.Result r = FileVerifier.verify(root,
                new ArrayList<>(Arrays.asList(entry("models/gta3.img", "abc"))), null);

        assertEquals(FileVerifier.Status.UNREADABLE, r.entries.get(0).status);
        assertFalse(r.isComplete());
    }
}
