package com.example.electronichome.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Account information as returned by {@code GET /api/accounts/{number}}. */
public record AccountResponse(
        String number,
        String address,
        Premise premise,
        List<Owner> owners,
        @JsonProperty("reading_period") ReadingPeriodInfo readingPeriod,
        List<Meter> meters) {

    public record Premise(String street, String house, String apartment) {
    }

    public record Owner(@JsonProperty("full_name") String fullName) {
    }

    /**
     * The current reading period. {@code allSubmitted}: every meter has a reading in it; {@code enforced}: false
     * while the lock is switched off for testing.
     */
    public record ReadingPeriodInfo(
            LocalDate start,
            LocalDate end,
            @JsonProperty("next_start") LocalDate nextStart,
            @JsonProperty("all_submitted") boolean allSubmitted,
            boolean enforced) {
    }

    /**
     * A water meter; {@code waterType} is {@code HOT} or {@code COLD}, {@code verificationDate} is the end of its
     * verification period (may be null), {@code currentPeriodReading} is the reading taken in the current period
     * (null if none), {@code inputLocked} tells that a new one may not be entered now, readings are newest first.
     */
    public record Meter(
            String number,
            @JsonProperty("water_type") String waterType,
            @JsonProperty("verification_date") LocalDate verificationDate,
            @JsonProperty("current_period_reading") Reading currentPeriodReading,
            @JsonProperty("input_locked") boolean inputLocked,
            List<Reading> readings) {
    }

    public record Reading(
            BigDecimal value, @JsonProperty("reading_date") LocalDate readingDate, PreviousReading previous) {
    }

    public record PreviousReading(BigDecimal value, @JsonProperty("reading_date") LocalDate readingDate) {
    }
}
