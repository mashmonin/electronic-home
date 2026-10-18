package com.example.electronichome.account;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One problem found in a readings request; {@code meterNumber} is null for problems of the whole request. */
public record ReadingError(@JsonProperty("meter_number") String meterNumber, String code) {

    static final String EMPTY_REQUEST = "EMPTY_REQUEST";
    static final String METER_NOT_FOUND = "METER_NOT_FOUND";
    static final String DUPLICATE_METER = "DUPLICATE_METER";
}
