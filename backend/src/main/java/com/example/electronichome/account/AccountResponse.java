package com.example.electronichome.account;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Account information as returned by {@code GET /api/accounts/{number}}. */
public record AccountResponse(String number, String address, Premise premise, List<Owner> owners) {

    public record Premise(String street, String house, String apartment) {
    }

    public record Owner(@JsonProperty("full_name") String fullName) {
    }
}
