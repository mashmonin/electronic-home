package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MeterSchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE meter_readings, water_meters, account_owners, address_owners, accounts, owners, addresses "
                + "RESTART IDENTITY CASCADE");
    }

    private long address(String apartment) {
        return jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Садовая', '12', ?) RETURNING id",
                Long.class, apartment);
    }

    private long meter(Long addressId, String type, String number) {
        return jdbc.queryForObject(
                "INSERT INTO water_meters (address_id, water_type, number) VALUES (?, ?, ?) RETURNING id",
                Long.class, addressId, type, number);
    }

    private long reading(Long meterId, String value, String date, Long previousId) {
        return jdbc.queryForObject(
                "INSERT INTO meter_readings (meter_id, value, reading_date, previous_reading_id) "
                        + "VALUES (?, ?::numeric, ?::date, ?) RETURNING id",
                Long.class, meterId, value, date, previousId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void migrationCreatesMeterTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains("water_meters", "meter_readings");
    }

    @Test
    void apartmentCanHaveSeveralMeters() {
        long apartment = address("47");
        meter(apartment, "HOT", "A-1");
        meter(apartment, "COLD", "A-2");
        meter(apartment, "COLD", "A-3");

        assertThat(count("SELECT count(*) FROM water_meters WHERE address_id = ?", apartment)).isEqualTo(3);
    }

    @Test
    void waterTypeMustBeHotOrCold() {
        long apartment = address("47");

        assertThatThrownBy(() -> meter(apartment, "STEAM", "A-1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> meter(apartment, null, "A-2")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void meterRequiresExistingApartment() {
        assertThatThrownBy(() -> meter(null, "HOT", "A-1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> meter(999L, "HOT", "A-1")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void meterNumberIsUniquePerApartmentOnly() {
        long first = address("47");
        long second = address("48");
        meter(first, "HOT", "A-1");

        assertThatThrownBy(() -> meter(first, "COLD", "A-1")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(meter(second, "HOT", "A-1")).isPositive();
    }

    @Test
    void meterCanHaveSeveralReadings() {
        long meter = meter(address("47"), "HOT", "A-1");
        long first = reading(meter, "100.000", "2026-08-25", null);
        reading(meter, "110.500", "2026-09-25", first);

        assertThat(count("SELECT count(*) FROM meter_readings WHERE meter_id = ?", meter)).isEqualTo(2);
    }

    @Test
    void readingValueMustBeNonNegativeAndMeterMustExist() {
        long meter = meter(address("47"), "HOT", "A-1");

        assertThatThrownBy(() -> reading(meter, "-1.000", "2026-08-25", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reading(null, "1.000", "2026-08-25", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reading(999L, "1.000", "2026-08-25", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reading(meter, "0.000", "2026-08-25", null)).isPositive();
    }

    @Test
    void recordsAreNotTestByDefault() {
        long meter = meter(address("47"), "HOT", "A-1");
        reading(meter, "1.000", "2026-08-25", null);

        assertThat(jdbc.queryForObject("SELECT is_test FROM water_meters", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT is_test FROM meter_readings", Boolean.class)).isFalse();
    }

    @Test
    void previousReadingFormsAChain() {
        long meter = meter(address("47"), "HOT", "A-1");
        long first = reading(meter, "100.000", "2026-08-25", null);
        long second = reading(meter, "110.000", "2026-09-25", first);

        assertThat(jdbc.queryForObject("SELECT previous_reading_id FROM meter_readings WHERE id = ?", Long.class, second))
                .isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT previous_reading_id FROM meter_readings WHERE id = ?", Long.class, first))
                .isNull();
    }

    @Test
    void previousReadingMustBelongToTheSameMeter() {
        long apartment = address("47");
        long hot = meter(apartment, "HOT", "A-1");
        long cold = meter(apartment, "COLD", "A-2");
        long hotFirst = reading(hot, "100.000", "2026-08-25", null);
        reading(cold, "200.000", "2026-08-25", null);

        assertThatThrownBy(() -> reading(cold, "210.000", "2026-09-25", hotFirst))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readingCannotBeItsOwnPrevious() {
        long meter = meter(address("47"), "HOT", "A-1");
        long first = reading(meter, "100.000", "2026-08-25", null);

        assertThatThrownBy(() -> jdbc.update("UPDATE meter_readings SET previous_reading_id = id WHERE id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chainCannotBranch() {
        long meter = meter(address("47"), "HOT", "A-1");
        long first = reading(meter, "100.000", "2026-08-25", null);
        reading(meter, "110.000", "2026-09-25", first);

        assertThatThrownBy(() -> reading(meter, "111.000", "2026-09-26", first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void meterHasAtMostOneFirstReading() {
        long apartment = address("47");
        long meter = meter(apartment, "HOT", "A-1");
        long other = meter(apartment, "COLD", "A-2");
        reading(meter, "100.000", "2026-08-25", null);

        assertThatThrownBy(() -> reading(meter, "90.000", "2026-07-25", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reading(other, "50.000", "2026-08-25", null)).isPositive();
    }

    @Test
    void deletingRecordsWithDependantsIsRejected() {
        long apartment = address("47");
        long meter = meter(apartment, "HOT", "A-1");
        long first = reading(meter, "100.000", "2026-08-25", null);
        reading(meter, "110.000", "2026-09-25", first);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM addresses WHERE id = ?", apartment))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM water_meters WHERE id = ?", meter))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM meter_readings WHERE id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("SELECT count(*) FROM meter_readings")).isEqualTo(2);
    }
}
