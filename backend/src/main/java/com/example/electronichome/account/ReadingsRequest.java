package com.example.electronichome.account;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST /api/accounts/{number}/readings}; the reading date is always set by the server. */
public record ReadingsRequest(List<Item> readings) {

    public record Item(@JsonProperty("meter_number") String meterNumber, BigDecimal value) {
    }
}
