package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// No admin token is configured (as in production before RECEIPTS_ADMIN_TOKEN is set): nobody can publish, reading works.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "receipts.admin-token=")
class ReceiptsPublishingDisabledIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private HttpResponse<String> put(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/000000000001/receipts/2026-09"))
                .header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofString("%PDF-1.4\n%%EOF\n", StandardCharsets.UTF_8));
        if (token != null) {
            request.header("X-Admin-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void publishingIsRefusedWhateverTheTokenIs() throws Exception {
        assertThat(put(null).statusCode()).isEqualTo(403);
        assertThat(put("").statusCode()).isEqualTo(403);
        assertThat(put("dev-admin-token").statusCode()).isEqualTo(403);
    }

    @Test
    void readingStillWorks() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/000000000001/receipts"))
                .header("Authorization", AuthSupport.adminBearer(jdbc, port)).build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"receipts\"");
    }
}
