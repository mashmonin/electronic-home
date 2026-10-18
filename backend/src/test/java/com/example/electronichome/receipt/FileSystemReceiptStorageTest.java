package com.example.electronichome.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class FileSystemReceiptStorageTest {

    @TempDir
    Path directory;

    FileSystemReceiptStorage storage;

    @BeforeEach
    void createStorage() throws IOException {
        storage = new FileSystemReceiptStorage(
                new ReceiptProperties(new ReceiptProperties.Storage(directory.toString()), DataSize.ofMegabytes(5), ""));
        storage.prepare();
    }

    private static InputStream pdf(String text) {
        return new ByteArrayInputStream(("%PDF-1.4\n" + text + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> files() throws IOException {
        try (Stream<Path> list = Files.list(directory)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    void storesAFileUnderAKeyOfItsOwnAndReadsItBack() throws Exception {
        byte[] content = "%PDF-1.4\nhello\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

        ReceiptStorage.Stored stored = storage.save(new ByteArrayInputStream(content), 1024);

        assertThat(stored.key()).matches("[0-9a-f-]{36}");
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(sha256(content));
        assertThat(files()).containsExactly(stored.key());
        try (InputStream in = storage.open(stored.key()).orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void everyFileGetsANewKey() throws Exception {
        String first = storage.save(pdf("a"), 1024).key();
        String second = storage.save(pdf("a"), 1024).key();

        assertThat(first).isNotEqualTo(second);
        assertThat(files()).hasSize(2);
    }

    @Test
    void deletesAFileAndIgnoresAMissingOne() throws Exception {
        String key = storage.save(pdf("a"), 1024).key();

        storage.delete(key);
        storage.delete(key);

        assertThat(files()).isEmpty();
        assertThat(storage.open(key)).isEmpty();
    }

    @Test
    void refusesAFileOverTheLimitAndLeavesNothing() throws Exception {
        assertThatThrownBy(() -> storage.save(pdf("x".repeat(100)), 50)).isInstanceOf(ReceiptStorage.TooLargeException.class);

        assertThat(files()).isEmpty();
    }

    @Test
    void acceptsAFileOfExactlyTheLimit() throws Exception {
        byte[] content = "%PDF-12345".getBytes(StandardCharsets.UTF_8);

        assertThat(storage.save(new ByteArrayInputStream(content), content.length).size()).isEqualTo(content.length);
    }

    @Test
    void refusesWhatIsNotAPdfAndLeavesNothing() throws Exception {
        assertThatThrownBy(() -> storage.save(bytes("<html>not a pdf</html>"), 1024))
                .isInstanceOf(ReceiptStorage.InvalidContentException.class);
        assertThatThrownBy(() -> storage.save(bytes("%PD"), 1024)).isInstanceOf(ReceiptStorage.InvalidContentException.class);
        assertThatThrownBy(() -> storage.save(bytes("  %PDF-1.4"), 1024)).isInstanceOf(ReceiptStorage.InvalidContentException.class);

        assertThat(files()).isEmpty();
    }

    @Test
    void refusesAnEmptyFile() throws Exception {
        assertThatThrownBy(() -> storage.save(bytes(""), 1024)).isInstanceOf(ReceiptStorage.InvalidContentException.class);

        assertThat(files()).isEmpty();
    }

    @Test
    void leavesNothingWhenReadingFails() throws Exception {
        InputStream failing = new InputStream() {
            private int count;

            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (count++ == 0) {
                    byte[] head = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(head, 0, b, off, head.length);
                    return head.length;
                }
                throw new IOException("broken");
            }
        };

        assertThatThrownBy(() -> storage.save(failing, 1024)).isInstanceOf(IOException.class);

        assertThat(files()).isEmpty();
    }

    @Test
    void neverLeavesTheDirectoryWhatTheKeyIs() throws Exception {
        for (String key : List.of("../secret", "..", "/etc/passwd", "a/b", "", "not-a-key", "0000000000000000000000000000000000000",
                "00000000-0000-0000-0000-000000000000/../x", "00000000-0000-0000-0000-00000000000G",
                "00000000-0000-0000-0000-000000000000.tmp", "00000000-0000-0000-0000-00000000000A")) {
            assertThatThrownBy(() -> storage.open(key)).as("open %s", key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(key)).as("delete %s", key).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> storage.open(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotTouchFilesOutsideTheDirectory() throws Exception {
        Path outside = Files.createTempFile("outside", ".txt");
        try {
            assertThatThrownBy(() -> storage.delete("../" + outside.getFileName())).isInstanceOf(IllegalArgumentException.class);
            assertThat(outside).exists();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void removesOldTemporaryFilesWhenItStartsButKeepsFreshOnes() throws Exception {
        Path old = Files.createFile(directory.resolve("00000000-0000-0000-0000-000000000001.tmp"));
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));
        Path fresh = Files.createFile(directory.resolve("00000000-0000-0000-0000-000000000002.tmp"));
        String kept = storage.save(pdf("kept"), 1024).key();

        storage.prepare();

        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(directory.resolve(kept)).exists();
    }

    @Test
    void createsTheDirectoryWhenItIsMissing() throws Exception {
        Path nested = directory.resolve("a/b");
        FileSystemReceiptStorage other = new FileSystemReceiptStorage(
                new ReceiptProperties(new ReceiptProperties.Storage(nested.toString()), DataSize.ofMegabytes(5), ""));

        other.prepare();
        String key = other.save(pdf("x"), 1024).key();

        assertThat(nested.resolve(key)).exists();
    }
}
