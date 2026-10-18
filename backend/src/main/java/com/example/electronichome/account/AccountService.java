package com.example.electronichome.account;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AccountService {

    private static final Comparator<OwnerEntity> OWNER_ORDER =
            Comparator.comparing(OwnerEntity::getFullName).thenComparing(OwnerEntity::getId);
    private static final Comparator<WaterMeterEntity> METER_ORDER =
            Comparator.comparing(WaterMeterEntity::getNumber).thenComparing(WaterMeterEntity::getId);
    private static final Comparator<MeterReadingEntity> NEWEST_FIRST =
            Comparator.comparing(MeterReadingEntity::getReadingDate).thenComparing(MeterReadingEntity::getId).reversed();

    private final AccountRepository accounts;
    private final WaterMeterRepository meters;
    private final ReadingPeriodProperties periodProperties;
    private final Clock clock;

    AccountService(AccountRepository accounts, WaterMeterRepository meters, ReadingPeriodProperties periodProperties, Clock clock) {
        this.accounts = accounts;
        this.meters = meters;
        this.periodProperties = periodProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    AccountResponse getByNumber(String number) {
        AccountEntity account = accounts.findByNumber(number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AddressEntity address = account.getAddress();
        List<AccountResponse.Owner> owners = account.getOwners().stream()
                .sorted(OWNER_ORDER)
                .map(owner -> new AccountResponse.Owner(owner.getFullName()))
                .toList();
        ReadingPeriod period = ReadingPeriod.containing(LocalDate.now(clock), periodProperties.closingDay());
        List<AccountResponse.Meter> meterList = meters.findByAddress(address).stream()
                .sorted(METER_ORDER)
                .map(meter -> toResponse(meter, period))
                .toList();
        boolean allSubmitted = !meterList.isEmpty() && meterList.stream().allMatch(m -> m.currentPeriodReading() != null);
        return new AccountResponse(
                account.getNumber(),
                "%s, д. %s, кв. %s".formatted(address.getStreet(), address.getHouse(), address.getApartment()),
                new AccountResponse.Premise(address.getStreet(), address.getHouse(), address.getApartment()),
                owners,
                new AccountResponse.ReadingPeriodInfo(
                        period.start(), period.end(), period.next(), allSubmitted, periodProperties.enforced()),
                meterList);
    }

    private AccountResponse.Meter toResponse(WaterMeterEntity meter, ReadingPeriod period) {
        List<MeterReadingEntity> sorted = meter.getReadings().stream().sorted(NEWEST_FIRST).toList();
        List<AccountResponse.Reading> readings = sorted.stream().map(this::toReading).toList();
        // newest first, so the first one inside the period is the latest one taken in it
        AccountResponse.Reading current = sorted.stream()
                .filter(reading -> period.contains(reading.getReadingDate()))
                .findFirst()
                .map(this::toReading)
                .orElse(null);
        boolean locked = periodProperties.enforced() && current != null;
        return new AccountResponse.Meter(
                meter.getNumber(), meter.getWaterType(), meter.getVerificationDate(), current, locked, readings);
    }

    private AccountResponse.Reading toReading(MeterReadingEntity reading) {
        return new AccountResponse.Reading(reading.getValue(), reading.getReadingDate(), toPrevious(reading.getPreviousReading()));
    }

    private AccountResponse.PreviousReading toPrevious(MeterReadingEntity previous) {
        return previous == null ? null : new AccountResponse.PreviousReading(previous.getValue(), previous.getReadingDate());
    }
}
