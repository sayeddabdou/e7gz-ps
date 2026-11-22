package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * E7-78, O10 (arch 06 section 2.8, FR-VEN-08..11) and Platform Attendance Protection (arch 07 section 7.5,
 * FR-ATT-14..26) through the real disable and re-enable commands ({@link VenueAccounts#setStatus}) against the real
 * database: every effect by booking state in the disabling transaction, the restriction interval it opens and the
 * re-enable closes, the protection candidate, notification event and admin alert of an ACTIVE session, and - the
 * done-when - the one protection event and one counter reset of a session that spans two disable/re-enable cycles.
 */
class VenueAccountDisableIntegrationTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    VenueAccounts accounts;

    @Autowired
    Idempotency idempotency;

    BookingFlows flows;
    AttendanceFlows attendance;
    UUID admin;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        admin = AuditFixtures.admin();
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // --------------------------------------------------------------------------------- FR-VEN-09, by state

    /**
     * PENDING: VENUE_RESPONSE_EXPIRED at once with its Booking Event, the reservation released, T1 withdrawn and T6
     * armed at startAt (E3); the event says why, so no customer notification follows (E1).
     */
    @Test
    void aPendingRequestExpiresAtOnceWithTheDisableEventAndItsStartTimeResolution() {
        Booking booking = flows.pending();
        UUID reservation = reservationOf(booking);

        disable(booking);

        assertThat(booking(booking.bookingId()).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(booking(booking.bookingId()).terminalReason()).isNull();
        assertThat(history(booking.bookingId())).filteredOn(row -> row.toStatus() != null).last().satisfies(row -> {
            assertThat(row.fromStatus()).isEqualTo("PENDING");
            assertThat(row.toStatus()).isEqualTo("VENUE_RESPONSE_EXPIRED");
            assertThat(row.actorType()).isEqualTo("SYSTEM");
        });
        assertThat(bookingEvents(booking)).containsExactly("VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE");
        assertThat(VenueAnswerDb.reservation(reservation).active()).isFalse();
        assertThat(VenueAnswerDb.job(VenueAnswerDb.t1(booking.bookingId()))).hasValueSatisfying(job ->
                assertThat(job.status()).isEqualTo("SUPERSEDED"));
        assertThat(DueJobs.job(BookingFlows.expiredResolutionKey(booking.bookingId()))).satisfies(job -> {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.dueAt()).isEqualTo(booking.startAt());
        });
        assertThat(VenueAnswerDb.events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, booking.bookingId()))
                .singleElement().asString().contains("\"interruptedByAccountDisable\": true");
        assertThat(intervals(booking)).containsExactly("open ADMIN " + admin);
    }

    /** E3: still disabled at startAt, T6 cancels it VENUE_ACCOUNT_DISABLED - never VENUE_DID_NOT_RESPOND_IN_TIME. */
    @Test
    void aRequestStillDisabledAtItsStartIsCancelledForTheDisable() {
        Booking booking = flows.pending();
        disable(booking);
        Booking overdue = startPassed(booking);

        assertThat(flows.fireExpiredResolution(overdue)).isTrue();

        assertThat(booking(booking.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(booking(booking.bookingId()).terminalReason()).isEqualTo("VENUE_ACCOUNT_DISABLED");
    }

    /** AWAITING: CANCELLED VENUE_ACCOUNT_DISABLED, its hold released, a full refund and one BookingCancelled. */
    @Test
    void anAwaitingBookingIsCancelledWithAFullRefund() {
        Booking booking = flows.lateConfirmed();

        disable(booking);

        VenueAnswerDb.BookingRow row = booking(booking.bookingId());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("VENUE_ACCOUNT_DISABLED");
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'LATE_CONFIRM_HOLD' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
        assertThat(VenueAnswerDb.events(IntegrationEvents.BOOKING_CANCELLED, booking.bookingId())).singleElement()
                .asString().contains("VENUE_ACCOUNT_DISABLED");
    }

    /** FR-VEN-09, FR-VEN-10: a CONFIRMED booking is untouched and may still start. */
    @Test
    void aConfirmedBookingIsUnaffected() {
        Booking booking = flows.pending();
        flows.accept(booking);
        long version = booking(booking.bookingId()).version();

        disable(booking);

        assertThat(booking(booking.bookingId()).status()).isEqualTo("CONFIRMED");
        assertThat(booking(booking.bookingId()).version()).isEqualTo(version);
    }

    /**
     * FR-ATT-14, FR-ATT-20, FR-ATT-25, FR-ATT-26 (E2): an ACTIVE booking continues; the disable records one candidate
     * with the evidence at disable time, one customer event and one admin alert with what FR-ATT-26 lists.
     */
    @Test
    void anActiveBookingContinuesAndBecomesAProtectionCandidateWithItsNotifications() {
        Booking booking = flows.active(Duration.ofMinutes(20));

        disable(booking);

        assertThat(booking(booking.bookingId()).status()).isEqualTo("ACTIVE");
        assertThat(candidates(booking)).isOne();
        assertThat(VenueAnswerDb.events(IntegrationEvents.ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED,
                booking.bookingId())).hasSize(1);
        List<Map<String, Object>> alerts = OWNER.sql("""
                        SELECT kind, severity, payload->>'venueId' AS venue, payload->>'startAt' AS start_at,
                               payload->>'endAt' AS end_at, payload->>'disabledAt' AS disabled_at,
                               payload->'attendanceEvidence' AS evidence,
                               payload->>'protectionMayBeRequired' AS protection
                          FROM admin.admin_alert WHERE subject_id = :id""")
                .param("id", booking.bookingId()).query().listOfRows();
        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.get("kind")).isEqualTo("ACCOUNT_DISABLE_OVERLAPS_ACTIVE_BOOKING");
            assertThat(alert.get("severity")).isEqualTo("SEV_3");
            assertThat(alert.get("venue")).isEqualTo(booking.venueId().toString());
            assertThat(alert.get("start_at")).isEqualTo(booking.startAt().toString());
            assertThat(alert.get("disabled_at")).isNotNull();
            assertThat(alert.get("evidence").toString()).isEqualTo("{}");
            assertThat(alert.get("protection")).isEqualTo("true");
        });
    }

    /** FR-ATT-15: a check-in before the disable stays authoritative, and the alert says protection is not needed. */
    @Test
    void aCheckInBeforeTheDisableIsReportedAndNeedsNoProtection() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());

        disable(booking);

        assertThat(OWNER.sql("""
                        SELECT payload->>'protectionMayBeRequired' FROM admin.admin_alert WHERE subject_id = :id""")
                .param("id", booking.bookingId()).query(String.class).single()).isEqualTo("false");
        Booking ended = flows.pastItsEnd(booking, Duration.ofSeconds(5));
        shiftRestrictions(booking, booking.startAt(), ended.startAt());
        assertThat(flows.fireCompletion(ended)).isTrue();
        assertThat(protectionEvents(booking)).isZero();
    }

    // -------------------------------------------------------------------------------------- re-enable

    /** FR-ATT-21: the re-enable closes the open interval once; a second enable changes nothing. */
    @Test
    void theReEnableClosesTheInterval() {
        Booking booking = flows.pending();
        disable(booking);

        enable(booking);
        enable(booking);

        assertThat(intervals(booking)).containsExactly("closed ADMIN " + admin);
        assertThat(OWNER.sql("SELECT account_status FROM iam.venue_account WHERE id = :id")
                .param("id", booking.venueAccountId()).query(String.class).single()).isEqualTo("ENABLED");
    }

    // ------------------------------------------------------------------------------------ done-when

    /**
     * Done when: two disable/re-enable cycles in one session -> one protection event and one reset (FR-ATT-21). The
     * customer has a no-show behind them; the session has no evidence, so T5 resolves ATTENDANCE_UNCONFIRMED and the one
     * protection event resets the counter to 0.
     */
    @Test
    void twoDisableCyclesInOneSessionGiveOneProtectionEventAndOneReset() {
        BookingFlows.Customer customer = flows.customer();
        Booking missed = customer.nextActive(Duration.ofMinutes(20));
        attendance.noShow(missed.venueAccountId(), missed.bookingId());
        flows.noShowLongAgo(missed, Duration.ofDays(1));      // replay orders by startAt: the no-show comes first
        assertThat(consecutiveNoShows(missed.customerId())).isEqualTo(1);
        Booking booking = customer.nextActive(Duration.ofMinutes(20));

        disable(booking);
        enable(booking);
        disable(booking);
        enable(booking);

        assertThat(intervals(booking)).containsExactly("closed ADMIN " + admin, "closed ADMIN " + admin);
        assertThat(candidates(booking)).isEqualTo(2);
        assertThat(VenueAnswerDb.events(IntegrationEvents.ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED,
                booking.bookingId())).hasSize(2);

        Booking ended = flows.pastItsEnd(booking, Duration.ofSeconds(5));
        shiftRestrictions(booking, booking.startAt(), ended.startAt());
        assertThat(flows.fireCompletion(ended)).as("T5 settled").isTrue();

        assertThat(OWNER.sql("SELECT result || ' ' || protection_applied FROM attendance.attendance_record "
                + "WHERE booking_id = :id").param("id", booking.bookingId()).query(String.class).single())
                .isEqualTo("ATTENDANCE_UNCONFIRMED true");
        assertThat(protectionEvents(booking)).isOne();
        assertThat(OWNER.sql("""
                        SELECT effective_at FROM trust.trust_event
                         WHERE booking_id = :id AND event_type = 'BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION'""")
                .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(ended.startAt());
        assertThat(consecutiveNoShows(booking.customerId())).as("one reset").isZero();
    }

    /** FR-ATT-22: a session that resolves ATTENDED after the re-enable gets no protection event on top. */
    @Test
    void anAttendedSessionGetsNoProtection() {
        Booking booking = flows.active(Duration.ofMinutes(2));
        disable(booking);
        enable(booking);
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());

        Booking ended = flows.pastItsEnd(booking, Duration.ofSeconds(5));
        shiftRestrictions(booking, booking.startAt(), ended.startAt());
        assertThat(flows.fireCompletion(ended)).isTrue();

        assertThat(OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).single()).isEqualTo("ATTENDED");
        assertThat(protectionEvents(booking)).isZero();
    }

    // ------------------------------------------------------------------------------------------ D4

    /**
     * D4 (PO decision 19 Nov 2026): replaying a disable that cancelled an AWAITING booking with the same
     * Idempotency-Key returns the stored answer and cancels or refunds nothing twice.
     */
    @Test
    void aReplayedDisableCancelsAndRefundsNothingTwice() {
        Booking booking = flows.lateConfirmed();
        UUID key = UUID.randomUUID();

        var first = accounts.setStatus(admin, booking.venueAccountId(), VenueAccountStatus.DISABLED, "Support 7",
                request(booking, key));
        long history = history(booking.bookingId()).size();
        long refunds = feeDecisions(booking);
        var second = accounts.setStatus(admin, booking.venueAccountId(), VenueAccountStatus.DISABLED, "Support 7",
                request(booking, key));

        assertThat(second).isEqualTo(first);
        assertThat(history(booking.bookingId())).hasSize((int) history);
        assertThat(feeDecisions(booking)).isEqualTo(refunds);
        assertThat(VenueAnswerDb.events(IntegrationEvents.BOOKING_CANCELLED, booking.bookingId())).hasSize(1);
        assertThat(intervals(booking)).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void disable(Booking booking) {
        accounts.setStatus(admin, booking.venueAccountId(), VenueAccountStatus.DISABLED, "Support ticket 7");
    }

    private void enable(Booking booking) {
        accounts.setStatus(admin, booking.venueAccountId(), VenueAccountStatus.ENABLED, "Resolved");
    }

    private IdempotentRequest request(Booking booking, UUID key) {
        return idempotency.request(new AuthenticatedSubject(AuthSubjectType.ADMIN, admin),
                "POST /api/v1/admin/venue-accounts/{venueAccountId}/disable", key,
                Map.of("venueAccountId", booking.venueAccountId()), Map.of("reason", "Support 7"));
    }

    /**
     * The booking a disable expired, moved back whole so its startAt lies 5 seconds in the past
     * ({@link BookingStates#shiftExpiredBack}, as T6's own tests move it), with the venue's restriction interval moved
     * by as much: still in force at the new startAt.
     */
    private Booking startPassed(Booking booking) {
        Instant start = VenueAnswerDb.dbNow().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Duration by = Duration.between(start, booking.startAt());
        BookingStates.shiftExpiredBack(booking.bookingId(), by);
        double seconds = by.toNanos() / 1e9;
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE iam.venue_account_restriction_interval
                           SET disabled_at = disabled_at - make_interval(secs => :by)
                         WHERE venue_id = :venue""").param("by", seconds).param("venue", booking.venueId()).update());
        return new Booking(booking.bookingId(), booking.customerId(), booking.venueId(), booking.venueName(),
                booking.venueAccountId(), booking.resourceId(), start, null);
    }

    /**
     * The venue's restriction intervals and the booking's candidates moved back by as much as {@link
     * BookingFlows#pastItsEnd} moved the booking, so each keeps its place inside the session.
     */
    private static void shiftRestrictions(Booking booking, Instant startBefore, Instant startAfter) {
        double seconds = Duration.between(startAfter, startBefore).toNanos() / 1e9;
        VenueAnswerDb.withoutTriggers(jdbc -> {
            jdbc.sql("""
                            UPDATE iam.venue_account_restriction_interval
                               SET disabled_at = disabled_at - make_interval(secs => :by),
                                   reenabled_at = reenabled_at - make_interval(secs => :by)
                             WHERE venue_id = :venue""").param("by", seconds).param("venue", booking.venueId())
                    .update();
            jdbc.sql("""
                            UPDATE attendance.attendance_protection_candidate
                               SET recorded_at = recorded_at - make_interval(secs => :by)
                             WHERE booking_id = :id""").param("by", seconds).param("id", booking.bookingId())
                    .update();
        });
    }

    private static UUID reservationOf(Booking booking) {
        return OWNER.sql("""
                        SELECT id FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(UUID.class).single();
    }

    private static List<String> bookingEvents(Booking booking) {
        return OWNER.sql("""
                        SELECT event_type FROM booking.booking_history
                         WHERE booking_id = :id AND entry_kind = 'BOOKING_EVENT' ORDER BY sequence""")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static List<String> intervals(Booking booking) {
        return OWNER.sql("""
                        SELECT CASE WHEN reenabled_at IS NULL THEN 'open' ELSE 'closed' END || ' ' || source || ' '
                               || admin_id
                          FROM iam.venue_account_restriction_interval WHERE venue_id = :venue ORDER BY disabled_at""")
                .param("venue", booking.venueId()).query(String.class).list();
    }

    private static long candidates(Booking booking) {
        return VenueAnswerDb.count("attendance.attendance_protection_candidate WHERE booking_id = :id",
                booking.bookingId());
    }

    private static long protectionEvents(Booking booking) {
        return VenueAnswerDb.count("""
                trust.trust_event WHERE booking_id = :id
                   AND event_type = 'BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION'""", booking.bookingId());
    }

    private static long feeDecisions(Booking booking) {
        return OWNER.sql("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    private static int consecutiveNoShows(UUID customerId) {
        return OWNER.sql("SELECT COALESCE(consecutive_no_shows, 0) FROM trust.trust_projection WHERE user_id = :id")
                .param("id", customerId).query(Integer.class).optional().orElse(0);
    }
}
