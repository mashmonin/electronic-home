package com.example.electronichome.account;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

interface WaterMeterRepository extends Repository<WaterMeterEntity, Long> {

    @EntityGraph(attributePaths = {"readings", "readings.previousReading"})
    List<WaterMeterEntity> findByAddress(AddressEntity address);
}
