package com.example.electronichome.auth;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Bodies of the answers of {@code /api/auth}. */
final class AuthResponses {

    private AuthResponses() {
    }

    record Token(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn) {
    }

    record Me(String login, Role role, List<String> accounts) {
    }
}
