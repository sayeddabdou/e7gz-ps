package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-59 against the real database: each attendance action writes its evidence, history, audit and one integration event
 * in one transaction, which the database's own guards accept at commit; a no-show also moves ACTIVE -> NO_SHOW,
 * releases the reservation at the no-show instant (the room is sellable again at once) and writes the canonical
 * {@code BOOKING_NO_SHOW}; refusals and failures write nothing; concurrent actions write the evidence once. The exact
 * window boundaries are {@link CheckInBoundaryTest}'s.
 */
class AttendanceIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    Attendances attendances;

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

    // ------------------------------------------------------------------------------------------------- check-in

    @Test
    void aCheckInRecordsTheEvidenceByTheVenueAccountWithItsHistoryAuditAndOneEvent() {
        Booking booking = flows.active(Duration.ofMinutes(5));

        RecordedAttendance recorded = record(AttendanceAction.CHECK_IN, booking);

        assertThat(recorded.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(recorded.attendanceResult()).isNull();
        assertThat(recorded.latePresentAt()).isNull();
        assertThat(recorded.noShowAt()).isNull();
        Instant at = recorded.checkedInAt();
        assertThat(at).isNotNull();
        assertThat(record(booking)).containsEntry("check_in_at", at)
                .containsEntry("recorded_by_venue_account_id", booking.venueAccountId())
                .containsEntry("result", null);
        assertThat(venueEvents(booking)).containsExactly("CHECK_IN_RECORDED " + at + " " + booking.venueAccountId());
        assertThat(audits(booking)).containsExactly("CHECK_IN_RECORDED VENUE " + booking.venueAccountId() + " " + at);
        assertThat(status(booking)).isEqualTo("ACTIVE");
        assertThat(activeReservations(booking)).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.CHECK_IN_RECORDED);
        assertThat(payload(booking, IntegrationEvents.CHECK_IN_RECORDED)).containsEntry("bookingId",
                        booking.bookingId().toString()).containsEntry("customerId", booking.customerId().toString())
                .containsEntry("venueId", booking.venueId().toString())
                .containsEntry("resourceId", booking.resourceId().toString())
                .containsEntry("startAt", booking.startAt().toString())
                .containsEntry("endAt", booking.startAt().plus(Duration.ofMinutes(60)).toString())
                .containsEntry("recordedAt", at.toString());
    }

    @Test
    void aSecondCheckInIsRefusedAndWritesNothing() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        record(AttendanceAction.CHECK_IN, booking);

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);

        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.CHECK_IN_RECORDED);
    }

    /** FR-ATT-04: once checked in, neither Late Present nor no-show - outside their window here, refused either way. */
    @Test
    void aCheckedInBookingIsNeverMarkedLateOrNoShowInTheCheckInWindow() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        record(AttendanceAction.CHECK_IN, booking);

        assertRefused(AttendanceAction.LATE_PRESENT, booking, ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE);
        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
        assertThat(status(booking)).isEqualTo("ACTIVE");
    }

    @Test
    void afterTheCheckInWindowCheckInIsRefusedAndLatePresentIsTheAction() {
        Booking booking = flows.active(Duration.ofMinutes(20));

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
        RecordedAttendance late = record(AttendanceAction.LATE_PRESENT, booking);

        assertThat(late.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(late.latePresentAt()).isNotNull();
        assertThat(late.checkedInAt()).isNull();
        assertThat(venueEvents(booking)).containsExactly("LATE_PRESENT_RECORDED " + late.latePresentAt() + " "
                + booking.venueAccountId());
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.LATE_PRESENT_RECORDED);
    }

    /** FR-ATT-04: a customer marked Late Present can never afterwards be marked no-show for the same booking. */
    @Test
    void aLatePresentBookingCannotBeMarkedNoShowAndKeepsItsRoom() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        record(AttendanceAction.LATE_PRESENT, booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
        assertRefused(AttendanceAction.LATE_PRESENT, booking, ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE);

        assertThat(status(booking)).isEqualTo("ACTIVE");
        assertThat(activeReservations(booking)).isOne();
        assertThat(trustEvents(booking)).isZero();
        assertThat(eventTypes(booking)).doesNotContain(IntegrationEvents.NO_SHOW_RECORDED);
    }

    @Test
    void beforeTheStartCheckInIsRefused() {
        Booking booking = flows.pending();
        flows.accept(booking);

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
        assertThat(record(booking)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------- no-show

    @Test
    void aNoShowEndsTheBookingReleasesTheRoomAtItsInstantAndWritesTheTrustOutcome() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        Instant end = booking.startAt().plus(Duration.ofMinutes(60));

        RecordedAttendance recorded = record(AttendanceAction.NO_SHOW, booking);

        Instant at = recorded.noShowAt();
        assertThat(at).isNotNull();
        assertThat(recorded.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(recorded.attendanceResult()).isEqualTo(AttendanceResult.ABSENT_NO_SHOW);
        assertThat(record(booking)).containsEntry("no_show_at", at).containsEntry("result", "ABSENT_NO_SHOW")
                .containsEntry("resolved_at", at);
        assertThat(OWNER.sql("""
                        SELECT status, terminal_at, terminal_reason IS NULL, commitment_period IS NULL, end_at
                          FROM booking.booking WHERE id = :id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getBoolean(3), rs.getBoolean(4), rs.getObject(5, OffsetDateTime.class).toInstant()))
                .single())
                .as("NO_SHOW at the no-show instant, no reason, no commitment, endAt untouched")
                .containsExactly("NO_SHOW", at, true, true, end);
        assertThat(OWNER.sql("""
                        SELECT active_flag, actual_reservation_release_at, planned_start, planned_end
                          FROM inventory.resource_reservation WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getBoolean(1),
                        rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getObject(3, OffsetDateTime.class).toInstant(),
                        rs.getObject(4, OffsetDateTime.class).toInstant())).single())
                .as("released at the no-show instant, its planned interval kept")
                .containsExactly(false, at, booking.startAt(), end);
        assertThat(OWNER.sql("""
                        SELECT from_status || '->' || to_status || ' ' || actor_type || ' '
                               || COALESCE(actor_id::text, 'null')
                          FROM booking.booking_history WHERE booking_id = :id AND entry_kind = 'STATUS_TRANSITION'
                         ORDER BY sequence DESC LIMIT 1""")
                .param("id", booking.bookingId()).query(String.class).single())
                .isEqualTo("ACTIVE->NO_SHOW VENUE " + booking.venueAccountId());
        assertThat(venueEvents(booking)).containsExactly("NO_SHOW_RECORDED " + at + " " + booking.venueAccountId());
        assertThat(audits(booking)).containsExactly("NO_SHOW_RECORDED VENUE " + booking.venueAccountId() + " " + at);
        assertThat(trustEvents(booking)).isOne();
        assertThat(OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single()).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.NO_SHOW_RECORDED)
                .doesNotContain("BookingNoShowRecorded");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event e
                          JOIN inventory.resource_reservation r ON r.id = e.aggregate_id
                         WHERE r.booking_id = :id AND e.event_type = :type""")
                .param("id", booking.bookingId()).param("type", IntegrationEvents.RESERVATION_RELEASED)
                .query(Long.class).single()).isOne();
    }

    /** FR-ATT-05: the remaining interval is sellable again at once, manually here, under the normal rules. */
    @Test
    void afterANoShowTheRoomCanBeSoldAgainAtOnce() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        assertThatThrownBy(() -> flows.walkInOnRoomOf(booking, 60)).as("the room is taken before the no-show")
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE));

        record(AttendanceAction.NO_SHOW, booking);

        UUID walkIn = flows.walkInOnRoomOf(booking, 60);
        assertThat(OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", walkIn)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    /** FR-ATT-06: a committed no-show is terminal; venue staff cannot undo or overwrite it. */
    @Test
    void aCommittedNoShowCannotBeUndoneByTheVenue() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        record(AttendanceAction.NO_SHOW, booking);

        for (AttendanceAction action : AttendanceAction.values()) {
            assertRefused(action, booking, ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        assertThat(status(booking)).isEqualTo("NO_SHOW");
        assertThat(trustEvents(booking)).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.NO_SHOW_RECORDED);
    }

    /**
     * The third-consecutive-no-show boundary (arch 07 sections 3.1, 4 and 5.1; FR-TRS-13, FR-TRS-14): at stage 0 the
     * third {@code BOOKING_NO_SHOW} brings the replayed counter to the threshold 3, so replay derives a Ban
     * Qualification. {@code trust.assert_user_integrity} (via {@code tr_trust_event_projection_current}) then requires
     * its exactly-once operational consequence - a Ban Instance consuming it, with its cancellation cascade - in the
     * same transaction. That is the O14 reconciler of E7-71, not built yet, so today the database refuses the commit
     * and the whole no-show rolls back: nothing partial is left. E7-71 replaces this test's expectation with the ban.
     */
    @Test
    void theThirdConsecutiveNoShowNeedsE771sBanAndUntilThenRollsBackWhole() {
        BookingFlows.Customer customer = flows.customer();
        for (int i = 0; i < 2; i++) {
            Booking earlier = customer.nextActive(Duration.ofMinutes(20));
            assertThat(record(AttendanceAction.NO_SHOW, earlier).status()).isEqualTo(BookingStatus.NO_SHOW);
        }
        Booking third = customer.nextActive(Duration.ofMinutes(20));
        assertThat(consecutiveNoShows(third)).isEqualTo(2);

        assertThatThrownBy(() -> record(AttendanceAction.NO_SHOW, third))
                .rootCause().hasMessageContaining("has no exactly-once operational consequence");

        assertThat(status(third)).isEqualTo("ACTIVE");
        assertThat(activeReservations(third)).isOne();
        assertThat(record(third)).isEmpty();
        assertThat(venueEvents(third)).isEmpty();
        assertThat(audits(third)).isEmpty();
        assertThat(trustEvents(third)).isZero();
        assertThat(eventTypes(third)).doesNotContain(IntegrationEvents.NO_SHOW_RECORDED);
        assertThat(consecutiveNoShows(third)).as("the projection is unchanged").isEqualTo(2);
        assertThat(OWNER.sql("SELECT count(*) FROM trust.ban_instance WHERE user_id = :id")
                .param("id", third.customerId()).query(Long.class).single()).isZero();
    }

    // ---------- the delayed-start race (Amr Heiba, 17 Oct 2026; PO decision 19 Oct 2026, R1: all three actions)

    /**
     * A CONFIRMED booking in its no-show window only because its start has not been processed: the no-show first
     * starts it exactly as T4 would at startAt (R1) - its waiting BOOKING_START withdrawn, CONFIRMED -> ACTIVE by SYSTEM
     * dated startAt, BookingStarted, BOOKING_COMPLETE at endAt - and then moves ACTIVE -> NO_SHOW with everything an
     * ACTIVE no-show writes: evidence, release at the no-show instant, history, audit, Trust outcome and one event; the
     * BOOKING_COMPLETE it scheduled is superseded.
     */
    @Test
    void aNoShowBehindADelayedStartStartsTheBookingThenEndsIt() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        String start = BookingFlows.bookingStartKey(booking.bookingId());
        assertThat(DueJobs.job(start).status()).isEqualTo("PENDING");

        RecordedAttendance recorded = record(AttendanceAction.NO_SHOW, booking);

        Instant at = recorded.noShowAt();
        assertThat(recorded.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(recorded.attendanceResult()).isEqualTo(AttendanceResult.ABSENT_NO_SHOW);
        assertThat(DueJobs.job(start).status()).as("the late start can never fire").isEqualTo("SUPERSEDED");
        assertThat(DueJobs.job(BookingFlows.bookingCompleteKey(booking.bookingId())).status())
                .as("T5 has nothing left to complete").isEqualTo("SUPERSEDED");
        assertThat(status(booking)).isEqualTo("NO_SHOW");
        assertStartedAtStartAt(booking);
        assertThat(lastTransition(booking)).isEqualTo("ACTIVE->NO_SHOW VENUE " + booking.venueAccountId());
        assertThat(activeReservations(booking)).isZero();
        assertThat(OWNER.sql("""
                        SELECT actual_reservation_release_at FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant()).isEqualTo(at);
        assertThat(venueEvents(booking)).containsExactly("NO_SHOW_RECORDED " + at + " " + booking.venueAccountId());
        assertThat(audits(booking)).containsExactly("NO_SHOW_RECORDED VENUE " + booking.venueAccountId() + " " + at);
        assertThat(trustEvents(booking)).isOne();
        assertThat(consecutiveNoShows(booking)).isOne();
        assertThat(eventTypes(booking)).containsSubsequence(IntegrationEvents.BOOKING_STARTED,
                        IntegrationEvents.NO_SHOW_RECORDED)
                .containsOnlyOnce(IntegrationEvents.BOOKING_STARTED, IntegrationEvents.NO_SHOW_RECORDED);
    }

    /**
     * R1 (PO decision 19 Oct 2026): check-in inside its window and Mark Present - Late inside its own, on a booking
     * whose start has not been processed, first start it exactly as T4 would at startAt - never 409 - and then record
     * the evidence on the ACTIVE booking in the same command, with its Booking Event, audit and one event each.
     */
    @Test
    void checkInAndLatePresentBehindADelayedStartStartTheBookingThenRecordTheEvidence() {
        Booking inCheckIn = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        Booking inLate = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));

        RecordedAttendance checkedIn = record(AttendanceAction.CHECK_IN, inCheckIn);
        RecordedAttendance late = record(AttendanceAction.LATE_PRESENT, inLate);

        assertThat(checkedIn.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(checkedIn.checkedInAt()).isNotNull();
        assertThat(late.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(late.latePresentAt()).isNotNull();
        for (Booking booking : List.of(inCheckIn, inLate)) {
            assertThat(status(booking)).isEqualTo("ACTIVE");
            assertStartedAtStartAt(booking);
            assertThat(lastTransition(booking)).as("the evidence moves no status")
                    .isEqualTo("CONFIRMED->ACTIVE SYSTEM null");
            assertThat(DueJobs.job(BookingFlows.bookingStartKey(booking.bookingId())).status()).isEqualTo("SUPERSEDED");
            assertThat(activeReservations(booking)).as("the room stays the booking's").isOne();
            assertThat(trustEvents(booking)).isZero();
        }
        assertThat(eventTypes(inCheckIn)).containsSubsequence(IntegrationEvents.BOOKING_STARTED,
                IntegrationEvents.CHECK_IN_RECORDED).containsOnlyOnce(IntegrationEvents.BOOKING_STARTED);
        assertThat(eventTypes(inLate)).containsSubsequence(IntegrationEvents.BOOKING_STARTED,
                IntegrationEvents.LATE_PRESENT_RECORDED).containsOnlyOnce(IntegrationEvents.BOOKING_STARTED);
        assertThat(venueEvents(inCheckIn)).singleElement().asString().startsWith("CHECK_IN_RECORDED ");
        assertThat(venueEvents(inLate)).singleElement().asString().startsWith("LATE_PRESENT_RECORDED ");
        assertRefused(AttendanceAction.NO_SHOW, inLate, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
    }

    /** R1: a check-in or Late Present behind a start that would cancel is the same committed 409 as the no-show. */
    @Test
    void checkInAndLatePresentBehindACancellingDelayedStartCancelWithoutAttendance() {
        Booking inCheckIn = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        Booking inLate = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceDisabledBehindTheGuards(inCheckIn);
        flows.resourceDisabledBehindTheGuards(inLate);

        assertRefused(AttendanceAction.CHECK_IN, inCheckIn, ApiErrorCode.BOOKING_STATE_CHANGED);
        assertRefused(AttendanceAction.LATE_PRESENT, inLate, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertStartCancelled(inCheckIn, "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        assertStartCancelled(inLate, "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        assertThat(eventTypes(inCheckIn)).doesNotContain(IntegrationEvents.BOOKING_STARTED,
                IntegrationEvents.CHECK_IN_RECORDED);
        assertThat(eventTypes(inLate)).doesNotContain(IntegrationEvents.BOOKING_STARTED,
                IntegrationEvents.LATE_PRESENT_RECORDED);
    }

    /** R1: a check-in or Late Present whose start is not waiting or cannot be resolved is the pure 409, no write. */
    @Test
    void checkInAndLatePresentBehindAnUnresolvableDelayedStartArePure409s() {
        Booking withdrawn = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.withdrawStart(withdrawn);
        Booking blocked = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.venueSellingBlockSince(blocked, blocked.startAt().minus(Duration.ofMinutes(1)));
        Booking leased = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.startLeased(leased);

        assertRefused(AttendanceAction.CHECK_IN, withdrawn, ApiErrorCode.BOOKING_STATE_CHANGED);
        assertRefused(AttendanceAction.LATE_PRESENT, blocked, ApiErrorCode.BOOKING_STATE_CHANGED);
        assertRefused(AttendanceAction.CHECK_IN, leased, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertNothingWritten(blocked);
        for (Booking booking : List.of(withdrawn, leased)) {
            assertThat(status(booking)).isEqualTo("CONFIRMED");
            assertThat(record(booking)).isEmpty();
            assertThat(eventTypes(booking)).doesNotContain(IntegrationEvents.BOOKING_STARTED,
                    IntegrationEvents.CHECK_IN_RECORDED);
        }
        assertThat(DueJobs.job(BookingFlows.bookingStartKey(leased.bookingId())).status()).isEqualTo("LEASED");
    }

    /**
     * R1: a check-in racing the real T4 on one booking - serialised by the booking's locks and the job's lease. Whoever
     * wins, the booking is started exactly once, as of startAt, and a committed check-in is recorded once on ACTIVE.
     */
    @Test
    void aCheckInRacingTheRealStartStartsTheBookingOnce() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.makeStartDue(booking);

        ConcurrentRace.Result<Object> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            start.await();
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return racer == 0 ? flows.fireStartIfDue(booking)
                        : attendances.record(AttendanceAction.CHECK_IN, booking.venueAccountId(), booking.bookingId());
            }
        });

        assertThat(race.failures()).allSatisfy(failure -> assertThat(failure)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED)));
        boolean checkedIn = race.values().stream().anyMatch(RecordedAttendance.class::isInstance);
        assertThat(status(booking)).isEqualTo("ACTIVE");
        assertStartedAtStartAt(booking);
        assertThat(eventTypes(booking).stream().filter(IntegrationEvents.CHECK_IN_RECORDED::equals))
                .hasSize(checkedIn ? 1 : 0);
        assertThat(record(booking).get("check_in_at") != null).isEqualTo(checkedIn);
    }

    /**
     * The start as T4 writes it, once: one CONFIRMED -> ACTIVE transition by SYSTEM dated the stored startAt, one
     * BookingStarted and one BOOKING_COMPLETE job, at endAt.
     */
    private static void assertStartedAtStartAt(Booking booking) {
        assertThat(OWNER.sql("""
                        SELECT occurred_at, actor_type FROM booking.booking_history
                         WHERE booking_id = :id AND entry_kind = 'STATUS_TRANSITION' AND to_status = 'ACTIVE'""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getString(2)))
                .list()).containsExactly(List.of(booking.startAt(), "SYSTEM"));
        assertThat(eventTypes(booking).stream().filter(IntegrationEvents.BOOKING_STARTED::equals)).hasSize(1);
        assertThat(OWNER.sql("""
                        SELECT j.due_at = b.end_at FROM technical.scheduled_job j, booking.booking b
                         WHERE j.idempotency_key = :key AND b.id = :id""")
                .param("key", BookingFlows.bookingCompleteKey(booking.bookingId())).param("id", booking.bookingId())
                .query(Boolean.class).list()).as("one BOOKING_COMPLETE, at endAt").containsExactly(true);
    }

    /** The database refuses NO_SHOW -> ACTIVE outright, so even a start that slipped through could not reactivate it. */
    @Test
    void aStartCanNeverReactivateTheNoShow() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        record(AttendanceAction.NO_SHOW, booking);

        assertThatThrownBy(() -> OWNER.sql("UPDATE booking.booking SET status = 'ACTIVE' WHERE id = :id")
                .param("id", booking.bookingId()).update())
                .hasMessageContaining("Invalid Booking transition NO_SHOW -> ACTIVE");
        assertThat(status(booking)).isEqualTo("NO_SHOW");
    }

    /**
     * E7-60 regression: once the delayed-start no-show is committed, the real T4 never runs - its job is SUPERSEDED, so
     * the worker cannot fire it - and the booking stays NO_SHOW, started once (by the no-show, R1).
     */
    @Test
    void theRealStartNeverRunsAfterACommittedNoShow() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        record(AttendanceAction.NO_SHOW, booking);

        assertThat(flows.fireStart(booking)).isFalse();
        assertThat(flows.fireStartIfDue(booking)).isFalse();

        assertThat(status(booking)).isEqualTo("NO_SHOW");
        assertThat(DueJobs.job(BookingFlows.bookingStartKey(booking.bookingId())).status()).isEqualTo("SUPERSEDED");
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.BOOKING_STARTED);
        assertThat(trustEvents(booking)).isOne();
    }

    /**
     * E7-60: T4 and the delayed-start no-show racing on one booking are serialised by the booking's locks and the job's
     * lease. Whichever wins, the booking is started exactly once (by T4, or by the no-show itself, R1) and never ACTIVE
     * after a no-show, the no-show is written at most once, and a committed no-show is ABSENT_NO_SHOW with one Trust
     * outcome.
     */
    @Test
    void theStartRacingADelayedNoShowNeverReactivatesItAndWritesEachOnce() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.makeStartDue(booking);

        ConcurrentRace.Result<Object> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            start.await();
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return racer == 0 ? flows.fireStartIfDue(booking)
                        : attendances.record(AttendanceAction.NO_SHOW, booking.venueAccountId(), booking.bookingId());
            }
        });

        assertThat(race.failures()).allSatisfy(failure -> assertThat(failure)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED)));
        boolean noShowCommitted = race.values().stream().anyMatch(RecordedAttendance.class::isInstance);
        assertThat(status(booking)).isEqualTo(noShowCommitted ? "NO_SHOW" : "ACTIVE");
        assertThat(eventTypes(booking).stream().filter(IntegrationEvents.BOOKING_STARTED::equals)).hasSize(1);
        assertThat(eventTypes(booking).stream().filter(IntegrationEvents.NO_SHOW_RECORDED::equals))
                .hasSize(noShowCommitted ? 1 : 0);
        assertThat(trustEvents(booking)).isEqualTo(noShowCommitted ? 1 : 0);
        if (noShowCommitted) {
            assertThat(record(booking)).containsEntry("result", "ABSENT_NO_SHOW");
        }
    }

    /** CONFIRMED with no start job waiting is not the race: BOOKING_STATE_CHANGED, nothing written. */
    @Test
    void aConfirmedBookingWhoseStartIsNoLongerWaitingIsBookingStateChanged() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.withdrawStart(booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(status(booking)).isEqualTo("CONFIRMED");
        assertThat(activeReservations(booking)).isOne();
        assertThat(record(booking)).isEmpty();
        assertThat(trustEvents(booking)).isZero();
        assertThat(eventTypes(booking)).doesNotContain(IntegrationEvents.NO_SHOW_RECORDED);
    }

    // ---------------- a delayed start that would have cancelled (Amr Heiba 17 Oct 2026: never a no-show penalty)

    /**
     * FR-BOK-28 at a delayed start: the room DISABLED behind the guards - the start would have cancelled under
     * SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, so the no-show cancels it instead, dated startAt, with its full refund and
     * SEV_1 alert, answers 409 BOOKING_STATE_CHANGED, and writes no no-show, no Trust outcome, no penalty.
     */
    @Test
    void aDelayedStartOnADisabledRoomCancelsWithTheIntegrityErrorAndNoPenalty() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceDisabledBehindTheGuards(booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertStartCancelled(booking, "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        assertThat(OWNER.sql("""
                        SELECT severity || ' ' || (payload ->> 'unexpectedState') || ' ' || (payload ->> 'startAt')
                          FROM admin.admin_alert
                         WHERE kind = 'SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR' AND subject_id = :id""")
                .param("id", booking.bookingId()).query(String.class).list())
                .containsExactly("SEV_1 DISABLED " + booking.startAt());
    }

    /**
     * Decision of Amr Heiba (18 Oct 2026): a Resource Emergency that began after startAt and still blocks the booking
     * is not a start-time cause and not a no-show - a pure 409 BOOKING_STATE_CHANGED with no write at all: no
     * cancellation, refund or release, the start job left waiting; the interruption flow owns the problem.
     */
    @Test
    void anEmergencyBeganAfterStartAtIsAPure409WithoutWrites() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceEmergencySince(booking, booking.startAt().plus(Duration.ofMinutes(2)));

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertNothingWritten(booking);
    }

    /** The same for an Owner Review entered after startAt and still in force (decision of Amr Heiba, 18 Oct 2026). */
    @Test
    void anOwnerReviewBeganAfterStartAtIsAPure409WithoutWrites() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.ownerReviewSince(booking, booking.startAt().plus(Duration.ofMinutes(2)));

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertNothingWritten(booking);
    }

    /** An Emergency unresolved at startAt and still now: a start-time cause, so RESOURCE_EMERGENCY_OUTAGE by priority. */
    @Test
    void anEmergencyAlreadyUnresolvedAtStartAtCancelsByPriority() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceEmergencySince(booking, booking.startAt().minus(Duration.ofMinutes(1)));
        flows.resourceDisabledBehindTheGuards(booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertStartCancelled(booking, "RESOURCE_EMERGENCY_OUTAGE");
    }

    /** Owner Review in force at startAt: RESOURCE_OWNER_REVIEW_REQUIRED (priority 1), never a no-show. */
    @Test
    void aDelayedStartUnderOwnerReviewCancelsUnderItsReason() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.ownerReviewSince(booking, booking.startAt().minus(Duration.ofMinutes(1)));
        flows.resourceDisabledBehindTheGuards(booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertStartCancelled(booking, "RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(OWNER.sql("SELECT count(*) FROM admin.admin_alert WHERE subject_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single())
                .as("one reason by the frozen priority, so no integrity alert").isZero();
    }

    /**
     * Judged as of startAt, never as of the late no-show: a Resource Emergency unresolved at startAt and ENDED since
     * still cancels under RESOURCE_EMERGENCY_OUTAGE, as the on-time start would have.
     */
    @Test
    void aDelayedStartIsJudgedAtStartAtNotWhenTheNoShowRuns() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceEmergencyEnded(booking, booking.startAt().minus(Duration.ofMinutes(5)),
                booking.startAt().plus(Duration.ofMinutes(2)));

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertStartCancelled(booking, "RESOURCE_EMERGENCY_OUTAGE");
    }

    /**
     * Spec v5 section 31.2 lists the selling blocks as start gates but section 31.3 gives them no reason: the start
     * cannot be resolved, so the no-show is refused and nothing at all is written - the start job still waits.
     */
    @Test
    void aDelayedStartBehindASellingBlockIsUndeterminedAndWritesNothing() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.venueSellingBlockSince(booking, booking.startAt().minus(Duration.ofMinutes(1)));

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertNothingWritten(booking);
    }

    /** A start a worker holds LEASED right now is being processed: refused, nothing written, the lease untouched. */
    @Test
    void aStartBeingProcessedRefusesTheNoShowAndWritesNothing() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.startLeased(booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThat(DueJobs.job(BookingFlows.bookingStartKey(booking.bookingId())).status()).isEqualTo("LEASED");
        assertThat(status(booking)).isEqualTo("CONFIRMED");
        assertThat(record(booking)).isEmpty();
        assertThat(trustEvents(booking)).isZero();
    }

    /** One second before +15:00 the no-show window is closed: nothing is cancelled, even on a disabled room. */
    @Test
    void beforeFifteenMinutesADelayedStartIsNeitherCancelledNorNoShowed() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(14));
        flows.resourceDisabledBehindTheGuards(booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);

        assertNothingWritten(booking);
    }

    /** A defect found while cancelling (no reservation) rolls the whole command back: nothing cancelled, start waits. */
    @Test
    void aFailingStartTimeCancellationRollsBackWhole() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceDisabledBehindTheGuards(booking);
        withoutTriggers("""
                UPDATE inventory.resource_reservation
                   SET active_flag = false, actual_reservation_release_at = actual_reservation_start,
                       release_reason = 'E7-59 test defect'
                 WHERE booking_id = :id AND active_flag""", booking.bookingId());

        assertThatThrownBy(() -> record(AttendanceAction.NO_SHOW, booking))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure");

        assertThat(status(booking)).isEqualTo("CONFIRMED");
        assertThat(DueJobs.job(BookingFlows.bookingStartKey(booking.bookingId())).status()).isEqualTo("PENDING");
        assertThat(record(booking)).isEmpty();
        assertThat(eventTypes(booking)).doesNotContain(IntegrationEvents.BOOKING_CANCELLED);
    }

    /** Two no-shows racing on a delayed start that would cancel: one cancellation, both answered 409, no no-show. */
    @Test
    void concurrentNoShowsOnACancellingDelayedStartCancelOnce() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceDisabledBehindTheGuards(booking);

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer -> AttendanceAction.NO_SHOW);

        assertThat(race.values()).isEmpty();
        assertThat(race.failures()).hasSize(2).allSatisfy(failure -> assertThat(failure)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED)));
        assertStartCancelled(booking, "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
    }

    /** A cancelled booking is terminal: no late start can make it ACTIVE (tr_03_booking_transition_guard). */
    @Test
    void aStartCanNeverReactivateTheStartTimeCancellation() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceDisabledBehindTheGuards(booking);
        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);

        assertThatThrownBy(() -> OWNER.sql("UPDATE booking.booking SET status = 'ACTIVE' WHERE id = :id")
                .param("id", booking.bookingId()).update())
                .hasMessageContaining("Terminal Booking state CANCELLED is immutable");
        assertThat(status(booking)).isEqualTo("CANCELLED");
    }

    /**
     * The start-time cancellation as the on-time start would have written it, and nothing of a no-show: CANCELLED under
     * {@code reason} dated startAt, FULL_REFUND with its one fee decision, the reservation released at startAt, one
     * SYSTEM transition and one BookingCancelled, the start withdrawn, attendance NOT_APPLICABLE; no no-show evidence,
     * no attendance Booking Event or audit, no Trust outcome, no NoShowRecorded.
     */
    private static void assertStartCancelled(Booking booking, String reason) {
        assertThat(OWNER.sql("""
                        SELECT status, terminal_reason, fee_treatment, terminal_at FROM booking.booking WHERE id = :id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant())).single())
                .containsExactly("CANCELLED", reason, "FULL_REFUND", booking.startAt());
        assertThat(OWNER.sql("SELECT treatment FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list()).containsExactly("FULL_REFUND");
        assertThat(OWNER.sql("""
                        SELECT active_flag, actual_reservation_release_at FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getBoolean(1), rs.getObject(2, OffsetDateTime.class).toInstant()))
                .single()).containsExactly(false, booking.startAt());
        assertThat(lastTransition(booking)).isEqualTo("CONFIRMED->CANCELLED SYSTEM null");
        assertThat(DueJobs.job(BookingFlows.bookingStartKey(booking.bookingId())).status()).isEqualTo("SUPERSEDED");
        assertThat(record(booking)).containsEntry("result", "NOT_APPLICABLE").containsEntry("no_show_at", null);
        assertThat(venueEvents(booking)).isEmpty();
        assertThat(audits(booking)).isEmpty();
        assertThat(trustEvents(booking)).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM trust.trust_event WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.BOOKING_CANCELLED)
                .doesNotContain(IntegrationEvents.NO_SHOW_RECORDED);
    }

    /** A refused delayed-start no-show that wrote nothing: still CONFIRMED and reserved, its start job still waiting. */
    private static void assertNothingWritten(Booking booking) {
        assertThat(status(booking)).isEqualTo("CONFIRMED");
        assertThat(activeReservations(booking)).isOne();
        assertThat(DueJobs.job(BookingFlows.bookingStartKey(booking.bookingId())).status()).isEqualTo("PENDING");
        assertThat(record(booking)).isEmpty();
        assertThat(lastTransition(booking)).as("no new history row").isEqualTo("PENDING->CONFIRMED VENUE "
                + booking.venueAccountId());
        assertThat(venueEvents(booking)).isEmpty();
        assertThat(audits(booking)).isEmpty();
        assertThat(trustEvents(booking)).isZero();
        assertThat(OWNER.sql("""
                        SELECT (SELECT count(*) FROM loyalty.reward_application WHERE booking_id = :id)
                             + (SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = :id)""")
                .param("id", booking.bookingId()).query(Long.class).single()).as("no Loyalty write").isZero();
        assertThat(eventTypes(booking)).doesNotContain(IntegrationEvents.BOOKING_CANCELLED,
                IntegrationEvents.NO_SHOW_RECORDED);
    }

    /** Two no-shows racing on a delayed start: one wins; the start is withdrawn and the outcome written once. */
    @Test
    void concurrentNoShowsBehindADelayedStartWriteOnce() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer -> AttendanceAction.NO_SHOW);

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(DueJobs.job(BookingFlows.bookingStartKey(booking.bookingId())).status()).isEqualTo("SUPERSEDED");
        assertThat(trustEvents(booking)).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.NO_SHOW_RECORDED);
    }

    // ----------------------------------------------------------------------------------- refusals by booking

    /** FR-MAN-04: a Manual Booking has no attendance actions; each answers its own code and writes nothing. */
    @Test
    void aManualBookingAnswersEachActionsOwnCode() {
        Booking walkIn = flows.walkIn();

        assertRefused(AttendanceAction.CHECK_IN, walkIn, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
        assertRefused(AttendanceAction.LATE_PRESENT, walkIn, ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE);
        assertRefused(AttendanceAction.NO_SHOW, walkIn, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
        assertThat(record(walkIn)).isEmpty();
        assertThat(status(walkIn)).isEqualTo("ACTIVE");
    }

    @Test
    void aBookingNoLongerInProgressIsBookingStateChanged() {
        Booking booking = flows.pending();
        flows.customerCancels(booking);

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.BOOKING_STATE_CHANGED);
        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    @Test
    void anotherVenuesBookingIsNotFound() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        UUID otherAccount = Fixtures.aVenueAccount().create().id();

        assertThatThrownBy(() -> attendances.record(AttendanceAction.CHECK_IN, otherAccount, booking.bookingId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThat(record(booking)).isEmpty();
    }

    // ------------------------------------------------------------------------------------ atomicity, races

    /**
     * D11 (E7-47): a no-show on an ACTIVE booking without its reservation fails after the evidence and the NO_SHOW
     * transition were written - the whole command rolls back: no evidence, no transition, no Trust outcome, no event.
     */
    @Test
    void aFailingNoShowRollsBackEverythingItWrote() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        withoutTriggers("""
                UPDATE inventory.resource_reservation
                   SET active_flag = false, actual_reservation_release_at = actual_reservation_start,
                       release_reason = 'E7-59 test defect'
                 WHERE booking_id = :id AND active_flag""", booking.bookingId());
        long outboxBefore = eventTypes(booking).size();

        assertThatThrownBy(() -> record(AttendanceAction.NO_SHOW, booking))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure");

        assertThat(status(booking)).isEqualTo("ACTIVE");
        assertThat(record(booking)).isEmpty();
        assertThat(venueEvents(booking)).isEmpty();
        assertThat(audits(booking)).isEmpty();
        assertThat(trustEvents(booking)).isZero();
        assertThat(eventTypes(booking)).hasSize((int) outboxBefore);
    }

    @Test
    void concurrentNoShowsWriteTheEvidenceTheReleaseAndTheEventsOnce() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer -> AttendanceAction.NO_SHOW);

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(trustEvents(booking)).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.NO_SHOW_RECORDED);
    }

    @Test
    void concurrentCheckInsWriteTheEvidenceOnce() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(5));

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer -> AttendanceAction.CHECK_IN);

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.CHECK_IN_WINDOW_CLOSED));
        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.CHECK_IN_RECORDED);
    }

    /** Late Present against no-show: exactly one wins, and the evidence stays mutually exclusive (FR-ATT-04). */
    @Test
    void latePresentRacingANoShowLeavesExactlyOneOfThem() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer ->
                racer == 0 ? AttendanceAction.LATE_PRESENT : AttendanceAction.NO_SHOW);

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOf(ApiException.class);
        Map<String, Object> evidence = record(booking);
        assertThat(evidence.get("late_present_at") == null).isNotEqualTo(evidence.get("no_show_at") == null);
        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(status(booking)).isEqualTo(evidence.get("no_show_at") == null ? "ACTIVE" : "NO_SHOW");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ConcurrentRace.Result<RecordedAttendance> race(Booking booking,
                                                           IntFunction<AttendanceAction> actionOf)
            throws Exception {
        return ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            AttendanceAction action = actionOf.apply(racer);
            start.await();
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return attendances.record(action, booking.venueAccountId(), booking.bookingId());
            }
        });
    }

    private RecordedAttendance record(AttendanceAction action, Booking booking) {
        return attendances.record(action, booking.venueAccountId(), booking.bookingId());
    }

    private void assertRefused(AttendanceAction action, Booking booking, ApiErrorCode code) {
        assertThatThrownBy(() -> record(action, booking)).as(action.name())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).as(action.name())
                        .isEqualTo(code));
    }

    /** The booking's attendance record as column -> value (instants as {@link Instant}); empty when none. */
    static Map<String, Object> record(Booking booking) {
        return OWNER.sql("""
                        SELECT result, check_in_at, late_present_at, no_show_at, resolved_at,
                               recorded_by_venue_account_id
                          FROM attendance.attendance_record WHERE booking_id = :id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("result", rs.getString("result"));
                    for (String column : List.of("check_in_at", "late_present_at", "no_show_at", "resolved_at")) {
                        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
                        row.put(column, value == null ? null : value.toInstant());
                    }
                    row.put("recorded_by_venue_account_id", rs.getObject("recorded_by_venue_account_id", UUID.class));
                    return row;
                })
                .optional().orElse(Map.of());
    }

    /** "FROM->TO ACTOR_TYPE actorId" of the booking's latest status transition. */
    static String lastTransition(Booking booking) {
        return OWNER.sql("""
                        SELECT from_status || '->' || to_status || ' ' || actor_type || ' '
                               || COALESCE(actor_id::text, 'null')
                          FROM booking.booking_history WHERE booking_id = :id AND entry_kind = 'STATUS_TRANSITION'
                         ORDER BY sequence DESC LIMIT 1""")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    static String status(Booking booking) {
        return OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", booking.bookingId())
                .query(String.class).single();
    }

    /** The attendance Booking Events, "EVENT at actorId", oldest first. */
    static List<String> venueEvents(Booking booking) {
        return OWNER.sql("""
                        SELECT event_type, occurred_at, actor_type, actor_id FROM booking.booking_history
                         WHERE booking_id = :id AND entry_kind = 'BOOKING_EVENT' ORDER BY sequence""")
                .param("id", booking.bookingId())
                .query((rs, n) -> {
                    assertThat(rs.getString("actor_type")).isEqualTo("VENUE");
                    return rs.getString("event_type") + " " + rs.getObject("occurred_at", OffsetDateTime.class)
                            .toInstant() + " " + rs.getObject("actor_id", UUID.class);
                })
                .list();
    }

    /** "ACTION ACTOR_TYPE actorId at" of the booking's audit rows. */
    static List<String> audits(Booking booking) {
        return OWNER.sql("""
                        SELECT action, actor_type, actor_id, occurred_at FROM audit.audit_event
                         WHERE subject_type = 'BOOKING' AND subject_id = :id ORDER BY occurred_at""")
                .param("id", booking.bookingId())
                .query((rs, n) -> rs.getString("action") + " " + rs.getString("actor_type") + " "
                        + rs.getObject("actor_id", UUID.class) + " "
                        + rs.getObject("occurred_at", OffsetDateTime.class).toInstant())
                .list();
    }

    static long trustEvents(Booking booking) {
        return OWNER.sql("SELECT count(*) FROM trust.trust_event WHERE booking_id = :id AND event_type = 'BOOKING_NO_SHOW'")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    static int consecutiveNoShows(Booking booking) {
        return OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single();
    }

    static long activeReservations(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    static List<String> eventTypes(Booking booking) {
        return OWNER.sql("SELECT event_type FROM technical.outbox_event WHERE aggregate_id = :id ORDER BY id")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> payload(Booking booking, String type) {
        String json = OWNER.sql("""
                        SELECT payload::text FROM technical.outbox_event WHERE aggregate_id = :id AND event_type = :type""")
                .param("id", booking.bookingId()).param("type", type).query(String.class).single();
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fixture write past every trigger, as the booking tests plant defects ({@code VenueAnswerDb}). */
    private static void withoutTriggers(String sql, UUID id) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql(sql).param("id", id).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }
}
