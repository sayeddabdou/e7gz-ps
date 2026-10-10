package ps.e7gz.support;

import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the Spring context and a real, migrated database.
 * Flyway migrates the shared container once; later contexts find it already migrated.
 */
@SpringBootTest
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        properties().forEach((name, value) -> registry.add(name, () -> value));
    }

    /**
     * Every setting a test context needs: the shared database and object store, and the test-only keys. E7-44: also
     * what a test starts a second application instance with (two API replicas on one database).
     */
    public static Map<String, Object> properties() {
        String url = PostgresTestContainer.jdbcUrl(DATABASE);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.flyway.url", url);
        properties.put("spring.flyway.user", MIGRATOR);
        properties.put("spring.flyway.password", MIGRATOR);
        properties.put("e7gz.datasource.app.jdbc-url", url);
        properties.put("e7gz.datasource.app.username", APP_RUNTIME);
        properties.put("e7gz.datasource.app.password", APP_RUNTIME);
        properties.put("e7gz.datasource.admin.jdbc-url", url);
        properties.put("e7gz.datasource.admin.username", ADMIN_RUNTIME);
        properties.put("e7gz.datasource.admin.password", ADMIN_RUNTIME);
        properties.put("e7gz.security.jwt.secret", TEST_JWT_SECRET);
        properties.put("e7gz.identity.otp-payload-key", TEST_OTP_PAYLOAD_KEY);
        properties.put("e7gz.idempotency.fingerprint-key", TEST_IDEMPOTENCY_FINGERPRINT_KEY);
        properties.put("e7gz.security.cors.allowed-origins", DASHBOARD_ORIGIN + "," + ADMIN_ORIGIN);
        // E7-23 PR 3: venue photos in a real S3 server (SeaweedFS, as in docker compose).
        properties.put("e7gz.object-store.endpoint", ObjectStoreTestContainer.endpoint().toString());
        properties.put("e7gz.object-store.bucket", ObjectStoreTestContainer.BUCKET);
        properties.put("e7gz.object-store.access-key", ObjectStoreTestContainer.ACCESS_KEY);
        properties.put("e7gz.object-store.secret-key", ObjectStoreTestContainer.SECRET_KEY);
        properties.put("e7gz.object-store.create-bucket", "false");
        return properties;
    }

    /** E7-22: the browser origins the tests' Venue Dashboard and Platform Admin run on. */
    public static final String DASHBOARD_ORIGIN = "https://venue.e7gz.test";
    public static final String ADMIN_ORIGIN = "https://admin.e7gz.test";

    /** E7-14: a test-only HS512 key (65 bytes, Base64). Every context needs one; production reads E7GZ_JWT_SECRET. */
    public static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
            "e7gz-test-only-access-token-key-never-used-outside-tests-64bytes!".getBytes(
                    StandardCharsets.US_ASCII));

    /** E7-41: a test-only AES-256 key (32 bytes, Base64) for the sealed OTP; production reads E7GZ_OTP_PAYLOAD_KEY. */
    public static final String TEST_OTP_PAYLOAD_KEY = Base64.getEncoder().encodeToString(
            "e7gz-test-only-otp-payload-key!!".getBytes(StandardCharsets.US_ASCII));

    /** E7-42: a test-only HMAC key (32 bytes, Base64) for idempotency fingerprints; production reads
     * E7GZ_IDEMPOTENCY_FINGERPRINT_KEY. */
    public static final String TEST_IDEMPOTENCY_FINGERPRINT_KEY = Base64.getEncoder().encodeToString(
            "e7gz-test-only-idempotency-key!!".getBytes(StandardCharsets.US_ASCII));
}
