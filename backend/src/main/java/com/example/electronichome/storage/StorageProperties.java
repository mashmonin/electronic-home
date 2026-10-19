package com.example.electronichome.storage;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection to the object storage with the S3 API ({@code storage.s3.*}).
 *
 * @param endpoint          the address of the S3 API, for example {@code http://s3:8333}
 * @param region            the region the requests are signed for; the storage does not check it
 * @param accessKey         the access key; no default in prod
 * @param secretKey         the secret key; at least 16 characters, no default in prod
 * @param receiptsBucket    the bucket of the PDF files of the receipts
 * @param attachmentsBucket the bucket of the files attached to the messages of the requests
 */
@ConfigurationProperties("storage.s3")
public record StorageProperties(
        String endpoint,
        @DefaultValue("us-east-1") String region,
        String accessKey,
        String secretKey,
        @DefaultValue("electronic-home-receipts") String receiptsBucket,
        @DefaultValue("electronic-home-attachments") String attachmentsBucket) {

    static final int MIN_SECRET_LENGTH = 16;
    // the keys are written into a JSON file of the storage container, so they must not need escaping
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.+=/-]+");

    public StorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("storage.s3.endpoint (S3_ENDPOINT) is required");
        }
        if (accessKey == null || !KEY.matcher(accessKey).matches()) {
            throw new IllegalArgumentException("storage.s3.access-key (S3_ACCESS_KEY) is required: letters, digits and - _ . + = / only");
        }
        if (secretKey == null || secretKey.length() < MIN_SECRET_LENGTH || !KEY.matcher(secretKey).matches()) {
            throw new IllegalArgumentException(
                    "storage.s3.secret-key (S3_SECRET_KEY) must be at least " + MIN_SECRET_LENGTH
                            + " characters: letters, digits and - _ . + = / only");
        }
        if (receiptsBucket.equals(attachmentsBucket)) {
            throw new IllegalArgumentException("The buckets of the receipts and of the attachments must differ");
        }
    }
}
