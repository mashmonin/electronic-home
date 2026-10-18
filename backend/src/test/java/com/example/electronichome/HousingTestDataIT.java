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

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HousingTestDataIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void devProfileSeedsTestOwners() {
        List<String> owners = jdbc.queryForList("SELECT full_name FROM owners ORDER BY full_name", String.class);

        assertThat(owners).containsExactly("Тестовая Теста Тестовна", "Тестовый Тест Тестович");
    }

    @Test
    void devProfileSeedsTestApartmentsWithoutCity() {
        List<String> addresses = jdbc.queryForList(
                "SELECT street || '|' || house || '|' || apartment FROM addresses ORDER BY apartment", String.class);

        assertThat(addresses).containsExactly("ул. Тестовая|Тест|1", "ул. Тестовая|Тест|2");
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'addresses'", String.class))
                .doesNotContain("city");
    }

    @Test
    void testAccountBelongsToFirstApartmentWithBothCoOwners() {
        assertThat(jdbc.queryForObject(
                "SELECT a.apartment FROM accounts c JOIN addresses a ON a.id = c.address_id WHERE c.number = '000000000001'",
                String.class)).isEqualTo("1");
        assertThat(jdbc.queryForList("""
                SELECT o.full_name FROM account_owners ao
                JOIN accounts c ON c.id = ao.account_id
                JOIN owners o ON o.id = ao.owner_id
                WHERE c.number = '000000000001' ORDER BY o.full_name
                """, String.class)).containsExactly("Тестовая Теста Тестовна", "Тестовый Тест Тестович");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class)).isEqualTo(1);
    }

    @Test
    void apartmentOwnersMatchTheDescription() {
        assertThat(ownersOfApartment("1")).containsExactly("Тестовая Теста Тестовна", "Тестовый Тест Тестович");
        assertThat(ownersOfApartment("2")).containsExactly("Тестовый Тест Тестович");
    }

    @Test
    void everySeededRowIsMarkedAsTest() {
        for (String table : List.of("addresses", "accounts", "owners", "address_owners", "account_owners")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE NOT is_test", Integer.class))
                    .as(table).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .as(table).isPositive();
        }
    }

    private List<String> ownersOfApartment(String apartment) {
        return jdbc.queryForList("""
                SELECT o.full_name FROM address_owners ao
                JOIN addresses a ON a.id = ao.address_id
                JOIN owners o ON o.id = ao.owner_id
                WHERE a.apartment = ? ORDER BY o.full_name
                """, String.class, apartment);
    }
}
