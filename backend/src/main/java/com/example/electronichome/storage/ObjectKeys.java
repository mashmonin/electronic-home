package com.example.electronichome.storage;

import java.util.UUID;
import java.util.regex.Pattern;

/** The keys of the objects are UUIDs made by the server; this is the only shape the storage accepts. */
public final class ObjectKeys {

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ObjectKeys() {
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** The key itself when it has the shape of a UUID, otherwise an exception: nothing reaches the storage. */
    public static String require(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a key of an object");
        }
        return key;
    }
}
