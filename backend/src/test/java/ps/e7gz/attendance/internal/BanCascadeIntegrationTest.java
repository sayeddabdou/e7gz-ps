package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * E7-71 (O14) against the real database (arch 07 sections 4, 5.1-5.6; FR-TRS-14..25): Bans activated, extended and
 * corrected by the real attendance and Admin correction commands, every step committed and accepted by the database's
 * exactly-once proofs ({@code trust.assert_user_integrity}, {@code trust.assert_ban_target_completion},
 * {@code trust.assert_ban_lifecycle_audit}). Nothing is planted behind a guard.
 *
 * <p>An extension needs a second crossing after the Ban's own: a banned customer can create no booking, the cascade
 * cancels every future one and an ACTIVE booking cannot overlap the no-show that banned them, so the only way a
 * customer's history gains one is an Admin correction - the path {@link #aSecondQualificationDuringAnActiveBanExtendsOneInstance}
 * takes. The tournament side (FR-TRN-26) has no participants to reconcile until E7-73.
 */
class BanCascadeIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    AttendanceCorrections corrections;

    @Autowired
    TrustReconciler reconciler;

    @Autowired
    CommandRunner runner;

    @Autowired
    javax.sql.DataSource appDataSource;

    BookingFlows flows;
    AttendanceFlows attendance;
    CorrelationId.Scope correlation;
    UUID admin;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        admin = anAdmin().create().id();
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /**
     * Done when: a second qualification during an active Ban gives one instance, an advanced stage and BAN_EXTENDED
     * (FR-TRS-22). Two completed bookings before three live no-shows are corrected to ABSENT_NO_SHOW one by one: the
     * first moves the stage-1 crossing earlier, so the original Ban loses its support (CORRECTED) and a new one is
     * activated now; the second makes the history cross stage 1 and then stage 2 - one new instance, extended in the
     * same command to MAX(now + 7 days, now + 1 month), with its exact BAN_EXTENDED audit and one BanExtended. Exactly
     * one Ban is ACTIVE at every point.
     */
    @Test
    void aSecondQualificationDuringAnActiveBanExtendsOneInstance() {
        BookingFlows.Customer customer = flows.customer();
        Booking earliest = completed(customer, Duration.ofHours(3));
        Booking earlier = completed(customer, Duration.ofHours(2));
        missed(customer, Duration.ofMinutes(50));
        missed(customer, Duration.ofMinutes(40));
        Booking third = missed(customer, Duration.ofMinutes(30));
        UUID user = third.customerId();
        assertThat(bans(user)).containsExactly("ACTIVE 1");

        corrections.correct(admin, earlier.bookingId(), AttendanceResult.ABSENT_NO_SHOW, "was absent");
        assertThat(bans(user)).containsExactly("CORRECTED 1", "ACTIVE 1");

        corrections.correct(admin, earliest.bookingId(), AttendanceResult.ABSENT_NO_SHOW, "was absent too");

        assertThat(bans(user)).containsExactly("CORRECTED 1", "CORRECTED 1", "ACTIVE 2");
        UUID active = activeBan(user);
        assertThat(OWNER.sql("""
                        SELECT ends_at = ((last_command_at AT TIME ZONE 'UTC') + interval '1 month') AT TIME ZONE 'UTC'
                          FROM trust.ban_instance WHERE id = :id""")
                .param("id", active).query(Boolean.class).single()).as("MAX(now + 7 days, now + 1 month)").isTrue();
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'BAN_EXTENDED' AND subject_id = :user AND payload ->> 'banInstanceId' = :ban
                           AND payload ->> 'previousPenaltyStage' = '1' AND payload ->> 'newPenaltyStage' = '2'""")
                .param("user", user).param("ban", active.toString()).query(Long.class).single()).isOne();
        assertThat(JdbcClient.create(appDataSource).sql("""
                        SELECT ends_at = activated_at + duration AND duration = ends_at - activated_at
                          FROM trust.ban_instance WHERE id = :id""")
                .param("id", active).query(Boolean.class).single())
                .as("ck_ban_instance_time_arithmetic on the application's UTC session").isTrue();
        assertThat(events(user, "BanExtended")).isOne();
        assertThat(events(user, "BanActivated")).isEqualTo(3);
        assertThat(events(user, "BanCorrected")).isEqualTo(2);
        assertThat(OWNER.sql("SELECT count(*) FROM trust.cascade_execution WHERE source_id = :ban")
                .param("ban", active).query(Long.class).single()).as("its cascade ran once, at activation").isOne();
        assertThat(projection(user)).isEqualTo("0/2/" + active);
    }

    /**
     * Done when: replaying an already banned customer creates nothing twice (FR-TRS-21, NFR-06) - twice in one
     * command and again later: no instance, qualification, consumption, cascade, event or audit row is added.
     */
    @Test
    void reconcilingABannedCustomerAgainCreatesNothing() {
        Booking third = banned(flows.customer());
        UUID user = third.customerId();
        List<Long> before = derived(user);

        runner.run(TestCommands.under(scope(third), command -> {
            reconciler.reconcile(command, user);
            return reconciler.reconcile(command, user);
        }));
        runner.run(TestCommands.under(scope(third), command -> reconciler.reconcile(command, user)));

        assertThat(derived(user)).isEqualTo(before);
    }

    /**
     * FR-TRS-24, FR-TRS-25: reversing the no-show that banned the customer leaves the Ban without replay support, so
     * the same command CORRECTS it (SYSTEM, citing the reversal), the restriction stops and the projection no longer
     * points at a Ban - but the bookings its cascade cancelled stay cancelled and no slot is taken back.
     */
    @Test
    void reversingTheTriggeringNoShowCorrectsTheBanAndCancelledBookingsStayCancelled() {
        BookingFlows.Customer customer = flows.customer();
        missed(customer, Duration.ofMinutes(20));
        missed(customer, Duration.ofMinutes(20));
        Booking pending = customer.nextPending(Duration.ofHours(3));
        Booking third = missed(customer, Duration.ofMinutes(20));
        UUID user = third.customerId();
        assertThat(bookingState(pending)).isEqualTo("CANCELLED/USER_BANNED");

        corrections.reverseNoShow(admin, third.bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, false,
                "the venue marked the wrong customer");

        assertThat(bans(user)).containsExactly("CORRECTED 1");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'BAN_CORRECTED' AND subject_id = :user AND actor_type = 'SYSTEM'""")
                .param("user", user).query(Long.class).single()).isOne();
        assertThat(events(user, "BanCorrected")).isOne();
        assertThat(projection(user)).isEqualTo("2/0/null");
        assertThat(bookingState(pending)).as("FR-TRS-24: never returns").isEqualTo("CANCELLED/USER_BANNED");
    }

    /** FR-ACC-17: a banned customer creates no booking; the refusal writes nothing. */
    @Test
    void aBannedCustomerCannotBook() {
        BookingFlows.Customer customer = flows.customer();
        Booking third = banned(customer);
        long before = bookingsOf(third.customerId());

        assertThatThrownBy(() -> customer.nextPending(Duration.ofHours(6)));

        assertThat(bookingsOf(third.customerId())).isEqualTo(before);
    }

    /**
     * Two venue no-shows of the third booking at once: the Customer lock serialises them, one records the no-show and
     * activates the Ban with its cascade, the other is refused - one instance, one cascade, one BanActivated.
     */
    @Test
    void concurrentThirdNoShowsActivateOneBan() {
        BookingFlows.Customer customer = flows.customer();
        missed(customer, Duration.ofMinutes(20));
        missed(customer, Duration.ofMinutes(20));
        customer.nextConfirmed(Duration.ofHours(3));
        Booking third = customer.nextActive(Duration.ofMinutes(20));

        ConcurrentRace.Result<RecordedAttendance> race = ConcurrentRace.run(2, Duration.ofSeconds(30),
                (racer, start) -> {
                    try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                        start.await();
                        return attendance.noShow(third.venueAccountId(), third.bookingId());
                    }
                });

        assertThat(race.values()).singleElement()
                .satisfies(recorded -> assertThat(recorded.status()).isEqualTo(BookingStatus.NO_SHOW));
        assertThat(race.failures()).singleElement().isInstanceOf(ApiException.class);
        assertThat(bans(third.customerId())).containsExactly("ACTIVE 1");
        assertThat(OWNER.sql("SELECT count(*) FROM trust.cascade_execution WHERE subject_user_id = :id")
                .param("id", third.customerId()).query(Long.class).single()).isOne();
        assertThat(events(third.customerId(), "BanActivated")).isOne();
    }

    // ----------------------------------------------------------------------------------------------------- support

    private Booking banned(BookingFlows.Customer customer) {
        missed(customer, Duration.ofMinutes(20));
        missed(customer, Duration.ofMinutes(20));
        Booking third = missed(customer, Duration.ofMinutes(20));
        assertThat(bans(third.customerId())).containsExactly("ACTIVE 1");
        return third;
    }

    private Booking missed(BookingFlows.Customer customer, Duration startedAgo) {
        Booking booking = customer.nextActive(startedAgo);
        attendance.noShow(booking.venueAccountId(), booking.bookingId());
        return booking;
    }

    /** Played without evidence, ended {@code endedAgo} ago and completed by the real T5: ATTENDANCE_UNCONFIRMED. */
    private Booking completed(BookingFlows.Customer customer, Duration endedAgo) {
        Booking booking = flows.pastItsEnd(customer.nextActive(Duration.ofMinutes(5)), endedAgo);
        assertThat(flows.fireCompletion(booking)).as("T5 settled").isTrue();
        return booking;
    }

    private static CommandScope scope(Booking booking) {
        return CommandScope.shared(LockSet.builder().users(booking.customerId()).venues(booking.venueId())
                .resources(booking.resourceId()).build());
    }

    /** "STATUS stage" of each Ban Instance of the User, by activation. */
    private static List<String> bans(UUID user) {
        return OWNER.sql("""
                        SELECT status || ' ' || penalty_stage FROM trust.ban_instance
                         WHERE user_id = :id ORDER BY activated_at, id""")
                .param("id", user).query(String.class).list();
    }

    private static UUID activeBan(UUID user) {
        return OWNER.sql("SELECT id FROM trust.ban_instance WHERE user_id = :id AND status = 'ACTIVE'")
                .param("id", user).query(UUID.class).single();
    }

    private static long events(UUID user, String type) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND payload ->> 'userId' = :user""")
                .param("type", type).param("user", user.toString()).query(Long.class).single();
    }

    /** "counter/stage/active Ban pointer". */
    private static String projection(UUID user) {
        return OWNER.sql("""
                        SELECT consecutive_no_shows || '/' || penalty_stage || '/'
                               || COALESCE(CAST(active_ban_instance_id AS text), 'null')
                          FROM trust.trust_projection WHERE user_id = :id""")
                .param("id", user).query(String.class).single();
    }

    private static String bookingState(Booking booking) {
        return OWNER.sql("SELECT status || '/' || COALESCE(terminal_reason, '-') FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    private static long bookingsOf(UUID user) {
        return OWNER.sql("SELECT count(*) FROM booking.booking WHERE customer_id = :id")
                .param("id", user).query(Long.class).single();
    }

    /** Every row a Ban reconciliation could write for the User, and the projection version. */
    private static List<Long> derived(UUID user) {
        Map<String, Object> row = OWNER.sql("""
                        SELECT (SELECT count(*) FROM trust.ban_instance WHERE user_id = :id) AS bans,
                               (SELECT count(*) FROM trust.ban_qualification WHERE user_id = :id) AS qualifications,
                               (SELECT count(*) FROM trust.ban_qualification_consumption c
                                  JOIN trust.ban_instance b ON b.id = c.ban_instance_id WHERE b.user_id = :id) AS consumed,
                               (SELECT count(*) FROM trust.cascade_execution WHERE subject_user_id = :id) AS cascades,
                               (SELECT count(*) FROM technical.outbox_event
                                 WHERE payload ->> 'userId' = CAST(:id AS text)) AS events,
                               (SELECT count(*) FROM audit.audit_event
                                 WHERE subject_type = 'USER' AND subject_id = :id) AS audits,
                               (SELECT projection_version FROM trust.trust_projection WHERE user_id = :id) AS version""")
                .param("id", user).query().singleRow();
        return row.values().stream().map(value -> ((Number) value).longValue()).toList();
    }
}
