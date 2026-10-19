package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// With the prod Liquibase context there are no test requests.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.liquibase.contexts=prod")
class RequestProdDataIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void noRequestsByDefault() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requests", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_messages", Integer.class)).isZero();
    }

    @Test
    void theFirstRequestOfAFreshSystemGetsTheNumberOne() {
        assertThat(jdbc.queryForObject("SELECT nextval('request_number_seq')", Long.class)).isEqualTo(1L);
    }
}
