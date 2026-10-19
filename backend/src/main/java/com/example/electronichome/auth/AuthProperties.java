package com.example.electronichome.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the access tokens ({@code auth.jwt.*}).
 *
 * @param secret the HS256 signing key; at least 32 bytes, no default in prod
 * @param ttl    how long a token is valid
 * @param issuer the {@code iss} claim of the tokens
 */
@ConfigurationProperties("auth.jwt")
public record AuthProperties(
        String secret, @DefaultValue("8h") Duration ttl, @DefaultValue("electronic-home") String issuer) {

    static final int MIN_SECRET_BYTES = 32;

    public AuthProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("auth.jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes long");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("auth.jwt.ttl must be positive");
        }
    }
}
