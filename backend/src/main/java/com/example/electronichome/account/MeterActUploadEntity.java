package com.example.electronichome.account;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A file of an act that is in the bucket and not used yet: the key is what the client sends back to change the date. */
@Entity
@Table(name = "meter_act_uploads")
class MeterActUploadEntity {

    @Id
    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    protected MeterActUploadEntity() {
    }

    MeterActUploadEntity(
            String objectKey, Long meterId, Long userId, String fileName, String contentType, long sizeBytes, String sha256,
            Instant createdAt, boolean test) {
        this.objectKey = objectKey;
        this.meterId = meterId;
        this.userId = userId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.createdAt = createdAt;
        this.test = test;
    }

    String getObjectKey() {
        return objectKey;
    }

    Long getMeterId() {
        return meterId;
    }

    Long getUserId() {
        return userId;
    }

    String getFileName() {
        return fileName;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    boolean isTest() {
        return test;
    }
}
