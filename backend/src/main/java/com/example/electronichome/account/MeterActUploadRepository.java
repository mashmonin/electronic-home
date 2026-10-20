package com.example.electronichome.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

import jakarta.persistence.LockModeType;

interface MeterActUploadRepository extends Repository<MeterActUploadEntity, String> {

    MeterActUploadEntity save(MeterActUploadEntity upload);

    /** Locks the upload, so that two changes cannot use the same file. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MeterActUploadEntity> findById(String objectKey);

    List<MeterActUploadEntity> findByCreatedAtBefore(Instant before);

    void delete(MeterActUploadEntity upload);
}
