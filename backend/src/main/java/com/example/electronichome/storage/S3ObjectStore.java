package com.example.electronichome.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** The object store on an S3-compatible server. Buckets are created on first use and remembered. */
@Component
public class S3ObjectStore implements ObjectStore {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final S3Client client;
    private final ConcurrentMap<String, Boolean> knownBuckets = new ConcurrentHashMap<>();

    S3ObjectStore(S3Client client) {
        this.client = client;
    }

    @Override
    public void put(String bucket, String key, Path file, long size, String contentType) {
        ObjectKeys.require(key);
        ensureBucket(bucket);
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(size).build(),
                    RequestBody.fromFile(file));
        } catch (SdkException e) {
            throw unavailable("write", e);
        }
    }

    @Override
    public Optional<InputStream> open(String bucket, String key) {
        ObjectKeys.require(key);
        try {
            return Optional.of(client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()));
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("read", e);
        } catch (SdkException e) {
            throw unavailable("read", e);
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        ObjectKeys.require(key);
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw unavailable("look up", e);
        } catch (SdkException e) {
            throw unavailable("look up", e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        ObjectKeys.require(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            // nothing to delete
        } catch (SdkException e) {
            throw unavailable("delete", e);
        }
    }

    @Override
    public void ensureBucket(String bucket) {
        if (!knownBuckets.containsKey(bucket)) {
            check(bucket);
        }
    }

    @Override
    public void check(String bucket) {
        HeadBucketRequest head = HeadBucketRequest.builder()
                .bucket(bucket)
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(PROBE_TIMEOUT).apiCallAttemptTimeout(PROBE_TIMEOUT))
                .build();
        try {
            try {
                client.headBucket(head);
            } catch (NoSuchBucketException e) {
                create(bucket);
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                create(bucket);
            }
        } catch (SdkException e) {
            knownBuckets.remove(bucket);
            throw unavailable("prepare the bucket of", e);
        }
        knownBuckets.put(bucket, Boolean.TRUE);
    }

    private void create(String bucket) {
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            // somebody else created it a moment ago: that is what we wanted
        }
    }

    private static StorageUnavailableException unavailable(String action, SdkException cause) {
        return new StorageUnavailableException("Cannot " + action + " the object storage", cause);
    }
}
