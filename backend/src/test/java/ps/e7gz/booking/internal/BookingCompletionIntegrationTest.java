package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;

/**
 * E7-61, T5 (O12) through the real deadline worker against the real database: ACTIVE -> COMPLETED as of the stored
 * {@code endAt} with the reservation released at {@code endAt}, the attendance default (arch 07 section 7.3) and - for an
 * Online Booking - its Trust outcome and Loyalty reconciliation, {@code BookingCompleted} and {@code AttendanceResolved},
 * all in the fire command with the job's DONE; the database's own guards accept the whole at commit. Each booking is
 * moved back whole past its end ({@link BookingFlows#pastItsEnd}) after its evidence was recorded through the real
 * commands, so a fire is "late" by that amount and must give the on-time outcome (arch 08 section 2.2). The exact
 * second of the end is the boundary test's (Phase 2); the customer notification is
 * {@code BookingNotificationsIntegrationTest}'s.
 */
class BookingCompletionIntegrationTest extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ApplicationContext context;

    BookingFlows flows;
    AttendanceFlows attendance;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------- the attendance defaults

    /**
     * Rule 1: a valid check-in completes ATTENDED - BOOKING_ATTENDED at startAt, the counter at zero, one attended
     * completion toward Verified Player and the attended reward (the launch value, 200) credited once.
     */
    @Test
    void aCheckedInBookingCompletesAttendedWithItsTrustOutcomeAndReward() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        booking = flows.pastItsEnd(booking, Duration.ofMinutes(10));

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertCompletedAtEndAt(booking);
        assertResolved(booking, "ATTENDED");
        assertThat(trustOutcomes(booking)).containsExactly("BOOKING_ATTENDED");
        assertThat(consecutiveNoShows(booking)).isZero();
        assertThat(completedAttended(booking)).isOne();
        assertThat(points(booking)).containsExactly("ATTENDED_COMPLETION 200");
        assertThat(entitlements(booking)).containsExactly(200);
        assertThat(payload(booking, IntegrationEvents.ATTENDANCE_RESOLVED).path("attendanceResult").asText())
                .isEqualTo("ATTENDED");
    }

    /**
     * Rule 2 behind Mark Present - Late (FR-ATT-03): ATTENDANCE_UNCONFIRMED - BOOKING_ATTENDANCE_UNCONFIRMED, no points,
     * no counter reset (the customer's earlier no-show still counts) and no Verified Player progress.
     */
    @Test
    void aLatePresentBookingCompletesUnconfirmedWithNoRewardNoResetAndNoVerifiedProgress() {
        BookingFlows.Customer customer = flows.customer();
        Booking missed = customer.nextActive(Duration.ofMinutes(20));
        attendance.noShow(missed.venueAccountId(), missed.bookingId());
        Booking late = customer.nextActive(Duration.ofMinutes(20));
        attendance.latePresent(late.venueAccountId(), late.bookingId());
        late = flows.pastItsEnd(late, Duration.ofMinutes(10));
        assertThat(consecutiveNoShows(late)).isOne();

        assertThat(flows.fireCompletion(late)).isTrue();

        assertCompletedAtEndAt(late);
        assertResolved(late, "ATTENDANCE_UNCONFIRMED");
        assertThat(trustOutcomes(late)).containsExactly("BOOKING_ATTENDANCE_UNCONFIRMED");
        assertThat(consecutiveNoShows(late)).as("no reset").isOne();
        assertThat(completedAttended(late)).as("no Verified Player progress").isZero();
        assertThat(points(late)).as("no reward").isEmpty();
        assertThat(entitlements(late)).as("reconciled to nothing").containsExactly(0);
    }

    /** Rule 2 with no evidence at all: ATTENDANCE_UNCONFIRMED, its record created by the completion. */
    @Test
    void aBookingWithoutEvidenceCompletesUnconfirmed() {
        Booking booking = flows.pastItsEnd(flows.active(Duration.ofMinutes(5)), Duration.ofMinutes(10));

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertCompletedAtEndAt(booking);
        assertResolved(booking, "ATTENDANCE_UNCONFIRMED");
        assertThat(trustOutcomes(booking)).containsExactly("BOOKING_ATTENDANCE_UNCONFIRMED");
        assertThat(points(booking)).isEmpty();
    }

    /**
     * Rule 2 (arch 07 sections 7.3 and 7.4): a check-in a Platform Admin reversed is not a valid check-in, so the
     * booking completes ATTENDANCE_UNCONFIRMED - no reward, no Verified Player progress - and the database agrees
     * ({@code attendance.assert_attendance_row} reads only unreversed evidence). The reversal itself is E7-70's (no
     * command writes it yet), so its marker is planted past the triggers; the reversed evidence stays recorded.
     */
    @Test
    void aReversedCheckInIsNoValidEvidenceAndCompletesUnconfirmed() {
        Booking started = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(started.venueAccountId(), started.bookingId());
        Booking booking = flows.pastItsEnd(started, Duration.ofMinutes(10));
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE attendance.attendance_record SET check_in_reversed_at = check_in_at + interval '1 minute'
                         WHERE booking_id = :id""").param("id", booking.bookingId()).update());

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertCompletedAtEndAt(booking);
        assertResolved(booking, "ATTENDANCE_UNCONFIRMED");
        assertThat(trustOutcomes(booking)).containsExactly("BOOKING_ATTENDANCE_UNCONFIRMED");
        assertThat(completedAttended(booking)).isZero();
        assertThat(points(booking)).isEmpty();
        assertThat(OWNER.sql("SELECT check_in_at IS NOT NULL FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Boolean.class).single()).as("the evidence stays").isTrue();
    }

    /**
     * Rule 5 and FR-MAN-06 (E7-48 carry-forward):a walk-in completes with its own T5, NOT_APPLICABLE, no Trust outcome
     * and no reward; both events carry {@code source: MANUAL} and no customer.
     */
    @Test
    void aWalkInCompletesNotApplicableWithoutTrustOrReward() {
        Booking booking = flows.pastItsEnd(flows.walkIn(), Duration.ofMinutes(10));

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertCompletedAtEndAt(booking);
        assertResolved(booking, "NOT_APPLICABLE");
        assertThat(trustOutcomes(booking)).isEmpty();
        assertThat(entitlements(booking)).isEmpty();
        assertThat(points(booking)).isEmpty();
        for (String type : List.of(IntegrationEvents.BOOKING_COMPLETED, IntegrationEvents.ATTENDANCE_RESOLVED)) {
            JsonNode payload = payload(booking, type);
            assertThat(payload.path("source").asText()).as(type).isEqualTo("MANUAL");
            assertThat(payload.path("customerId").isNull()).as(type).isTrue();
        }
        assertThat(payload(booking, IntegrationEvents.ATTENDANCE_RESOLVED).path("attendanceResult").asText())
                .isEqualTo("NOT_APPLICABLE");
    }

    // ------------------------------------------------------------------------------------------- the deadline

    /**
     * Sprint 2 review (12 Oct 2026): a BOOKING_COMPLETE fired hours late completes as of the stored endAt - terminal,
     * history and release dated endAt. The attendance resolution and the Trust outcome are the command instant, as the
     * database requires (the outcome's occurredAt is the shared command instant and equals resolved_at); replay orders
     * the outcome by its effectiveAt, the booking's startAt, so the late fire has the on-time effect.
     */
    @Test
    void anOverdueCompletionIsDatedAtTheStoredEndNotTheRunTime() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        booking = flows.pastItsEnd(booking, Duration.ofHours(5));
        Instant endAt = BookingFlows.endAt(booking.bookingId());

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertCompletedAtEndAt(booking);
        Instant resolvedAt = resolvedAt(booking);
        assertThat(resolvedAt).as("the run time, hours after endAt").isAfter(endAt.plus(Duration.ofHours(4)));
        assertThat(trustInstants(booking)).containsExactly(booking.startAt() + " " + resolvedAt);
        assertThat(points(booking)).containsExactly("ATTENDED_COMPLETION 200");
    }

    /** FR-AVL-15: a T5 that fires before the stored endAt changes nothing and waits for endAt. */
    @Test
    void anEarlyCompletionIsRescheduledToTheStoredEnd() {
        Booking booking = flows.active(Duration.ofMinutes(5));

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertThat(booking(booking.bookingId()).status()).isEqualTo("ACTIVE");
        assertThat(DueJobs.job(complete(booking))).satisfies(job -> {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.dueAt()).isEqualTo(BookingFlows.endAt(booking.bookingId()));
        });
        assertNothingCompleted(booking);
        assertThat(activeReservations(booking)).isOne();
    }

    /**
     * A T5 that finds the booking no longer ACTIVE - here a committed no-show (E7-59) - is SUPERSEDED and writes
     * nothing: no transition, attendance change, Trust outcome, reward or event.
     */
    @Test
    void aCompletionOfABookingNoLongerActiveChangesNothing() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        attendance.noShow(booking.venueAccountId(), booking.bookingId());
        int historyBefore = history(booking.bookingId()).size();
        List<String> outboxBefore = outboxTypes(booking.bookingId());

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertThat(DueJobs.job(complete(booking)).status()).isEqualTo("SUPERSEDED");
        assertThat(booking(booking.bookingId()).status()).isEqualTo("NO_SHOW");
        assertThat(history(booking.bookingId())).hasSize(historyBefore);
        assertThat(outboxTypes(booking.bookingId())).isEqualTo(outboxBefore);
        assertThat(attendanceResults(booking)).containsExactly("ABSENT_NO_SHOW");
        assertThat(trustOutcomes(booking)).containsExactly("BOOKING_NO_SHOW");
    }

    /** A completed booking is completed once: its T5 is DONE, and both events exist once. */
    @Test
    void aCompletionRunsOnce() {
        Booking booking = flows.pastItsEnd(flows.active(Duration.ofMinutes(5)), Duration.ofMinutes(10));
        assertThat(flows.fireCompletion(booking)).isTrue();

        assertThat(flows.fireCompletion(booking)).as("DONE never fires again").isFalse();

        assertThat(DueJobs.job(complete(booking)).status()).isEqualTo("DONE");
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_COMPLETED,
                IntegrationEvents.ATTENDANCE_RESOLVED);
        assertThat(history(booking.bookingId()).stream().filter(row -> "COMPLETED".equals(row.toStatus())))
                .hasSize(1);
    }

    // ------------------------------------------------------------------------------------------- atomicity

    /**
     * D11 (E7-47): an ACTIVE booking without its reservation is a data defect - the fire fails before any write, the
     * job backs off, and one SEV_1 booking integrity alert is raised after the rollback.
     */
    @Test
    void aCompletionWithoutItsReservationRollsBackWithItsAlert() {
        Booking booking = flows.pastItsEnd(flows.active(Duration.ofMinutes(5)), Duration.ofMinutes(10));
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE inventory.resource_reservation
                           SET active_flag = false, actual_reservation_release_at = actual_reservation_start,
                               release_reason = 'E7-61 test defect'
                         WHERE booking_id = :id AND active_flag""").param("id", booking.bookingId()).update());

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertNotCompletedAndBackedOff(booking);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM admin.admin_alert
                         WHERE subject_id = :id AND kind = 'BOOKING_INTEGRITY_FAILURE'""")
                .param("id", booking.bookingId()).query(Long.class).single()).isOne();
    }

    /**
     * A failure after the booking's own writes - here an attendance record already resolved, which no Product flow
     * leaves on an ACTIVE booking (FR-ATT-09: a stored result is never re-derived) - rolls back everything the fire
     * wrote: the transition, its history row, the release and the event; the stored result is untouched.
     */
    @Test
    void aFailureAfterTheCompletionWritesRollsBackWhole() {
        Booking booking = flows.pastItsEnd(flows.active(Duration.ofMinutes(5)), Duration.ofMinutes(10));
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO attendance.attendance_record (id, booking_id, result, resolved_at)
                        VALUES (gen_random_uuid(), :id, 'ATTENDANCE_UNCONFIRMED', now() - interval '1 hour')""")
                .param("id", booking.bookingId()).update());
        Instant plantedAt = resolvedAt(booking);

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertNotCompletedAndBackedOff(booking);
        assertThat(attendanceResults(booking)).containsExactly("ATTENDANCE_UNCONFIRMED");
        assertThat(resolvedAt(booking)).isEqualTo(plantedAt);
    }

    // ------------------------------------------------------------------------------- E7-60 start and R1

    /**
     * E7-60 (Sprint 2 review): a T4 fired after endAt starts the booking dated startAt and schedules a T5 already due;
     * that T5 then completes it dated endAt - the start before the completion in the booking's history.
     */
    @Test
    void aLateStartThenItsCompletionGiveTheOnTimeOutcome() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(90));
        assertThat(flows.fireStart(booking)).isTrue();

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertCompletedAtEndAt(booking);
        assertThat(history(booking.bookingId()).stream().map(row -> row.fromStatus() + "->" + row.toStatus())
                .filter(transition -> transition.endsWith("ACTIVE") || transition.endsWith("COMPLETED")))
                .containsExactly("CONFIRMED->ACTIVE", "ACTIVE->COMPLETED");
        assertResolved(booking, "ATTENDANCE_UNCONFIRMED");
    }

    /**
     * R1 (PO decision 19 Oct 2026): a check-in behind a delayed start starts the booking as T4 would, with its T5 at
     * endAt; that T5 completes the booking ATTENDED.
     */
    @Test
    void aCheckInBehindADelayedStartCompletesAttendedThroughTheT5ItsStartScheduled() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        assertThat(DueJobs.job(complete(booking)).status()).as("scheduled by the start").isEqualTo("PENDING");
        booking = flows.pastItsEnd(booking, Duration.ofMinutes(10));

        assertThat(flows.fireCompletion(booking)).isTrue();

        assertCompletedAtEndAt(booking);
        assertResolved(booking, "ATTENDED");
        assertThat(trustOutcomes(booking)).containsExactly("BOOKING_ATTENDED");
    }

    /**
     * R1: a no-show behind a delayed start ends the booking NO_SHOW and withdraws the T5 its start scheduled - no
     * completion ever follows.
     */
    @Test
    void aNoShowBehindADelayedStartLeavesNoCompletion() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        attendance.noShow(booking.venueAccountId(), booking.bookingId());

        assertThat(flows.fireCompletion(booking)).as("withdrawn, never fires").isFalse();

        assertThat(DueJobs.job(complete(booking)).status()).isEqualTo("SUPERSEDED");
        assertThat(booking(booking.bookingId()).status()).isEqualTo("NO_SHOW");
        assertNothingCompleted(booking);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * ACTIVE -> COMPLETED as of endAt: terminal_at endAt, no reason or fee treatment, one SYSTEM history row dated
     * endAt, the reservation released at endAt with its planned interval kept, both events once and T5 DONE.
     */
    private static void assertCompletedAtEndAt(Booking booking) {
        Instant endAt = BookingFlows.endAt(booking.bookingId());
        VenueAnswerDb.BookingRow row = booking(booking.bookingId());
        assertThat(row.status()).isEqualTo(BookingStatus.COMPLETED.name());
        assertThat(row.terminalAt()).isEqualTo(endAt);
        assertThat(row.terminalReason()).isNull();
        assertThat(row.feeTreatment()).isNull();
        assertThat(row.counted()).as("no commitment once terminal").isFalse();
        assertThat(history(booking.bookingId()).getLast()).satisfies(h -> {
            assertThat(h.fromStatus() + "->" + h.toStatus()).isEqualTo("ACTIVE->COMPLETED");
            assertThat(h.actorType()).isEqualTo("SYSTEM");
            assertThat(h.actorId()).isNull();
            assertThat(h.occurredAt()).isEqualTo(endAt);
        });
        assertThat(activeReservations(booking)).isZero();
        assertThat(OWNER.sql("""
                        SELECT actual_reservation_release_at, release_reason, planned_end
                          FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", booking.bookingId())
                .query((rs, n) -> rs.getObject("actual_reservation_release_at", OffsetDateTime.class).toInstant()
                        + " " + rs.getString("release_reason") + " "
                        + rs.getObject("planned_end", OffsetDateTime.class).toInstant())
                .single()).as("released at endAt, planned interval kept").isEqualTo(endAt + " COMPLETED " + endAt);
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_COMPLETED,
                IntegrationEvents.ATTENDANCE_RESOLVED);
        JsonNode completed = payload(booking, IntegrationEvents.BOOKING_COMPLETED);
        assertThat(completed.path("bookingId").asText()).isEqualTo(booking.bookingId().toString());
        assertThat(completed.path("endAt").asText()).isEqualTo(endAt.toString());
        assertThat(DueJobs.job(complete(booking)).status()).isEqualTo("DONE");
    }

    /** The stored result, resolved once at the command instant, never before endAt. */
    private static void assertResolved(Booking booking, String result) {
        assertThat(attendanceResults(booking)).containsExactly(result);
        assertThat(resolvedAt(booking)).isAfterOrEqualTo(BookingFlows.endAt(booking.bookingId()));
    }

    /** Still ACTIVE and reserved, no completion written, T5 backed off (PENDING, one attempt used). */
    private static void assertNotCompletedAndBackedOff(Booking booking) {
        assertThat(booking(booking.bookingId()).status()).isEqualTo("ACTIVE");
        assertThat(booking(booking.bookingId()).terminalAt()).isNull();
        assertNothingCompleted(booking);
        assertThat(DueJobs.job(complete(booking))).satisfies(job -> {
            assertThat(job.status()).as("backed off").isEqualTo("PENDING");
            assertThat(job.attempt()).isOne();
        });
    }

    private static void assertNothingCompleted(Booking booking) {
        assertThat(history(booking.bookingId())).noneMatch(row -> "COMPLETED".equals(row.toStatus()));
        assertThat(outboxTypes(booking.bookingId())).doesNotContain(IntegrationEvents.BOOKING_COMPLETED,
                IntegrationEvents.ATTENDANCE_RESOLVED);
        assertThat(trustOutcomes(booking)).doesNotContain("BOOKING_ATTENDED", "BOOKING_ATTENDANCE_UNCONFIRMED");
        assertThat(entitlements(booking)).doesNotContain(200);
    }

    private static String complete(Booking booking) {
        return BookingFlows.bookingCompleteKey(booking.bookingId());
    }

    private static long activeReservations(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    private static List<String> attendanceResults(Booking booking) {
        return OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static Instant resolvedAt(Booking booking) {
        return OWNER.sql("SELECT resolved_at FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant();
    }

    private static List<String> trustOutcomes(Booking booking) {
        return OWNER.sql("SELECT event_type FROM trust.trust_event WHERE booking_id = :id ORDER BY event_sequence")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    /** "effectiveAt occurredAt" of the booking's Trust outcomes. */
    private static List<String> trustInstants(Booking booking) {
        return OWNER.sql("""
                        SELECT effective_at, occurred_at FROM trust.trust_event WHERE booking_id = :id
                         ORDER BY event_sequence""")
                .param("id", booking.bookingId())
                .query((rs, n) -> rs.getObject("effective_at", OffsetDateTime.class).toInstant() + " "
                        + rs.getObject("occurred_at", OffsetDateTime.class).toInstant())
                .list();
    }

    private static int consecutiveNoShows(Booking booking) {
        return OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single();
    }

    private static int completedAttended(Booking booking) {
        return OWNER.sql("SELECT completed_attended_count FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single();
    }

    /** "CAUSE amount" of the booking's points transactions. */
    private static List<String> points(Booking booking) {
        return OWNER.sql("""
                        SELECT cause || ' ' || amount FROM loyalty.points_transaction WHERE booking_id = :id
                         ORDER BY ledger_sequence""")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    /** The {@code entitlement_after} of the booking's reward applications, oldest first. */
    private static List<Integer> entitlements(Booking booking) {
        return OWNER.sql("""
                        SELECT entitlement_after FROM loyalty.reward_application WHERE booking_id = :id
                         ORDER BY application_sequence""")
                .param("id", booking.bookingId()).query(Integer.class).list();
    }

    private static JsonNode payload(Booking booking, String type) {
        List<String> payloads = VenueAnswerDb.events(type, booking.bookingId());
        assertThat(payloads).as(type).hasSize(1);
        try {
            return JSON.readTree(payloads.getFirst());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
