package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Runs the dev profile with the Liquibase context of prod, to check that no test meters are created there.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.liquibase.contexts=prod")
class MeterProdDataIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void prodContextCreatesNoTestMetersOrReadings() {
        for (String table : List.of("water_meters", "meter_readings")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE is_test", Integer.class)).as(table).isZero();
        }
    }

    @Test
    void prodContextHasTheMetersAndReadingsOf2025And2026() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM water_meters", Integer.class)).isEqualTo(418);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM water_meters WHERE water_type = 'COLD'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM water_meters WHERE water_type = 'HOT'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meter_readings", Integer.class)).isEqualTo(6594);
        assertThat(jdbc.queryForObject("SELECT min(reading_date)::text || ' ' || max(reading_date)::text FROM meter_readings", String.class))
                .isEqualTo("2025-01-20 2026-10-20");
        // every meter has readings, one of them without a previous one, and all the others point to an earlier reading of the same meter
        assertThat(jdbc.queryForObject("SELECT count(*) FROM water_meters m WHERE NOT EXISTS (SELECT 1 FROM meter_readings r WHERE r.meter_id = m.id)",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meter_readings WHERE previous_reading_id IS NULL", Integer.class)).isEqualTo(418);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM meter_readings r JOIN meter_readings p ON p.id = r.previous_reading_id
                WHERE p.reading_date >= r.reading_date""", Integer.class)).isZero();
    }

    @Test
    void everyMeterOfTheHouseHasTheDateOfItsVerification() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM water_meters WHERE verification_date IS NULL", Integer.class)).isZero();
        // "25.10.2028г" in the sheet for the cold meter 10-09-04514 of the apartment 1
        assertThat(jdbc.queryForObject("""
                SELECT m.verification_date::text FROM water_meters m JOIN addresses a ON a.id = m.address_id
                WHERE a.apartment = '1' AND m.number = '10-09-04514'""", String.class)).isEqualTo("2028-10-25");
    }

    @Test
    void aReadingWithTheConsumptionInTheCellIsTheNumberBeforeTheSeparator() {
        // the sheet has "285,5\\1" for the hot meter 250477976 of the apartment 1 in November 2025
        assertThat(jdbc.queryForObject("""
                SELECT r.value::text FROM meter_readings r JOIN water_meters m ON m.id = r.meter_id JOIN addresses a ON a.id = m.address_id
                WHERE a.apartment = '1' AND m.number = '250477976' AND r.reading_date = DATE '2025-11-20'""", String.class)).isEqualTo("285.500");
    }
}
