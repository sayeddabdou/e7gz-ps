package ps.e7gz.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.Container.ExecResult;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-28 done-when: the dev and perf seeds work on a fresh database. Each case creates its own empty database in the
 * test container (as {@code docker compose down -v} would leave one), lets the seed migrate and fill it, and checks
 * what is there. The perf case runs the perf seed's code at launch volume to keep {@code check} fast; {@code seedPerf}
 * writes ten times that.
 */
class SeedIntegrationTest {

    @Test
    void theDevSeedFillsAFreshDatabase() throws Exception {
        String url = freshDatabase("seed_dev");

        SeedApplication.Report report = SeedApplication.run(SeedApplication.Mode.DEV, null, connectTo(url));

        assertThat(report.counts()).containsEntry("areas", 2L).containsEntry("venues", 6L)
                .containsEntry("venues ACTIVE", 4L).containsEntry("venue accounts", 6L)
                .containsEntry("resources", 23L).containsEntry("price overrides", 1L)
                .containsEntry("opening templates", 5L).containsEntry("customers", 20L)
                .containsEntry("customers verified", 17L);
        // 4 PlayStation venues × 4 console/mode rows, + billiards ×2, + ping pong ×2 (Cue & Console has none yet).
        assertThat(report.counts()).containsEntry("default prices", 20L);
        assertThat(report.counts().get("operating sessions")).as("about 120 days for each venue with hours")
                .isGreaterThan(5 * 100L);

        JdbcClient db = client(url, MIGRATOR);
        assertThat(db.sql("""
                        SELECT v.marketplace_status FROM venue.venue v WHERE v.name = 'Cue & Console'""")
                .query(String.class).single()).isEqualTo("ONBOARDING");
        assertThat(db.sql("SELECT count(*) FROM iam.admin_account").query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM audit.audit_event").query(Long.class).single())
                .as("written through the audited services").isGreaterThan(30L);
    }

    @Test
    void theDevSeedRefusesADatabaseThatIsNotFresh() throws Exception {
        String url = freshDatabase("seed_twice");
        SeedApplication.run(SeedApplication.Mode.DEV, null, connectTo(url));

        assertThatThrownBy(() -> SeedApplication.run(SeedApplication.Mode.DEV, null, connectTo(url)))
                .hasMessageContaining("fresh database");
    }

    @Test
    void thePerfSeedFillsAFreshDatabase() throws Exception {
        String url = freshDatabase("seed_perf");

        SeedApplication.Report report =
                SeedApplication.run(SeedApplication.Mode.PERF, PerfSeed.Volume.LAUNCH, connectTo(url));

        assertThat(report.counts()).containsEntry("areas", 6L).containsEntry("venues", 15L)
                .containsEntry("venue accounts", 15L).containsEntry("customers", 2_000L);
        assertThat(report.counts().get("resources")).isBetween(15 * 3L, 15 * 14L);
        assertThat(report.counts().get("customers verified")).isBetween(1_700L, 1_900L);
        assertThat(report.counts().get("operating sessions")).isGreaterThan(15 * 100L);
    }

    @Test
    void thePerfVolumeIsTenTimesTheLaunchCity() {
        assertThat(PerfSeed.Volume.PERF).isEqualTo(new PerfSeed.Volume(6, 150, 20_000));
    }

    @Test
    void onlyADatabaseOnThisMachineIsSeeded() {
        SeedGuard.requireLocal("jdbc:postgresql://localhost:5433/e7gz_ps");
        SeedGuard.requireLocal("jdbc:postgresql://127.0.0.1/e7gz_ps?sslmode=disable");
        SeedGuard.requireLocal("jdbc:postgresql://[::1]:5432/e7gz_ps");

        for (String remote : new String[]{"jdbc:postgresql://db.e7gz.ps:5432/e7gz_ps",
                "jdbc:postgresql://localhost.evil.example/e7gz_ps", "jdbc:postgresql://localhost:5432,prod:5432/db",
                "jdbc:postgresql:e7gz_ps", null}) {
            assertThatThrownBy(() -> SeedGuard.requireLocal(remote)).as(remote)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void referralCodesAreUniqueEightCharacterCodes() {
        assertThat(SeedCustomersCommand.referralCode(0)).isEqualTo("SAAAAAAA");
        assertThat(SeedCustomersCommand.referralCode(1)).isEqualTo("SAAAAAAB");
        assertThat(SeedCustomersCommand.referralCode(19_999)).hasSize(8).startsWith("S")
                .isNotEqualTo(SeedCustomersCommand.referralCode(19_998));
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** An empty database in the test container, created like the docker compose one. */
    private static String freshDatabase(String name) throws Exception {
        psql("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        psql("CREATE DATABASE " + name + " TEMPLATE template0 ENCODING 'UTF8'");
        return PostgresTestContainer.jdbcUrl(name);
    }

    private static Map<String, Object> connectTo(String url) {
        return Map.of(
                "spring.flyway.url", url,
                "spring.flyway.user", MIGRATOR,
                "spring.flyway.password", MIGRATOR,
                "e7gz.datasource.app.jdbc-url", url,
                "e7gz.datasource.app.username", APP_RUNTIME,
                "e7gz.datasource.app.password", APP_RUNTIME,
                "e7gz.datasource.admin.jdbc-url", url,
                "e7gz.datasource.admin.username", ADMIN_RUNTIME,
                "e7gz.datasource.admin.password", ADMIN_RUNTIME);
    }

    private static JdbcClient client(String url, String role) {
        return JdbcClient.create(new SimpleDriverDataSource(new Driver(), url, role, role));
    }

    private static void psql(String sql) throws Exception {
        ExecResult r = PostgresTestContainer.get().execInContainer("psql", "-U", "postgres", "-d", "postgres", "-v",
                "ON_ERROR_STOP=1", "-c", sql);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql failed: " + sql + "\n" + r.getStderr());
        }
    }
}
