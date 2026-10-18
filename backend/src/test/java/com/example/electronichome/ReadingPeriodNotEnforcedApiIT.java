package com.example.electronichome;

import static com.example.electronichome.PeriodTestSupport.ACCOUNT;
import static com.example.electronichome.PeriodTestSupport.get;
import static com.example.electronichome.PeriodTestSupport.post;
import static com.example.electronichome.PeriodTestSupport.reading;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.PeriodTestSupport.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The reading period with the lock switched off for testing (readings.period.enforced=false).
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "readings.period.enforced=false")
class ReadingPeriodNotEnforcedApiIT {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void today() {
        clock.set("2026-10-18");
    }

    @AfterEach
    void removeCreatedReadings() {
        jdbc.update("DELETE FROM meter_readings WHERE reading_date >= DATE '2026-09-26'");
    }

    @Test
    void submittedMetersAreReportedButNotLocked() throws Exception {
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250"), reading("Тест 2", "520")).statusCode()).isEqualTo(201);

        JsonNode account = json.readTree(get(port, ACCOUNT).body());

        assertThat(account.get("reading_period").get("enforced").asBoolean()).isFalse();
        assertThat(account.get("reading_period").get("all_submitted").asBoolean()).isTrue();
        for (JsonNode meter : account.get("meters")) {
            assertThat(meter.get("current_period_reading").isNull()).isFalse();
            assertThat(meter.get("input_locked").asBoolean()).isFalse();
        }
    }

    @Test
    void aSecondSubmissionInTheSamePeriodIsAccepted() throws Exception {
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250")).statusCode()).isEqualTo(201);

        assertThat(post(port, ACCOUNT, reading("Тест 1", "260")).statusCode()).isEqualTo(201);
    }

    @Test
    void theOtherRulesStillApply() throws Exception {
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250")).statusCode()).isEqualTo(201);

        // lower than the previous one
        assertThat(post(port, ACCOUNT, reading("Тест 1", "249")).statusCode()).isEqualTo(400);
    }
}
