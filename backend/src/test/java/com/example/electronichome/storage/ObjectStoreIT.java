package com.example.electronichome.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.SeaweedFsTestContainer;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

// The object store against a real SeaweedFS: writing, reading, deleting, the buckets that are made on demand.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "management.health.objectStorage.enabled=false")
class ObjectStoreIT {

    private static String[] buckets;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        buckets = SeaweedFsTestContainer.register(registry);
    }

    @Autowired
    ObjectStore store;

    @Autowired
    S3Client client;

    private static Path file(String content) throws Exception {
        Path file = Files.createTempFile("it-", ".bin");
        Files.writeString(file, content);
        file.toFile().deleteOnExit();
        return file;
    }

    private static String read(InputStream stream) throws Exception {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void writesAndReadsAnObjectByteForByte() throws Exception {
        String key = ObjectKeys.newKey();
        Path file = file("%PDF-1.4 содержимое");

        store.put(buckets[0], key, file, Files.size(file), "application/pdf");

        assertThat(store.exists(buckets[0], key)).isTrue();
        assertThat(read(store.open(buckets[0], key).orElseThrow())).isEqualTo("%PDF-1.4 содержимое");
    }

    @Test
    void aMissingObjectIsEmptyNotAnError() throws Exception {
        String key = ObjectKeys.newKey();
        store.ensureBucket(buckets[0]);

        assertThat(store.open(buckets[0], key)).isEmpty();
        assertThat(store.exists(buckets[0], key)).isFalse();
    }

    @Test
    void deletesAnObjectAndDeletingAgainIsNotAnError() throws Exception {
        String key = ObjectKeys.newKey();
        Path file = file("data");
        store.put(buckets[0], key, file, Files.size(file), "text/plain");

        store.delete(buckets[0], key);
        store.delete(buckets[0], key);

        assertThat(store.exists(buckets[0], key)).isFalse();
    }

    @Test
    void theBucketsAreSeparate() throws Exception {
        String key = ObjectKeys.newKey();
        Path file = file("data");
        store.put(buckets[0], key, file, Files.size(file), "text/plain");

        assertThat(store.exists(buckets[1], key)).isFalse();
    }

    @Test
    void createsAMissingBucketOnTheFirstUseOnly() throws Exception {
        String bucket = "it-fresh-" + ObjectKeys.newKey().substring(0, 8);
        String key = ObjectKeys.newKey();
        Path file = file("data");

        store.put(bucket, key, file, Files.size(file), "text/plain");
        store.put(bucket, ObjectKeys.newKey(), file, Files.size(file), "text/plain");

        assertThat(client.listBuckets().buckets()).anyMatch(b -> b.name().equals(bucket));
        assertThat(store.exists(bucket, key)).isTrue();
    }

    @Test
    void manyThreadsMakingTheSameBucketAtOnceAllSucceed() throws Exception {
        String bucket = "it-race-" + ObjectKeys.newKey().substring(0, 8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> {
                    store.ensureBucket(bucket);
                    return true;
                });
            }
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(client.listBuckets().buckets()).filteredOn(b -> b.name().equals(bucket)).hasSize(1);
    }

    @Test
    void aKeyThatIsNotAUuidNeverReachesTheStorage() throws Exception {
        Path file = file("data");

        for (String bad : new String[] {"../secret", "/etc/passwd", "a/b", "not-a-uuid", "", null}) {
            assertThatThrownBy(() -> store.put(buckets[0], bad, file, 4, "text/plain")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.open(buckets[0], bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.delete(buckets[0], bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.exists(buckets[0], bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(client.listObjectsV2(ListObjectsV2Request.builder().bucket(buckets[0]).prefix("..").build()).contents()).isEmpty();
    }

    @Test
    void theNameOfTheClientFileIsNotPartOfTheKey() throws Exception {
        Path file = Files.createTempFile("..-evil-name", ".pdf");
        Files.writeString(file, "%PDF-1.4");
        String key = ObjectKeys.newKey();

        store.put(buckets[0], key, file, 8, "application/pdf");

        assertThat(client.listObjectsV2(ListObjectsV2Request.builder().bucket(buckets[0]).build()).contents())
                .noneMatch(object -> object.key().contains("evil"));
    }
}
