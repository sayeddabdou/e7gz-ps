package ps.e7gz.support;

import java.net.URI;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * One SeaweedFS S3 server for the whole test run (E7-23 PR 3), the same image and identity file as {@code docker
 * compose}. Started once, with the test bucket created; stopped by Testcontainers at JVM exit.
 */
public final class ObjectStoreTestContainer {

    public static final String ACCESS_KEY = "e7gz-dev";
    public static final String SECRET_KEY = "e7gz-dev-secret";
    public static final String BUCKET = "e7gz-test-photos";
    private static final int S3_PORT = 8333;

    private static final GenericContainer<?> CONTAINER =
            new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
                    .withCommand("server", "-dir=/data", "-s3", "-s3.port=" + S3_PORT,
                            "-s3.config=/etc/seaweedfs/s3.json", "-ip.bind=0.0.0.0")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(RepoFiles.get("infra/local/seaweedfs/s3.json")),
                            "/etc/seaweedfs/s3.json")
                    .withExposedPorts(S3_PORT)
                    .waitingFor(Wait.forListeningPorts(S3_PORT).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        CONTAINER.start();
        createBucket();
    }

    private ObjectStoreTestContainer() {
    }

    public static URI endpoint() {
        return URI.create("http://%s:%d".formatted(CONTAINER.getHost(), CONTAINER.getMappedPort(S3_PORT)));
    }

    /** A client on the test bucket, for checks the API does not expose (stored files, copies). */
    public static S3Client client() {
        return S3Client.builder().endpointOverride(endpoint()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    /** The S3 port opens before the filer behind it is ready: retry until the bucket exists. */
    private static void createBucket() {
        RuntimeException last = null;
        try (S3Client s3 = client()) {
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
                        s3.createBucket(r -> r.bucket(BUCKET));
                    }
                    return;
                } catch (RuntimeException e) {
                    last = e;
                    pause();
                }
            }
        }
        throw new IllegalStateException("SeaweedFS did not accept the test bucket", last);
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
