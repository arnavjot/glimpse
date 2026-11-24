package com.arnavjot.glimpse.server.uploads;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Original uploads in S3-compatible storage (SeaweedFS locally, Amazon S3 in production:
 * same code). Phones upload straight to storage with a pre-signed PUT URL, so video bytes
 * never pass through the API servers.
 *
 * <p>Two endpoints: the server talks to storage on the internal network, while upload URLs
 * are signed for the address phones can reach (the host is part of the signature).
 */
@Component
public class ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private volatile boolean bucketReady;

    public ObjectStorage(StorageProperties p) {
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
        // Path-style (http://host/bucket/key): what SeaweedFS and MinIO expect locally.
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(p.endpoint()))
                .region(Region.of(p.region()))
                .credentialsProvider(creds)
                .serviceConfiguration(pathStyle)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(p.publicEndpoint()))
                .region(Region.of(p.region()))
                .credentialsProvider(creds)
                .serviceConfiguration(pathStyle)
                .build();
        this.bucket = p.bucket();
    }

    /**
     * A URL the phone can PUT the file to for {@code ttl}. Content-Type and Content-Length
     * are part of the signature: storage refuses a different type or any other size, so the
     * size limit is enforced at upload time, not only checked afterwards.
     */
    public URL presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
        ensureBucket();
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(contentType).contentLength(sizeBytes).build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl).putObjectRequest(put).build()).url();
    }

    /** Size of a stored object, or empty if it isn't there (yet). */
    public OptionalLong size(String key) {
        try {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(key));
            return OptionalLong.of(head.contentLength());
        } catch (NoSuchKeyException e) {
            return OptionalLong.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return OptionalLong.empty();
            throw e;
        }
    }

    /** Removes an object. Deleting one that isn't there is fine (S3 semantics): idempotent. */
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    public void download(String key, Path target) {
        s3.getObject(b -> b.bucket(bucket).key(key), ResponseTransformer.toFile(target));
    }

    /** Creates the bucket the first time it's needed (storage may start after the API). */
    void ensureBucket() {
        if (bucketReady) return;
        synchronized (this) {
            if (bucketReady) return;
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) throw e;
                s3.createBucket(b -> b.bucket(bucket));
                log.info("Created storage bucket {}", bucket);
            }
            bucketReady = true;
        }
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }
}
