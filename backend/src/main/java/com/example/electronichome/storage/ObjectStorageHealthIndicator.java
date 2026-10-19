package com.example.electronichome.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code objectStorage} in the health: UP while the storage answers and both buckets are there. */
@Component
class ObjectStorageHealthIndicator implements HealthIndicator {

    private final ObjectStore store;
    private final StorageProperties properties;

    ObjectStorageHealthIndicator(ObjectStore store, StorageProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            // asks the storage itself: the buckets that are known from memory would hide a storage that has stopped
            store.check(properties.receiptsBucket());
            store.check(properties.attachmentsBucket());
            return Health.up().build();
        } catch (RuntimeException e) {
            // no address and no text of the error: the settings of prod do not show details anyway
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
