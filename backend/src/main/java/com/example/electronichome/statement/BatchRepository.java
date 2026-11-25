package com.example.electronichome.statement;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the batches of receipts and their items. */
@Repository
class BatchRepository {

    static final int LIST_LIMIT = 50;

    /** A batch as stored. */
    record Batch(
            long id, YearMonth month, String sourceKey, String status, int total, int processed, int created, int replaced, int skipped,
            int failed, String error, Instant createdAt, Instant finishedAt) {
    }

    record Item(String accountNumber, String status, String reason) {
    }

    private static final String SELECT = """
            SELECT id, period_month, source_key, status, total, processed, created, replaced, skipped, failed, error, created_at, finished_at
            FROM receipt_batches""";

    private final JdbcClient jdbc;

    BatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    long create(YearMonth month, String sourceKey, long size, String sha256, int total, long userId) {
        return jdbc.sql("""
                        INSERT INTO receipt_batches (period_month, source_key, size_bytes, sha256, status, total, created_by)
                        VALUES (:month, :key, :size, :sha, 'QUEUED', :total, :user) RETURNING id""")
                .param("month", Date.valueOf(month.atDay(1)))
                .param("key", sourceKey)
                .param("size", size)
                .param("sha", sha256)
                .param("total", total)
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    Optional<Batch> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, row) -> map(rs)).optional();
    }

    List<Batch> latest() {
        return jdbc.sql(SELECT + " ORDER BY id DESC LIMIT " + LIST_LIMIT).query((rs, row) -> map(rs)).list();
    }

    List<Item> items(long batchId) {
        return jdbc.sql("SELECT account_number, status, reason FROM receipt_batch_items WHERE batch_id = :id ORDER BY id")
                .param("id", batchId)
                .query((rs, row) -> new Item(rs.getString("account_number"), rs.getString("status"), rs.getString("reason")))
                .list();
    }

    @Transactional
    void markRunning(long id) {
        jdbc.sql("UPDATE receipt_batches SET status = 'RUNNING' WHERE id = :id AND status = 'QUEUED'").param("id", id).update();
    }

    /** Writes the item and counts it in the batch, in one transaction, so that the progress is never ahead of the items. */
    @Transactional
    void record(long batchId, String accountNumber, String status, String reason) {
        jdbc.sql("INSERT INTO receipt_batch_items (batch_id, account_number, status, reason) VALUES (:id, :number, :status, :reason)")
                .param("id", batchId)
                .param("number", accountNumber)
                .param("status", status)
                .param("reason", reason)
                .update();
        String counter = switch (status) {
            case BatchStatus.CREATED -> "created";
            case BatchStatus.REPLACED -> "replaced";
            case BatchStatus.SKIPPED -> "skipped";
            default -> "failed";
        };
        jdbc.sql("UPDATE receipt_batches SET processed = processed + 1, " + counter + " = " + counter + " + 1 WHERE id = :id")
                .param("id", batchId)
                .update();
    }

    @Transactional
    void finish(long id, String status, String error) {
        jdbc.sql("UPDATE receipt_batches SET status = :status, error = :error, finished_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status)
                .param("error", error)
                .update();
    }

    /** The batches that were queued or running when the application stopped cannot go on: they are marked as failed. */
    @Transactional
    int failInterrupted() {
        return jdbc.sql("""
                        UPDATE receipt_batches SET status = 'FAILED', error = 'INTERRUPTED', finished_at = now()
                        WHERE status IN ('QUEUED', 'RUNNING')""")
                .update();
    }

    private static Batch map(ResultSet rs) throws SQLException {
        Instant finished = rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant();
        return new Batch(
                rs.getLong("id"), YearMonth.from(rs.getDate("period_month").toLocalDate()), rs.getString("source_key"), rs.getString("status"),
                rs.getInt("total"), rs.getInt("processed"), rs.getInt("created"), rs.getInt("replaced"), rs.getInt("skipped"),
                rs.getInt("failed"), rs.getString("error"), rs.getTimestamp("created_at").toInstant(), finished);
    }
}
