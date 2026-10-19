package com.example.electronichome.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Properties.class);

    @EnableConfigurationProperties(AuthProperties.class)
    static class Properties {
    }

    @Test
    void prodNeedsASecret() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("JWT_SECRET"));
    }

    @Test
    void prodRejectsAShortSecret() {
        runner.withPropertyValues("spring.profiles.active=prod", "auth.jwt.secret=too-short")
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("at least 32 bytes"));
    }

    @Test
    void prodAcceptsALongSecret() {
        runner.withPropertyValues("spring.profiles.active=prod", "auth.jwt.secret=" + "x".repeat(32))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devHasADefaultSecretAndDefaults() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            AuthProperties properties = context.getBean(AuthProperties.class);
            assertThat(properties.secret()).isNotBlank();
            assertThat(properties.ttl()).isEqualTo(Duration.ofHours(8));
            assertThat(properties.issuer()).isEqualTo("electronic-home");
        });
    }

    @Test
    void ttlMustBePositive() {
        assertThatThrownBy(() -> new AuthProperties("x".repeat(32), Duration.ZERO, "i"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
