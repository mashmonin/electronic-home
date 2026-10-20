package com.example.electronichome.account;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.electronichome.request.VerificationRequests;
import com.example.electronichome.storage.FileTypes;
import com.example.electronichome.storage.ObjectKeys;
import com.example.electronichome.storage.ObjectStore;
import com.example.electronichome.storage.SpooledUpload;
import com.example.electronichome.storage.StorageProperties;

/**
 * The change of the end of the verification period of a meter with the act that confirms it. The file goes to the
 * bucket of the acts first (an upload), then the change takes it by its key. An owner only leaves a draft and a request
 * for the administrator; the date changes when the administrator confirms the request. An administrator changes it at
 * once. The act that was in force is archived, never deleted.
 */
@Service
class MeterVerificationService {

    private static final Logger log = LoggerFactory.getLogger(MeterVerificationService.class);

    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final Duration UPLOAD_LIFETIME = Duration.ofHours(24);
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final AccountRepository accounts;
    private final WaterMeterRepository meters;
    private final MeterActUploadRepository uploads;
    private final MeterVerificationActRepository acts;
    private final VerificationRequests verificationRequests;
    private final ObjectStore store;
    private final String bucket;
    private final TransactionTemplate transaction;
    private final Clock clock;

    MeterVerificationService(
            AccountRepository accounts,
            WaterMeterRepository meters,
            MeterActUploadRepository uploads,
            MeterVerificationActRepository acts,
            VerificationRequests verificationRequests,
            ObjectStore store,
            StorageProperties properties,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.accounts = accounts;
        this.meters = meters;
        this.uploads = uploads;
        this.acts = acts;
        this.verificationRequests = verificationRequests;
        this.store = store;
        this.bucket = properties.meterActsBucket();
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** Checks the file, puts it into the bucket of the acts and remembers it as an upload of the user for the meter. */
    MeterVerificationResponses.Uploaded upload(String accountNumber, String meterNumber, long userId, MultipartFile file) {
        WaterMeterEntity meter = transaction.execute(status -> findMeter(accountNumber, meterNumber, false));
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The part \"file\" is required");
        }
        String name = FileTypes.cleanName(file.getOriginalFilename());
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file has no name");
        }
        String extension = FileTypes.extensionOf(name);
        if (!FileTypes.ACT_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This type of file is not allowed");
        }
        String contentType = FileTypes.contentTypeFor(extension).orElseThrow();
        try (SpooledUpload upload = spool(file)) {
            if (upload.size() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
            }
            if (!FileTypes.looksLike(extension, upload.head())) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The content is not what the extension says");
            }
            String key = ObjectKeys.newKey();
            store.put(bucket, key, upload.file(), upload.size(), contentType);
            try {
                transaction.executeWithoutResult(status -> uploads.save(new MeterActUploadEntity(
                        key, meter.getId(), userId, name, contentType, upload.size(), upload.sha256(), Instant.now(clock), meter.isTest())));
            } catch (RuntimeException e) {
                deleteQuietly(key);
                throw e;
            }
            return new MeterVerificationResponses.Uploaded(key, name, upload.size());
        }
    }

    /**
     * Changes the date with the act of the upload. For an owner nothing changes in the meter: a draft of the act and a
     * request for the administrator are made. For an administrator the date and the act are in force at once.
     */
    @Transactional
    MeterVerificationResponses.Changed changeDate(
            String accountNumber, String meterNumber, long userId, boolean admin, String rawDate, String actFileKey) {
        LocalDate date = parseDate(rawDate);
        String key = parseKey(actFileKey);
        WaterMeterEntity meter = findMeter(accountNumber, meterNumber, true);
        MeterActUploadEntity upload = uploads.findById(key)
                .filter(candidate -> candidate.getMeterId().equals(meter.getId()) && candidate.getUserId() == userId)
                .filter(candidate -> candidate.getCreatedAt().plus(UPLOAD_LIFETIME).isAfter(Instant.now(clock)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "The act is not uploaded or is not valid any more"));
        uploads.delete(upload);

        MeterVerificationActEntity act = new MeterVerificationActEntity(upload, date, !admin, Instant.now(clock));
        if (admin) {
            putInForce(meter, act);
            return new MeterVerificationResponses.Changed(true, meter.getVerificationDate(), null);
        }
        acts.saveAndFlush(act);
        VerificationRequests.Created request = makeRequest(userId, accountNumber, meter, act);
        act.linkRequest(request.id());
        acts.saveAndFlush(act);
        return new MeterVerificationResponses.Changed(
                false, meter.getVerificationDate(), new MeterVerificationResponses.RequestInfo(request.id(), request.number()));
    }

    /** Puts the draft of the request in force: called when the administrator takes the request into work. */
    @Transactional
    void apply(long requestId) {
        MeterVerificationActEntity act = acts.findByRequestIdAndDraftTrue(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "There is no draft of an act for the request"));
        WaterMeterEntity meter = meters.lockById(act.getMeterId()).orElseThrow();
        putInForce(meter, act);
    }

    // The act in force is archived first (the unique index allows one), then the draft takes its place and the date follows it.
    private void putInForce(WaterMeterEntity meter, MeterVerificationActEntity act) {
        acts.archiveInForce(meter.getId());
        act.putInForce();
        acts.saveAndFlush(act);
        meter.changeVerificationDate(act.getVerificationDate());
    }

    private VerificationRequests.Created makeRequest(long userId, String accountNumber, WaterMeterEntity meter, MeterVerificationActEntity act) {
        Long accountId = accounts.findByNumber(accountNumber).orElseThrow().getId();
        try (InputStream content = store.open(bucket, act.getObjectKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The file of the act is missing in the storage"));
                SpooledUpload copy = SpooledUpload.read(content, MAX_FILE_BYTES)) {
            return verificationRequests.create(userId, accountId, meter.getNumber(),
                    new VerificationRequests.ActFile(act.getFileName(), act.getContentType(), copy.size(), copy.sha256(), copy.file()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy the act", e);
        }
    }

    private WaterMeterEntity findMeter(String accountNumber, String meterNumber, boolean lock) {
        AccountEntity account = accounts.findByNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        WaterMeterEntity found = meters.findByAddressAndNumber(account.getAddress(), meterNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return lock ? meters.lockById(found.getId()).orElseThrow() : found;
    }

    static LocalDate parseDate(String raw) {
        if (raw == null || !DATE.matcher(raw).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date must be YYYY-MM-DD");
        }
        try {
            LocalDate date = LocalDate.parse(raw);
            if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The year must be " + MIN_YEAR + " to " + MAX_YEAR);
            }
            return date;
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a date");
        }
    }

    private static String parseKey(String key) {
        try {
            return ObjectKeys.require(key);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The act_file_key is required");
        }
    }

    private static SpooledUpload spool(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return SpooledUpload.read(in, MAX_FILE_BYTES);
        } catch (SpooledUpload.TooLargeException e) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "The file is bigger than " + MAX_FILE_BYTES + " bytes");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file cannot be read");
        }
    }

    private void deleteQuietly(String key) {
        try {
            store.delete(bucket, key);
        } catch (RuntimeException e) {
            log.warn("Cannot delete the object {} of an act", key, e);
        }
    }

    /** Deletes the uploads that were not used for a day: the object first, then the row; a failure leaves the row for the next run. */
    void deleteExpiredUploads() {
        Instant limit = Instant.now(clock).minus(UPLOAD_LIFETIME);
        for (MeterActUploadEntity upload : transaction.execute(status -> uploads.findByCreatedAtBefore(limit))) {
            try {
                store.delete(bucket, upload.getObjectKey());
                transaction.executeWithoutResult(status -> uploads.findById(upload.getObjectKey()).ifPresent(uploads::delete));
            } catch (RuntimeException e) {
                log.warn("Cannot delete the expired upload {}", upload.getObjectKey(), e);
            }
        }
    }
}
