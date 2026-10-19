package com.example.electronichome;

import static com.example.electronichome.PeriodTestSupport.ACCOUNT;
import static com.example.electronichome.PeriodTestSupport.get;
import static com.example.electronichome.PeriodTestSupport.post;
import static com.example.electronichome.PeriodTestSupport.reading;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

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

// The reading period with the lock switched on (readings.period.enforced=true), as in production.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "readings.period.enforced=true")
class ReadingPeriodApiIT {

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

    @BeforeEach
    void adminUser() throws Exception {
        AuthSupport.adminBearer(jdbc, port);
    }

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void today() {
        clock.set("2026-10-18");
    }

    @AfterEach
    void removeCreatedReadings() {
        // the seeded readings are older than the start of the period of the tests
        jdbc.update("DELETE FROM meter_readings WHERE reading_date >= DATE '2026-09-26'");
        jdbc.update("DELETE FROM water_meters WHERE number LIKE 'Новый%'");
        jdbc.update("DELETE FROM accounts WHERE number = '000000000099'");
        jdbc.update("DELETE FROM addresses WHERE apartment = '99'");
    }

    private JsonNode account() throws Exception {
        return json.readTree(get(port, ACCOUNT).body());
    }

    private JsonNode meter(JsonNode account, String number) {
        for (JsonNode meter : account.get("meters")) {
            if (meter.get("number").asString().equals(number)) {
                return meter;
            }
        }
        throw new AssertionError("no meter " + number);
    }

    private int readingsSince(String date) {
        return jdbc.queryForObject("SELECT count(*) FROM meter_readings WHERE reading_date >= DATE '" + date + "'", Integer.class);
    }

    private JsonNode errorsOf(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        return json.readTree(response.body()).get("errors");
    }

    // ---- reading side

    @Test
    void nothingSubmittedYet() throws Exception {
        JsonNode account = account();
        JsonNode period = account.get("reading_period");

        assertThat(period.get("start").asString()).isEqualTo("2026-09-26");
        assertThat(period.get("end").asString()).isEqualTo("2026-10-25");
        assertThat(period.get("next_start").asString()).isEqualTo("2026-10-26");
        assertThat(period.get("all_submitted").asBoolean()).isFalse();
        assertThat(period.get("enforced").asBoolean()).isTrue();
        for (String name : List.of("Тест 1", "Тест 2")) {
            assertThat(meter(account, name).get("current_period_reading").isNull()).isTrue();
            assertThat(meter(account, name).get("input_locked").asBoolean()).isFalse();
        }
    }

    @Test
    void everythingSubmitted() throws Exception {
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250.125"), reading("Тест 2", "520")).statusCode()).isEqualTo(201);

        JsonNode account = account();

        assertThat(account.get("reading_period").get("all_submitted").asBoolean()).isTrue();
        JsonNode hot = meter(account, "Тест 1");
        assertThat(hot.get("input_locked").asBoolean()).isTrue();
        assertThat(hot.get("current_period_reading").get("value").decimalValue()).isEqualByComparingTo("250.125");
        assertThat(hot.get("current_period_reading").get("reading_date").asString()).isEqualTo("2026-10-18");
        assertThat(hot.get("current_period_reading").get("previous").get("value").decimalValue()).isEqualByComparingTo("245.300");
        assertThat(meter(account, "Тест 2").get("input_locked").asBoolean()).isTrue();
    }

    @Test
    void onlyOneMeterSubmitted() throws Exception {
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250")).statusCode()).isEqualTo(201);

        JsonNode account = account();

        assertThat(account.get("reading_period").get("all_submitted").asBoolean()).isFalse();
        assertThat(meter(account, "Тест 1").get("input_locked").asBoolean()).isTrue();
        assertThat(meter(account, "Тест 2").get("input_locked").asBoolean()).isFalse();
        assertThat(meter(account, "Тест 2").get("current_period_reading").isNull()).isTrue();
    }

    @Test
    void theLockHoldsUntilTheClosingDayAndEndsTheDayAfter() throws Exception {
        post(port, ACCOUNT, reading("Тест 1", "250"), reading("Тест 2", "520"));

        clock.set("2026-10-25");
        assertThat(meter(account(), "Тест 1").get("input_locked").asBoolean()).isTrue();

        clock.set("2026-10-26");
        JsonNode account = account();
        assertThat(account.get("reading_period").get("start").asString()).isEqualTo("2026-10-26");
        assertThat(account.get("reading_period").get("end").asString()).isEqualTo("2026-11-25");
        assertThat(account.get("reading_period").get("all_submitted").asBoolean()).isFalse();
        JsonNode hot = meter(account, "Тест 1");
        assertThat(hot.get("input_locked").asBoolean()).isFalse();
        assertThat(hot.get("current_period_reading").isNull()).isTrue();
        // the reading of the finished period is now the latest one
        assertThat(hot.get("readings").get(0).get("value").decimalValue()).isEqualByComparingTo("250");
    }

