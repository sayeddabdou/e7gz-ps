package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-71: Ban durations across Africa/Cairo DST, deterministically, at fixed instants (the database clock cannot move,
 * and the live E7-71 tests cross a transition only on some dates). Egypt leaves DST at 2026-10-29T21:00Z and enters it
 * at 2027-04-29T22:00Z (PostgreSQL's tz data).
 *
 * <p>The guard computes a Ban's end and duration in the session's zone: activation {@code ends_at = now + duration}
 * with {@code trust.penalty_duration(stage)}, extension {@code duration := ends_at - activated_at} followed by the check
 * {@code ends_at = activated_at + duration}, which is also {@code ck_ban_instance_time_arithmetic}. On the application's
 * UTC sessions that is exact - seven days of 24 hours, one UTC calendar month - and the identity holds across DST; in an
 * Africa/Cairo session it does not, which is why every session is pinned to UTC ({@code SessionTimeZoneIntegrationTest}).
 */
class BanDstArithmeticIntegrationTest extends IntegrationTest {

    private JdbcClient app;

    @Autowired
    void appSessions(DataSource appDataSource) {
        app = JdbcClient.create(appDataSource);
    }

    /** Activation across the autumn change; one month across it; seven days across the spring change. */
    @ParameterizedTest
    @CsvSource({
            "2026-10-25T12:00:00Z, 1, 2026-11-01T12:00:00Z",
            "2026-10-20T12:00:00Z, 2, 2026-11-20T12:00:00Z",
            "2026-10-20T12:00:00Z, 3, 2026-12-20T12:00:00Z",
            "2027-04-26T12:00:00Z, 1, 2027-05-03T12:00:00Z",
            "2027-04-10T12:00:00Z, 2, 2027-05-10T12:00:00Z"})
    void onTheApplicationsSessionsEachStageEndsExactlyAndTheGuardIdentityHolds(Instant activatedAt, int stage,
                                                                               Instant expectedEnd) {
        Instant end = app.sql("SELECT CAST(:at AS timestamptz) + trust.penalty_duration(CAST(:stage AS smallint))")
                .param("at", utc(activatedAt)).param("stage", stage)
                .query(OffsetDateTime.class).single().toInstant();
        assertThat(end).as("seven days of 24 h / one UTC calendar month / two months").isEqualTo(expectedEnd);
        assertThat(identity(app, activatedAt, end)).as("ends_at = activated_at + (ends_at - activated_at)").isTrue();
    }

    /**
     * FR-TRS-22 across the autumn change: a stage-1 Ban activated 2026-10-27 is extended on 2026-10-28 to stage 2 -
     * MAX(old end, now + 1 month) is the month - and the guard's new duration reproduces the new end exactly.
     */
    @Test
    void anExtensionAcrossTheChangeKeepsTheGuardIdentity() {
        Instant activated = Instant.parse("2026-10-27T12:00:00Z");
        Instant extendedAt = Instant.parse("2026-10-28T12:00:00Z");
        Instant newEnd = app.sql("""
                        SELECT GREATEST(CAST(:activated AS timestamptz) + trust.penalty_duration(CAST(1 AS smallint)),
                                        CAST(:now AS timestamptz) + trust.penalty_duration(CAST(2 AS smallint)))""")
                .param("activated", utc(activated)).param("now", utc(extendedAt))
                .query(OffsetDateTime.class).single().toInstant();

        assertThat(newEnd).isEqualTo(Instant.parse("2026-11-28T12:00:00Z"));
        assertThat(identity(app, activated, newEnd)).isTrue();
    }

    /**
     * The defect the pin prevents: in an Africa/Cairo session the same arithmetic follows the wall clock, so a duration
     * read back across the change no longer reproduces the end - the guard would refuse a valid extension
     * ("Ban duration/time arithmetic is invalid"). A non-pooled owner connection, never an application one.
     */
    @Test
    void inACairoSessionTheIdentityBreaksAcrossTheChange() {
        SingleConnectionDataSource cairo = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient session = JdbcClient.create(cairo);
            session.sql("SET TIME ZONE 'Africa/Cairo'").update();
            Instant activated = Instant.parse("2026-10-27T12:00:00Z");
            Instant end = Instant.parse("2026-11-28T12:00:00Z");
            assertThat(identity(session, activated, end)).isFalse();
            assertThat(session.sql("SELECT CAST(:at AS timestamptz) + interval '7 days'")
                    .param("at", utc(Instant.parse("2026-10-25T12:00:00Z")))
                    .query(OffsetDateTime.class).single().toInstant())
                    .as("seven Cairo wall-clock days are 169 hours across the change")
                    .isEqualTo(Instant.parse("2026-11-01T13:00:00Z"));
        } finally {
            cairo.destroy();
        }
    }

    /** FR-TRS-23: in force on [activatedAt, endsAt) - expired exactly at endsAt, not a microsecond before. */
    @Test
    void theBanExpiresExactlyAtItsEnd() {
        Instant end = Instant.parse("2026-11-01T12:00:00Z");
        assertThat(BanExpiryHandler.due(end.minus(Duration.ofNanos(1_000)), end)).isFalse();
        assertThat(BanExpiryHandler.due(end, end)).isTrue();
        assertThat(BanExpiryHandler.due(end.plusSeconds(1), end)).isTrue();
    }

    /** The guard's extension check: the duration it stores reproduces the end. */
    private static boolean identity(JdbcClient session, Instant activatedAt, Instant end) {
        return Boolean.TRUE.equals(session.sql("""
                        SELECT CAST(:a AS timestamptz) + (CAST(:e AS timestamptz) - CAST(:a AS timestamptz))
                               = CAST(:e AS timestamptz)""")
                .param("a", utc(activatedAt)).param("e", utc(end)).query(Boolean.class).single());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
