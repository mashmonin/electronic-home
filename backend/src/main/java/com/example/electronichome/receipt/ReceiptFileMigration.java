package com.example.electronichome.receipt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.example.electronichome.storage.ObjectKeys;
import com.example.electronichome.storage.ObjectStore;
import com.example.electronichome.storage.StorageProperties;

/**
 * Copies the receipts that were kept in a directory before the object storage into the bucket, under the same key. It is
 * safe to run at every start: what is in the bucket already is left alone, a file that does not match its record (size,
 * SHA-256) is not copied, the source files are never changed or deleted, and a failure of one file or of the whole
 * migration is only logged, so it never stops the application from starting.
 */
@Component
class ReceiptFileMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReceiptFileMigration.class);

    /** What happened to the receipts: copied now, already in the bucket, without a source file, not copied because of an error. */
    record Result(int copied, int alreadyThere, int missing, int failed) {
    }

    private record Row(String key, long size, String sha256) {
    }

    private final JdbcClient jdbc;
    private final ObjectStore store;
    private final String bucket;
    private final String sourcePath;

    ReceiptFileMigration(JdbcClient jdbc, ObjectStore store, StorageProperties storage, ReceiptProperties receipts) {
        this.jdbc = jdbc;
        this.store = store;
        this.bucket = storage.receiptsBucket();
        this.sourcePath = receipts.migration().sourcePath();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (RuntimeException e) {
            log.error("The receipts were not copied into the object storage; the next start will try again", e);
        }
    }

    /** Does the copying; nothing happens (and the storage is not touched) when there is no source or no receipt. */
    Result migrate() {
        if (sourcePath == null || sourcePath.isBlank()) {
            return new Result(0, 0, 0, 0);
        }
        Path directory = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            log.info("No directory of old receipts at {}: nothing to copy", directory);
            return new Result(0, 0, 0, 0);
        }
        List<Row> rows = jdbc.sql("SELECT file_key, size_bytes, sha256 FROM receipts ORDER BY id")
                .query((rs, row) -> new Row(rs.getString("file_key"), rs.getLong("size_bytes"), rs.getString("sha256")))
                .list();
        int copied = 0;
        int alreadyThere = 0;
        int missing = 0;
        int failed = 0;
        for (Row row : rows) {
            try {
                ObjectKeys.require(row.key());
                if (store.exists(bucket, row.key())) {
                    alreadyThere++;
                    continue;
                }
                Path source = directory.resolve(row.key()).normalize();
                if (!source.startsWith(directory) || !Files.isRegularFile(source)) {
                    log.warn("The file of the receipt {} is neither in the object storage nor in {}", row.key(), directory);
                    missing++;
                    continue;
                }
                if (!matches(source, row)) {
                    log.error("The file {} does not match its record (size or SHA-256): it is not copied", source);
                    failed++;
                    continue;
                }
                store.put(bucket, row.key(), source, row.size(), "application/pdf");
                copied++;
            } catch (RuntimeException | IOException e) {
                log.error("The receipt {} was not copied into the object storage", row.key(), e);
                failed++;
            }
        }
        log.info("Receipts copied into the object storage: copied {}, already there {}, no source file {}, errors {}",
                copied, alreadyThere, missing, failed);
        return new Result(copied, alreadyThere, missing, failed);
    }

    private static boolean matches(Path file, Row row) throws IOException {
        if (Files.size(file) != row.size()) {
            return false;
        }
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(row.sha256());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
