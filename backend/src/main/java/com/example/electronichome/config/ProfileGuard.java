package com.example.electronichome.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails fast unless exactly one of the supported profiles ({@code dev} or {@code prod}) is active.
 */
@Configuration(proxyBeanMethods = false)
public class ProfileGuard {

    static final List<String> SUPPORTED = List.of("dev", "prod");

    public ProfileGuard(Environment environment) {
        List<String> active = Arrays.stream(environment.getActiveProfiles())
                .filter(SUPPORTED::contains)
                .toList();
        if (active.size() != 1) {
            throw new IllegalStateException(
                    "Exactly one Spring profile must be active: " + SUPPORTED
                            + " (set SPRING_PROFILES_ACTIVE). Active: "
                            + Arrays.toString(environment.getActiveProfiles()));
        }
    }
}
