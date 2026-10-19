package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void removeExtraRows() {
        jdbc.update("DELETE FROM water_meters WHERE address_id IN (SELECT id FROM addresses WHERE apartment = '99')");
        jdbc.update("DELETE FROM account_owners WHERE account_id IN (SELECT id FROM accounts WHERE number = '000000000099')");
        jdbc.update("DELETE FROM accounts WHERE number = '000000000099'");
        jdbc.update("DELETE FROM addresses WHERE apartment = '99'");
    }

    private HttpResponse<String> get(String number, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/" + number));
        request.header("Authorization", AuthSupport.adminBearer(jdbc, port));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void returnsTestAccountWithAddressAndPremise() throws Exception {
        HttpResponse<String> response = get("000000000001");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("number").asString()).isEqualTo("000000000001");
        assertThat(body.get("address").asString()).isEqualTo("ул. Тестовая, д. Тест, кв. 1");
        assertThat(body.get("premise").get("street").asString()).isEqualTo("ул. Тестовая");
        assertThat(body.get("premise").get("house").asString()).isEqualTo("Тест");
        assertThat(body.get("premise").get("apartment").asString()).isEqualTo("1");
    }

    @Test
    void ownersAreSortedByFullName() throws Exception {
        JsonNode body = json.readTree(get("000000000001").body());

        List<String> names = new ArrayList<>();
        body.get("owners").forEach(owner -> names.add(owner.get("full_name").asString()));
        assertThat(names).containsExactly("Тестовая Теста Тестовна", "Тестовый Тест Тестович");
    }

    @Test
    void accountWithoutOwnersReturnsEmptyList() throws Exception {
        Long address = jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Тестовая', 'Тест', '99') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO accounts (number, address_id) VALUES ('000000000099', ?)", address);

        HttpResponse<String> response = get("000000000099");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("owners").isArray()).isTrue();
        assertThat(body.get("owners")).isEmpty();
    }

    private JsonNode meter(JsonNode body, String number) {
        for (JsonNode meter : body.get("meters")) {
            if (meter.get("number").asString().equals(number)) {
                return meter;
            }
        }
        throw new AssertionError("no meter " + number);
    }

    @Test
    void listsHotAndColdTestMetersSortedByNumber() throws Exception {
        JsonNode body = json.readTree(get("000000000001").body());

        List<String> described = new ArrayList<>();
        body.get("meters").forEach(m -> described.add(m.get("number").asString() + ":" + m.get("water_type").asString()));
        assertThat(described).containsExactly("Тест 1:HOT", "Тест 2:COLD");
    }

    @Test
    void readingsAreNewestFirstWithPreviousReading() throws Exception {
        JsonNode hot = meter(json.readTree(get("000000000001").body()), "Тест 1");

        JsonNode readings = hot.get("readings");
        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).get("value").decimalValue()).isEqualByComparingTo("245.300");
        assertThat(readings.get(0).get("reading_date").asString()).isEqualTo("2026-09-25");
        assertThat(readings.get(0).get("previous").get("value").decimalValue()).isEqualByComparingTo("230.000");
        assertThat(readings.get(0).get("previous").get("reading_date").asString()).isEqualTo("2026-08-25");
        assertThat(readings.get(1).get("value").decimalValue()).isEqualByComparingTo("230.000");
        assertThat(readings.get(1).get("previous").isNull()).isTrue();
    }

    @Test
    void coldMeterReadingsMatchTheSeed() throws Exception {
        JsonNode cold = meter(json.readTree(get("000000000001").body()), "Тест 2");

        assertThat(cold.get("readings").get(0).get("value").decimalValue()).isEqualByComparingTo("512.100");
        assertThat(cold.get("readings").get(0).get("previous").get("value").decimalValue()).isEqualByComparingTo("498.500");
    }

    @Test
    void metersCarryTheirVerificationDate() throws Exception {
        JsonNode body = json.readTree(get("000000000001").body());

        assertThat(meter(body, "Тест 1").get("verification_date").asString()).isEqualTo("2028-01-01");
        assertThat(meter(body, "Тест 2").get("verification_date").asString()).isEqualTo("2026-10-17");
        assertThat(get("000000000001").body()).doesNotContain("verificationDate");
    }

    @Test
    void meterWithoutVerificationDateReturnsNull() throws Exception {
        Long address = jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Тестовая', 'Тест', '99') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO accounts (number, address_id) VALUES ('000000000099', ?)", address);
        jdbc.update("INSERT INTO water_meters (address_id, water_type, number) VALUES (?, 'HOT', 'Без даты')", address);

        JsonNode body = json.readTree(get("000000000099").body());

        assertThat(meter(body, "Без даты").has("verification_date")).isTrue();
        assertThat(meter(body, "Без даты").get("verification_date").isNull()).isTrue();
    }

    @Test
    void apartmentWithoutMetersReturnsEmptyList() throws Exception {
        Long address = jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Тестовая', 'Тест', '99') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO accounts (number, address_id) VALUES ('000000000099', ?)", address);

        JsonNode body = json.readTree(get("000000000099").body());

        assertThat(body.get("meters").isArray()).isTrue();
        assertThat(body.get("meters")).isEmpty();
    }

    @Test
    void meterWithoutReadingsReturnsEmptyReadings() throws Exception {
        Long address = jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Тестовая', 'Тест', '99') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO accounts (number, address_id) VALUES ('000000000099', ?)", address);
        jdbc.update("INSERT INTO water_meters (address_id, water_type, number) VALUES (?, 'COLD', 'Пустой')", address);

        JsonNode body = json.readTree(get("000000000099").body());

        assertThat(meter(body, "Пустой").get("readings")).isEmpty();
    }

    @Test
    void metersUseSnakeCaseAndExposeNoInternalFields() throws Exception {
        String body = get("000000000001").body();

        assertThat(body).contains("\"water_type\"", "\"reading_date\"", "\"previous\"");
        assertThat(body).doesNotContain("waterType", "readingDate", "previousReading", "meter_id");
    }

    @Test
    void unknownAccountReturnsNotFound() throws Exception {
        assertThat(get("999999999999").statusCode()).isEqualTo(404);
    }

    @Test
    void responseHasNoInternalFieldsAndUsesSnakeCase() throws Exception {
        String body = get("000000000001").body();

        assertThat(body).doesNotContain("\"id\"").doesNotContain("is_test").doesNotContain("isTest");
        assertThat(body).contains("\"full_name\"").doesNotContain("fullName");
    }

    @Test
    void leadingZerosAreKept() throws Exception {
        assertThat(json.readTree(get("000000000001").body()).get("number").isString()).isTrue();
    }

    @Test
    void devCorsAllowsLocalFrontend() throws Exception {
        HttpResponse<String> response = get("000000000001", "Origin", "http://localhost:5173");

        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
    }
}
