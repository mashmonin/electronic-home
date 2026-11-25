package com.example.electronichome.receipt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.example.electronichome.account.AccountEntity;

@Service
class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    // an amount in roubles: digits, optionally a dot and one or two digits; no sign, no exponent, fits numeric(12,2)
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");
    static final String SOURCE_MANUAL = "MANUAL";
    static final String SOURCE_PARSED = "PARSED";

    /** The result of a publication: what to answer and whether the receipt was new. */
    record Result(ReceiptResponses.Published body, boolean created) {
    }

    /** A file to send. */
    record Download(InputStream content, long size) {
    }

    private final ReceiptRepository receipts;
    private final ReceiptAccounts accounts;
    private final ReceiptStorage storage;
    private final ReceiptProperties properties;
    private final Clock clock;

    ReceiptService(
            ReceiptRepository receipts,
            ReceiptAccounts accounts,
            ReceiptStorage storage,
            ReceiptProperties properties,
            Clock clock) {
        this.receipts = receipts;
        this.accounts = accounts;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes the receipt of the month for an administrator or for a caller with the admin token: the file is stored first, then the record is saved. When the transaction
     * does not commit, the new file is deleted; when it does and an older file was replaced, the older one is deleted.
     */
    @Transactional
    Result publish(
            String accountNumber, String month, boolean admin, String token, String capitalRepair, String utilities, InputStream content)
            throws IOException {
        checkAllowed(admin, token);
        LocalDate periodMonth = parsePublishableMonth(month);
        BigDecimal[] amounts = parseAmounts(capitalRepair, utilities);
        AccountEntity account = accounts.findByNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return store(account, month, periodMonth, content, amounts, SOURCE_MANUAL);
    }

    /**
     * Publishes a receipt that the system made from a statement of the charges: the same storing and replacing as
     * {@link #publish}, with both amounts and the source {@code PARSED}. The month is not checked against today (the
     * caller did it for the whole statement).
     */
    @Transactional
    Result publishGenerated(String accountNumber, YearMonth month, BigDecimal capitalRepair, BigDecimal utilities, InputStream content)
            throws IOException {
        AccountEntity account = accounts.findByNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return store(account, month.toString(), month.atDay(1), content, new BigDecimal[] {capitalRepair, utilities}, SOURCE_PARSED);
    }

    // Stores the file first, then saves the record (replacing the receipt of the month if there is one).
    private Result store(AccountEntity account, String month, LocalDate periodMonth, InputStream content, BigDecimal[] amounts, String source)
            throws IOException {
        ReceiptStorage.Stored stored;
        try {
            stored = storage.save(content, properties.maxSize().toBytes());
        } catch (ReceiptStorage.TooLargeException e) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
        } catch (ReceiptStorage.InvalidContentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        deleteOnRollback(stored.key());

        Optional<ReceiptEntity> existing = receipts.lockByAccountNumberAndPeriodMonth(account.getNumber(), periodMonth);
        String replacedKey = existing.map(ReceiptEntity::getFileKey).orElse(null);
        ReceiptEntity receipt = existing.orElseGet(() -> new ReceiptEntity(account, periodMonth));
        Instant now = Instant.now(clock);
        receipt.setFile(stored.key(), stored.size(), stored.sha256(), now);
        if (amounts == null) {
            receipt.clearAmounts();
        } else {
            receipt.setAmounts(amounts[0], amounts[1], source, now);
        }
        receipts.saveAndFlush(receipt);
        if (replacedKey != null) {
            deleteAfterCommit(replacedKey);
        }

        return new Result(
                new ReceiptResponses.Published(
                        month, stored.size(), stored.sha256(),
                        receipt.getCapitalRepairAmount(), receipt.getUtilitiesAmount(), receipt.getPublishedAt()),
                existing.isEmpty());
    }

    @Transactional(readOnly = true)
    ReceiptResponses.Items list(String accountNumber) {
        accounts.findByNumber(accountNumber).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<ReceiptResponses.Item> items = receipts.findByAccountNumberOrderByPeriodMonthDesc(accountNumber).stream()
                .map(r -> new ReceiptResponses.Item(
                        YearMonth.from(r.getPeriodMonth()).toString(), r.getSizeBytes(),
                        r.getCapitalRepairAmount(), r.getUtilitiesAmount(), r.getPublishedAt()))
                .toList();
        return new ReceiptResponses.Items(items);
    }

    @Transactional(readOnly = true)
    Download download(String accountNumber, String month) throws IOException {
        if (!MONTH.matcher(month).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ReceiptEntity receipt = receipts
                .findByAccountNumberAndPeriodMonth(accountNumber, YearMonth.parse(month).atDay(1))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        InputStream content = storage.open(receipt.getFileKey()).orElse(null);
        if (content == null) {
            log.error("The file of the receipt {} / {} is missing in the storage", accountNumber, month);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return new Download(content, receipt.getSizeBytes());
    }

    // A signed-in administrator may publish; anyone else needs the configured token.
    private void checkAllowed(boolean admin, String token) {
        if (admin) {
            return;
        }
        String expected = properties.adminToken();
        boolean ok = expected != null && !expected.isBlank() && token != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    // The first day of the month; the month must be written as YYYY-MM and must not be in the future.
    private LocalDate parsePublishableMonth(String month) {
        if (!MONTH.matcher(month).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The month must be YYYY-MM");
        }
        try {
            YearMonth parsed = YearMonth.parse(month);
            if (parsed.isAfter(YearMonth.now(clock))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The month is in the future");
            }
            return parsed.atDay(1);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The month must be YYYY-MM");
        }
    }

    // Both amounts or none: null when none is given; anything else that is not an amount is a bad request.
    private BigDecimal[] parseAmounts(String capitalRepair, String utilities) {
        if (capitalRepair == null && utilities == null) {
            return null;
        }
        if (capitalRepair == null || utilities == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give both amounts or none");
        }
        return new BigDecimal[] {parseAmount(capitalRepair), parseAmount(utilities)};
    }

    private BigDecimal parseAmount(String amount) {
        if (!AMOUNT.matcher(amount).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an amount in roubles");
        }
        return new BigDecimal(amount).setScale(2);
    }

    // A transaction that does not commit must not leave the new file behind.
    private void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.delete(key);
                }
            }
        });
    }

    // The replaced file goes only when the record that points to the new one is committed.
    private void deleteAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(key);
            }
        });
    }
}
