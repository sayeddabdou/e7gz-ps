package ps.e7gz.support.fixtures;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.support.PostgresTestContainer;

/**
 * TEST-ONLY (E7-66 PR 2): a Customer's trust projection and Ban Instance written directly, past the trust guards and
 * foreign keys ({@code session_replication_role = replica}), for states no command produces yet - a ban (E7-71), a
 * Verified Player (E7-72) or completed attended bookings (E7-61). Every CHECK still applies, so each row has the shape
 * the database accepts: the threshold of the stage, a counter below it, QUALIFIED exactly from 10 completed bookings.
 * Read-only tests only: the planted rows have no trust events behind them, so no trust command may run on that
 * Customer afterwards.
 */
public final class TrustStates {

    private TrustStates() {
    }

    /** The projection {@code trust.refresh_projection} would leave, with these values. */
    public static void projection(UUID userId, int consecutiveNoShows, int penaltyStage, int completedBookings,
                                  String verifiedPlayerStatus) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO trust.trust_projection (user_id, consecutive_no_shows, penalty_stage,
                                                            current_threshold, completed_attended_count,
                                                            vp_qualification, vp_operational_status, recomputed_at)
                        VALUES (:user, :counter, :stage, trust.penalty_threshold(CAST(:stage AS smallint)), :completed,
                                CASE WHEN :completed >= 10 THEN 'QUALIFIED' ELSE 'NOT_QUALIFIED' END, :status,
                                clock_timestamp())""")
                .param("user", userId).param("counter", consecutiveNoShows).param("stage", penaltyStage)
                .param("completed", completedBookings).param("status", verifiedPlayerStatus).update());
    }

    /** A Ban Instance of the Customer; returns its id. */
    public static UUID ban(UUID userId, int penaltyStage, String status, Instant activatedAt, Instant endsAt) {
        UUID id = UUID.randomUUID();
        withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO trust.ban_instance (id, user_id, qualification_id, penalty_stage, activated_at,
                                                        ends_at, duration, status, activation_reason,
                                                        latest_qualification_id, last_command_at)
                        VALUES (:id, :user, :qualification, :stage, :activated, :ends, make_interval(secs => :seconds),
                                :status, 'E7-66 test', :qualification, :activated)""")
                .param("id", id).param("user", userId).param("qualification", UUID.randomUUID())
                .param("stage", penaltyStage).param("status", status)
                .param("activated", utc(activatedAt)).param("ends", utc(endsAt))
                // exact seconds: a calendar-day interval moves across a Cairo DST change
                // (ck_ban_instance_time_arithmetic)
                .param("seconds", Duration.between(activatedAt, endsAt).toSeconds()).update());
        return id;
    }

    /** The database's clock, to the microsecond it stores. */
    public static Instant dbNow() {
        Instant[] now = new Instant[1];
        withoutTriggers(jdbc -> now[0] = jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single()
                .toInstant());
        return now[0];
    }

    private static void withoutTriggers(Consumer<JdbcClient> writes) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            writes.accept(jdbc);
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
