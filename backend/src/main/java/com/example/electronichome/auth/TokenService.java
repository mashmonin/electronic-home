package com.example.electronichome.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues the access tokens: signed JWTs with the standard claims and the role in {@code roles}. */
@Service
public class TokenService {

    /** A token and the number of seconds it is valid. */
    public record Issued(String value, long expiresInSeconds) {
    }

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    TokenService(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public Issued issue(long userId, Role role) {
        // the real time, not the application clock: the decoder validates against the real time too
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .claim("roles", List.of(role.name()))
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Issued(value, properties.ttl().toSeconds());
    }
}
