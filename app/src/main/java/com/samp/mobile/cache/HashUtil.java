package com.samp.mobile.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.CRC32;

/** Checksums used for cache integrity verification. */
public final class HashUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtil() {
    }

    /**
     * @return lowercase hex digest, or null when the file cannot be read or the
     *         algorithm is unsupported.
     */
    public static String compute(File file, RemoteFile.HashType type) {
        if (file == null || type == null || type == RemoteFile.HashType.NONE) {
            return null;
        }

        if (type == RemoteFile.HashType.CRC32) {
            return crc32(file);
        }

        InputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance(
                    type == RemoteFile.HashType.SHA1 ? "SHA-1" : "MD5");
            in = new FileInputStream(file);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String crc32(File file) {
        InputStream in = null;
        try {
            CRC32 crc = new CRC32();
            in = new FileInputStream(file);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return String.format(Locale.US, "%08x", crc.getValue());
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
