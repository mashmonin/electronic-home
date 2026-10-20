package com.example.electronichome.request;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.electronichome.storage.FileTypes;
import com.example.electronichome.storage.ObjectKeys;
import com.example.electronichome.storage.ObjectStore;
import com.example.electronichome.storage.SpooledUpload;
import com.example.electronichome.storage.StorageProperties;

/**
 * Checks the files of a message and puts them into the bucket of the attachments. All the files are checked before the
 * first one is written; when something fails after that, what was written is deleted.
 */
@Component
class AttachmentStorage {

    static final int MAX_FILES = 5;
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorage.class);

    /** A file that is in the bucket: the key, the name for showing, the type given by the server, the size and SHA-256. */
    record Stored(String key, String name, String contentType, long size, String sha256) {
    }

    private record Prepared(SpooledUpload upload, String name, String contentType) {
    }

    private final ObjectStore store;
    private final String bucket;

    AttachmentStorage(ObjectStore store, StorageProperties properties) {
        this.store = store;
        this.bucket = properties.attachmentsBucket();
    }

    /** The parts that carry a file: a form sent without a chosen file has a part with no name and no content. */
    static List<MultipartFile> realFiles(List<MultipartFile> parts) {
        if (parts == null) {
            return List.of();
        }
        return parts.stream()
                .filter(part -> !(part.isEmpty() && (part.getOriginalFilename() == null || part.getOriginalFilename().isBlank())))
                .toList();
    }

    /**
     * Stores the files; the answer to a bad file is 400 (too many files, an empty file or a name), 413 (a file or all of
     * them are too big) or 415 (the type is not allowed or the content is not what the extension says).
     */
    List<Stored> store(List<MultipartFile> files) {
        if (files.size() > MAX_FILES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_FILES + " files in a message");
        }
        List<Prepared> prepared = new ArrayList<>();
        List<Stored> stored = new ArrayList<>();
        try {
            long total = 0;
            for (MultipartFile file : files) {
                String name = FileTypes.cleanName(file.getOriginalFilename());
                if (name == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file has no name");
                }
                String extension = FileTypes.extensionOf(name);
                String contentType = FileTypes.contentTypeFor(extension)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This type of file is not allowed"));
                SpooledUpload upload = spool(file);
                prepared.add(new Prepared(upload, name, contentType));
                if (upload.size() == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
                }
                total += upload.size();
                if (total > MAX_TOTAL_BYTES) {
                    throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "The files are bigger than " + MAX_TOTAL_BYTES + " bytes");
                }
                if (!FileTypes.looksLike(extension, upload.head())) {
                    throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The content is not what the extension says");
                }
            }
            for (Prepared file : prepared) {
                String key = ObjectKeys.newKey();
                store.put(bucket, key, file.upload().file(), file.upload().size(), file.contentType());
                stored.add(new Stored(key, file.name(), file.contentType(), file.upload().size(), file.upload().sha256()));
            }
            return stored;
        } catch (RuntimeException e) {
            deleteQuietly(stored);
            throw e;
        } finally {
            prepared.forEach(file -> file.upload().close());
        }
    }

    Optional<InputStream> open(String key) {
        return store.open(bucket, ObjectKeys.require(key));
    }

    /** Deletes what was written when the message could not be saved; a failure is only logged. */
    void deleteQuietly(List<Stored> files) {
        for (Stored file : files) {
            try {
                store.delete(bucket, file.key());
            } catch (RuntimeException e) {
                log.warn("Cannot delete the attachment object {}", file.key(), e);
            }
        }
    }

    private static SpooledUpload spool(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return SpooledUpload.read(in, MAX_FILE_BYTES);
        } catch (SpooledUpload.TooLargeException e) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "A file is bigger than " + MAX_FILE_BYTES + " bytes");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file cannot be read");
        }
    }
}
