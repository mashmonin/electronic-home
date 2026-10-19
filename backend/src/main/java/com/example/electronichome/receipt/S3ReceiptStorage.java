package com.example.electronichome.receipt;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.electronichome.storage.ObjectKeys;
import com.example.electronichome.storage.ObjectStore;
import com.example.electronichome.storage.SpooledUpload;
import com.example.electronichome.storage.StorageProperties;

import java.nio.charset.StandardCharsets;

/**
 * Keeps the receipts in the bucket of the receipts of the object storage. A file is read into a temporary file first, so
 * its size, hash and format are known before anything is written; the key is a UUID made here, never taken from the
 * client.
 */
@Component
class S3ReceiptStorage implements ReceiptStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ReceiptStorage.class);
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final String PDF = "application/pdf";

    private final ObjectStore store;
    private final String bucket;

    S3ReceiptStorage(ObjectStore store, StorageProperties properties) {
        this.store = store;
        this.bucket = properties.receiptsBucket();
    }

    @Override
    public Stored save(InputStream content, long maxBytes) throws IOException {
        try (SpooledUpload upload = SpooledUpload.read(content, maxBytes)) {
            if (upload.size() == 0) {
                throw new InvalidContentException("The file is empty");
            }
            if (upload.size() < PDF_HEADER.length || !Arrays.equals(Arrays.copyOf(upload.head(), PDF_HEADER.length), PDF_HEADER)) {
                throw new InvalidContentException("The file is not a PDF");
            }
            String key = ObjectKeys.newKey();
            store.put(bucket, key, upload.file(), upload.size(), PDF);
            return new Stored(key, upload.size(), upload.sha256());
        } catch (SpooledUpload.TooLargeException e) {
            throw new TooLargeException(maxBytes);
        }
    }

    @Override
    public Optional<InputStream> open(String key) throws IOException {
        return store.open(bucket, ObjectKeys.require(key));
    }

    @Override
    public void delete(String key) {
        try {
            store.delete(bucket, ObjectKeys.require(key));
        } catch (RuntimeException e) {
            log.warn("Cannot delete the receipt file {}", key, e);
        }
    }
}
