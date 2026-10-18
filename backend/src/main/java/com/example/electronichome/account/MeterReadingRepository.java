package com.example.electronichome.account;

import java.util.Optional;

import org.springframework.data.repository.Repository;

interface MeterReadingRepository extends Repository<MeterReadingEntity, Long> {

    MeterReadingEntity save(MeterReadingEntity reading);

    Optional<MeterReadingEntity> findFirstByMeterOrderByReadingDateDescIdDesc(WaterMeterEntity meter);
}
