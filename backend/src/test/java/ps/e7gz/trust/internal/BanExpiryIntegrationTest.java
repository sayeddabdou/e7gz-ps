package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.tournament.internal.TournamentFlows;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * T10 Ban expiry (arch 08 section 1; FR-TRS-23; E7-71), fired by the real deadline worker with the real handler on a
 * Ban activated by three real no-shows. A seven-day Ban cannot be waited for, so the expiry case moves the Ban's own
 * activation facts eight days back together (the database clock cannot move); nothing else is touched.
 */
class BanExpiryIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    DataSource appDataSource;

    @Autowired
    CommandRunner runner;

    @Autowired
    JdbcBanReconciliation bans;

    @Autowired
    TrustReconciler reconciler;

    BookingFlows flows;
    AttendanceFlows attendance;
    DueJobs due;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        due = DueJobs.of(context, new BanExpiryHandler(appDataSource, bans, reconciler));
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /**
     * At its end the Ban EXPIRES once: its audit and one BanExpired, the projection no longer points at it, and the
     * customer can book again; a second fire changes nothing.
     */
    @Test
    void aBanPastItsEndExpiresOnceAndTheCustomerCanBookAgain() {
        BookingFlows.Customer customer = flows.customer();
        Booking third = banned(customer);
        UUID ban = ban(third.customerId());
        endedEightDaysAgo(ban);

        fireNow(third, ban);

        assertThat(OWNER.sql("SELECT status FROM trust.ban_instance WHERE id = :id").param("id", ban)
                .query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(OWNER.sql("SELECT active_ban_instance_id IS NULL FROM trust.trust_projection WHERE user_id = :id")
                .param("id", third.customerId()).query(Boolean.class).single()).isTrue();
        assertThat(count("technical.outbox_event", "event_type = 'BanExpired' AND aggregate_id = :ban", ban)).isOne();
        assertThat(count("audit.audit_event", "action = 'BAN_EXPIRED' AND payload ->> 'banInstanceId' = CAST(:ban AS text)",
                ban)).isOne();
        assertThat(DueJobs.job(key(ban)).status()).isEqualTo("DONE");

        assertThat(due.fire(key(ban))).as("nothing left to fire").isFalse();
        assertThat(count("technical.outbox_event", "event_type = 'BanExpired' AND aggregate_id = :ban", ban)).isOne();

        Booking again = customer.nextPending(Duration.ofHours(4));
        assertThat(again.bookingId()).isNotNull();
    }

    /**
     * E7-93 (ADR-023, FR-TRN-26) end to end: the Ban the third no-show activates makes the customer's Tournament
     * participation INELIGIBLE/BAN in that same command - Trust's reconciliation hands it to the Tournament module -
     * and its expiry, fired by the real worker, restores it to JOINED before the Tournament starts.
     */
    @Test
    void aBanTakesTheCustomerOutOfATournamentAndItsExpiryBringsThemBack() {
        TournamentFlows tournaments = TournamentFlows.of(context);
        UUID tournament = tournaments.openTournament();
        BookingFlows.Customer customer = flows.customer();
        Booking first = customer.nextActive(Duration.ofMinutes(20));
        tournaments.join(first.customerId(), tournament);
        attendance.noShow(first.venueAccountId(), first.bookingId());
        Booking last = null;
        for (int i = 0; i < 2; i++) {
            last = customer.nextActive(Duration.ofMinutes(20));
            attendance.noShow(last.venueAccountId(), last.bookingId());
        }

        assertThat(TournamentFlows.participant(tournament, first.customerId())).isEqualTo("INELIGIBLE:BAN");

        UUID ban = ban(first.customerId());
        endedEightDaysAgo(ban);
        fireNow(last, ban);

        assertThat(OWNER.sql("SELECT status FROM trust.ban_instance WHERE id = :id").param("id", ban)
                .query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(TournamentFlows.participant(tournament, first.customerId())).isEqualTo("JOINED:");
    }

    /** Fired before the Ban's end (an extension moved it, or an early wake): rescheduled to the end, nothing expired. */
    @Test
    void anEarlyFireReschedulesToTheBansEnd() {
        Booking third = banned(flows.customer());
        UUID ban = ban(third.customerId());

        fireNow(third, ban);

        assertThat(OWNER.sql("SELECT status FROM trust.ban_instance WHERE id = :id").param("id", ban)
                .query(String.class).single()).isEqualTo("ACTIVE");
        DueJobs.JobRow job = DueJobs.job(key(ban));
        assertThat(job.status()).isEqualTo("PENDING");
        assertThat(job.dueAt()).isEqualTo(OWNER.sql("SELECT ends_at FROM trust.ban_instance WHERE id = :id")
                .param("id", ban).query(OffsetDateTime.class).single().toInstant());
        assertThat(count("technical.outbox_event", "event_type = 'BanExpired' AND aggregate_id = :ban", ban)).isZero();
    }

    private Booking banned(BookingFlows.Customer customer) {
        Booking last = null;
        for (int i = 0; i < 3; i++) {
            last = customer.nextActive(Duration.ofMinutes(20));
            attendance.noShow(last.venueAccountId(), last.bookingId());
        }
        return last;
    }

    /** The job made due now, then fired once by the worker. */
    private void fireNow(Booking booking, UUID ban) {
        runner.run(TestCommands.under(CommandScope.shared(LockSet.builder().users(booking.customerId()).build()),
                command -> {
                    due.makeDue(command, key(ban), command.commandNow());
                    return null;
                }));
        due.fire(key(ban));
    }

    /**
     * The Ban as if activated eight days ago: its activation, end, markers, cascade proof and consumption move back
     * together (the customer had no other booking, so the cascade had no target), behind the triggers.
     */
    private static void endedEightDaysAgo(UUID ban) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            // UTC like every application session (arch 04 P2): in the JVM's Africa/Cairo, "- 8 days" crosses a DST
            // change differently for activated_at and ends_at, and ck_ban_instance_time_arithmetic refuses the row.
            jdbc.sql("SET TIME ZONE 'UTC'").update();
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql("""
                            UPDATE trust.ban_instance
                               SET activated_at = activated_at - interval '8 days', ends_at = ends_at - interval '8 days',
                                   last_command_at = last_command_at - interval '8 days',
                                   cascade_executed_at = cascade_executed_at - interval '8 days',
                                   cascade_targets_registered_at = cascade_targets_registered_at - interval '8 days'
                             WHERE id = :id""").param("id", ban).update();
            jdbc.sql("UPDATE trust.cascade_execution SET executed_at = executed_at - interval '8 days' WHERE source_id = :id")
                    .param("id", ban).update();
            jdbc.sql("""
                            UPDATE trust.ban_qualification_consumption SET consumed_at = consumed_at - interval '8 days'
                             WHERE ban_instance_id = :id""").param("id", ban).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }

    private static UUID ban(UUID user) {
        return OWNER.sql("SELECT id FROM trust.ban_instance WHERE user_id = :id").param("id", user)
                .query(UUID.class).single();
    }

    private static String key(UUID ban) {
        return JdbcBanReconciliation.expiryKey(ban);
    }

    private static long count(String table, String where, UUID ban) {
        return OWNER.sql("SELECT count(*) FROM " + table + " WHERE " + where).param("ban", ban)
                .query(Long.class).single();
    }
}
