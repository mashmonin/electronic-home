package com.example.electronichome.account;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An act of the verification of a meter. A draft waits for the administrator and is not in force; the act in force is
 * neither a draft nor archived; a replaced act is archived (it stays here and in the bucket).
 */
@Entity
@Table(name = "meter_verification_acts")
class MeterVerificationActEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    /** The end of the verification period that the act confirms. */
    @Column(name = "verification_date", nullable = false)
    private LocalDate verificationDate;

    /** The request that the administrator confirms; null for the act of an administrator. */
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "is_draft", nullable = false)
    private boolean draft;

    @Column(name = "is_archive", nullable = false)
    private boolean archive;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    protected MeterVerificationActEntity() {
    }

    MeterVerificationActEntity(MeterActUploadEntity upload, LocalDate verificationDate, boolean draft, Instant createdAt) {
        this.meterId = upload.getMeterId();
        this.objectKey = upload.getObjectKey();
        this.fileName = upload.getFileName();
        this.contentType = upload.getContentType();
        this.sizeBytes = upload.getSizeBytes();
        this.sha256 = upload.getSha256();
        this.verificationDate = verificationDate;
        this.createdBy = upload.getUserId();
        this.createdAt = createdAt;
        this.draft = draft;
        this.test = upload.isTest();
    }

    void linkRequest(long requestId) {
        this.requestId = requestId;
    }

    /** The draft is confirmed: from now on it is the act in force. */
    void putInForce() {
        this.draft = false;
    }

    Long getId() {
        return id;
    }

    Long getMeterId() {
        return meterId;
    }

    String getObjectKey() {
        return objectKey;
    }

    String getFileName() {
        return fileName;
    }

    String getContentType() {
        return contentType;
    }

    LocalDate getVerificationDate() {
        return verificationDate;
    }

    boolean isDraft() {
        return draft;
    }

    boolean isArchive() {
        return archive;
    }

    Long getRequestId() {
        return requestId;
    }
}
