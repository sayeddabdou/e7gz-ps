package ps.e7gz.support;

import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

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
    }
}
