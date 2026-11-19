package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-118, T6 (FR-BOK-23, Spec v5 section 24.3) through the real deadline worker against the real database. T1 arms it
 * at the original startAt; a booking still VENUE_RESPONSE_EXPIRED there becomes CANCELLED with exactly one reason by
 * the frozen priority, judged as of startAt, with its full refund and one BookingCancelled; a Late Confirm or a Reject
 * After Expiry withdraws it. Every booking fired here was expired by the real T1 and moved back whole so its startAt
 * has passed ({@link BookingFlows#expiredWithOverdueStart}): a fire is late by that much and must give the on-time
 * outcome. The exact second of startAt is {@link ExpiredRequestResolutionBoundaryTest}'s.
 */
class ExpiredRequestResolutionIntegrationTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ExpiredRequestResolutions resolutions;

    @Autowired
    CommandRunner runner;

    BookingFlows flows;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // --------------------------------------------------------------------------------------------- scheduling

    /** The T1 that expires the request arms T6 in the same command, due at the stored startAt. */
    @Test
    void theExpiryArmsT6AtTheOriginalStart() {
        Booking booking = flows.pending();

        flows.expire(booking);

        assertThat(DueJobs.job(t6(booking))).satisfies(job -> {
            assertThat(job.type()).isEqualTo(ExpiredRequestResolutions.VENUE_RESPONSE_EXPIRED_RESOLUTION);
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.dueAt()).isEqualTo(booking.startAt());
        });
    }

    /** A request accepted in time never expires, so it never gets a T6. */
    @Test
    void anAcceptedRequestHasNoT6() {
        Booking booking = flows.pending();

        flows.accept(booking);

        assertThat(VenueAnswerDb.job(t6(booking))).isEmpty();
    }

    // -------------------------------------------------------------------------------------- the one reason

    /** Done when: no cause at startAt - VENUE_DID_NOT_RESPOND_IN_TIME, everything dated startAt; T6 DONE. */
    @Test
    void aRequestStillExpiredAtItsStartIsCancelledForTheVenuesNonResponse() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofSeconds(5));

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_DID_NOT_RESPOND_IN_TIME");
    }

    /** FR-BOK-23: all four overriding causes at once - exactly one reason, the first. */
    @Test
    void ownerReviewWinsOverEveryOtherCause() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofSeconds(30));
        Instant before = booking.startAt().minus(Duration.ofMinutes(1));
        flows.ownerReviewSince(booking, before);
        flows.resourceEmergencySince(booking, before);
        venueEmergencySince(booking, before);
        accountDisabled(booking, before, null);

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "RESOURCE_OWNER_REVIEW_REQUIRED");
    }

    @Test
    void aResourceEmergencyWinsOverAVenueEmergencyAndADisabledAccount() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofSeconds(30));
        Instant before = booking.startAt().minus(Duration.ofMinutes(1));
        flows.resourceEmergencySince(booking, before);
        venueEmergencySince(booking, before);
        accountDisabled(booking, before, null);

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "RESOURCE_EMERGENCY_OUTAGE");
    }

    @Test
    void aVenueEmergencyWinsOverADisabledAccount() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofSeconds(30));
        Instant before = booking.startAt().minus(Duration.ofMinutes(1));
        venueEmergencySince(booking, before);
        accountDisabled(booking, before, null);

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_EMERGENCY_CLOSURE");
    }

    /** Spec v5 section 24.3 rung 4: an account disable is a platform cause, never ordinary non-response. */
    @Test
    void aVenueAccountStillDisabledAtTheStartIsTheReason() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofSeconds(30));
        accountDisabled(booking, booking.startAt().minus(Duration.ofMinutes(1)), null);

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_ACCOUNT_DISABLED");
    }

    /**
     * PO decisions D1 and D3 (19-20 Oct 2026): a Venue Closure over the booking and a DISABLED room are not reasons of
     * FR-BOK-23's frozen list - still VENUE_DID_NOT_RESPOND_IN_TIME, and no integrity alert (that is CONFIRMED's).
     */
    @Test
    void aClosureAndADisabledRoomAreNotReasonsOfTheFrozenList() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofSeconds(30));
        closure(booking);
        flows.resourceDisabledBehindTheGuards(booking);

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_DID_NOT_RESPOND_IN_TIME");
        assertThat(VenueAnswerDb.adminAlerts(booking.bookingId())).isEmpty();
    }

    // ------------------------------------------------------------------------- judged at startAt, not at the fire

    /** A Resource Emergency unresolved at startAt and ENDED since still decides a late T6. */
    @Test
    void aLateT6IsJudgedAtStartAtNotWhenItFires() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceEmergencyEnded(booking, booking.startAt().minus(Duration.ofMinutes(5)),
                booking.startAt().plus(Duration.ofMinutes(2)));

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "RESOURCE_EMERGENCY_OUTAGE");
    }

    /** An account disabled at startAt and re-enabled before the late fire: still VENUE_ACCOUNT_DISABLED. */
    @Test
    void anAccountDisabledAtStartAtAndReEnabledSinceIsStillTheReason() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofMinutes(20));
        accountDisabled(booking, booking.startAt().minus(Duration.ofMinutes(5)),
                booking.startAt().plus(Duration.ofMinutes(2)));

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_ACCOUNT_DISABLED");
    }

    /** Causes that arose only after startAt - or ended before it - do not count. */
    @Test
    void causesThatBeganAfterStartAtOrEndedBeforeItDoNotCount() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofMinutes(20));
        Instant after = booking.startAt().plus(Duration.ofMinutes(2));
        flows.ownerReviewSince(booking, after);
        flows.resourceEmergencySince(booking, after);
        accountDisabled(booking, after, null);
        accountDisabled(booking, booking.startAt().minus(Duration.ofMinutes(30)),
                booking.startAt().minus(Duration.ofMinutes(10)));

        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_DID_NOT_RESPOND_IN_TIME");
    }

    // ------------------------------------------------------------------------------- once, and only while expired

    /** A DONE job never fires again: one transition, one refund decision, one event. */
    @Test
    void aT6FiredTwiceCancelsOnce() {
        Booking booking = flows.expiredWithOverdueStart(Duration.ofSeconds(5));
        assertThat(flows.fireExpiredResolution(booking)).isTrue();

        assertThat(flows.fireExpiredResolution(booking)).isFalse();

        assertCancelledAtStart(booking, "VENUE_DID_NOT_RESPOND_IN_TIME");
    }

    /** A Late Confirm takes the booking out of VENUE_RESPONSE_EXPIRED: its T6 is withdrawn in the same command. */
    @Test
    void aLateConfirmWithdrawsT6AndLeavesTheBookingUntouched() {
        Booking booking = flows.lateConfirmed();

        assertThat(DueJobs.job(t6(booking)).status()).isEqualTo("SUPERSEDED");
        assertThat(booking(booking.bookingId()).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
    }

    /** Reject After Expiry ends the request itself: its T6 is withdrawn in the same command. */
    @Test
    void aRejectAfterExpiryWithdrawsT6() {
        Booking booking = flows.pending();
        flows.expire(booking);

        flows.rejectAfterExpiry(booking);

        assertThat(DueJobs.job(t6(booking)).status()).isEqualTo("SUPERSEDED");
        assertThat(booking(booking.bookingId()).terminalReason()).isEqualTo("REJECTED_AFTER_EXPIRY");
    }

    /**
     * A T6 that finds its booking no longer VENUE_RESPONSE_EXPIRED - here rejected after expiry - supersedes itself and
     * writes nothing, whatever the time.
     */
    @Test
    void aT6OfABookingNoLongerExpiredIsSupersededAndWritesNothing() {
        Booking booking = flows.pending();
        flows.expire(booking);
        flows.rejectAfterExpiry(booking);
        List<String> events = outboxTypes(booking.bookingId());

        JobOutcome outcome = runner.run(TestCommands.under(flows.scope(booking), command -> resolutions.resolve(
                command, new ScheduledJob(UUID.randomUUID(), ExpiredRequestResolutions.VENUE_RESPONSE_EXPIRED_RESOLUTION,
                        CreateOnlineBookingCommand.BOOKING_SUBJECT, booking.bookingId(), booking.startAt(), 1,
                        t6(booking), CorrelationId.generate()))));

        assertThat(outcome).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(booking(booking.bookingId()).status()).isEqualTo("REJECTED");
        assertThat(outboxTypes(booking.bookingId())).isEqualTo(events);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * Section 24.3 as T6 writes it: CANCELLED under {@code reason} dated startAt, FULL_REFUND with its one fee
     * decision, the SYSTEM history row VENUE_RESPONSE_EXPIRED -> CANCELLED, one BookingCancelled, no reservation, no
     * attendance row (NOT_APPLICABLE by default), no penalty; T6 DONE.
     */
    private static void assertCancelledAtStart(Booking booking, String reason) {
        VenueAnswerDb.BookingRow row = booking(booking.bookingId());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(reason);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.terminalAt()).isEqualTo(booking.startAt());
        assertThat(history(booking.bookingId()).getLast()).satisfies(h -> {
            assertThat(h.fromStatus() + "->" + h.toStatus()).isEqualTo("VENUE_RESPONSE_EXPIRED->CANCELLED");
            assertThat(h.terminalReason()).isEqualTo(reason);
            assertThat(h.actorType()).isEqualTo("SYSTEM");
            assertThat(h.actorId()).isNull();
            assertThat(h.occurredAt()).isEqualTo(booking.startAt());
        });
        assertThat(history(booking.bookingId()).stream().filter(h -> "CANCELLED".equals(h.toStatus()))).hasSize(1);
        assertThat(OWNER.sql("SELECT treatment FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list()).containsExactly("FULL_REFUND");
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_CANCELLED);
        assertThat(VenueAnswerDb.events(IntegrationEvents.BOOKING_CANCELLED, booking.bookingId()).getFirst())
                .contains("terminalReason").contains("\"" + reason + "\"");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM trust.trust_event WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).as("no penalty").isZero();
        assertThat(DueJobs.job(t6(booking)).status()).isEqualTo("DONE");
    }

    private static String t6(Booking booking) {
        return BookingFlows.expiredResolutionKey(booking.bookingId());
    }

    /** A restriction interval of the venue's account from {@code disabledAt}, re-enabled at {@code reenabledAt}. */
    private static void accountDisabled(Booking booking, Instant disabledAt, Instant reenabledAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO iam.venue_account_restriction_interval (id, venue_id, disabled_at, reenabled_at,
                                                                            source, reason, correlation_id)
                        VALUES (gen_random_uuid(), :venue, :disabled, CAST(:reenabled AS timestamptz), 'SYSTEM',
                                'E7-118 test', gen_random_uuid())""")
                .param("venue", booking.venueId())
                .param("disabled", utc(disabledAt))
                .param("reenabled", reenabledAt == null ? null : utc(reenabledAt)).update());
    }

    /** A Venue Emergency declared at {@code declaredAt}, unresolved, with its selling block. */
    private static void venueEmergencySince(Booking booking, Instant declaredAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        WITH e AS (
                            INSERT INTO operations.emergency (id, scope, venue_id, status, declared_at, expected_end_at,
                                                              declared_period, reason)
                            VALUES (gen_random_uuid(), 'VENUE', :venue, 'ACTIVE', :declared,
                                    CAST(:declared AS timestamptz) + interval '2 hours',
                                    tstzrange(:declared, CAST(:declared AS timestamptz) + interval '2 hours', '[)'),
                                    'E7-118 test')
                            RETURNING id, declared_at)
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
                        SELECT gen_random_uuid(), :venue, 'EMERGENCY', e.id, true, e.declared_at FROM e""")
                .param("venue", booking.venueId()).param("declared", utc(declaredAt)).update());
    }

    /** A Venue Closure over the booked interval. */
    private static void closure(Booking booking) {
        Instant end = BookingFlows.endAt(booking.bookingId());
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                        VALUES (gen_random_uuid(), :venue, tstzrange(:start, :end, '[)'), 'PARTIAL', 'E7-118 closure')""")
                .param("venue", booking.venueId())
                .param("start", utc(booking.startAt())).param("end", utc(end)).update());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
