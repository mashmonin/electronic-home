package com.example.electronichome.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.SeaweedFsTestContainer;
import com.example.electronichome.storage.ObjectStore;
import com.example.electronichome.storage.StorageProperties;
import com.example.electronichome.storage.StorageUnavailableException;

// The old files of the receipts are copied into the storage once, safely, whatever happens to a single file.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "management.health.objectStorage.enabled=false")
class ReceiptFileMigrationIT {

    private static String[] buckets;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        buckets = SeaweedFsTestContainer.register(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectStore store;

    @Autowired
    StorageProperties storage;

    private final List<Path> directories = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM receipts");
    }

    private Path directory() throws Exception {
        Path directory = Files.createTempDirectory("old-receipts");
        directories.add(directory);
        return directory;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** A receipt record for the month; when {@code writeFile} is true, its file is put into the directory. */
    private String receipt(Path directory, String month, byte[] content, boolean writeFile, String recordedSha, Long recordedSize) throws Exception {
        String key = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO receipts (account_id, period_month, file_key, size_bytes, sha256)
                SELECT id, ?::date, ?, ?, ? FROM accounts WHERE number = '000000000001'""",
                month + "-01", key, recordedSize != null ? recordedSize : content.length, recordedSha != null ? recordedSha : sha256(content));
        if (writeFile) {
            Files.write(directory.resolve(key), content);
        }
        return key;
    }

    private ReceiptFileMigration migration(String sourcePath) {
        return migration(store, sourcePath);
    }

    private ReceiptFileMigration migration(ObjectStore objectStore, String sourcePath) {
        return new ReceiptFileMigration(jdbcClient, objectStore, storage, new ReceiptProperties(null, "", new ReceiptProperties.Migration(sourcePath)));
    }

    private static byte[] pdf(String text) {
        return ("%PDF-1.4\n" + text + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void copiesTheFilesOfAllTheReceiptsUnderTheSameKeysAndKeepsTheSources() throws Exception {
        Path directory = directory();
        byte[] first = pdf("первая");
        byte[] second = pdf("вторая");
        String key1 = receipt(directory, "2026-09", first, true, null, null);
        String key2 = receipt(directory, "2026-10", second, true, null, null);

        ReceiptFileMigration.Result result = migration(directory.toString()).migrate();

        assertThat(result).isEqualTo(new ReceiptFileMigration.Result(2, 0, 0, 0));
        assertThat(store.open(storage.receiptsBucket(), key1).orElseThrow().readAllBytes()).isEqualTo(first);
        assertThat(store.open(storage.receiptsBucket(), key2).orElseThrow().readAllBytes()).isEqualTo(second);
        assertThat(Files.readAllBytes(directory.resolve(key1))).isEqualTo(first);
        assertThat(Files.readAllBytes(directory.resolve(key2))).isEqualTo(second);
    }

    @Test
    void runningAgainCopiesNothingAndChangesNothing() throws Exception {
        Path directory = directory();
        String key = receipt(directory, "2026-09", pdf("x"), true, null, null);
        ReceiptFileMigration migration = migration(directory.toString());
        migration.migrate();
        // the source changes after the first copy: the object that is there must not be overwritten
        Files.write(directory.resolve(key), pdf("changed"));

        ReceiptFileMigration.Result again = migration.migrate();

        assertThat(again).isEqualTo(new ReceiptFileMigration.Result(0, 1, 0, 0));
        assertThat(new String(store.open(storage.receiptsBucket(), key).orElseThrow().readAllBytes(), StandardCharsets.UTF_8)).contains("x");
    }

    @Test
    void aReceiptWithoutASourceFileIsReportedAndTheOthersAreCopied() throws Exception {
        Path directory = directory();
        String lost = receipt(directory, "2026-08", pdf("lost"), false, null, null);
        String kept = receipt(directory, "2026-09", pdf("kept"), true, null, null);

        ReceiptFileMigration.Result result = migration(directory.toString()).migrate();

        assertThat(result).isEqualTo(new ReceiptFileMigration.Result(1, 0, 1, 0));
        assertThat(store.exists(storage.receiptsBucket(), lost)).isFalse();
        assertThat(store.exists(storage.receiptsBucket(), kept)).isTrue();
    }

    @Test
    void aFileThatDoesNotMatchItsRecordIsNotCopied() throws Exception {
        Path directory = directory();
        String wrongHash = receipt(directory, "2026-07", pdf("a"), true, "0".repeat(64), null);
        String wrongSize = receipt(directory, "2026-08", pdf("b"), true, null, 9999L);
        String good = receipt(directory, "2026-09", pdf("c"), true, null, null);

        ReceiptFileMigration.Result result = migration(directory.toString()).migrate();

        assertThat(result).isEqualTo(new ReceiptFileMigration.Result(1, 0, 0, 2));
        assertThat(store.exists(storage.receiptsBucket(), wrongHash)).isFalse();
        assertThat(store.exists(storage.receiptsBucket(), wrongSize)).isFalse();
        assertThat(store.exists(storage.receiptsBucket(), good)).isTrue();
    }

    @Test
    void aKeyThatLeavesTheDirectoryIsNeverRead() throws Exception {
        Path directory = directory();
        Path outside = directory.getParent().resolve("outside-secret.pdf");
        Files.write(outside, pdf("secret"));
        jdbc.update("""
                INSERT INTO receipts (account_id, period_month, file_key, size_bytes, sha256)
                SELECT id, DATE '2026-09-01', '../outside-secret.pdf', 10, 'abc' FROM accounts WHERE number = '000000000001'""");

        ReceiptFileMigration.Result result = migration(directory.toString()).migrate();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.copied()).isZero();
        Files.deleteIfExists(outside);
    }

    @Test
    void doesNothingWithoutASourceOrWithAMissingDirectory() throws Exception {
        receipt(directory(), "2026-09", pdf("x"), false, null, null);
        ObjectStore refusing = new ObjectStore() {
            @Override
            public void put(String bucket, String key, Path file, long size, String contentType) {
                throw new AssertionError("the storage must not be touched");
            }

            @Override
            public Optional<java.io.InputStream> open(String bucket, String key) {
                throw new AssertionError("the storage must not be touched");
            }

            @Override
            public boolean exists(String bucket, String key) {
                throw new AssertionError("the storage must not be touched");
            }

            @Override
            public void delete(String bucket, String key) {
                throw new AssertionError("the storage must not be touched");
            }

            @Override
            public void ensureBucket(String bucket) {
                throw new AssertionError("the storage must not be touched");
            }
        };

        for (String source : new String[] {null, "", "  ", "/no/such/directory"}) {
            assertThat(migration(refusing, source).migrate()).isEqualTo(new ReceiptFileMigration.Result(0, 0, 0, 0));
        }
    }

    @Test
    void doesNotTouchTheStorageWhenThereAreNoReceipts() throws Exception {
        Path directory = directory();
        ObjectStore refusing = new FailingStore();

        assertThat(migration(refusing, directory.toString()).migrate()).isEqualTo(new ReceiptFileMigration.Result(0, 0, 0, 0));
    }

    @Test
    void anUnavailableStorageNeverStopsTheStart() throws Exception {
        Path directory = directory();
        receipt(directory, "2026-09", pdf("x"), true, null, null);

        migration(new FailingStore(), directory.toString()).run(null);
        ReceiptFileMigration.Result result = migration(new FailingStore(), directory.toString()).migrate();

        assertThat(result).isEqualTo(new ReceiptFileMigration.Result(0, 0, 0, 1));
    }

    @Test
    void theMigrationIsPartOfTheApplication(@Autowired List<org.springframework.boot.ApplicationRunner> runners) {
        assertThat(runners).anyMatch(runner -> runner instanceof ReceiptFileMigration);
    }

    /** A storage that cannot be reached. */
    private static final class FailingStore implements ObjectStore {
        @Override
        public void put(String bucket, String key, Path file, long size, String contentType) {
            throw new StorageUnavailableException("down", null);
        }

        @Override
        public Optional<java.io.InputStream> open(String bucket, String key) {
            throw new StorageUnavailableException("down", null);
        }

        @Override
        public boolean exists(String bucket, String key) {
            throw new StorageUnavailableException("down", null);
        }

        @Override
        public void delete(String bucket, String key) {
            throw new StorageUnavailableException("down", null);
        }

        @Override
        public void ensureBucket(String bucket) {
            throw new StorageUnavailableException("down", null);
        }
    }
}
