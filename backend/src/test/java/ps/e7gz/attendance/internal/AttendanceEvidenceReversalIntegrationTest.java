package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.trust.api.TrustReplay;

/**
 * E7-70 against the real database (arch 07 section 7.4, FR-ATT-11, FR-ATT-12): the Platform Admin reverses a check-in
 * or a Mark Present - Late. The evidence stays recorded and gains its reversal marker, with the Admin Booking Event and
 * the audit row with the reason, which {@code attendance.assert_attendance_action_audit} requires at commit. Evidence
 * is recorded by the real venue commands and bookings are completed by the real T5.
 */
class AttendanceEvidenceReversalIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private static final String REASON = "Venue checked in the wrong customer.";

    @Autowired
    ApplicationContext context;

    @Autowired
    AttendanceCorrections corrections;

    @Autowired
    TrustReplay replay;

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

    // ------------------------------------------------------------------------------------------------ check-in

    /**
     * Arch 07 section 7.4: a check-in reversed while ACTIVE leaves the booking ACTIVE and unresolved; nothing was
     * granted, so no Trust event and no points move. The evidence stays, with its marker.
     */
    @Test
    void aCheckInReversedWhileActiveKeepsTheBookingActive() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());

        AttendanceState state = corrections.reverseCheckIn(admin, booking.bookingId(), REASON);

        assertThat(state.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(state.terminalReason()).isNull();
        assertThat(state.result()).isNull();
        assertThat(state.checkedInAt()).as("reversed evidence is absent from the answer").isNull();
        assertThat(evidence(booking)).as("check-in recorded, check-in reversed, result")
                .containsExactly("recorded", "reversed", null);
        assertThat(adminEvidence(booking, "CHECK_IN_REVERSED")).isOne();
        assertThat(trustEvents(booking)).isEmpty();
        assertThat(outbox(booking, IntegrationEvents.ATTENDANCE_CORRECTED)).isOne();
    }

    /** Arch 07 section 7.4: with the check-in reversed, no-show is open again inside its window. */
    @Test
    void aReversedCheckInReopensNoShowInsideItsWindow() {
        Booking checkedIn = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(checkedIn.venueAccountId(), checkedIn.bookingId());
        // the whole booking, its check-in included, moved 15 minutes back: 20 minutes in, inside the no-show window
        Booking booking = flows.endingAt(checkedIn, dbNow().plus(Duration.ofMinutes(40)));
        assertThatThrownBy(() -> attendance.noShow(booking.venueAccountId(), booking.bookingId()))
                .as("a valid check-in stands").isInstanceOf(ApiException.class);

        corrections.reverseCheckIn(admin, booking.bookingId(), REASON);
        attendance.noShow(booking.venueAccountId(), booking.bookingId());

        assertThat(status(booking)).isEqualTo("NO_SHOW");
        assertThat(evidence(booking)).containsExactly("recorded", "reversed", "ABSENT_NO_SHOW");
        assertThat(trustEvents(booking)).containsExactly("BOOKING_NO_SHOW");
    }

    /**
     * Arch 07 section 7.4: a check-in reversed after an ATTENDED completion creates {@code ATTENDANCE_CORRECTED} and the
     * points are reversed by a compensating transaction; the status stays COMPLETED (FR-ATT-09). Replay then counts
     * the booking as ATTENDANCE_UNCONFIRMED at its own startAt: no Verified Player progress.
     */
    @Test
    void aCheckInReversedAfterAnAttendedCompletionCorrectsItAndCompensatesThePoints() {
        Booking played = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(played.venueAccountId(), played.bookingId());
        Booking booking = flows.pastItsEnd(played, Duration.ofMinutes(10));
        assertThat(flows.fireCompletion(booking)).as("T5 settled").isTrue();
        assertThat(points(booking.customerId())).isEqualTo(200);

        AttendanceState state = corrections.reverseCheckIn(admin, booking.bookingId(), REASON);

        assertThat(state.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(state.result()).isEqualTo(AttendanceResult.ATTENDANCE_UNCONFIRMED);
        assertThat(state.checkedInAt()).isNull();
        assertThat(evidence(booking)).containsExactly("recorded", "reversed", "ATTENDANCE_UNCONFIRMED");
        assertThat(adminEvidence(booking, "CHECK_IN_REVERSED")).isOne();
        assertThat(adminEvidence(booking, "ATTENDANCE_CORRECTED")).isOne();
        assertThat(trustEvents(booking)).containsExactly("BOOKING_ATTENDED", "ATTENDANCE_CORRECTED");
        var effects = replay.resolveEffectiveOutcomes(booking.customerId(), null);
        assertThat(effects).extracting(TrustReplay.Effect::type)
                .containsExactly(TrustEventType.BOOKING_ATTENDANCE_UNCONFIRMED);
        assertThat(effects.getFirst().effectiveAt()).as("at the booking, not at the correction")
                .isEqualTo(booking.startAt());
        assertThat(replay.replay(booking.customerId(), null).completedAttendedCount()).isZero();
        assertThat(points(booking.customerId())).as("compensated, never negative").isZero();
    }

    // -------------------------------------------------------------------------------------------- late present

    /** Arch 07 section 7.4: a Late Present reversed before endAt re-opens no-show eligibility. */
    @Test
    void aLatePresentReversedBeforeTheEndReopensNoShow() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        attendance.latePresent(booking.venueAccountId(), booking.bookingId());
        assertThatThrownBy(() -> attendance.noShow(booking.venueAccountId(), booking.bookingId()))
                .as("FR-ATT-04: never a no-show after Late Present").isInstanceOf(ApiException.class);

        AttendanceState state = corrections.reverseLatePresent(admin, booking.bookingId(), REASON);

        assertThat(state.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(state.result()).isNull();
        assertThat(state.latePresentAt()).isNull();
        assertThat(adminEvidence(booking, "LATE_PRESENT_REVERSED")).isOne();
        assertThat(trustEvents(booking)).isEmpty();

        attendance.noShow(booking.venueAccountId(), booking.bookingId());
        assertThat(status(booking)).isEqualTo("NO_SHOW");
    }

    // ------------------------------------------------------------------------------------------------ refusals

    /** PO decision D5: a state that does not allow the action is ADMIN_ACTION_NOT_ALLOWED, and nothing is written. */
    @Test
    void refusalsWriteNothing() {
        Booking untouched = flows.active(Duration.ofMinutes(5));
        refusedCheckIn(untouched, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                       // no evidence at all
        refusedLatePresent(untouched, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);

        Booking walkIn = flows.walkIn();
        refusedCheckIn(walkIn, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                          // FR-MAN-04

        Booking late = flows.active(Duration.ofMinutes(20));
        attendance.latePresent(late.venueAccountId(), late.bookingId());
        refusedCheckIn(late, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                            // not that evidence
        Booking ended = flows.pastItsEnd(late, Duration.ofMinutes(10));
        refusedLatePresent(ended, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                       // not before endAt
        assertThat(flows.fireCompletion(ended)).isTrue();
        refusedLatePresent(ended, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                       // resolved

        assertThatThrownBy(() -> corrections.reverseCheckIn(admin, UUID.randomUUID(), REASON))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> corrections.reverseCheckIn(admin, untouched.bookingId(), "  "))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        for (Booking booking : List.of(untouched, walkIn, late)) {
            assertThat(adminEvidence(booking, "CHECK_IN_REVERSED") + adminEvidence(booking, "LATE_PRESENT_REVERSED"))
                    .isZero();
            assertThat(outbox(booking, IntegrationEvents.ATTENDANCE_CORRECTED)).isZero();
        }
    }

    /** A reversal is done once: its marker is immutable, and a second one finds no evidence standing. */
    @Test
    void aSecondReversalIsRefused() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        corrections.reverseCheckIn(admin, booking.bookingId(), REASON);

        refusedCheckIn(booking, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);

        assertThat(adminEvidence(booking, "CHECK_IN_REVERSED")).isOne();
        assertThat(outbox(booking, IntegrationEvents.ATTENDANCE_CORRECTED)).isOne();
    }

    // ------------------------------------------------------------------------------------------------- support

    private void refusedCheckIn(Booking booking, ApiErrorCode code) {
        assertThatThrownBy(() -> corrections.reverseCheckIn(admin, booking.bookingId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(code));
    }

    private void refusedLatePresent(Booking booking, ApiErrorCode code) {
        assertThatThrownBy(() -> corrections.reverseLatePresent(admin, booking.bookingId(), REASON))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(code));
    }

    private static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Whether the check-in is recorded, whether it is reversed, and the stored result. */
    private static List<String> evidence(Booking booking) {
        return OWNER.sql("""
                        SELECT CASE WHEN check_in_at IS NOT NULL THEN 'recorded' END,
                               CASE WHEN check_in_reversed_at IS NOT NULL THEN 'reversed' END, result
                          FROM attendance.attendance_record WHERE booking_id = :id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single();
    }

    /** Admin history rows of the action matched by an Admin audit row with a reason, at the same instant. */
    private static long adminEvidence(Booking booking, String action) {
        return OWNER.sql("""
                        SELECT count(*) FROM booking.booking_history bh
                          JOIN audit.audit_event ae ON ae.subject_type = 'BOOKING' AND ae.subject_id = bh.booking_id
                           AND ae.action = bh.event_type AND ae.actor_type = 'ADMIN'
                           AND ae.actor_id = bh.actor_id AND ae.occurred_at = bh.occurred_at
                           AND btrim(ae.reason) <> ''
                         WHERE bh.booking_id = :id AND bh.entry_kind = 'BOOKING_EVENT'
                           AND bh.event_type = :action AND bh.actor_type = 'ADMIN'""")
                .param("id", booking.bookingId()).param("action", action).query(Long.class).single();
    }

    private static List<String> trustEvents(Booking booking) {
        return OWNER.sql("SELECT event_type FROM trust.trust_event WHERE booking_id = :id ORDER BY event_sequence")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static long outbox(Booking booking, String type) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE aggregate_id = :id AND event_type = :type""")
                .param("id", booking.bookingId()).param("type", type).query(Long.class).single();
    }

    private static String status(Booking booking) {
        return OWNER.sql("SELECT status FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    private static int points(UUID customer) {
        return OWNER.sql("SELECT balance FROM loyalty.points_account WHERE user_id = :id")
                .param("id", customer).query(Integer.class).single();
    }
}
