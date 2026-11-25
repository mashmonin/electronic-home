package com.example.electronichome.statement;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.electronichome.storage.ObjectKeys;
import com.example.electronichome.storage.ObjectStore;
import com.example.electronichome.storage.StorageProperties;

/** Keeps the uploaded statements in the bucket of the statements under keys that are UUIDs made here. */
@Component
class StatementStorage {

    private static final String XLS = "application/vnd.ms-excel";

    private final ObjectStore store;
    private final String bucket;

    StatementStorage(ObjectStore store, StorageProperties properties) {
        this.store = store;
        this.bucket = properties.statementsBucket();
    }

    /** Writes the file and returns the new key. */
    String save(Path file, long size) {
        String key = ObjectKeys.newKey();
        store.put(bucket, key, file, size, XLS);
        return key;
    }

    Optional<InputStream> open(String key) {
        return store.open(bucket, ObjectKeys.require(key));
    }
}
