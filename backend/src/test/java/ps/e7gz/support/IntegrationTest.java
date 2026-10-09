package ps.e7gz.support;

import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        String url = PostgresTestContainer.jdbcUrl(DATABASE);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> MIGRATOR);
        registry.add("spring.flyway.password", () -> MIGRATOR);
        registry.add("e7gz.datasource.app.jdbc-url", () -> url);
        registry.add("e7gz.datasource.app.username", () -> APP_RUNTIME);
        registry.add("e7gz.datasource.app.password", () -> APP_RUNTIME);
        registry.add("e7gz.datasource.admin.jdbc-url", () -> url);
        registry.add("e7gz.datasource.admin.username", () -> ADMIN_RUNTIME);
        registry.add("e7gz.datasource.admin.password", () -> ADMIN_RUNTIME);
        registry.add("e7gz.security.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("e7gz.identity.otp-payload-key", () -> TEST_OTP_PAYLOAD_KEY);
        registry.add("e7gz.security.cors.allowed-origins", () -> DASHBOARD_ORIGIN + "," + ADMIN_ORIGIN);
        // E7-23 PR 3: venue photos in a real S3 server (SeaweedFS, as in docker compose).
        registry.add("e7gz.object-store.endpoint", () -> ObjectStoreTestContainer.endpoint().toString());
        registry.add("e7gz.object-store.bucket", () -> ObjectStoreTestContainer.BUCKET);
        registry.add("e7gz.object-store.access-key", () -> ObjectStoreTestContainer.ACCESS_KEY);
        registry.add("e7gz.object-store.secret-key", () -> ObjectStoreTestContainer.SECRET_KEY);
        registry.add("e7gz.object-store.create-bucket", () -> "false");
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
}
