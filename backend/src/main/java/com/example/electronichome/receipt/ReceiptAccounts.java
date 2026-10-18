package com.example.electronichome.receipt;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.example.electronichome.account.AccountEntity;

/** Finds the account a receipt belongs to. */
interface ReceiptAccounts extends Repository<AccountEntity, Long> {

    Optional<AccountEntity> findByNumber(String number);
}
