package com.example.electronichome.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * An upload read into a temporary file: its size, SHA-256 and the beginning (for the check of the format) are known
 * before anything goes to the storage, and the memory does not grow with the size of the file. {@link #close()} deletes
 * the file.
 */
public final class SpooledUpload implements AutoCloseable {

    /** How many bytes from the start are kept for the check of the format. */
    public static final int HEAD_BYTES = 8192;

    /** The content is bigger than the allowed size. */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException(long maxBytes) {
            super("The file is bigger than " + maxBytes + " bytes");
        }
    }

    private final Path file;
    private final long size;
    private final String sha256;
    private final byte[] head;

    private SpooledUpload(Path file, long size, String sha256, byte[] head) {
        this.file = file;
        this.size = size;
        this.sha256 = sha256;
        this.head = head;
    }

    /** Reads up to {@code maxBytes} bytes; more than that is {@link TooLargeException} and nothing stays on the disk. */
    public static SpooledUpload read(InputStream content, long maxBytes) throws IOException {
        Path file = Files.createTempFile("upload-", ".tmp");
        boolean done = false;
        try {
            MessageDigest digest = newDigest();
            byte[] head = new byte[HEAD_BYTES];
            long total = 0;
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    if (total + read > maxBytes) {
                        throw new TooLargeException(maxBytes);
                    }
                    if (total < HEAD_BYTES) {
                        System.arraycopy(buffer, 0, head, (int) total, (int) Math.min(read, HEAD_BYTES - total));
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
            done = true;
            return new SpooledUpload(file, total, HexFormat.of().formatHex(digest.digest()), Arrays.copyOf(head, (int) Math.min(total, HEAD_BYTES)));
        } finally {
            if (!done) {
                Files.deleteIfExists(file);
            }
        }
    }

    public Path file() {
        return file;
    }

    public long size() {
        return size;
    }

    public String sha256() {
        return sha256;
    }

    /** The first bytes of the content (at most {@value #HEAD_BYTES}). */
    public byte[] head() {
        return head;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a temporary file that stays is cleaned by the system
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
