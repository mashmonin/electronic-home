package com.example.electronichome.receipt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Keeps the files in one directory. A file is written to a temporary file next to its place and moved there
 * atomically, so nobody can read half of it; the key is a UUID made here and never taken from the client.
 */
@Component
class FileSystemReceiptStorage implements ReceiptStorage {

    private static final Logger log = LoggerFactory.getLogger(FileSystemReceiptStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final String TEMP_SUFFIX = ".tmp";
    private static final Duration TEMP_FILE_LIFETIME = Duration.ofDays(1);

    private final Path directory;

    FileSystemReceiptStorage(ReceiptProperties properties) {
        this.directory = Path.of(properties.storage().path()).toAbsolutePath().normalize();
    }

    /** Creates the directory and removes temporary files that a crashed process left. */
    @PostConstruct
    void prepare() throws IOException {
        Files.createDirectories(directory);
        Instant limit = Instant.now().minus(TEMP_FILE_LIFETIME);
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(file -> file.getFileName().toString().endsWith(TEMP_SUFFIX)).forEach(file -> {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(limit)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    log.warn("Cannot remove the old temporary file {}", file, e);
                }
            });
        }
    }

    @Override
    public Stored save(InputStream content, long maxBytes) throws IOException {
        Files.createDirectories(directory);
        String key = UUID.randomUUID().toString();
        Path temp = directory.resolve(key + TEMP_SUFFIX);
        Path target = resolve(key);
        try {
            MessageDigest digest = sha256();
            byte[] header = new byte[PDF_HEADER.length];
            long total = 0;
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    if (total + read > maxBytes) {
                        throw new TooLargeException(maxBytes);
                    }
                    for (int i = 0; i < read && total + i < header.length; i++) {
                        header[(int) total + i] = buffer[i];
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
            if (total == 0) {
                throw new InvalidContentException("The file is empty");
            }
            if (total < PDF_HEADER.length || !java.util.Arrays.equals(header, PDF_HEADER)) {
                throw new InvalidContentException("The file is not a PDF");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return new Stored(key, total, HexFormat.of().formatHex(digest.digest()));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public Optional<InputStream> open(String key) throws IOException {
        Path file = resolve(key);
        return Files.isRegularFile(file) ? Optional.of(Files.newInputStream(file)) : Optional.empty();
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Cannot delete the receipt file {}", key, e);
        }
    }

    // The only way from a key to a path: the key has the shape of a UUID and the path stays inside the directory.
    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a receipt file key");
        }
        Path file = directory.resolve(key).normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("Not a receipt file key");
        }
        return file;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
