package com.example.electronichome.statement;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.electronichome.receipt.ReceiptPublisher;
import com.example.electronichome.statement.Statement.AccountStatement;

import jakarta.annotation.PreDestroy;

/**
 * Makes the receipts of a batch in the background, one batch at a time: reads the statement from the storage, makes the
 * PDF of every account and publishes it. A failure of one account never stops the others. Nothing here writes names,
 * addresses or amounts to the log.
 */
@Component
class BatchRunner {

    private static final Pattern NUMBER = Pattern.compile("[1-9]\\d{0,11}");
    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final BatchRepository batches;
    private final StatementStorage storage;
    private final StatementParser parser;
    private final ReceiptPdfGenerator pdf;
    private final ReceiptPublisher publisher;
    private final ExecutorService executor;

    BatchRunner(BatchRepository batches, StatementStorage storage, StatementParser parser, ReceiptPdfGenerator pdf, ReceiptPublisher publisher) {
        this.batches = batches;
        this.storage = storage;
        this.parser = parser;
        this.pdf = pdf;
        this.publisher = publisher;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "receipt-batches");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /** What was left queued or running by the previous run of the application cannot go on. */
    @EventListener(ApplicationReadyEvent.class)
    void failInterrupted() {
        int count = batches.failInterrupted();
        if (count > 0) {
            log.warn("{} batch(es) of receipts were interrupted by the restart", count);
        }
    }

    void submit(long batchId) {
        executor.execute(() -> run(batchId));
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    // visible for the tests: runs the batch in the calling thread
    void run(long batchId) {
        BatchRepository.Batch batch = batches.find(batchId).orElse(null);
        if (batch == null || !BatchStatus.QUEUED.equals(batch.status())) {
            return;
        }
        batches.markRunning(batchId);
        try {
            Statement statement = read(batch.sourceKey());
            for (AccountStatement account : statement.accounts().values()) {
                process(batchId, statement, account);
            }
            batches.finish(batchId, BatchStatus.DONE, null);
        } catch (RuntimeException | IOException e) {
            // the text of the error may carry a name from the sheet: only the class goes to the log and the batch
            log.error("The batch {} of receipts failed: {}", batchId, e.getClass().getSimpleName());
            batches.finish(batchId, BatchStatus.FAILED, e.getClass().getSimpleName());
        }
    }

    private Statement read(String key) throws IOException {
        try (InputStream in = storage.open(key).orElseThrow(() -> new IllegalStateException("The statement is missing in the storage"))) {
            return parser.parse(in);
        }
    }

    private void process(long batchId, Statement statement, AccountStatement account) {
        String number = account.number();
        // "003" (an office) is not the account "3": a number with a zero in front is never one of the accounts of the apartments
        if (!NUMBER.matcher(number).matches()) {
            batches.record(batchId, number, BatchStatus.SKIPPED, BatchStatus.ACCOUNT_NOT_FOUND);
            return;
        }
        String padded = padded(number);
        try {
            if (account.utilities() == null || account.capitalRepair() == null) {
                batches.record(batchId, number, BatchStatus.FAILED, BatchStatus.MISSING_PART);
                return;
            }
            byte[] file;
            try {
                file = pdf.generate(account);
            } catch (RuntimeException e) {
                log.warn("The PDF of the account {} of the batch {} cannot be made: {}", number, batchId, e.getClass().getSimpleName());
                batches.record(batchId, number, BatchStatus.FAILED, BatchStatus.PDF_ERROR);
                return;
            }
            BigDecimal capitalRepair = account.capitalRepair().total();
            BigDecimal utilities = account.utilities().total();
            ReceiptPublisher.Outcome outcome = publisher.publish(padded, statement.period(), file, capitalRepair, utilities);
            switch (outcome) {
                case CREATED -> batches.record(batchId, number, BatchStatus.CREATED, null);
                case REPLACED -> batches.record(batchId, number, BatchStatus.REPLACED, null);
                case ACCOUNT_NOT_FOUND -> batches.record(batchId, number, BatchStatus.SKIPPED, BatchStatus.ACCOUNT_NOT_FOUND);
            }
        } catch (RuntimeException e) {
            log.warn("The receipt of the account {} of the batch {} cannot be published: {}", number, batchId, e.getClass().getSimpleName());
            batches.record(batchId, number, BatchStatus.FAILED, BatchStatus.PUBLISH_ERROR);
        }
    }

    // the account numbers of the database have 12 digits, the statement the number without the zeros
    static String padded(String number) {
        return "0".repeat(Math.max(0, 12 - number.length())) + number;
    }
}
