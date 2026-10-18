package com.example.electronichome.receipt;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface ReceiptRepository extends Repository<ReceiptEntity, Long> {

    ReceiptEntity saveAndFlush(ReceiptEntity receipt);

    Optional<ReceiptEntity> findByAccountNumberAndPeriodMonth(String accountNumber, LocalDate periodMonth);

    List<ReceiptEntity> findByAccountNumberOrderByPeriodMonthDesc(String accountNumber);

    /**
     * The receipt of the month, locked until the end of the transaction: two replacements of the same receipt queue
     * up, so the second one sees (and later deletes) the file of the first instead of overwriting its key.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReceiptEntity r where r.account.number = :number and r.periodMonth = :month")
    Optional<ReceiptEntity> lockByAccountNumberAndPeriodMonth(@Param("number") String accountNumber, @Param("month") LocalDate month);
}
