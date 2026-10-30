package com.samp.mobile.cache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compares the local cache folder against the remote manifest.
 *
 * Runs entirely on the caller's thread and touches nothing but the file system,
 * so it is unit tested against temporary directories.
 */
public final class FileVerifier {

    /** Why a single manifest entry is not satisfied. */
    public enum Status {
        /** Present, correct size, checksum matched (or no checksum was offered). */
        OK,
        /** Not on disk at all. */
        MISSING,
        /** Present but the length differs: truncated, partially written or empty. */
        SIZE_MISMATCH,
        /** Same length but the checksum differs: the bytes were altered. */
        HASH_MISMATCH,
        /** Path not readable or a directory where a file was expected. */
        UNREADABLE
    }

    public static final class Entry {
        public final RemoteFile remote;
        public final Status status;
        public final long localSize;

        Entry(RemoteFile remote, Status status, long localSize) {
            this.remote = remote;
            this.status = status;
            this.localSize = localSize;
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }

    /** Full picture of one verification pass. */
    public static final class Result {
        public final List<Entry> entries;
        public final long bytesToDownload;
        /** Files that need (re)downloading. */
        public final List<RemoteFile> incomplete;

        Result(List<Entry> entries, long bytesToDownload, List<RemoteFile> incomplete) {
            this.entries = Collections.unmodifiableList(entries);
            this.bytesToDownload = bytesToDownload;
            this.incomplete = Collections.unmodifiableList(incomplete);
        }

        public int totalCount() {
            return entries.size();
        }

        public int okCount() {
            int n = 0;
            for (Entry e : entries) {
                if (e.isOk()) {
                    n++;
                }
            }
            return n;
        }

        public boolean isComplete() {
            return incomplete.isEmpty();
        }

        /** e.g. "12 files missing, 3 corrupted". */
        public String describeProblems() {
            int missing = 0;
            int corrupted = 0;
            for (Entry e : entries) {
                if (e.status == Status.MISSING) {
                    missing++;
                } else if (e.status == Status.SIZE_MISMATCH
                        || e.status == Status.HASH_MISMATCH
                        || e.status == Status.UNREADABLE) {
                    corrupted++;
                }
            }
            StringBuilder sb = new StringBuilder();
            if (missing > 0) {
                sb.append(missing).append(missing == 1 ? " file missing" : " files missing");
            }
            if (corrupted > 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(corrupted).append(corrupted == 1 ? " file corrupted" : " files corrupted");
            }
            if (sb.length() == 0) {
                sb.append("all files verified");
            }
            return sb.toString();
        }
    }

    private FileVerifier() {
    }

    public static Result verify(File root, List<RemoteFile> manifest, ProgressListener progress) {
        return verify(root, manifest, null, progress);
    }

    /**
     * @param root      cache folder (e.g. /storage/emulated/0/GTA)
     * @param manifest  device-filtered manifest
     * @param receipts  what the files looked like when they were written; may be
     *                  null, in which case only sizes and server checksums are
     *                  used
     * @param progress  optional callback, may be null
     */
    public static Result verify(File root, List<RemoteFile> manifest,
                                ReceiptStore receipts, ProgressListener progress) {
        List<Entry> entries = new ArrayList<>(manifest.size());
        List<RemoteFile> incomplete = new ArrayList<>();
        long bytesToDownload = 0;

        int total = manifest.size();
        int index = 0;

        for (RemoteFile remote : manifest) {
            index++;
            long localSize = -1;

            if (!remote.isSafePath()) {
                entries.add(new Entry(remote, Status.UNREADABLE, -1));
                incomplete.add(remote);
                bytesToDownload += Math.max(0, remote.getSize());
                continue;
            }

            File local = new File(root, remote.getPath());
            Status status;

            if (!local.exists()) {
                status = Status.MISSING;
            } else if (!local.isFile() || !local.canRead()) {
                status = Status.UNREADABLE;
                localSize = local.length();
            } else {
                localSize = local.length();
                status = checkContent(local, localSize, remote, receipts);
            }

            entries.add(new Entry(remote, status, localSize));

            if (status != Status.OK) {
                incomplete.add(remote);
                long size = remote.getSize();
                if (size <= 0) {
                    size = Math.max(0, localSize);
                }
                bytesToDownload += size;
            }

            if (progress != null && (index % 25 == 0 || index == total)) {
                progress.onVerifyProgress(index, total);
            }
        }

        return new Result(entries, bytesToDownload, incomplete);
    }

    /**
     * The heart of "the tree must match the server exactly".
     *
     * Order of evidence, strongest first:
     *
     *  1. the server's checksum, when the manifest publishes one - this is the
     *     only thing that can prove the bytes are the bytes the server holds;
     *  2. the receipt recorded when the file was downloaded, which detects a
     *     file that was edited afterwards, even without changing its length;
     *  3. the length alone, for files that existed before this app ever wrote
     *     them and for which nothing else is known yet.
     */
    private static Status checkContent(File local, long localSize,
                                       RemoteFile remote, ReceiptStore receipts) {
        if (remote.getSize() >= 0 && localSize != remote.getSize()) {
            return Status.SIZE_MISMATCH;
        }

        if (remote.hasHash()) {
            String actual = HashUtil.compute(local, remote.getHashType());
            if (actual == null || !actual.equalsIgnoreCase(remote.getHash().trim())) {
                return Status.HASH_MISMATCH;
            }
            if (receipts != null && remote.getHashType() == RemoteFile.HashType.MD5) {
                receipts.put(remote.getPath(), localSize, local.lastModified(), actual);
            }
            return Status.OK;
        }

        if (receipts == null) {
            return Status.OK;
        }

        ReceiptStore.Entry receipt = receipts.get(remote.getPath());
        if (receipt == null) {
            // Nothing known about this file yet: accept the length but record
            // nothing, because hashing the whole cache here would make every
            // launch slow.
            return Status.OK;
        }

        if (receipt.size == localSize && receipt.mtime == local.lastModified()
                && receipt.md5 != null) {
            // Untouched since it was written: no need to read a single byte.
            return Status.OK;
        }

        // Length or timestamp moved: prove the content instead of assuming it.
        String actual = HashUtil.compute(local, RemoteFile.HashType.MD5);
        if (actual == null) {
            return Status.UNREADABLE;
        }
        if (!actual.equalsIgnoreCase(receipt.md5)) {
            return Status.HASH_MISMATCH;
        }
        receipts.put(remote.getPath(), localSize, local.lastModified(), actual);
        return Status.OK;
    }

    public interface ProgressListener {
        void onVerifyProgress(int checked, int total);
    }
}
