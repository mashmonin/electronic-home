package com.example.electronichome.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class SpooledUploadTest {

    @Test
    void keepsTheContentTheSizeTheHashAndTheHead() throws Exception {
        byte[] content = "%PDF-1.4 привет".getBytes(StandardCharsets.UTF_8);

        try (SpooledUpload upload = SpooledUpload.read(new ByteArrayInputStream(content), 1000)) {
            assertThat(upload.size()).isEqualTo(content.length);
            assertThat(Files.readAllBytes(upload.file())).isEqualTo(content);
            assertThat(upload.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
            assertThat(upload.head()).isEqualTo(content);
        }
    }

    @Test
    void theHeadIsAtMost8192Bytes() throws Exception {
        byte[] content = new byte[20000];
        content[0] = 1;

        try (SpooledUpload upload = SpooledUpload.read(new ByteArrayInputStream(content), 30000)) {
            assertThat(upload.head()).hasSize(SpooledUpload.HEAD_BYTES);
            assertThat(upload.size()).isEqualTo(20000);
        }
    }

    @Test
    void anEmptyContentIsEmpty() throws Exception {
        try (SpooledUpload upload = SpooledUpload.read(new ByteArrayInputStream(new byte[0]), 10)) {
            assertThat(upload.size()).isZero();
            assertThat(upload.head()).isEmpty();
        }
    }

    @Test
    void exactlyTheLimitIsAcceptedOneByteMoreIsNot() throws Exception {
        try (SpooledUpload upload = SpooledUpload.read(new ByteArrayInputStream(new byte[100]), 100)) {
            assertThat(upload.size()).isEqualTo(100);
        }
        assertThatThrownBy(() -> SpooledUpload.read(new ByteArrayInputStream(new byte[101]), 100))
                .isInstanceOf(SpooledUpload.TooLargeException.class);
    }

    @Test
    void closingDeletesTheTemporaryFile() throws Exception {
        SpooledUpload upload = SpooledUpload.read(new ByteArrayInputStream(new byte[10]), 100);
        Path file = upload.file();
        assertThat(file).exists();

        upload.close();

        assertThat(file).doesNotExist();
    }

    @Test
    void aTooLargeContentLeavesNoFileBehind() throws Exception {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        long before;
        try (var files = Files.list(temp)) {
            before = files.filter(f -> f.getFileName().toString().startsWith("upload-")).count();
        }

        assertThatThrownBy(() -> SpooledUpload.read(new ByteArrayInputStream(new byte[500]), 100)).isInstanceOf(SpooledUpload.TooLargeException.class);

        try (var files = Files.list(temp)) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith("upload-")).count()).isEqualTo(before);
        }
    }
}
