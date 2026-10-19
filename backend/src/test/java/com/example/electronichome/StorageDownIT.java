package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// The storage does not answer: the files give 503 without details, the text of the chats and the rest keep working.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "storage.s3.endpoint=http://localhost:1")
class StorageDownIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private String owner;
    private long requestId;
    private long attachmentId;

    @BeforeEach
    void data() throws Exception {
        AuthSupport.createUser(jdbc, "it-owner-down", "OWNER", true, "000000000001");
        owner = AuthSupport.bearer(port, "it-owner-down");
        requestId = jdbc.queryForObject("""
                INSERT INTO requests (account_id, author_id, number)
                SELECT a.id, u.id, 'О-000000-9001' FROM accounts a, users u WHERE a.number = '000000000001' AND u.login = 'it-owner-down' RETURNING id""", Long.class);
        long message = jdbc.queryForObject("""
                INSERT INTO request_messages (request_id, author_id, body) SELECT ?, id, 'Текст' FROM users WHERE login = 'it-owner-down' RETURNING id""", Long.class, requestId);
        attachmentId = jdbc.queryForObject("""
                INSERT INTO request_attachments (message_id, object_key, file_name, content_type, size_bytes, sha256)
                VALUES (?, '77777777-7777-7777-7777-777777777777', 'a.pdf', 'application/pdf', 10, ?) RETURNING id""", Long.class, message, "a".repeat(64));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM requests WHERE number = 'О-000000-9001'");
        AuthSupport.deleteUser(jdbc, "it-owner-down");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.header("Authorization", owner).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void downloadingAFileGivesServiceUnavailableWithoutDetails() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/requests/" + requestId + "/attachments/" + attachmentId)));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("localhost").doesNotContain("Exception").doesNotContain("amazon");
    }

    @Test
    void sendingAFileGivesServiceUnavailableAndSavesNothing() throws Exception {
        String boundary = "----down";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"b.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4 body\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/requests/" + requestId + "/messages"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_messages WHERE request_id = ?", Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void theTextOfTheChatKeepsWorking() throws Exception {
        HttpResponse<String> messages = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/requests/" + requestId + "/messages")));
        HttpResponse<String> posted = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/requests/" + requestId + "/messages"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"Без файлов\"}")));

        assertThat(messages.statusCode()).isEqualTo(200);
        assertThat(messages.body()).contains("a.pdf");
        assertThat(posted.statusCode()).isEqualTo(201);
    }

    @Test
    void downloadingAReceiptGivesServiceUnavailable() throws Exception {
        jdbc.update("""
                INSERT INTO receipts (account_id, period_month, file_key, size_bytes, sha256)
                SELECT id, DATE '2026-09-01', '88888888-8888-8888-8888-888888888888', 10, ? FROM accounts WHERE number = '000000000001'""", "b".repeat(64));
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/000000000001/receipts/2026-09")));
            assertThat(response.statusCode()).isEqualTo(503);
        } finally {
            jdbc.update("DELETE FROM receipts WHERE file_key = '88888888-8888-8888-8888-888888888888'");
        }
    }
}
