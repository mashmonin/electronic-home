package com.example.electronichome.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Bodies of the answers of the overview of the readings of all the apartments; no technical fields in them. */
final class ReadingsOverviewResponses {

    private ReadingsOverviewResponses() {
    }

    /** A month: the period of the readings named after the month it ends in. */
    record Period(String month, LocalDate start, LocalDate end) {
    }

    record Periods(List<Period> periods) {
    }

    record Reading(
            BigDecimal value,
            @JsonProperty("reading_date") LocalDate readingDate,
            @JsonProperty("previous_value") BigDecimal previousValue,
            BigDecimal consumption) {
    }

    /** {@code reading} is null while the meter has no reading in the month. */
    record Meter(String number, @JsonProperty("water_type") String waterType, Reading reading) {
    }

    record Apartment(
            @JsonProperty("account_number") String accountNumber,
            String apartment,
            String address,
            int submitted,
            int total,
            List<Meter> meters) {
    }

    record Month(String month, LocalDate start, LocalDate end, List<Apartment> apartments) {
    }
}
