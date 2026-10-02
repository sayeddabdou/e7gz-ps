package ps.e7gz.venue.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The S3 adapter (AWS SDK v2; SeaweedFS locally, arch 12 §2). Path-style addressing, so one host serves every bucket.
 * Signed links use {@link ObjectStoreProperties#publicEndpoint()} when the browser reaches storage on another address.
 */
@Component
class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);

    private final ObjectStoreProperties properties;
    private final S3Client client;
    private final S3Presigner presigner;

    S3ObjectStore(ObjectStoreProperties properties) {
        this.properties = properties;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        Region region = Region.of(properties.region());
        this.client = S3Client.builder().endpointOverride(properties.endpoint()).region(region)
                .credentialsProvider(credentials).serviceConfiguration(pathStyle).build();
        this.presigner = S3Presigner.builder().endpointOverride(properties.signingEndpoint()).region(region)
                .credentialsProvider(credentials).serviceConfiguration(pathStyle).build();
    }

    @Override
    public URI signedPut(String key, String contentType, long sizeBytes, Duration ttl) {
        return uri(presigner.presignPutObject(p -> p.signatureDuration(ttl).putObjectRequest(r -> r
                .bucket(properties.bucket()).key(key).contentType(contentType).contentLength(sizeBytes))).url().toString());
    }

    @Override
    public URI signedGet(String key, Duration ttl) {
        return uri(presigner.presignGetObject(p -> p.signatureDuration(ttl)
                .getObjectRequest(r -> r.bucket(properties.bucket()).key(key))).url().toString());
    }

    @Override
    public Optional<StoredObject> head(String key) {
        try {
            HeadObjectResponse head = client.headObject(r -> r.bucket(properties.bucket()).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            return Optional.of(client.getObjectAsBytes(r -> r.bucket(properties.bucket()).key(key)).asByteArray());
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        }
    }

    @Override
    public void write(String key, byte[] bytes, String contentType) {
        client.putObject(r -> r.bucket(properties.bucket()).key(key).contentType(contentType),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(r -> r.bucket(properties.bucket()).key(key));
    }

    /**
     * Local development (PO decision 3 Oct 2026): create the private bucket with a CORS rule for the web apps' origins,
     * so the Venue Dashboard and Platform Admin can PUT and GET with signed links. Never fatal: storage may be down.
     */
    @EventListener(ApplicationReadyEvent.class)
    void createLocalBucket() {
        if (!properties.createBucket()) {
            return;
        }
        try {
            boolean exists = client.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(properties.bucket()));
            if (!exists) {
                client.createBucket(r -> r.bucket(properties.bucket()));
                log.info("Created object storage bucket {}", properties.bucket());
            }
            if (!properties.allowedOrigins().isEmpty()) {
                CORSRule rule = CORSRule.builder().allowedOrigins(properties.allowedOrigins())
                        .allowedMethods(List.of("GET", "PUT", "HEAD")).allowedHeaders(List.of("*"))
                        .exposeHeaders(List.of("ETag")).maxAgeSeconds(3600).build();
                client.putBucketCors(r -> r.bucket(properties.bucket()).corsConfiguration(c -> c.corsRules(rule)));
            }
        } catch (RuntimeException e) {
            log.warn("Could not prepare object storage bucket {}: {}", properties.bucket(), e.toString());
        }
    }

    private static URI uri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The S3 presigner returned an invalid URL", e);
        }
    }
}
