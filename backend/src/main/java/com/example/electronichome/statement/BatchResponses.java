package com.example.electronichome.statement;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The bodies of the batch API answers; no names, addresses or amounts, and no keys of the files. */
public final class BatchResponses {

    private BatchResponses() {
    }

    public record Batch(
            long id,
            String month,
            String status,
            int total,
            int processed,
            int created,
            int replaced,
            int skipped,
            int failed,
            String error,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("finished_at") Instant finishedAt) {
    }

    public record Item(
            @JsonProperty("account_number") String accountNumber,
            String status,
            @JsonProperty("reason_code") String reasonCode,
            String reason) {
    }

    public record Detail(
            long id,
            String month,
            String status,
            int total,
            int processed,
            int created,
            int replaced,
            int skipped,
            int failed,
            String error,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("finished_at") Instant finishedAt,
            List<Item> items) {
    }

    /** The reason of a refusal. */
    public record Message(String message) {
    }

    public record Batches(List<Batch> batches) {
    }
}
