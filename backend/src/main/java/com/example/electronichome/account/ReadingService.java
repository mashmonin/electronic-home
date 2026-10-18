package com.example.electronichome.account;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ReadingService {

    private final AccountRepository accounts;
    private final WaterMeterRepository meters;
    private final MeterReadingRepository readings;
    private final Clock clock;

    ReadingService(AccountRepository accounts, WaterMeterRepository meters, MeterReadingRepository readings, Clock clock) {
        this.accounts = accounts;
        this.meters = meters;
        this.readings = readings;
        this.clock = clock;
    }

    /** Saves all readings of the request in one transaction, or none of them. */
    @Transactional
    ReadingsResponse save(String accountNumber, ReadingsRequest request) {
        AccountEntity account = accounts.findByNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<ReadingsRequest.Item> items = request == null || request.readings() == null ? List.of() : request.readings();
        if (items.isEmpty()) {
            throw new InvalidReadingsException(List.of(new ReadingError(null, ReadingError.EMPTY_REQUEST)));
        }

        List<ReadingError> errors = new ArrayList<>();
        List<ReadingsRequest.Item> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReadingsRequest.Item item : items) {
            if (item.meterNumber() != null && !seen.add(item.meterNumber())) {
                errors.add(new ReadingError(item.meterNumber(), ReadingError.DUPLICATE_METER));
            } else {
                unique.add(item);
            }
        }

        Map<String, WaterMeterEntity> byNumber = seen.isEmpty()
                ? Map.of()
                : meters.lockByAddressAndNumbers(account.getAddress(), seen)
                        .stream()
                        .collect(Collectors.toMap(WaterMeterEntity::getNumber, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, MeterReadingEntity> latest = new LinkedHashMap<>();
        for (ReadingsRequest.Item item : unique) {
            WaterMeterEntity meter = item.meterNumber() == null ? null : byNumber.get(item.meterNumber());
            if (meter == null) {
                errors.add(new ReadingError(item.meterNumber(), ReadingError.METER_NOT_FOUND));
                continue;
            }
            MeterReadingEntity last = readings.findFirstByMeterOrderByReadingDateDescIdDesc(meter).orElse(null);
            latest.put(meter.getNumber(), last);
            String code = ReadingValidation.check(item.value(), last == null ? null : last.getValue());
            if (code != null) {
                errors.add(new ReadingError(meter.getNumber(), code));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidReadingsException(errors);
        }

        LocalDate today = LocalDate.now(clock);
        List<ReadingsResponse.Saved> saved = new ArrayList<>();
        for (ReadingsRequest.Item item : unique) {
            WaterMeterEntity meter = byNumber.get(item.meterNumber());
            MeterReadingEntity previous = latest.get(meter.getNumber());
            MeterReadingEntity reading = readings.save(
                    new MeterReadingEntity(meter, item.value().setScale(3), today, previous, meter.isTest()));
            saved.add(new ReadingsResponse.Saved(
                    meter.getNumber(),
                    meter.getWaterType(),
                    reading.getValue(),
                    reading.getReadingDate(),
                    previous == null ? null : new ReadingsResponse.Previous(previous.getValue(), previous.getReadingDate())));
        }
        return new ReadingsResponse(saved);
    }
}
