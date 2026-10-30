package com.samp.mobile.cache;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HashUtilTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File fileWith(String content) throws Exception {
        File f = temp.newFile("data.bin");
        FileOutputStream out = new FileOutputStream(f);
        out.write(content.getBytes("UTF-8"));
        out.close();
        return f;
    }

    @Test
    public void md5OfAbc() throws Exception {
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                HashUtil.compute(fileWith("abc"), RemoteFile.HashType.MD5));
    }

    @Test
    public void sha1OfAbc() throws Exception {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                HashUtil.compute(fileWith("abc"), RemoteFile.HashType.SHA1));
    }

    @Test
    public void crc32OfAbc() throws Exception {
        assertEquals("352441c2",
                HashUtil.compute(fileWith("abc"), RemoteFile.HashType.CRC32));
    }

    @Test
    public void noneHasNoDigest() throws Exception {
        assertNull(HashUtil.compute(fileWith("abc"), RemoteFile.HashType.NONE));
    }

    @Test
    public void missingFileHasNoDigest() {
        assertNull(HashUtil.compute(new File(temp.getRoot(), "nope"), RemoteFile.HashType.MD5));
    }
}
