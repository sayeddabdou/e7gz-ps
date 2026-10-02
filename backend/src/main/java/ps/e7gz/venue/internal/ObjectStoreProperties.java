package ps.e7gz.venue.internal;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3-compatible object storage for venue photos ({@code e7gz.object-store.*}, arch 12 §2). Locally SeaweedFS from
 * {@code docker compose}; staging and production buckets and keys come from the secret store (E7-101). The bucket is
 * private: every read and write goes through a signed link.
 *
 * @param endpoint        the S3 API the backend calls
 * @param publicEndpoint  the S3 API as browsers and apps reach it, used in signed links; the endpoint when empty
 * @param region          signing region (SeaweedFS accepts any)
 * @param bucket          the venue photo bucket
 * @param accessKey       access key (secret store; the local default is the docker compose identity)
 * @param secretKey       secret key (secret store)
 * @param createBucket    create the bucket and its CORS rule at start-up when missing: local development only
 *                        (PO decision 3 Oct 2026); E7-101 creates the deployed buckets
 * @param allowedOrigins  browser origins allowed to PUT and GET with signed links (CORS rule of a created bucket)
 */
@ConfigurationProperties("e7gz.object-store")
public record ObjectStoreProperties(
        URI endpoint,
        URI publicEndpoint,
        @DefaultValue("us-east-1") String region,
        String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("false") boolean createBucket,
        @DefaultValue List<String> allowedOrigins) {

    URI signingEndpoint() {
        return publicEndpoint == null || publicEndpoint.toString().isBlank() ? endpoint : publicEndpoint;
    }
}
