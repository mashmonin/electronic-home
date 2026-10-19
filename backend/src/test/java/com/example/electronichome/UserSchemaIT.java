package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
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

// The tables of users and the test user, against a real database.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserSchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void removeUsers() {
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
    }

    private void insert(String login, String role) {
        jdbc.update("INSERT INTO users (login, password_hash, role) VALUES (?, 'hash', ?)", login, role);
    }

    @Test
    void loginIsUniqueIgnoringCase() {
        insert("it-Ivan", "OWNER");

        assertThatThrownBy(() -> insert("it-ivan", "RESIDENT")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyKnownRolesAreAccepted() {
        for (String role : new String[] {"ADMIN", "OWNER", "RESIDENT"}) {
            insert("it-" + role, role);
        }

        assertThatThrownBy(() -> insert("it-root", "ROOT")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountIsLinkedToAUserOnlyOnce() {
        insert("it-link", "OWNER");
        String link = "INSERT INTO user_accounts (user_id, account_id) SELECT u.id, a.id FROM users u, accounts a "
                + "WHERE u.login = 'it-link' AND a.number = '000000000001'";
        jdbc.update(link);

        assertThatThrownBy(() -> jdbc.update(link)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testUserIsMarkedAndKeepsOnlyAHash() {
        var user = jdbc.queryForMap("SELECT password_hash, role, enabled, is_test FROM users WHERE login = 'owner'");

        assertThat(user.get("role")).isEqualTo("OWNER");
        assertThat(user.get("enabled")).isEqualTo(true);
        assertThat(user.get("is_test")).isEqualTo(true);
        assertThat((String) user.get("password_hash")).startsWith("$2").doesNotContain("Owner-dev-123");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT a.number FROM user_accounts ua JOIN users u ON u.id = ua.user_id
                JOIN accounts a ON a.id = ua.account_id WHERE u.login = 'owner'""", String.class)).isEqualTo("000000000001");
    }

    @Test
    void testAdministratorHasARoleAndNoLinkedAccounts() {
        var admin = jdbc.queryForMap("SELECT password_hash, role, enabled, is_test FROM users WHERE login = 'admin'");

        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(admin.get("is_test")).isEqualTo(true);
        assertThat((String) admin.get("password_hash")).startsWith("$2").doesNotContain("Admin-dev-123");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM user_accounts ua JOIN users u ON u.id = ua.user_id WHERE u.login = 'admin'""", Integer.class)).isZero();
    }
}
