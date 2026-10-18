package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

// A dev database created before the verification dates existed: its test meters must get the dates on upgrade.
@Testcontainers
class MeterVerificationUpgradeIT {

    /** Change sets of migrations 001-005: 5 + 1 + 5 + 2 + 2. */
    private static final int CHANGE_SETS_BEFORE_VERIFICATION_DATE = 15;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Test
    void upgradeAddsDatesToTestMetersCreatedByAnEarlierRun() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

            liquibase.update(CHANGE_SETS_BEFORE_VERIFICATION_DATE, new Contexts("dev"), new LabelExpression());
            assertThat(jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'water_meters'", String.class))
                    .doesNotContain("verification_date");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM water_meters", Integer.class)).isEqualTo(2);

            liquibase.update(new Contexts("dev"), new LabelExpression());

            List<String> dates = jdbc.queryForList(
                    "SELECT number || '|' || verification_date::text FROM water_meters ORDER BY number", String.class);
            assertThat(dates).containsExactly("Тест 1|2028-01-01", "Тест 2|2026-10-17");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM water_meters", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM meter_readings", Integer.class)).isEqualTo(4);
        }
    }
}
