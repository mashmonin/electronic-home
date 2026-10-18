package com.example.electronichome.account;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface WaterMeterRepository extends Repository<WaterMeterEntity, Long> {

    @EntityGraph(attributePaths = {"readings", "readings.previousReading"})
    List<WaterMeterEntity> findByAddress(AddressEntity address);

    /** Locks the meters in a fixed (id) order, so concurrent requests queue up instead of deadlocking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from WaterMeterEntity m where m.address = :address and m.number in :numbers order by m.id")
    List<WaterMeterEntity> lockByAddressAndNumbers(
            @Param("address") AddressEntity address, @Param("numbers") Collection<String> numbers);
}
