package com.example.electronichome.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;

// The storage of the receipts on a real SeaweedFS: what is kept, what is refused and what is left behind.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "management.health.objectStorage.enabled=false")
class S3ReceiptStorageIT {

    private static String[] buckets;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        buckets = SeaweedFsTestContainer.register(registry);
    }

    @Autowired
    ReceiptStorage storage;

    @Autowired
    S3Client s3;

    private static byte[] pdf(String text) {
        return ("%PDF-1.4\n" + text + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }

    private List<String> keys(String bucket) {
        try {
            return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents().stream().map(S3Object::key).sorted().toList();
        } catch (NoSuchBucketException e) {
            return List.of();
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    void storesAFileUnderAKeyOfItsOwnAndReadsItBack() throws Exception {
        byte[] content = pdf("привет");

        ReceiptStorage.Stored stored = storage.save(new ByteArrayInputStream(content), 1024);

        assertThat(stored.key()).matches("[0-9a-f-]{36}");
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(sha256(content));
        try (InputStream in = storage.open(stored.key()).orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(keys(buckets[0])).contains(stored.key());
    }

    @Test
    void theFileIsKeptWithTheTypeOfAPdfInTheBucketOfTheReceiptsOnly() throws Exception {
        ReceiptStorage.Stored stored = storage.save(new ByteArrayInputStream(pdf("x")), 1024);

        var head = s3.headObject(HeadObjectRequest.builder().bucket(buckets[0]).key(stored.key()).build());

        assertThat(head.contentType()).isEqualTo("application/pdf");
        assertThat(keys(buckets[1])).doesNotContain(stored.key());
    }

    @Test
    void everyFileGetsANewKey() throws Exception {
        byte[] content = pdf("same");
        String first = storage.save(new ByteArrayInputStream(content), 1024).key();
        String second = storage.save(new ByteArrayInputStream(content), 1024).key();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void deletesAFileAndIgnoresAMissingOne() throws Exception {
        String key = storage.save(new ByteArrayInputStream(pdf("x")), 1024).key();

        storage.delete(key);
        storage.delete(key);

        assertThat(storage.open(key)).isEmpty();
        assertThat(keys(buckets[0])).doesNotContain(key);
    }

    @Test
    void aMissingFileIsEmpty() throws Exception {
        assertThat(storage.open("11111111-1111-1111-1111-111111111111")).isEmpty();
    }

    @Test
    void refusesAFileOverTheLimitAndLeavesNothing() {
        List<String> before = keys(buckets[0]);

        assertThatThrownBy(() -> storage.save(new ByteArrayInputStream(pdf("x".repeat(500))), 100))
                .isInstanceOf(ReceiptStorage.TooLargeException.class);

        assertThat(keys(buckets[0])).isEqualTo(before);
    }

    @Test
    void acceptsAFileOfExactlyTheLimit() throws Exception {
        byte[] content = pdf("y".repeat(50));
        ReceiptStorage.Stored stored = storage.save(new ByteArrayInputStream(content), content.length);
        assertThat(stored.size()).isEqualTo(content.length);
    }

    @Test
    void refusesWhatIsNotAPdfAndLeavesNothing() {
        List<String> before = keys(buckets[0]);

        assertThatThrownBy(() -> storage.save(new ByteArrayInputStream("<html>no</html>".getBytes(StandardCharsets.UTF_8)), 1024))
                .isInstanceOf(ReceiptStorage.InvalidContentException.class);
        assertThatThrownBy(() -> storage.save(new ByteArrayInputStream("%PD".getBytes(StandardCharsets.UTF_8)), 1024))
                .isInstanceOf(ReceiptStorage.InvalidContentException.class);

        assertThat(keys(buckets[0])).isEqualTo(before);
    }

    @Test
    void refusesAnEmptyFile() {
        assertThatThrownBy(() -> storage.save(new ByteArrayInputStream(new byte[0]), 1024)).isInstanceOf(ReceiptStorage.InvalidContentException.class);
    }

    @Test
    void leavesNothingWhenReadingFails() {
        List<String> before = keys(buckets[0]);
        InputStream broken = new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                if (sent < 8) {
                    return "%PDF-1.4".charAt(sent++);
                }
                throw new IOException("the connection broke");
            }
        };

        assertThatThrownBy(() -> storage.save(broken, 1024)).isInstanceOf(IOException.class);

        assertThat(keys(buckets[0])).isEqualTo(before);
    }

    @Test
    void aKeyThatIsNotAUuidNeverReachesTheStorage() {
        for (String bad : new String[] {"../secret", "/etc/passwd", "a/b", "nope", ""}) {
            assertThatThrownBy(() -> storage.open(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        // deleting never throws, a bad key is only logged
        storage.delete("../secret");
    }

    @Test
    void theContentOfTheObjectIsTheContentThatWasSent() throws Exception {
        byte[] content = pdf("целостность");
        String key = storage.save(new ByteArrayInputStream(content), 1024).key();

        byte[] stored = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(buckets[0]).key(key).build()).asByteArray();

        assertThat(stored).isEqualTo(content);
    }
}
