package com.example.electronichome.account;

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

    AccountService(AccountRepository accounts, WaterMeterRepository meters) {
        this.accounts = accounts;
        this.meters = meters;
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
        List<AccountResponse.Meter> meterList = meters.findByAddress(address).stream()
                .sorted(METER_ORDER)
                .map(this::toResponse)
                .toList();
        return new AccountResponse(
                account.getNumber(),
                "%s, д. %s, кв. %s".formatted(address.getStreet(), address.getHouse(), address.getApartment()),
                new AccountResponse.Premise(address.getStreet(), address.getHouse(), address.getApartment()),
                owners,
                meterList);
    }

    private AccountResponse.Meter toResponse(WaterMeterEntity meter) {
        List<AccountResponse.Reading> readings = meter.getReadings().stream()
                .sorted(NEWEST_FIRST)
                .map(reading -> new AccountResponse.Reading(
                        reading.getValue(), reading.getReadingDate(), toPrevious(reading.getPreviousReading())))
                .toList();
        return new AccountResponse.Meter(meter.getNumber(), meter.getWaterType(), meter.getVerificationDate(), readings);
    }

    private AccountResponse.PreviousReading toPrevious(MeterReadingEntity previous) {
        return previous == null ? null : new AccountResponse.PreviousReading(previous.getValue(), previous.getReadingDate());
    }
}
