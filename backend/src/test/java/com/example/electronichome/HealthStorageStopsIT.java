package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.storage.ObjectStore;

// The storage works and then stops: the health must notice that, although the buckets are already known to the application.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthStorageStopsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    // a storage of its own: the shared one must not be stopped
    static final GenericContainer<?> STORAGE = SeaweedFsTestContainer.create();

    static {
        STORAGE.start();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.s3.endpoint", () -> "http://" + STORAGE.getHost() + ":" + STORAGE.getMappedPort(8333));
        registry.add("storage.s3.access-key", () -> SeaweedFsTestContainer.ACCESS_KEY);
        registry.add("storage.s3.secret-key", () -> SeaweedFsTestContainer.SECRET_KEY);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectStore store;

    @AfterAll
    static void stopStorage() {
        STORAGE.stop();
    }

    private HttpResponse<String> health() throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthGoesDownAfterTheStorageStopsEvenWhenTheBucketsWereUsedBefore() throws Exception {
        // the buckets become known to the application by a use, not only by the health
        java.nio.file.Path file = java.nio.file.Files.createTempFile("it-", ".bin");
        java.nio.file.Files.write(file, "data".getBytes(StandardCharsets.UTF_8));
        store.put("electronic-home-receipts", java.util.UUID.randomUUID().toString(), file, 4, "text/plain");
        assertThat(health().statusCode()).isEqualTo(200);

        STORAGE.stop();

        long started = System.currentTimeMillis();
        HttpResponse<String> down = health();
        assertThat(down.statusCode()).isEqualTo(503);
        assertThat(down.body()).containsPattern("\"objectStorage\":\\{[^}]*\\}?[^}]*\"status\":\"DOWN\"");
        assertThat(System.currentTimeMillis() - started).as("the probe has a short timeout").isLessThan(15_000);
    }
}
