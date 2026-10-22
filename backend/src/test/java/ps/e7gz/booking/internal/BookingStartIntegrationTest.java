package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-60, T4 (O11) through the real deadline worker against the real database: the start-time revalidation and the
 * CONFIRMED -> ACTIVE transition, or the start-time cancellation under the one reason of Spec v5 section 31.6, commit in
 * the fire command with the job's DONE (section 31.1). Every booking here is CONFIRMED with its stored {@code startAt}
 * already passed and its BOOKING_START still PENDING ({@link BookingFlows#confirmedWithOverdueStart}), so a fire is
 * "late" by the overdue amount and must give the on-time outcome (arch 08 section 2.2). The exact second of the start is
 * {@link BookingStartBoundaryTest}'s; the delayed-start no-show racing T4 is {@code AttendanceIntegrationTest}'s.
 */
class BookingStartIntegrationTest extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ApplicationContext context;

    @Autowired
    BookingStarts starts;

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

    // ------------------------------------------------------------------------------------------------- start

    /**
     * Every gate passes: CONFIRMED -> ACTIVE, one SYSTEM history row dated startAt, one BookingStarted, the reservation
     * kept over the booked interval and BOOKING_COMPLETE scheduled at endAt (D14, 11 Oct 2026); T4 DONE.
     */
    @Test
    void aStartWithEveryGatePassingActivatesTheBooking() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));

        assertThat(flows.fireStart(booking)).isTrue();

        assertStarted(booking);
        assertThat(attendance(booking)).as("no attendance result at the start").isEmpty();
    }

    /**
     * Sprint 2 review (12 Oct 2026): a BOOKING_START that waited since Sprint 2 fires hours late, after endAt - still
     * the on-time start, dated startAt, and the BOOKING_COMPLETE it schedules is already due, so it fires right after.
     */
    @Test
    void aStartFiredAfterEndAtGivesTheOnTimeOutcomeAndACompletionAlreadyDue() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(90));

        assertThat(flows.fireStart(booking)).isTrue();

        assertStarted(booking);
        assertThat(DueJobs.job(complete(booking)).dueAt()).as("due at once").isBefore(VenueAnswerDb.dbNow());
    }

    /** Spec v5 section 31.5: a Venue Account disable alone never cancels an already-CONFIRMED booking. */
    @Test
    void aDisabledVenueAccountAloneDoesNotStopTheStart() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        OWNER.sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", booking.venueAccountId()).update();

        assertThat(flows.fireStart(booking)).isTrue();

        assertStarted(booking);
    }

    /** A DONE job never fires again: one transition, one event, one completion job. */
    @Test
    void aStartFiredTwiceStartsOnce() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        assertThat(flows.fireStart(booking)).isTrue();

        assertThat(flows.fireStart(booking)).isFalse();
        assertThat(flows.fireStartIfDue(booking)).isFalse();

        assertStarted(booking);
    }

    /** Two workers racing for one due T4: the lease lets exactly one fire; the start is written once. */
    @Test
    void twoWorkersRacingForOneStartStartItOnce() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        flows.makeStartDue(booking);

        ConcurrentRace.Result<Boolean> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            start.await();
            return flows.fireStartIfDue(booking);
        });

        assertThat(race.failures()).isEmpty();
        assertThat(race.values()).containsExactlyInAnyOrder(true, false);
        assertStarted(booking);
    }

    /** E7-48 D8 / FR-MAN-04: a future Manual Booking starts at T4 too, with the MANUAL BookingStarted. */
    @Test
    void aFutureManualBookingStartsWithItsManualEvent() {
        Booking booking = flows.manualConfirmedWithOverdueStart(Duration.ofSeconds(5));

        assertThat(flows.fireStart(booking)).isTrue();

        assertStarted(booking);
        JsonNode started = payload(booking, IntegrationEvents.BOOKING_STARTED);
        assertThat(started.path("source").asText()).isEqualTo("MANUAL");
        assertThat(started.path("customerId").isNull()).isTrue();
    }

    // ------------------------------------------------------------------------------------- start-time failures

    /**
     * Done when (E7-60): forcing a resource DISABLED behind the predicate produces the integrity error and its alert -
     * CANCELLED under SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR dated startAt, FULL_REFUND with its one fee decision, the
     * room released at startAt, attendance NOT_APPLICABLE, one BookingCancelled, and one SEV_1 alert in the same
     * command carrying the full payload of arch 12 section 6.2 (FR-BOK-28).
     */
    @Test
    void aRoomDisabledBehindThePredicateCancelsWithTheIntegrityErrorAndItsAlert() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        flows.resourceDisabledBehindTheGuards(booking);

        assertThat(flows.fireStart(booking)).isTrue();

        assertCancelledAtStart(booking, "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        List<JsonNode> alerts = alerts(booking);
        assertThat(alerts).hasSize(1);
        JsonNode alert = alerts.getFirst();
        assertThat(alert.path("kind").asText()).isEqualTo("SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        assertThat(alert.path("severity").asText()).isEqualTo("SEV_1");
        JsonNode payload = alert.path("payload");
        assertThat(payload.path("bookingId").asText()).isEqualTo(booking.bookingId().toString());
        assertThat(payload.path("resourceId").asText()).isEqualTo(booking.resourceId().toString());
        assertThat(payload.path("unexpectedState").asText()).isEqualTo("DISABLED");
        assertThat(Instant.parse(payload.path("startAt").asText())).isEqualTo(booking.startAt());
        assertThat(Instant.parse(payload.path("detectedAt").asText())).isAfter(booking.startAt());
        assertThat(UUID.fromString(payload.path("correlationId").asText())).isNotNull();
    }

    /** Section 31.6: all five causes at once - exactly one reason, the first; no integrity alert. */
    @Test
    void ownerReviewWinsOverEveryOtherCause() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        Instant before = booking.startAt().minus(Duration.ofMinutes(1));
        flows.ownerReviewSince(booking, before);
        flows.resourceEmergencySince(booking, before);
        venueEmergencySince(booking, before);
        closure(booking);
        flows.resourceDisabledBehindTheGuards(booking);

        assertThat(flows.fireStart(booking)).isTrue();

        assertCancelledAtStart(booking, "RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(alerts(booking)).isEmpty();
    }

    @Test
    void aResourceEmergencyWinsOverAVenueEmergencyAClosureAndTheBaseState() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        Instant before = booking.startAt().minus(Duration.ofMinutes(1));
        flows.resourceEmergencySince(booking, before);
        venueEmergencySince(booking, before);
        closure(booking);
        flows.resourceDisabledBehindTheGuards(booking);

        assertThat(flows.fireStart(booking)).isTrue();

        assertCancelledAtStart(booking, "RESOURCE_EMERGENCY_OUTAGE");
        assertThat(alerts(booking)).isEmpty();
    }

    @Test
    void aVenueEmergencyWinsOverAClosure() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        venueEmergencySince(booking, booking.startAt().minus(Duration.ofMinutes(1)));
        closure(booking);

        assertThat(flows.fireStart(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_EMERGENCY_CLOSURE");
    }

    /** The defensive fallback of section 31.3: a closure still preventing service wins over the base state. */
    @Test
    void aClosureWinsOverTheBaseState() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        closure(booking);
        flows.resourceDisabledBehindTheGuards(booking);

        assertThat(flows.fireStart(booking)).isTrue();

        assertCancelledAtStart(booking, "VENUE_CLOSURE");
        assertThat(alerts(booking)).isEmpty();
    }

    /**
     * Judged as of startAt, never as of the late fire: a Resource Emergency unresolved at startAt and ENDED since still
     * cancels under RESOURCE_EMERGENCY_OUTAGE (section 31.3: the emergency itself, not its declared interval).
     */
    @Test
    void aLateStartIsJudgedAtStartAtNotWhenItFires() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceEmergencyEnded(booking, booking.startAt().minus(Duration.ofMinutes(5)),
                booking.startAt().plus(Duration.ofMinutes(2)));

        assertThat(flows.fireStart(booking)).isTrue();

        assertCancelledAtStart(booking, "RESOURCE_EMERGENCY_OUTAGE");
    }

    /** A Manual Booking cancelled at its start: no customer, so no fee decision; NOT_APPLICABLE; the alert. */
    @Test
    void aManualBookingOnADisabledRoomCancelsWithoutAFeeDecision() {
        Booking booking = flows.manualConfirmedWithOverdueStart(Duration.ofSeconds(5));
        flows.resourceDisabledBehindTheGuards(booking);

        assertThat(flows.fireStart(booking)).isTrue();

        BookingRowAssert.cancelled(booking, "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        assertThat(refunds(booking)).isEmpty();
        assertThat(attendance(booking)).containsExactly("NOT_APPLICABLE");
        assertThat(payload(booking, IntegrationEvents.BOOKING_CANCELLED).path("source").asText()).isEqualTo("MANUAL");
        assertThat(alerts(booking)).hasSize(1);
    }

    // ---------------------------------------------------------------------------- undetermined: nothing written

    /**
     * A selling block with no Emergency behind it is corrupted state, unreachable through any Product flow (Spec v5
     * sections 38.2 and 42.1). T4 guesses nothing - no start, no cancellation, no reason, no event - and escalates it
     * as one SEV_1 booking integrity failure, committed on its own although the fire rolled back; its retries fail the
     * same way and find it open, so they add none.
     */
    @Test
    void anOrphanedVenueSellingBlockIsEscalatedOnceAndWritesNothing() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        flows.venueSellingBlockSince(booking, booking.startAt().minus(Duration.ofMinutes(1)));

        assertThat(flows.fireStart(booking)).as("settled as a failed attempt").isTrue();
        assertThat(flows.fireStart(booking)).as("a retry").isTrue();
        assertThat(flows.fireStart(booking)).as("another retry").isTrue();

        assertNothingWritten(booking, 3);
        assertThat(VenueAnswerDb.adminAlerts(booking.bookingId()))
                .containsExactly("BOOKING_INTEGRITY_FAILURE SEV_1 " + booking.bookingId() + " true");
        assertThat(alertDetail(booking)).contains("VENUE_SELLING_BLOCK").contains("orphaned selling block");
    }

    /**
     * Deduplication is by the open alert, never by the attempt number: when the first attempt's alert never reached the
     * database (here removed, as a worker dying between its rollback and the raise would leave it), the next attempt
     * raises it, and the one after finds it open again.
     */
    @Test
    void aLostOrphanAlertIsRaisedByTheNextAttemptAndStillOnlyOnce() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        flows.venueSellingBlockSince(booking, booking.startAt().minus(Duration.ofMinutes(1)));
        assertThat(flows.fireStart(booking)).isTrue();
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("DELETE FROM admin.admin_alert WHERE subject_id = :id")
                .param("id", booking.bookingId()).update());

        assertThat(flows.fireStart(booking)).isTrue();
        assertThat(flows.fireStart(booking)).isTrue();

        assertNothingWritten(booking, 3);
        assertThat(VenueAnswerDb.adminAlerts(booking.bookingId()))
                .containsExactly("BOOKING_INTEGRITY_FAILURE SEV_1 " + booking.bookingId() + " true");
    }

    @Test
    void anOrphanedResourceSellingBlockIsEscalatedTheSameWay() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
                        VALUES (gen_random_uuid(), :room, 'E7-60 orphaned block', true, :at)""")
                .param("room", booking.resourceId())
                .param("at", OffsetDateTime.ofInstant(booking.startAt().minus(Duration.ofMinutes(1)), ZoneOffset.UTC))
                .update());

        assertThat(flows.fireStart(booking)).isTrue();

        assertNothingWritten(booking, 1);
        assertThat(alertDetail(booking)).contains("RESOURCE_SELLING_BLOCK");
    }

    /**
     * Why an orphan is the only block that can reach this rung: an Emergency's own block opens at its declaration and
     * closes with it (the database keeps them in lockstep). One that ENDED before startAt therefore leaves no block and
     * no cause at startAt, and the booking starts; one unresolved at startAt is cancelled under its own reason first
     * ({@link #aResourceEmergencyWinsOverAVenueEmergencyAClosureAndTheBaseState}).
     */
    @Test
    void anEmergencyAndItsBlockThatEndedBeforeStartAtDoNotStopTheStart() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(30));
        Instant declared = booking.startAt().minus(Duration.ofMinutes(30));
        Instant ended = booking.startAt().minus(Duration.ofMinutes(5));
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        WITH e AS (
                            INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                              expected_end_at, declared_period, reason, ended_at)
                            VALUES (gen_random_uuid(), 'RESOURCE', :venue, :room, 'ENDED', :declared,
                                    CAST(:declared AS timestamptz) + interval '10 minutes',
                                    tstzrange(:declared, CAST(:declared AS timestamptz) + interval '10 minutes', '[)'),
                                    'E7-60 test', :ended)
                            RETURNING id, declared_at, ended_at)
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active,
                                                                     started_at, ended_at)
                        SELECT gen_random_uuid(), :room, 'EMERGENCY', e.id, false, e.declared_at, e.ended_at FROM e""")
                .param("venue", booking.venueId()).param("room", booking.resourceId())
                .param("declared", OffsetDateTime.ofInstant(declared, ZoneOffset.UTC))
                .param("ended", OffsetDateTime.ofInstant(ended, ZoneOffset.UTC)).update());

        assertThat(flows.fireStart(booking)).isTrue();

        assertStarted(booking);
    }

    /**
     * Decision of Amr Heiba (18 Oct 2026, E7-59): an emergency that began after startAt is not a start-time cause - a
     * late T4 neither cancels nor penalises under it, and it is not corrupted state either (no integrity alert); it
     * writes nothing. Its intended handling - the valid start, then the ACTIVE interruption at the emergency's own
     * time - waits for E7-82's cascade.
     */
    @Test
    void anEmergencyThatBeganAfterStartAtNeitherCancelsNorStartsALateStart() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceEmergencySince(booking, booking.startAt().plus(Duration.ofMinutes(2)));

        assertThat(flows.fireStart(booking)).isTrue();

        assertNothingWritten(booking, 1);
        assertThat(alerts(booking)).isEmpty();
    }

    /** The same for an Owner Review entered after startAt (E7-84's cascade). */
    @Test
    void anOwnerReviewThatBeganAfterStartAtNeitherCancelsNorStartsALateStart() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.ownerReviewSince(booking, booking.startAt().plus(Duration.ofMinutes(2)));

        assertThat(flows.fireStart(booking)).isTrue();

        assertNothingWritten(booking, 1);
        assertThat(alerts(booking)).isEmpty();
    }

    /** D11: no reservation behind a CONFIRMED booking is a defect - rolled back whole, SEV_1 after the rollback. */
    @Test
    void aStartWithoutItsReservationRollsBackWhole() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE inventory.resource_reservation
                           SET active_flag = false, actual_reservation_release_at = actual_reservation_start,
                               release_reason = 'E7-60 test defect'
                         WHERE booking_id = :id AND active_flag""")
                .param("id", booking.bookingId()).update());

        assertThat(flows.fireStart(booking)).isTrue();

        assertThat(booking(booking.bookingId()).status()).isEqualTo("CONFIRMED");
        assertThat(history(booking.bookingId())).hasSize(2);
        assertThat(outboxTypes(booking.bookingId())).doesNotContain(IntegrationEvents.BOOKING_STARTED);
        assertThat(DueJobs.job(start(booking))).satisfies(job -> {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.attempt()).isOne();
        });
        assertThat(VenueAnswerDb.adminAlerts(booking.bookingId()))
                .containsExactly("BOOKING_INTEGRITY_FAILURE SEV_1 " + booking.bookingId() + " true");
    }

    // ----------------------------------------------------------------------------------- no longer CONFIRMED

    /**
     * A booking that left CONFIRMED - here cancelled at its start - is superseded by a later T4 without a write; the
     * database refuses a terminal booking's reactivation anyway (tr_03_booking_transition_guard).
     */
    @Test
    void aStartOfABookingNoLongerConfirmedIsSupersededAndWritesNothing() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        flows.resourceDisabledBehindTheGuards(booking);
        flows.fireStart(booking);
        List<String> events = outboxTypes(booking.bookingId());

        JobOutcome again = runner.run(TestCommands.under(flows.scope(booking), command -> starts.start(command,
                new ScheduledJob(UUID.randomUUID(), VenueAnswers.BOOKING_START,
                        CreateOnlineBookingCommand.BOOKING_SUBJECT, booking.bookingId(), booking.startAt(), 1,
                        start(booking), CorrelationId.generate()))));

        assertThat(again).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(booking(booking.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(outboxTypes(booking.bookingId())).isEqualTo(events);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void assertStarted(Booking booking) {
        assertThat(booking(booking.bookingId()).status()).isEqualTo("ACTIVE");
        assertThat(history(booking.bookingId()).getLast()).satisfies(row -> {
            assertThat(row.fromStatus() + "->" + row.toStatus()).isEqualTo("CONFIRMED->ACTIVE");
            assertThat(row.actorType()).isEqualTo("SYSTEM");
            assertThat(row.actorId()).isNull();
            assertThat(row.occurredAt()).isEqualTo(booking.startAt());
        });
        assertThat(history(booking.bookingId()).stream().filter(row -> "ACTIVE".equals(row.toStatus()))).hasSize(1);
        assertThat(activeReservations(booking)).isOne();
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_STARTED)
                .doesNotContain(IntegrationEvents.BOOKING_CANCELLED);
        assertThat(DueJobs.job(start(booking)).status()).isEqualTo("DONE");
        DueJobs.JobRow completion = DueJobs.job(complete(booking));
        assertThat(completion.type()).isEqualTo(ManualBookingCommand.BOOKING_COMPLETE);
        assertThat(completion.status()).isEqualTo("PENDING");
        assertThat(completion.dueAt()).isEqualTo(endAt(booking));
    }

    /** Section 31.3 as T4 writes it: the cancellation dated startAt, refund, attendance, one event, T4 DONE. */
    private static void assertCancelledAtStart(Booking booking, String reason) {
        BookingRowAssert.cancelled(booking, reason);
        assertThat(refunds(booking)).containsExactly("FULL_REFUND");
        assertThat(attendance(booking)).containsExactly("NOT_APPLICABLE");
        assertThat(OWNER.sql("SELECT count(*) FROM trust.trust_event WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).as("no penalty").isZero();
    }

    /** The parts common to an Online and a Manual start-time cancellation. */
    private static final class BookingRowAssert {

        static void cancelled(Booking booking, String reason) {
            VenueAnswerDb.BookingRow row = booking(booking.bookingId());
            assertThat(row.status()).isEqualTo("CANCELLED");
            assertThat(row.terminalReason()).isEqualTo(reason);
            assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
            assertThat(row.terminalAt()).isEqualTo(booking.startAt());
            assertThat(history(booking.bookingId()).getLast()).satisfies(h -> {
                assertThat(h.fromStatus() + "->" + h.toStatus()).isEqualTo("CONFIRMED->CANCELLED");
                assertThat(h.terminalReason()).isEqualTo(reason);
                assertThat(h.actorType()).isEqualTo("SYSTEM");
                assertThat(h.occurredAt()).isEqualTo(booking.startAt());
            });
            assertThat(activeReservations(booking)).isZero();
            assertThat(OWNER.sql("""
                            SELECT actual_reservation_release_at FROM inventory.resource_reservation
                             WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                    .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant())
                    .isEqualTo(booking.startAt());
            assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_CANCELLED)
                    .doesNotContain(IntegrationEvents.BOOKING_STARTED);
            assertThat(DueJobs.job(start(booking)).status()).isEqualTo("DONE");
            assertThat(VenueAnswerDb.job(complete(booking))).as("never started, never completed").isEmpty();
        }
    }

    /**
     * An undetermined start: still CONFIRMED and reserved, no history, event, refund or attendance, no start-time
     * alert; T4 backed off after {@code attempts} failed fires.
     */
    private static void assertNothingWritten(Booking booking, int attempts) {
        assertThat(booking(booking.bookingId()).status()).isEqualTo("CONFIRMED");
        assertThat(history(booking.bookingId())).hasSize(2);
        assertThat(activeReservations(booking)).isOne();
        assertThat(outboxTypes(booking.bookingId())).doesNotContain(IntegrationEvents.BOOKING_STARTED,
                IntegrationEvents.BOOKING_CANCELLED);
        assertThat(refunds(booking)).isEmpty();
        assertThat(attendance(booking)).isEmpty();
        assertThat(alerts(booking)).noneSatisfy(alert -> assertThat(alert.path("kind").asText())
                .isEqualTo("SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR"));
        assertThat(DueJobs.job(start(booking))).satisfies(job -> {
            assertThat(job.status()).as("backed off, not superseded").isEqualTo("PENDING");
            assertThat(job.attempt()).isEqualTo(attempts);
        });
        assertThat(VenueAnswerDb.job(complete(booking))).isEmpty();
    }

    /** The {@code detail} of the booking's one BOOKING_INTEGRITY_FAILURE alert. */
    private static String alertDetail(Booking booking) {
        return OWNER.sql("""
                        SELECT payload ->> 'detail' FROM admin.admin_alert
                         WHERE subject_id = :id AND kind = 'BOOKING_INTEGRITY_FAILURE'""")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    private static String start(Booking booking) {
        return VenueAnswerDb.bookingStart(booking.bookingId());
    }

    private static String complete(Booking booking) {
        return ManualBookingCommand.bookingCompleteKey(booking.bookingId());
    }

    private static Instant endAt(Booking booking) {
        return OWNER.sql("SELECT end_at FROM booking.booking WHERE id = :id").param("id", booking.bookingId())
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static long activeReservations(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    private static List<String> refunds(Booking booking) {
        return OWNER.sql("SELECT treatment FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static List<String> attendance(Booking booking) {
        return OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static List<JsonNode> alerts(Booking booking) {
        return OWNER.sql("""
                        SELECT json_build_object('kind', kind, 'severity', severity, 'payload', payload)::text
                          FROM admin.admin_alert WHERE subject_id = :id ORDER BY created_at""")
                .param("id", booking.bookingId()).query(String.class).list().stream()
                .map(BookingStartIntegrationTest::json).toList();
    }

    private static JsonNode payload(Booking booking, String type) {
        return json(VenueAnswerDb.events(type, booking.bookingId()).getFirst());
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
                                    'E7-60 test')
                            RETURNING id, declared_at)
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
                        SELECT gen_random_uuid(), :venue, 'EMERGENCY', e.id, true, e.declared_at FROM e""")
                .param("venue", booking.venueId())
                .param("declared", OffsetDateTime.ofInstant(declaredAt, ZoneOffset.UTC)).update());
    }

    /** A Venue Closure over the booked interval, behind the guards (it would have cancelled the booking itself). */
    private static void closure(Booking booking) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                        VALUES (gen_random_uuid(), :venue, tstzrange(:start, :end, '[)'), 'PARTIAL', 'E7-60 closure')""")
                .param("venue", booking.venueId())
                .param("start", OffsetDateTime.ofInstant(booking.startAt(), ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(endAt(booking), ZoneOffset.UTC)).update());
    }
}
