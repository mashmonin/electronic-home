package com.example.electronichome.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Readings saved by {@code POST /api/accounts/{number}/readings}, in the order of the request. */
public record ReadingsResponse(List<Saved> readings) {

    public record Saved(
            @JsonProperty("meter_number") String meterNumber,
            @JsonProperty("water_type") String waterType,
            BigDecimal value,
            @JsonProperty("reading_date") LocalDate readingDate,
            Previous previous) {
    }

    public record Previous(BigDecimal value, @JsonProperty("reading_date") LocalDate readingDate) {
    }
}
