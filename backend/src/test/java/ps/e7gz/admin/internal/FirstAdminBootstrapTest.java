package ps.e7gz.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-23, Sprint 0 review S-5: the first-admin bootstrap, on its own freshly migrated database so that
 * {@code iam.admin_account} starts empty (the shared test database already holds other tests' admins). The context
 * starts without a web server, like the {@code admin-bootstrap} profile, so web-only configuration must stay out of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstAdminBootstrapTest {

    private static final String DATABASE = "e7gz_first_admin";
    private static final String PASSWORD = "correct-horse-7";

    static {
        createEmptyDatabase();
    }

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
        registry.add("e7gz.security.jwt.secret", () -> IntegrationTest.TEST_JWT_SECRET);
        registry.add("e7gz.identity.otp-payload-key", () -> IntegrationTest.TEST_OTP_PAYLOAD_KEY);
        registry.add("e7gz.idempotency.fingerprint-key", () -> IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY);
    }

    @Autowired
    FirstAdminBootstrap bootstrap;

    @Autowired
    PasswordEncoder passwordEncoder;

    private final JdbcClient owner = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Test
    @Order(1)
    void invalidInputIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> bootstrap.create("not-an-email", "First Admin", PASSWORD.toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap.create("owner@example.com", " ", PASSWORD.toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
        char[] tooShort = "abc123".toCharArray();
        assertThatThrownBy(() -> bootstrap.create("owner@example.com", "First Admin", tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("abc123");
        assertThat(tooShort).containsOnly('\0');
        assertThatThrownBy(() -> bootstrap.create("owner@example.com", "First Admin", "no-digits-here".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(adminCount()).isZero();
    }

    /** Two operators racing: the advisory lock serialises them, exactly one admin is created. */
    @Test
    @Order(2)
    void concurrentBootstrapsCreateExactlyOneAuditedAdmin() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String email = "owner" + i + "@example.com";
                Callable<UUID> task = () -> {
                    start.await();
                    return bootstrap.create(email, "First Admin", PASSWORD.toCharArray());
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            List<UUID> created = new ArrayList<>();
            List<Throwable> refused = new ArrayList<>();
            for (Future<UUID> result : results) {
                try {
                    created.add(result.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    refused.add(e.getCause());
                }
            }
            assertThat(created).hasSize(1);
            assertThat(refused).singleElement()
                    .isInstanceOf(CreateFirstAdminCommand.FirstAdminAlreadyExistsException.class);

            UUID id = created.getFirst();
            Map<String, Object> row = owner.sql(
                            "SELECT password_hash, full_name, is_active FROM iam.admin_account WHERE id = :id")
                    .param("id", id).query().singleRow();
            assertThat(row.get("full_name")).isEqualTo("First Admin");
            assertThat(row.get("is_active")).isEqualTo(true);
            String hash = (String) row.get("password_hash");
            assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
            assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();

            Map<String, Object> audit = owner.sql("""
                            SELECT actor_type, actor_id, subject_type, payload
                              FROM audit.audit_event WHERE action = 'FIRST_ADMIN_CREATED' AND subject_id = :id""")
                    .param("id", id).query().singleRow();
            assertThat(audit.get("actor_type")).isEqualTo("SYSTEM");
            assertThat(audit.get("actor_id")).isNull();
            assertThat(audit.get("subject_type")).isEqualTo("ADMIN");
            assertThat(String.valueOf(audit.get("payload"))).doesNotContain("@example.com").doesNotContain(PASSWORD);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Order(3)
    void aSecondBootstrapIsRefusedOnceAnAdminExists() {
        long before = adminCount();
        assertThat(before).isEqualTo(1);
        assertThatThrownBy(() -> bootstrap.create("late@example.com", "Late Admin", PASSWORD.toCharArray()))
                .isInstanceOf(CreateFirstAdminCommand.FirstAdminAlreadyExistsException.class);
        assertThat(adminCount()).isEqualTo(before);
    }

    private long adminCount() {
        return owner.sql("SELECT count(*) FROM iam.admin_account").query(Long.class).single();
    }

    private static void createEmptyDatabase() {
        try {
            for (String sql : List.of("DROP DATABASE IF EXISTS " + DATABASE,
                    "CREATE DATABASE " + DATABASE + " TEMPLATE template0")) {
                ExecResult r = PostgresTestContainer.get().execInContainer(
                        "psql", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql);
                if (r.getExitCode() != 0) {
                    throw new IllegalStateException(sql + ": " + r.getStderr());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not create " + DATABASE, e);
        }
    }
}
