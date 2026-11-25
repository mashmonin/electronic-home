package com.example.electronichome.statement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.SeaweedFsTestContainer;

// What a restart does to the batches that were not finished.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchRunnerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        SeaweedFsTestContainer.register(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BatchRunner runner;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM receipt_batches");
    }

    private void batch(String key, String status) {
        jdbc.update("INSERT INTO receipt_batches (period_month, source_key, size_bytes, sha256, status, total) VALUES ('2026-09-01', ?, 1, 'x', ?, 3)", key, status);
    }

    @Test
    void queuedAndRunningBatchesAreFailedAsInterruptedAndTheFinishedOnesStay() {
        batch("11111111-1111-1111-1111-111111111111", "QUEUED");
        batch("22222222-2222-2222-2222-222222222222", "RUNNING");
        batch("33333333-3333-3333-3333-333333333333", "DONE");

        runner.failInterrupted();

        assertThat(jdbc.queryForList("SELECT status || ':' || coalesce(error, '') FROM receipt_batches ORDER BY source_key", String.class))
                .containsExactly("FAILED:INTERRUPTED", "FAILED:INTERRUPTED", "DONE:");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipt_batches WHERE status = 'FAILED' AND finished_at IS NOT NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void aBatchWhoseStatementIsMissingFailsWithoutDetails() {
        batch("44444444-4444-4444-4444-444444444444", "QUEUED");
        long id = jdbc.queryForObject("SELECT id FROM receipt_batches WHERE source_key = '44444444-4444-4444-4444-444444444444'", Long.class);

        runner.run(id);

        assertThat(jdbc.queryForObject("SELECT status FROM receipt_batches WHERE id = ?", String.class, id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error FROM receipt_batches WHERE id = ?", String.class, id)).isEqualTo("IllegalStateException");
    }
}
