package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "readings.period.enforced=false")
class ReadingsApiIT {

    private static final String TODAY = "2026-10-18";
    private static final String ACCOUNT = "000000000001";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse(TODAY + "T09:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void removeCreatedRows() {
        jdbc.update("DELETE FROM meter_readings WHERE reading_date = DATE '" + TODAY + "'");
        jdbc.update("DELETE FROM water_meters WHERE number LIKE 'Новый%'");
    }

    private HttpResponse<String> post(String account, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/" + account + "/readings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String account) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/" + account)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String reading(String meter, String value) {
        return "{\"meter_number\":\"" + meter + "\",\"value\":" + value + "}";
    }

    private static String request(String... readings) {
        return "{\"readings\":[" + String.join(",", readings) + "]}";
    }

    private void addMeter(String number, String type, boolean test) {
        jdbc.update("""
                INSERT INTO water_meters (address_id, water_type, number, is_test)
                SELECT id, ?, ?, ? FROM addresses WHERE street = 'ул. Тестовая' AND house = 'Тест' AND apartment = '1'
                """, type, number, test);
    }

    private int savedToday() {
        return jdbc.queryForObject("SELECT count(*) FROM meter_readings WHERE reading_date = DATE '" + TODAY + "'", Integer.class);
    }

    private JsonNode errors(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(400);
        return json.readTree(response.body()).get("errors");
    }

    private void assertSingleError(HttpResponse<String> response, String meter, String code) throws Exception {
        JsonNode errors = errors(response);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("code").asString()).isEqualTo(code);
        if (meter == null) {
            assertThat(errors.get(0).get("meter_number").isNull()).isTrue();
        } else {
            assertThat(errors.get(0).get("meter_number").asString()).isEqualTo(meter);
        }
        assertThat(savedToday()).isZero();
    }

    @Test
    void savesOneReadingWithServerDateAndPrevious() throws Exception {
        HttpResponse<String> response = post(ACCOUNT, request(reading("Тест 1", "250.125")));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode saved = json.readTree(response.body()).get("readings").get(0);
        assertThat(saved.get("meter_number").asString()).isEqualTo("Тест 1");
        assertThat(saved.get("water_type").asString()).isEqualTo("HOT");
        assertThat(saved.get("value").decimalValue()).isEqualByComparingTo("250.125");
        assertThat(saved.get("reading_date").asString()).isEqualTo(TODAY);
        assertThat(saved.get("previous").get("value").decimalValue()).isEqualByComparingTo("245.300");
        assertThat(saved.get("previous").get("reading_date").asString()).isEqualTo("2026-09-25");
        assertThat(response.body()).doesNotContain("\"id\"").doesNotContain("is_test");
    }

    @Test
    void savesSeveralReadingsInOneRequestInRequestOrder() throws Exception {
        HttpResponse<String> response = post(ACCOUNT, request(reading("Тест 2", "520"), reading("Тест 1", "250")));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode saved = json.readTree(response.body()).get("readings");
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).get("meter_number").asString()).isEqualTo("Тест 2");
        assertThat(saved.get(0).get("water_type").asString()).isEqualTo("COLD");
        assertThat(saved.get(0).get("previous").get("value").decimalValue()).isEqualByComparingTo("512.100");
        assertThat(saved.get(1).get("meter_number").asString()).isEqualTo("Тест 1");
        assertThat(savedToday()).isEqualTo(2);
    }

    @Test
    void ignoresTheDateSentByTheClient() throws Exception {
        String item = "{\"meter_number\":\"Тест 1\",\"value\":250,\"reading_date\":\"2020-01-01\"}";
        HttpResponse<String> response = post(ACCOUNT, "{\"reading_date\":\"2020-01-01\",\"readings\":[" + item + "]}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).get("readings").get(0).get("reading_date").asString()).isEqualTo(TODAY);
    }

    @Test
    void newReadingBecomesTheLatestOneInTheAccountInfo() throws Exception {
        post(ACCOUNT, request(reading("Тест 1", "250.125")));

        JsonNode hot = null;
        for (JsonNode meter : json.readTree(get(ACCOUNT).body()).get("meters")) {
            if (meter.get("number").asString().equals("Тест 1")) {
                hot = meter;
            }
        }
        assertThat(hot).isNotNull();
        assertThat(hot.get("readings")).hasSize(3);
        assertThat(hot.get("readings").get(0).get("value").decimalValue()).isEqualByComparingTo("250.125");
        assertThat(hot.get("readings").get(0).get("previous").get("value").decimalValue()).isEqualByComparingTo("245.300");
    }

    @Test
    void firstReadingOfAMeterHasNoPrevious() throws Exception {
        addMeter("Новый 1", "HOT", true);

        HttpResponse<String> response = post(ACCOUNT, request(reading("Новый 1", "5")));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).get("readings").get(0).get("previous").isNull()).isTrue();
    }

    @Test
    void newReadingInheritsTheTestFlagOfItsMeter() throws Exception {
        addMeter("Новый 1", "HOT", true);
        addMeter("Новый 2", "COLD", false);

        assertThat(post(ACCOUNT, request(reading("Новый 1", "1"), reading("Новый 2", "2"))).statusCode()).isEqualTo(201);

        assertThat(jdbc.queryForObject("""
                SELECT r.is_test FROM meter_readings r JOIN water_meters m ON m.id = r.meter_id
                WHERE m.number = 'Новый 1'""", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT r.is_test FROM meter_readings r JOIN water_meters m ON m.id = r.meter_id
                WHERE m.number = 'Новый 2'""", Boolean.class)).isFalse();
    }

    @Test
    void acceptsAValueEqualToThePrevious() throws Exception {
        assertThat(post(ACCOUNT, request(reading("Тест 1", "245.300"))).statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsAValueLowerThanThePrevious() throws Exception {
        assertSingleError(post(ACCOUNT, request(reading("Тест 1", "245.299"))), "Тест 1", "VALUE_LESS_THAN_PREVIOUS");
    }

    @Test
    void rejectsInvalidValues() throws Exception {
        for (String value : List.of("-1", "1.2345", "null", "10000000000")) {
            assertSingleError(post(ACCOUNT, request(reading("Тест 1", value))), "Тест 1", "INVALID_VALUE");
        }
        assertSingleError(post(ACCOUNT, request("{\"meter_number\":\"Тест 1\"}")), "Тест 1", "INVALID_VALUE");
    }

    @Test
    void rejectsAnEmptyRequest() throws Exception {
        assertSingleError(post(ACCOUNT, "{\"readings\":[]}"), null, "EMPTY_REQUEST");
        assertSingleError(post(ACCOUNT, "{}"), null, "EMPTY_REQUEST");
    }

    @Test
    void rejectsAMeterThatTheAccountDoesNotHave() throws Exception {
        assertSingleError(post(ACCOUNT, request(reading("Нет такого", "1"))), "Нет такого", "METER_NOT_FOUND");
        assertSingleError(post(ACCOUNT, request("{\"value\":1}")), null, "METER_NOT_FOUND");
    }

    @Test
    void rejectsTheSameMeterTwice() throws Exception {
        assertSingleError(post(ACCOUNT, request(reading("Тест 1", "250"), reading("Тест 1", "260"))), "Тест 1", "DUPLICATE_METER");
    }

    @Test
    void reportsAllErrorsAtOnce() throws Exception {
        JsonNode errors = errors(post(ACCOUNT, request(reading("Тест 1", "100"), reading("Нет такого", "1"))));

        List<String> described = new ArrayList<>();
        errors.forEach(e -> described.add(e.get("meter_number").asString() + ":" + e.get("code").asString()));
        assertThat(described).containsExactly("Тест 1:VALUE_LESS_THAN_PREVIOUS", "Нет такого:METER_NOT_FOUND");
    }

    @Test
    void savesNothingWhenOneReadingOfTheRequestIsInvalid() throws Exception {
        assertSingleError(post(ACCOUNT, request(reading("Тест 1", "250"), reading("Тест 2", "100"))), "Тест 2", "VALUE_LESS_THAN_PREVIOUS");
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        assertThat(post("999999999999", request(reading("Тест 1", "250"))).statusCode()).isEqualTo(404);
    }

    @Test
    void concurrentRequestsForOneMeterQueueUpAndKeepTheChainLinear() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return post(ACCOUNT, request(reading("Тест 2", "600"))).statusCode();
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get()).isEqualTo(201);
        }
        pool.shutdown();

        String forMeter = "FROM meter_readings r JOIN water_meters m ON m.id = r.meter_id WHERE m.number = 'Тест 2'";
        assertThat(jdbc.queryForObject("SELECT count(*) " + forMeter, Integer.class)).isEqualTo(2 + threads);
        assertThat(jdbc.queryForObject("SELECT count(*) " + forMeter + " AND r.previous_reading_id IS NULL", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT r.previous_reading_id) " + forMeter, Integer.class))
                .isEqualTo(1 + threads);
    }
}
