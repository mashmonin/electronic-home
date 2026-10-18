package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MeterTestDataIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void devSeedsHotAndColdMetersOnTheTestApartment() {
        List<Map<String, Object>> meters = jdbc.queryForList("""
                SELECT m.number, m.water_type, a.street, a.house, a.apartment
                FROM water_meters m JOIN addresses a ON a.id = m.address_id
                ORDER BY m.number
                """);

        assertThat(meters).hasSize(2);
        assertThat(meters.get(0)).containsEntry("number", "Тест 1").containsEntry("water_type", "HOT")
                .containsEntry("street", "ул. Тестовая").containsEntry("house", "Тест").containsEntry("apartment", "1");
        assertThat(meters.get(1)).containsEntry("number", "Тест 2").containsEntry("water_type", "COLD")
                .containsEntry("apartment", "1");
    }

    @Test
    void hotMeterHasChainedReadings() {
        assertThat(readings("Тест 1")).containsExactly("230.000|2026-08-25|-", "245.300|2026-09-25|230.000");
    }

    @Test
    void coldMeterHasChainedReadings() {
        assertThat(readings("Тест 2")).containsExactly("498.500|2026-08-25|-", "512.100|2026-09-25|498.500");
    }

    @Test
    void everySeededRowIsMarkedAsTest() {
        for (String table : List.of("water_meters", "meter_readings")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE NOT is_test", Integer.class))
                    .as(table).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .as(table).isPositive();
        }
    }

    private List<String> readings(String meterNumber) {
        return jdbc.queryForList("""
                SELECT r.value::text || '|' || r.reading_date::text || '|' || COALESCE(p.value::text, '-')
                FROM meter_readings r
                JOIN water_meters m ON m.id = r.meter_id
                LEFT JOIN meter_readings p ON p.id = r.previous_reading_id
                WHERE m.number = ?
                ORDER BY r.reading_date
                """, String.class, meterNumber);
    }
}
