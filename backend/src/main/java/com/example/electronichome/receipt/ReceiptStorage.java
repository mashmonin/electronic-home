package com.example.electronichome.receipt;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/** Keeps the PDF files of the receipts under keys that the storage itself creates. */
interface ReceiptStorage {

    /** What was stored: the key to find it by, its size and SHA-256 (hex). */
    record Stored(String key, long size, String sha256) {
    }

    /** The content is not a PDF (does not start with {@code %PDF-}), or is empty. */
    class InvalidContentException extends RuntimeException {
        InvalidContentException(String message) {
            super(message);
        }
    }

    /** The content is bigger than the allowed size. */
    class TooLargeException extends RuntimeException {
        TooLargeException(long maxBytes) {
            super("The file is bigger than " + maxBytes + " bytes");
        }
    }

    /** Reads the content up to {@code maxBytes}, checks it is a PDF and stores it; nothing is left behind on failure. */
    Stored save(InputStream content, long maxBytes) throws IOException;

    /** The content under the key, or empty when there is no such file. The key must be one made by {@link #save}. */
    Optional<InputStream> open(String key) throws IOException;

    /** Deletes the file; no error when it is not there. The key must be one made by {@link #save}. */
    void delete(String key);
}
