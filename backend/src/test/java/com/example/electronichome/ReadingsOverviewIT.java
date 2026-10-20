package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

// The overview of the readings of all the apartments by months, for the administrator; the date of the server is
// 18 October 2026 (the current period is 26 September - 25 October, "2026-10"); the test data of dev gives apartment 1.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadingsOverviewIT {

    private static final String[] APARTMENTS = {"9", "92", "93", "100"};

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private String admin;
    private String owner;
    private String resident;

    @BeforeEach
    void data() throws Exception {
        for (String apartment : APARTMENTS) {
            jdbc.update("INSERT INTO addresses (street, house, apartment, is_test) VALUES ('ул. Тестовая', 'Тест', ?, TRUE)", apartment);
            jdbc.update("INSERT INTO accounts (number, address_id, is_test) SELECT ?, id, TRUE FROM addresses WHERE apartment = ? AND street = 'ул. Тестовая' AND house = 'Тест'",
                    "0000000000" + String.format("%02d", Math.min(Integer.parseInt(apartment), 99)), apartment);
        }
        // apartment 92: A has readings in August, September and (on the first day of the period) October; B has one in October
        long a = meter("92", "HOT", "M-A");
        long b = meter("92", "COLD", "M-B");
        long a1 = reading(a, "50.000", "2026-08-20", null);
        long a2 = reading(a, "60.000", "2026-09-25", a1);
        reading(a, "70.500", "2026-09-26", a2);
        reading(b, "5.000", "2026-10-10", null);
        // apartment 93 has a meter and no readings; apartment 9 a meter with a reading; apartment 100 has no meters
        meter("93", "HOT", "N-1");
        reading(meter("9", "HOT", "K-1"), "1.000", "2026-10-02", null);
        AuthSupport.createUser(jdbc, "it-owner-ov", "OWNER", true, "000000000001");
        AuthSupport.createUser(jdbc, "it-resident-ov", "RESIDENT", true, "000000000001");
        AuthSupport.createUser(jdbc, "it-admin-ov", "ADMIN", true);
        admin = AuthSupport.bearer(port, "it-admin-ov");
        owner = AuthSupport.bearer(port, "it-owner-ov");
        resident = AuthSupport.bearer(port, "it-resident-ov");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM meter_readings WHERE meter_id IN (SELECT m.id FROM water_meters m JOIN addresses a ON a.id = m.address_id WHERE a.apartment IN ('9', '92', '93', '100') AND a.house = 'Тест' AND a.street = 'ул. Тестовая')");
        jdbc.update("DELETE FROM water_meters WHERE address_id IN (SELECT id FROM addresses WHERE apartment IN ('9', '92', '93', '100') AND house = 'Тест' AND street = 'ул. Тестовая')");
        jdbc.update("DELETE FROM accounts WHERE address_id IN (SELECT id FROM addresses WHERE apartment IN ('9', '92', '93', '100') AND house = 'Тест' AND street = 'ул. Тестовая')");
        jdbc.update("DELETE FROM addresses WHERE apartment IN ('9', '92', '93', '100') AND house = 'Тест' AND street = 'ул. Тестовая'");
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
    }

    private long meter(String apartment, String type, String number) {
        return jdbc.queryForObject("""
                INSERT INTO water_meters (address_id, water_type, number) SELECT id, ?, ? FROM addresses
                WHERE apartment = ? AND house = 'Тест' AND street = 'ул. Тестовая' RETURNING id""", Long.class, type, number, apartment);
    }

    private long reading(long meter, String value, String date, Long previous) {
        return jdbc.queryForObject(
                "INSERT INTO meter_readings (meter_id, value, reading_date, previous_reading_id) VALUES (?, ?::numeric, ?::date, ?) RETURNING id",
                Long.class, meter, value, date, previous);
    }

    private HttpResponse<String> get(String bearer, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String path) throws Exception {
        HttpResponse<String> response = get(admin, path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    // the apartment of the test house "Тест" (the apartments of the house of the readings have the same numbers)
    private JsonNode apartment(JsonNode month, String apartment) {
        for (JsonNode item : month.get("apartments")) {
            if (item.get("apartment").asString().equals(apartment) && item.get("address").asString().contains("д. Тест,")) {
                return item;
            }
        }
        return null;
    }

    private JsonNode meterOf(JsonNode apartment, String number) {
        for (JsonNode meter : apartment.get("meters")) {
            if (meter.get("number").asString().equals(number)) {
                return meter;
            }
        }
        throw new AssertionError("no meter " + number);
    }

    // ---- the months

    @Test
    void theMonthsAreThePeriodsWithReadingsAndTheCurrentOneNewestFirst() throws Exception {
        JsonNode periods = ok("/api/readings/periods").get("periods");

        List<String> months = new ArrayList<>();
        periods.forEach(period -> months.add(period.get("month").asString()));
        // 25 August and 20 August are August, 25 September is September, 26 September and 2 October are October
        // the readings of 2025 and 2026 of the house come with the migrations: the months run back to January 2025
        assertThat(months).startsWith("2026-10", "2026-09", "2026-08").endsWith("2025-02", "2025-01");
        assertThat(months).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(periods.get(0).get("start").asString()).isEqualTo("2026-09-26");
        assertThat(periods.get(0).get("end").asString()).isEqualTo("2026-10-25");
        assertThat(periods.get(1).get("start").asString()).isEqualTo("2026-08-26");
        assertThat(periods.get(1).get("end").asString()).isEqualTo("2026-09-25");
    }

    @Test
    void theCurrentPeriodIsThereEvenWithoutReadings() throws Exception {
        // the readings come back after the test: the other tests of the class need the months of 2025 and 2026
        jdbc.execute("CREATE TABLE readings_backup AS SELECT * FROM meter_readings");
        try {
            jdbc.update("DELETE FROM meter_readings");

            JsonNode periods = ok("/api/readings/periods").get("periods");

            assertThat(periods).hasSize(1);
            assertThat(periods.get(0).get("month").asString()).isEqualTo("2026-10");
        } finally {
            jdbc.update("INSERT INTO meter_readings SELECT * FROM readings_backup");
            jdbc.execute("DROP TABLE readings_backup");
        }
    }

    // ---- the readings of a month

    @Test
    void anApartmentThatHandedInAllItsMetersHasTheValuesAndTheConsumption() throws Exception {
        JsonNode october = ok("/api/readings?month=2026-10");
        JsonNode flat = apartment(october, "92");

        assertThat(october.get("month").asString()).isEqualTo("2026-10");
        assertThat(october.get("start").asString()).isEqualTo("2026-09-26");
        assertThat(october.get("end").asString()).isEqualTo("2026-10-25");
        assertThat(flat.get("submitted").asInt()).isEqualTo(2);
        assertThat(flat.get("total").asInt()).isEqualTo(2);
        assertThat(flat.get("address").asString()).isEqualTo("ул. Тестовая, д. Тест, кв. 92");
        JsonNode a = meterOf(flat, "M-A").get("reading");
        assertThat(meterOf(flat, "M-A").get("water_type").asString()).isEqualTo("HOT");
        assertThat(a.get("value").decimalValue()).isEqualByComparingTo("70.500");
        assertThat(a.get("reading_date").asString()).isEqualTo("2026-09-26");
        assertThat(a.get("previous_value").decimalValue()).isEqualByComparingTo("60.000");
        assertThat(a.get("consumption").decimalValue()).isEqualByComparingTo("10.500");
    }

    @Test
    void theFirstReadingOfAMeterHasNoPreviousValueAndNoConsumption() throws Exception {
        JsonNode reading = meterOf(apartment(ok("/api/readings?month=2026-10"), "92"), "M-B").get("reading");

        assertThat(reading.get("value").decimalValue()).isEqualByComparingTo("5.000");
        assertThat(reading.get("previous_value").isNull()).isTrue();
        assertThat(reading.get("consumption").isNull()).isTrue();
    }

    @Test
    void anApartmentWithoutReadingsIsThereWithTheMetersMarkedAsNotHandedIn() throws Exception {
        JsonNode flat = apartment(ok("/api/readings?month=2026-10"), "93");

        assertThat(flat.get("submitted").asInt()).isZero();
        assertThat(flat.get("total").asInt()).isEqualTo(1);
        assertThat(meterOf(flat, "N-1").get("reading").isNull()).isTrue();
    }

    @Test
    void theApartmentsOfTheHouseOfTheReadingsAreThereWithoutPersonalAccounts() throws Exception {
        JsonNode october = ok("/api/readings?month=2026-10");

        int houseApartments = 0;
        for (JsonNode item : october.get("apartments")) {
            if (item.get("address").asString().contains("д. Дом,")) {
                houseApartments++;
                assertThat(item.get("account_number").isNull()).isTrue();
            }
        }
        // the apartments of the house that have meters (some apartments of the sheet have none with readings of 2025 and 2026)
        int withMeters = jdbc.queryForObject("""
                SELECT count(DISTINCT a.id) FROM addresses a JOIN water_meters m ON m.address_id = a.id
                WHERE a.house = 'Дом' AND NOT a.is_test""", Integer.class);
        assertThat(withMeters).isGreaterThan(100);
        assertThat(houseApartments).isEqualTo(withMeters);
        JsonNode first = null;
        for (JsonNode item : october.get("apartments")) {
            if (item.get("address").asString().equals("ул. Тестовая, д. Дом, кв. 1")) {
                first = item;
            }
        }
        assertThat(first).isNotNull();
        assertThat(first.get("total").asInt()).isEqualTo(4);
        // not every meter of the house has a reading in October, but the house as a whole has
        int submitted = 0;
        for (JsonNode item : october.get("apartments")) {
            if (item.get("address").asString().contains("д. Дом,")) {
                submitted += item.get("submitted").asInt();
            }
        }
        assertThat(submitted).isPositive();
    }

    @Test
    void anApartmentWithoutMetersIsNotThere() throws Exception {
        assertThat(apartment(ok("/api/readings?month=2026-10"), "100")).isNull();
    }

    @Test
    void oneMeterOutOfTwoCountsAsOneHandedIn() throws Exception {
        // in September apartment 92 has a reading of A only
        JsonNode flat = apartment(ok("/api/readings?month=2026-09"), "92");

        assertThat(flat.get("submitted").asInt()).isEqualTo(1);
        assertThat(flat.get("total").asInt()).isEqualTo(2);
        assertThat(meterOf(flat, "M-A").get("reading").get("value").decimalValue()).isEqualByComparingTo("60.000");
        assertThat(meterOf(flat, "M-B").get("reading").isNull()).isTrue();
    }

    @Test
    void theTestDataOfApartmentOneHasTheSeptemberReadingWithTheConsumption() throws Exception {
        JsonNode reading = meterOf(apartment(ok("/api/readings?month=2026-09"), "1"), "Тест 1").get("reading");

        assertThat(reading.get("value").decimalValue()).isEqualByComparingTo("245.300");
        assertThat(reading.get("reading_date").asString()).isEqualTo("2026-09-25");
        assertThat(reading.get("consumption").decimalValue()).isEqualByComparingTo("15.300");
    }

    @Test
    void theApartmentsGoByTheNumberAndTheMetersByTheirNumber() throws Exception {
        JsonNode october = ok("/api/readings?month=2026-10");

        List<String> numbers = new ArrayList<>();
        october.get("apartments").forEach(item -> numbers.add(item.get("apartment").asString()));
        assertThat(numbers).containsSubsequence("1", "9", "92", "93");
        assertThat(meterOf(apartment(october, "92"), "M-A")).isNotNull();
        assertThat(apartment(october, "92").get("meters").get(0).get("number").asString()).isEqualTo("M-A");
    }

    @Test
    void aPastMonthWithoutReadingsListsTheApartmentsAsNotHandedIn() throws Exception {
        JsonNode may = ok("/api/readings?month=2026-05");

        assertThat(apartment(may, "92").get("submitted").asInt()).isZero();
        assertThat(apartment(may, "93").get("submitted").asInt()).isZero();
    }

    @Test
    void theAnswerHasNoTechnicalFields() throws Exception {
        String body = get(admin, "/api/readings?month=2026-10").body();

        assertThat(body).doesNotContain("is_test").doesNotContain("\"id\"").doesNotContain("previous_reading_id");
    }

    @Test
    void aWrongMonthIsABadRequest() throws Exception {
        for (String month : new String[] {"", "2026-13", "2026-00", "октябрь", "2026-1", "2026-11", "2027-01"}) {
            assertThat(get(admin, "/api/readings?month=" + URLEncoder.encode(month, StandardCharsets.UTF_8)).statusCode()).as(month).isEqualTo(400);
        }
        assertThat(get(admin, "/api/readings").statusCode()).isEqualTo(400);
    }

    // ---- the rights

    @Test
    void onlyAnAdministratorMayAsk() throws Exception {
        for (String path : new String[] {"/api/readings/periods", "/api/readings?month=2026-10"}) {
            assertThat(get(owner, path).statusCode()).as(path).isEqualTo(403);
            assertThat(get(resident, path).statusCode()).as(path).isEqualTo(403);
            assertThat(get(null, path).statusCode()).as(path).isEqualTo(401);
            assertThat(get(owner, path).body()).doesNotContain("apartments").doesNotContain("periods");
        }
    }
}
