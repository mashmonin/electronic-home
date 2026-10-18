package com.example.electronichome.receipt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The bodies of the receipt API answers; they never contain the key of a file or an internal id. */
public final class ReceiptResponses {

    private ReceiptResponses() {
    }

    /** The answer to a publication. */
    public record Published(
            String month,
            long size,
            String sha256,
            @JsonProperty("capital_repair_amount") BigDecimal capitalRepairAmount,
            @JsonProperty("utilities_amount") BigDecimal utilitiesAmount,
            @JsonProperty("published_at") Instant publishedAt) {
    }

    /** One receipt of the list. */
    public record Item(
            String month,
            long size,
            @JsonProperty("capital_repair_amount") BigDecimal capitalRepairAmount,
            @JsonProperty("utilities_amount") BigDecimal utilitiesAmount,
            @JsonProperty("published_at") Instant publishedAt) {
    }

    public record Items(List<Item> receipts) {
    }
}
