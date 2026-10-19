package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// The storage does not answer (nothing listens on the port): the health is DOWN, and a prod-like setting shows no details.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"storage.s3.endpoint=http://localhost:1", "management.endpoint.health.show-details=never"})
class HealthStorageDownIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @Test
    void healthIsDownWithoutDetailsWhenTheStorageDoesNotAnswer() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
        assertThat(response.body()).doesNotContain("localhost").doesNotContain("Exception").doesNotContain("test-access");
    }

    @Test
    void theApplicationStartsAndTheRestOfTheApiWorksWithoutTheStorage() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
