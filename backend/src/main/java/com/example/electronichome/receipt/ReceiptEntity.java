package com.example.electronichome.receipt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.example.electronichome.account.AccountEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A published receipt: the metadata of a PDF that lives in the file storage under {@code fileKey}. */
@Entity
@Table(name = "receipts")
public class ReceiptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    /** The first day of the month the receipt is for. */
    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "file_key", nullable = false)
    private String fileKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    /** Amount for capital repair in roubles; null while unknown. Set together with the utilities amount. */
    @Column(name = "capital_repair_amount", precision = 12, scale = 2)
    private BigDecimal capitalRepairAmount;

    /** Amount for utilities in roubles; null while unknown. */
    @Column(name = "utilities_amount", precision = 12, scale = 2)
    private BigDecimal utilitiesAmount;

    /** Where the amounts come from: MANUAL (given on publication) or PARSED (read from the PDF); null without amounts. */
    @Column(name = "amounts_source")
    private String amountsSource;

    @Column(name = "amounts_at")
    private Instant amountsAt;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    protected ReceiptEntity() {
    }

    ReceiptEntity(AccountEntity account, LocalDate periodMonth) {
        this.account = account;
        this.periodMonth = periodMonth;
        this.test = account.isTest();
    }

    void setFile(String fileKey, long sizeBytes, String sha256, Instant publishedAt) {
        this.fileKey = fileKey;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.publishedAt = publishedAt;
    }

    /** Records the amounts and where and when they come from. */
    void setAmounts(BigDecimal capitalRepair, BigDecimal utilities, String source, Instant at) {
        this.capitalRepairAmount = capitalRepair;
        this.utilitiesAmount = utilities;
        this.amountsSource = source;
        this.amountsAt = at;
    }

    /** The amounts belonged to the replaced file, so they are dropped together with it. */
    void clearAmounts() {
        setAmounts(null, null, null, null);
    }

    BigDecimal getCapitalRepairAmount() {
        return capitalRepairAmount;
    }

    BigDecimal getUtilitiesAmount() {
        return utilitiesAmount;
    }

    LocalDate getPeriodMonth() {
        return periodMonth;
    }

    String getFileKey() {
        return fileKey;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    boolean isTest() {
        return test;
    }
}
