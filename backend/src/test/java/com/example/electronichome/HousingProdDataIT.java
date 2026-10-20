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

// Runs the dev profile with the Liquibase context of prod, to check that no test data is created there.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.liquibase.contexts=prod")
class HousingProdDataIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void prodContextCreatesNoTestData() {
        for (String table : List.of("addresses", "accounts", "owners", "address_owners", "account_owners")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE is_test", Integer.class)).as(table).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'is_test'",
                    Integer.class, table)).as(table + ".is_test").isEqualTo(1);
        }
    }

    @Test
    void prodContextHasTheApartmentsOfTheHouseAndNoAccountsOrOwners() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM addresses", Integer.class)).isEqualTo(138);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT apartment) FROM addresses WHERE street = 'ул. Тестовая' AND house = 'Дом' AND NOT is_test AND apartment ~ '^[0-9]+$' AND apartment::int BETWEEN 1 AND 138",
                Integer.class)).isEqualTo(138);
        for (String table : List.of("accounts", "owners", "address_owners", "account_owners")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }
}
