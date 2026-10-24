package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// The import of the owners with the Liquibase context owners-import, from a file out of the classpath (as on the server).
// The people of the fixture are made up; the real SQL is made by tools/owners-import and is not in git.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.liquibase.contexts=prod,owners-import")
class OwnersImportIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) throws IOException {
        Path directory = Files.createTempDirectory("owners-import");
        for (String name : List.of("021-import-owners.sql", "021-import-owners.rollback.sql")) {
            Files.copy(new ClassPathResource("owners-import/" + name).getInputStream(), directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        registry.add("OWNERS_IMPORT_FILE", () -> "file:" + directory.resolve("021-import-owners.sql"));
        registry.add("OWNERS_IMPORT_ROLLBACK_FILE", () -> "file:" + directory.resolve("021-import-owners.rollback.sql"));
    }

    @Autowired
    JdbcTemplate jdbc;

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void createsOwnersAccountsAndLoginsOfTheFile() {
        assertThat(count("SELECT count(*) FROM accounts WHERE NOT is_test")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM owners WHERE NOT is_test")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM account_owners WHERE NOT is_test")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM address_owners WHERE NOT is_test")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT login FROM users WHERE role = 'OWNER' ORDER BY login", String.class)).containsExactly("appetrova", "iiivanov");
        assertThat(jdbc.queryForObject("SELECT a.apartment FROM accounts ac JOIN addresses a ON a.id = ac.address_id WHERE ac.number = '000000000003'", String.class)).isEqualTo("3");
    }

    @Test
    void oneUserHasAllTheAccountsOfTheSameName() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ac.number FROM user_accounts ua JOIN users u ON u.id = ua.user_id JOIN accounts ac ON ac.id = ua.account_id WHERE u.login = 'iiivanov' ORDER BY ac.number");
        assertThat(rows).extracting(row -> row.get("number")).containsExactly("000000000001", "000000000002");
    }

    @Test
    void storesOnlyTheBcryptHashOfThePassword() {
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE login = 'iiivanov'", String.class);
        assertThat(hash).startsWith("$2a$10$");
        assertThat(new BCryptPasswordEncoder().matches("Fixture-pass-1", hash)).isTrue();
    }

    @Test
    void runningTheScriptAgainMakesNoDuplicates() throws IOException {
        String script = Files.readString(new ClassPathResource("owners-import/021-import-owners.sql").getFile().toPath());
        jdbc.execute(script);
        assertThat(count("SELECT count(*) FROM accounts WHERE NOT is_test")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM owners WHERE NOT is_test")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM users WHERE role = 'OWNER'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM user_accounts")).isEqualTo(3);
    }

    @Test
    void rollbackRemovesWhatTheImportMadeAndTheImportCanBeRepeated() throws IOException {
        jdbc.execute(Files.readString(new ClassPathResource("owners-import/021-import-owners.rollback.sql").getFile().toPath()));
        for (String table : List.of("accounts", "owners", "account_owners", "address_owners", "user_accounts")) {
            assertThat(count("SELECT count(*) FROM " + table)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM users WHERE role = 'OWNER'")).isZero();
        jdbc.execute(Files.readString(new ClassPathResource("owners-import/021-import-owners.sql").getFile().toPath()));
        assertThat(count("SELECT count(*) FROM accounts")).isEqualTo(3);
    }
}
