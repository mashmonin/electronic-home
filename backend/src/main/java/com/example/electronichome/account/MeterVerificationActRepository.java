package com.example.electronichome.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface MeterVerificationActRepository extends Repository<MeterVerificationActEntity, Long> {

    MeterVerificationActEntity saveAndFlush(MeterVerificationActEntity act);

    Optional<MeterVerificationActEntity> findByRequestIdAndDraftTrue(Long requestId);

    boolean existsByObjectKey(String objectKey);

    /** Archives the act in force of the meter, if there is one; the object in the bucket stays. */
    @Modifying(flushAutomatically = true)
    @Query("update MeterVerificationActEntity a set a.archive = true where a.meterId = :meterId and a.draft = false and a.archive = false")
    int archiveInForce(@Param("meterId") Long meterId);
}
