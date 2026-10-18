package com.example.electronichome.account;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AccountService {

    private static final Comparator<OwnerEntity> BY_NAME_THEN_ID =
            Comparator.comparing(OwnerEntity::getFullName).thenComparing(OwnerEntity::getId);

    private final AccountRepository accounts;

    AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    AccountResponse getByNumber(String number) {
        AccountEntity account = accounts.findByNumber(number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AddressEntity address = account.getAddress();
        List<AccountResponse.Owner> owners = account.getOwners().stream()
                .sorted(BY_NAME_THEN_ID)
                .map(owner -> new AccountResponse.Owner(owner.getFullName()))
                .toList();
        return new AccountResponse(
                account.getNumber(),
                "%s, д. %s, кв. %s".formatted(address.getStreet(), address.getHouse(), address.getApartment()),
                new AccountResponse.Premise(address.getStreet(), address.getHouse(), address.getApartment()),
                owners);
    }
}
