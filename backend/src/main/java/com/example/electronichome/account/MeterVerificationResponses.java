package com.example.electronichome.account;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Bodies of the answers about the verification of a meter; no keys of the storage in them but the one the client sends back. */
final class MeterVerificationResponses {

    private MeterVerificationResponses() {
    }

    record Uploaded(
            @JsonProperty("file_key") String fileKey,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("size_bytes") long sizeBytes) {
    }

    record RequestInfo(long id, String number) {
    }

    /** {@code applied}: the date is in force now (administrator); otherwise the request waits for the confirmation. */
    record Changed(
            boolean applied,
            @JsonProperty("verification_date") LocalDate verificationDate,
            RequestInfo request) {
    }
}
