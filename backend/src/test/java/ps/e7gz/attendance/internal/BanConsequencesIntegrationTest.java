package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-71 carry-forwards and the edges of O14 against the real database, every step through a real command (arch 07
 * sections 5.3, 5.4, 5.6; FR-TRS-20, FR-TRS-22, FR-TRS-25; Product v5.0 sections 48.6 and 52.6):
 * <ul>
 *   <li>a booking still CONFIRMED after its startAt because T4 is late is treated as started: the Ban does not cancel
 *       it and the late T4 still starts it (PO decision 19 Oct 2026, the E7-59/E7-60 review);</li>
 *   <li>an AWAITING_CUSTOMER_CONFIRMATION booking is cancelled with USER_BANNED and its hold released;</li>
 *   <li>a Ban whose extension lost its support, while the first stage's days still run, is shortened to them under the
 *       correcting Admin (FR-TRS-25);</li>
 *   <li>each activation raises one {@code ADMIN_USER_BAN_ACTIVATED} in the admin alert inbox (section 52.6).</li>
 * </ul>
 */
class BanConsequencesIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    AttendanceCorrections corrections;

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
     * PO decision 19 Oct 2026 (Spec v5 section 48.6 does not cover it): a booking still CONFIRMED after its startAt
     * because T4 is late continues under the Ban - the database's cascade only takes CONFIRMED bookings starting in the
     * future - and the late T4 then still starts it. The same Ban cancels a future booking (the control).
     *
     * <p>The Ban is activated by an Admin correction, so the late-start booking can exist beside it: the third live
     * no-show would hold the same room at the same time.
     */
    @Test
    void aLateStartBookingContinuesUnderTheBanAndItsLateT4StillStartsIt() {
        BookingFlows.Customer customer = flows.customer();
        Booking played = completed(customer, Duration.ofHours(3));
        missed(customer, Duration.ofMinutes(50));
        Booking second = missed(customer, Duration.ofMinutes(40));
        Booking lateStart = customer.nextConfirmedWithOverdueStart(Duration.ofMinutes(10));
        Booking future = customer.nextPending(Duration.ofHours(6));
        UUID user = second.customerId();
        assertThat(bans(user)).isEmpty();

        corrections.correct(admin, played.bookingId(), AttendanceResult.ABSENT_NO_SHOW, "was absent");

        assertThat(bans(user)).containsExactly("ACTIVE 1");
        assertThat(bookingState(future)).isEqualTo("CANCELLED/USER_BANNED");
        assertThat(bookingState(lateStart)).as("treated as started: not a cascade target").isEqualTo("CONFIRMED/-");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM trust.ban_cascade_booking_target t
                          JOIN trust.ban_instance b ON b.id = t.ban_instance_id
                         WHERE b.user_id = :user AND t.booking_id = :booking""")
                .param("user", user).param("booking", lateStart.bookingId()).query(Long.class).single()).isZero();

        assertThat(flows.fireStart(lateStart)).as("the late T4 settled").isTrue();

        assertThat(bookingState(lateStart)).as("the Ban guard does not refuse the start").isEqualTo("ACTIVE/-");
    }

    /**
     * FR-TRS-20, arch 07 section 5.4: an AWAITING_CUSTOMER_CONFIRMATION booking is CANCELLED with USER_BANNED and its
     * Late Confirmation Hold released, in the command that activated the Ban.
     */
    @Test
    void anAwaitingBookingIsCancelledAndItsHoldReleased() {
        BookingFlows.Customer customer = flows.customer();
        Booking awaiting = customer.nextLateConfirmed(Duration.ofHours(6));
        assertThat(bookingState(awaiting)).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION/-");
        assertThat(activeReservations(awaiting)).isOne();

        Booking third = banned(customer);

        assertThat(bans(third.customerId())).containsExactly("ACTIVE 1");
        assertThat(bookingState(awaiting)).isEqualTo("CANCELLED/USER_BANNED");
        assertThat(activeReservations(awaiting)).as("the hold is released").isZero();
    }

    /**
     * FR-TRS-25: the Ban's extension (stage 2) loses its no-show, while the stage-1 qualification it consumed first still
     * supports seven days from that consumption. The Admin's correction shortens the Ban to exactly that end - still
     * ACTIVE - under that Admin, with BAN_SHORTENED, one BanShortened and T10 at the new end; nothing is CORRECTED.
     */
    @Test
    void aBanWhoseExtensionLostItsSupportIsShortenedUnderTheAdmin() {
        BookingFlows.Customer customer = flows.customer();
        Booking earliest = completed(customer, Duration.ofHours(3));
        Booking earlier = completed(customer, Duration.ofHours(2));
        missed(customer, Duration.ofMinutes(50));
        missed(customer, Duration.ofMinutes(40));
        Booking third = missed(customer, Duration.ofMinutes(30));
        UUID user = third.customerId();
        corrections.correct(admin, earlier.bookingId(), AttendanceResult.ABSENT_NO_SHOW, "was absent");
        corrections.correct(admin, earliest.bookingId(), AttendanceResult.ABSENT_NO_SHOW, "was absent too");
        assertThat(bans(user)).containsExactly("CORRECTED 1", "CORRECTED 1", "ACTIVE 2");
        UUID ban = activeBan(user);
        Instant stageOneUntil = OWNER.sql("""
                        SELECT c.consumed_at FROM trust.ban_qualification_consumption c
                          JOIN trust.ban_qualification q ON q.id = c.qualification_id
                         WHERE c.ban_instance_id = :ban AND q.penalty_stage = 1""")
                .param("ban", ban).query(OffsetDateTime.class).single().toInstant().plus(Duration.ofDays(7));

        corrections.reverseNoShow(admin, third.bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, false,
                "the venue marked the wrong customer");

        assertThat(bans(user)).containsExactly("CORRECTED 1", "CORRECTED 1", "ACTIVE 2");
        assertThat(endsAt(ban)).isEqualTo(stageOneUntil);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'BAN_SHORTENED' AND subject_id = :user AND actor_type = 'ADMIN'
                           AND actor_id = :admin AND payload ->> 'banInstanceId' = :ban""")
                .param("user", user).param("admin", admin).param("ban", ban.toString())
                .query(Long.class).single()).isOne();
        assertThat(events(user, "BanShortened")).isOne();
        assertThat(OWNER.sql("""
                        SELECT due_at FROM technical.scheduled_job
                         WHERE subject_id = :ban AND type = 'BAN_EXPIRY' AND status = 'PENDING'""")
                .param("ban", ban).query(OffsetDateTime.class).single().toInstant()).isEqualTo(stageOneUntil);
    }

    /**
     * Product v5.0 section 52.6, PO decision 20 Oct 2026: one ADMIN_USER_BAN_ACTIVATED in the admin alert inbox per
     * activation, about the User, with the Ban's facts - and no per-admin notification.
     */
    @Test
    void eachActivationRaisesOneAdminAlertAndNoAdminNotification() {
        Booking third = banned(flows.customer());
        UUID user = third.customerId();
        UUID ban = activeBan(user);

        assertThat(OWNER.sql("""
                        SELECT severity || ' ' || subject_type || ' ' || (payload ->> 'banInstanceId')
                          FROM admin.admin_alert WHERE kind = 'ADMIN_USER_BAN_ACTIVATED' AND subject_id = :user""")
                .param("user", user).query(String.class).list())
                .containsExactly("SEV_3 USER " + ban);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM notification.notification WHERE code = 'ADMIN_USER_BAN_ACTIVATED'""")
                .query(Long.class).single()).isZero();
    }

    // ----------------------------------------------------------------------------------------------------- support

    private Booking banned(BookingFlows.Customer customer) {
        missed(customer, Duration.ofMinutes(20));
        missed(customer, Duration.ofMinutes(20));
        return missed(customer, Duration.ofMinutes(20));
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

    private static Instant endsAt(UUID ban) {
        return OWNER.sql("SELECT ends_at FROM trust.ban_instance WHERE id = :id").param("id", ban)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static String bookingState(Booking booking) {
        return OWNER.sql("SELECT status || '/' || COALESCE(terminal_reason, '-') FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    private static long activeReservations(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = :id AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    private static long events(UUID user, String type) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND payload ->> 'userId' = :user""")
                .param("type", type).param("user", user.toString()).query(Long.class).single();
    }
}
