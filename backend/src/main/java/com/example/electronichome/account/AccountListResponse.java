package com.example.electronichome.account;

import java.util.List;

/** The answer of {@code GET /api/accounts}: every personal account with the address of its apartment. */
public record AccountListResponse(List<Item> accounts) {

    public record Item(String number, String address, String apartment) {
    }
}
