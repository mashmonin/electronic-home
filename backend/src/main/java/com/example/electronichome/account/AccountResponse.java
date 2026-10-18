package com.example.electronichome.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Account information as returned by {@code GET /api/accounts/{number}}. */
public record AccountResponse(
        String number, String address, Premise premise, List<Owner> owners, List<Meter> meters) {

    public record Premise(String street, String house, String apartment) {
    }

    public record Owner(@JsonProperty("full_name") String fullName) {
    }

    /** A water meter; {@code waterType} is {@code HOT} or {@code COLD}, readings are newest first. */
    public record Meter(String number, @JsonProperty("water_type") String waterType, List<Reading> readings) {
    }

    public record Reading(
            BigDecimal value, @JsonProperty("reading_date") LocalDate readingDate, PreviousReading previous) {
    }

    public record PreviousReading(BigDecimal value, @JsonProperty("reading_date") LocalDate readingDate) {
    }
}
