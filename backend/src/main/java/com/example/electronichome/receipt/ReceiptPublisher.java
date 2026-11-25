package com.example.electronichome.receipt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.YearMonth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The way for the other parts of the system to publish a receipt they made (the same rules and storage as the API). */
@Component
public class ReceiptPublisher {

    /** What the publication did. */
    public enum Outcome {
        CREATED,
        REPLACED,
        ACCOUNT_NOT_FOUND
    }

    private final ReceiptService service;

    ReceiptPublisher(ReceiptService service) {
        this.service = service;
    }

    /**
     * Publishes the PDF as the receipt of the account for the month with both amounts (the source is {@code PARSED}),
     * replacing the receipt that is there. Every call is a transaction of its own.
     */
    public Outcome publish(String accountNumber, YearMonth month, byte[] pdf, BigDecimal capitalRepair, BigDecimal utilities) {
        try {
            ReceiptService.Result result =
                    service.publishGenerated(accountNumber, month, capitalRepair, utilities, new ByteArrayInputStream(pdf));
            return result.created() ? Outcome.CREATED : Outcome.REPLACED;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Outcome.ACCOUNT_NOT_FOUND;
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
