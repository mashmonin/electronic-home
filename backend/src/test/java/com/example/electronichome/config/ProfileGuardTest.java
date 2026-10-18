package com.example.electronichome.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProfileGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProfileGuard.class);

    @Test
    void startupFailsWithoutProfile() {
        runner.run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("dev")
                .hasStackTraceContaining("prod"));
    }

    @Test
    void startupFailsWithUnsupportedProfile() {
        runner.withPropertyValues("spring.profiles.active=staging")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void startupSucceedsWithDevAndProd() {
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodHasNoDefaultDatabasePassword() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThatThrownBy(
                        () -> context.getEnvironment().getProperty("spring.datasource.password"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("DB_PASSWORD"));
    }

    @Test
    void devHasDefaultDatabasePassword() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(
                        context.getEnvironment().getProperty("spring.datasource.password")).isNotBlank());
    }
}