    @Test
    void accountWithoutMeters() throws Exception {
        Long address = jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Тестовая', 'Тест', '99') RETURNING id", Long.class);
        jdbc.update("INSERT INTO accounts (number, address_id) VALUES ('000000000099', ?)", address);

        JsonNode account = json.readTree(get(port, "000000000099").body());

        assertThat(account.get("meters")).isEmpty();
        assertThat(account.get("reading_period").get("all_submitted").asBoolean()).isFalse();
        assertThat(account.get("reading_period").get("start").asString()).isEqualTo("2026-09-26");
    }

    @Test
    void periodFieldsUseSnakeCaseAndNoInternalNames() throws Exception {
        String body = get(port, ACCOUNT).body();

        assertThat(body).contains("\"reading_period\"", "\"next_start\"", "\"all_submitted\"", "\"current_period_reading\"", "\"input_locked\"");
        assertThat(body).doesNotContain("readingPeriod", "nextStart", "allSubmitted", "inputLocked", "currentPeriodReading");
    }

    // ---- writing side

    @Test
    void secondSubmissionInTheSamePeriodIsRejected() throws Exception {
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250")).statusCode()).isEqualTo(201);

        HttpResponse<String> response = post(port, ACCOUNT, reading("Тест 1", "260"));

        JsonNode errors = errorsOf(response);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("meter_number").asString()).isEqualTo("Тест 1");
        assertThat(errors.get(0).get("code").asString()).isEqualTo("ALREADY_SUBMITTED");
        assertThat(readingsSince("2026-09-26")).isEqualTo(1);
    }

    @Test
    void submissionOnTheClosingDayIsStillTheSamePeriod() throws Exception {
        clock.set("2026-10-20");
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250")).statusCode()).isEqualTo(201);
        clock.set("2026-10-25");

        assertThat(errorsOf(post(port, ACCOUNT, reading("Тест 1", "260"))).get(0).get("code").asString()).isEqualTo("ALREADY_SUBMITTED");
    }

    @Test
    void submissionInTheNextPeriodIsAccepted() throws Exception {
        clock.set("2026-10-25");
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250")).statusCode()).isEqualTo(201);

        clock.set("2026-10-26");
        HttpResponse<String> response = post(port, ACCOUNT, reading("Тест 1", "260"));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode saved = json.readTree(response.body()).get("readings").get(0);
        assertThat(saved.get("reading_date").asString()).isEqualTo("2026-10-26");
        assertThat(saved.get("previous").get("value").decimalValue()).isEqualByComparingTo("250");
    }

    @Test
    void readingOfThePreviousPeriodDoesNotBlock() throws Exception {
        // the seeded readings are dated 25 September, the last day of the period "September"
        assertThat(post(port, ACCOUNT, reading("Тест 1", "250")).statusCode()).isEqualTo(201);
    }

    @Test
    void nothingIsSavedWhenOneOfTheMetersIsAlreadySubmitted() throws Exception {
        post(port, ACCOUNT, reading("Тест 1", "250"));

        JsonNode errors = errorsOf(post(port, ACCOUNT, reading("Тест 1", "260"), reading("Тест 2", "520")));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("meter_number").asString()).isEqualTo("Тест 1");
        assertThat(readingsSince("2026-09-26")).isEqualTo(1);
    }

    @Test
    void allErrorsAreReportedTogether() throws Exception {
        post(port, ACCOUNT, reading("Тест 1", "250"));

        JsonNode errors = errorsOf(post(port, ACCOUNT, reading("Тест 1", "260"), reading("Нет такого", "1")));

        List<String> described = new ArrayList<>();
        errors.forEach(e -> described.add(e.get("meter_number").asString() + ":" + e.get("code").asString()));
        assertThat(described).containsExactly("Тест 1:ALREADY_SUBMITTED", "Нет такого:METER_NOT_FOUND");
    }

    @Test
    void theRemainingMeterCanStillBeSubmitted() throws Exception {
        post(port, ACCOUNT, reading("Тест 1", "250"));

        assertThat(post(port, ACCOUNT, reading("Тест 2", "520")).statusCode()).isEqualTo(201);
        assertThat(account().get("reading_period").get("all_submitted").asBoolean()).isTrue();
    }
}
