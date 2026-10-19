package com.example.electronichome.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/** Keeps the files in buckets under keys that are UUIDs made by the server. */
public interface ObjectStore {

    /** Writes the whole file as the object; the bucket is created when it is not there yet. */
    void put(String bucket, String key, Path file, long size, String contentType);

    /** The content of the object, or empty when there is no such object. The caller closes the stream. */
    Optional<InputStream> open(String bucket, String key);

    boolean exists(String bucket, String key);

    /** Deletes the object; no error when it is not there. */
    void delete(String bucket, String key);

    /** Makes sure the bucket is there (creates it if needed); safe to call at the same time from many threads. */
    void ensureBucket(String bucket);

    /**
     * Asks the storage now (not from memory) whether the bucket is there, creating it if it is not, and fails when the
     * storage does not answer in a few seconds. For the health; the other operations use {@link #ensureBucket}.
     */
    default void check(String bucket) {
        ensureBucket(bucket);
    }
}
