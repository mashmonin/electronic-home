package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.springframework.jdbc.core.JdbcTemplate;

// A user "admin" created by hand before the migration of the test administrator must stay as it is.
@Testcontainers
class TestAdminMigrationIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Test
    void anExistingAdminIsKept() throws Exception {
        var dataSource = DataSourceBuilder.create()
                .url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database);
            // everything before the test administrator, then a hand-made administrator, then the rest
            var changeSets = liquibase.getDatabaseChangeLog().getChangeSets();
            int before = changeSets.stream().map(c -> c.getId()).toList().indexOf("011-seed-test-admin");
            liquibase.update(before, "dev");
            jdbc.update("INSERT INTO users (login, password_hash, role) VALUES ('Admin', 'my-own-hash', 'RESIDENT')");

            liquibase.update(new Contexts("dev"));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(login) = 'admin'", Integer.class)).isEqualTo(1);
        var admin = jdbc.queryForMap("SELECT password_hash, role, is_test FROM users WHERE lower(login) = 'admin'");
        assertThat(admin.get("password_hash")).isEqualTo("my-own-hash");
        assertThat(admin.get("role")).isEqualTo("RESIDENT");
        assertThat(admin.get("is_test")).isEqualTo(false);
    }
}
