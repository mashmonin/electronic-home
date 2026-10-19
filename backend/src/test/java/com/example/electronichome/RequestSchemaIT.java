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

// The tables of requests and messages and the test conversation, against a real database.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RequestSchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void removeRows() {
        jdbc.update("DELETE FROM requests WHERE NOT is_test");
    }

    private long insertRequest(String number) {
        return jdbc.queryForObject("""
                INSERT INTO requests (account_id, author_id, number)
                SELECT a.id, u.id, ? FROM accounts a, users u WHERE a.number = '000000000001' AND u.login = 'owner'
                RETURNING id""", Long.class, number);
    }

    private void insertMessage(long requestId, String body) {
        jdbc.update("INSERT INTO request_messages (request_id, author_id, body) SELECT ?, id, ? FROM users WHERE login = 'owner'",
                requestId, body);
    }

    @Test
    void aRequestHasNoSubjectAnyMore() {
        Integer columns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'requests' AND column_name = 'subject'", Integer.class);
        assertThat(columns).isZero();
    }

    @Test
    void aRequestHasNoStatus() {
        Integer columns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'requests' AND column_name = 'status'", Integer.class);
        assertThat(columns).isZero();
    }

    @Test
    void theNumbersAreUnique() {
        insertRequest("О-000000-0001");
        assertThatThrownBy(() -> insertRequest("О-000000-0001")).isInstanceOf(DataIntegrityViolationException.class);
        insertRequest("О-000000-0002");
    }

    @Test
    void anEmptyNumberIsRefusedButAMessageMayBeOnlyFiles() {
        assertThatThrownBy(() -> insertRequest("   ")).isInstanceOf(DataIntegrityViolationException.class);
        long id = insertRequest("О-000000-0001");
        // the text may be empty when the message carries files; the service demands a text or a file
        insertMessage(id, "");
    }

    @Test
    void aTooLongNumberOrBodyIsRefused() {
        assertThatThrownBy(() -> insertRequest("О".repeat(25))).isInstanceOf(DataIntegrityViolationException.class);
        long id = insertRequest("О".repeat(24));
        assertThatThrownBy(() -> insertMessage(id, "с".repeat(2001))).isInstanceOf(DataIntegrityViolationException.class);
        insertMessage(id, "с".repeat(2000));
    }

    @Test
    void messagesGoWithTheirRequest() {
        long id = insertRequest("О-000000-0001");
        insertMessage(id, "Первое");
        insertMessage(id, "Второе");

        jdbc.update("DELETE FROM requests WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_messages WHERE request_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void aUserWithRequestsCannotBeDeleted() {
        insertRequest("О-000000-0001");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE login = 'owner'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theNumberSequenceStartsAfterTheTestRequest() {
        // the test request took the first number, so the next one is the second
        assertThat(jdbc.queryForObject("SELECT nextval('request_number_seq')", Long.class)).isEqualTo(2L);
    }

    @Test
    void theTestConversationIsThereWithANumber() {
        var request = jdbc.queryForMap("SELECT id, number, is_test FROM requests WHERE is_test");
        assertThat((String) request.get("number")).matches("О-\\d{6}-0001");

        var authors = jdbc.queryForList("""
                SELECT u.login FROM request_messages m JOIN users u ON u.id = m.author_id
                WHERE m.request_id = ? ORDER BY m.id""", String.class, request.get("id"));
        assertThat(authors).containsExactly("owner", "admin");
    }

    // ---- the attachments

    private long messageOf(long requestId) {
        insertMessage(requestId, "С файлом");
        return jdbc.queryForObject("SELECT max(id) FROM request_messages WHERE request_id = ?", Long.class, requestId);
    }

    private void insertAttachment(long messageId, String key, String name, long size) {
        jdbc.update(
                "INSERT INTO request_attachments (message_id, object_key, file_name, content_type, size_bytes, sha256) VALUES (?, ?, ?, 'application/pdf', ?, ?)",
                messageId, key, name, size, "a".repeat(64));
    }

    @Test
    void anAttachmentBelongsToAMessage() {
        long message = messageOf(insertRequest("О-000000-0001"));

        insertAttachment(message, "11111111-1111-1111-1111-111111111111", "схема.pdf", 100);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_attachments WHERE message_id = ?", Integer.class, message)).isEqualTo(1);
    }

    @Test
    void theKeyOfAnObjectIsUnique() {
        long message = messageOf(insertRequest("О-000000-0001"));
        insertAttachment(message, "11111111-1111-1111-1111-111111111111", "a.pdf", 100);

        assertThatThrownBy(() -> insertAttachment(message, "11111111-1111-1111-1111-111111111111", "b.pdf", 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anEmptyFileOrAnEmptyNameIsRefused() {
        long message = messageOf(insertRequest("О-000000-0001"));

        assertThatThrownBy(() -> insertAttachment(message, "22222222-2222-2222-2222-222222222222", "a.pdf", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttachment(message, "33333333-3333-3333-3333-333333333333", "  ", 10))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAttachmentNeedsAnExistingMessage() {
        assertThatThrownBy(() -> insertAttachment(999999, "44444444-4444-4444-4444-444444444444", "a.pdf", 10))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attachmentsGoWithTheirMessageAndTheirRequest() {
        long request = insertRequest("О-000000-0001");
        long message = messageOf(request);
        insertAttachment(message, "55555555-5555-5555-5555-555555555555", "a.pdf", 10);

        jdbc.update("DELETE FROM requests WHERE id = ?", request);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_attachments WHERE object_key = '55555555-5555-5555-5555-555555555555'", Integer.class)).isZero();
    }

    @Test
    void thereIsNoContentInTheDatabase() {
        Integer columns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'request_attachments' AND data_type IN ('bytea', 'oid')", Integer.class);
        assertThat(columns).isZero();
    }
}
