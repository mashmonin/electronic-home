package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

// The requests that were created before the numbers get them by the migration, in the order of their creation.
@Testcontainers
class RequestNumberMigrationIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Test
    void existingRequestsGetNumbersByTheDateOfCreationAndTheSequenceGoesOn() throws Exception {
        var dataSource = DataSourceBuilder.create()
                .url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), database);
            int before = liquibase.getDatabaseChangeLog().getChangeSets().stream().map(c -> c.getId()).toList().indexOf("014-request-number");
            liquibase.update(before, "dev");
            // the test request of the migration 013 is the first one; two more, with subjects, as they were before the numbers
            for (String createdAt : List.of("2026-03-05 12:00:00+00", "2026-10-19 12:00:00+00")) {
                jdbc.update("""
                        INSERT INTO requests (account_id, author_id, subject, created_at)
                        SELECT a.id, u.id, 'Старая заявка', ?::timestamptz FROM accounts a, users u
                        WHERE a.number = '000000000001' AND u.login = 'owner'""", createdAt);
            }

            liquibase.update("dev");
        }

        List<String> numbers = jdbc.queryForList("SELECT number FROM requests ORDER BY id", String.class);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyy"));
        assertThat(numbers).hasSize(3);
        assertThat(numbers.get(0)).isEqualTo("О-" + today + "-0001");
        assertThat(numbers.get(1)).isEqualTo("О-050326-0002");
        assertThat(numbers.get(2)).isEqualTo("О-191026-0003");
        assertThat(jdbc.queryForObject("SELECT nextval('request_number_seq')", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'requests' AND column_name = 'subject'", Integer.class)).isZero();
    }
}
