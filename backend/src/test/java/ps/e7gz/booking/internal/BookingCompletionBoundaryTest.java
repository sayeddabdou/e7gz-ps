package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;

/**
 * T5, the booking completion, either side of its due instant {@code booking.end_at} through the real deadline worker,
 * E7-61's handler and the database's own guards (arch 08 sections 1 and 9; FR-AVL-15, arch 07 section 7.3; the E7-45
 * pending boundary test, moved here from {@code support.boundary.pending} and enabled by E7-61), for both completion
 * defaults: a checked-in booking (ATTENDED, with its reward) and a Late-Present one (ATTENDANCE_UNCONFIRMED, no points).
 *
 * <p><b>Two halves, by what each can prove.</b> The exact seconds - one second before, at, and one second after end_at
 * - are decided on a controlled command instant ({@code CommandInstants}) by
 * {@code BookingCompletionsTest.t5DecidesOnTheExactSecondOfTheEnd}: only there is "now" exact. Here the worker's fire
 * samples its own instant whenever it runs, which no test can pin to a second under load, so each side is set with a
 * margin that holds whatever that latency is, and nothing here claims the fire ran at an exact instant:
 * <ul>
 *   <li>{@link Side#BEFORE_THE_END} - end_at ten minutes after the database clock: the fire runs before it;</li>
 *   <li>{@link Side#AT_THE_END} - end_at the database clock read just before the booking is set up: the fire runs at it
 *       or after it, by its own latency;</li>
 *   <li>{@link Side#AFTER_THE_END} - end_at one second before that read: the fire runs at least a second after it.</li>
 * </ul>
 * {@code end_at} is an immutable snapshot that no command moves, so the booking, whose evidence the real attendance
 * commands recorded, is moved back whole ({@link BookingFlows#endingAt}) to the end under test; its T5 is then made due
 * and the real worker fires it once. No sleep, no wait on the clock.
 */
class BookingCompletionBoundaryTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    BookingFlows flows;
    AttendanceFlows attendance;
    CorrelationId.Scope correlation;

    /** Where end_at lies from the database clock read just before the booking is set up. */
    enum Side {
        BEFORE_THE_END(Duration.ofMinutes(10)),
        AT_THE_END(Duration.ZERO),
        AFTER_THE_END(Duration.ofSeconds(-1));

        private final Duration endFromNow;

        Side(Duration endFromNow) {
            this.endFromNow = endFromNow;
        }

        boolean beforeTheEnd() {
            return endFromNow.isPositive();
        }
    }

    /** The evidence a booking completes from (arch 07 section 7.3 rules 1 and 2). */
    enum Evidence {
        CHECK_IN, LATE_PRESENT
    }

    static Stream<Arguments> sides() {
        return Stream.of(Side.values())
                .flatMap(side -> Stream.of(Evidence.values()).map(evidence -> Arguments.of(side, evidence)));
    }

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

    /**
     * Before end_at the real fire reschedules T5 to end_at and changes nothing; at or after it, it completes the booking
     * once, as of end_at - status, terminal_at, the SYSTEM history row and the release all dated end_at, whenever the
     * fire ran - with its attendance default, its Trust outcome and its reward (200 for ATTENDED, none for a
     * Late-Present booking), {@code BookingCompleted} and {@code AttendanceResolved} once each; a second delivery finds
     * T5 DONE and changes nothing.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("sides")
    void t5CompletesTheBookingFromItsEndAndNeverBefore(Side side, Evidence evidence) {
        Booking booking = withEvidence(evidence);
        Instant read = Deadlines.databaseNow();
        booking = flows.endingAt(booking, read.plus(side.endFromNow));
        Instant end = BookingFlows.endAt(booking.bookingId());

        assertThat(flows.fireCompletion(booking)).as("settled by the real worker").isTrue();

        if (side.beforeTheEnd()) {
            assertThat(booking(booking.bookingId()).status()).isEqualTo("ACTIVE");
            assertThat(DueJobs.job(complete(booking))).satisfies(job -> {
                assertThat(job.status()).isEqualTo("PENDING");
                assertThat(job.dueAt()).as("rescheduled to end_at").isEqualTo(end);
            });
            assertThat(activeReservations(booking)).isOne();
            assertThat(results(booking)).as("no result before the end").containsOnlyNulls();
            assertThat(trustOutcomes(booking)).isEmpty();
            assertThat(entitlements(booking)).isEmpty();
            assertThat(outboxTypes(booking.bookingId())).doesNotContain(IntegrationEvents.BOOKING_COMPLETED,
                    IntegrationEvents.ATTENDANCE_RESOLVED);
            return;
        }
        VenueAnswerDb.BookingRow row = booking(booking.bookingId());
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.terminalAt()).isEqualTo(end);
        assertThat(history(booking.bookingId()).getLast()).satisfies(h -> {
            assertThat(h.fromStatus() + "->" + h.toStatus()).isEqualTo("ACTIVE->COMPLETED");
            assertThat(h.actorType()).isEqualTo("SYSTEM");
            assertThat(h.occurredAt()).isEqualTo(end);
        });
        assertThat(activeReservations(booking)).isZero();
        assertThat(OWNER.sql("""
                        SELECT actual_reservation_release_at FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant()).isEqualTo(end);
        // the fire's own instant (resolved_at is the command instant) is not end_at; it is only on the right side of it
        assertThat(resolvedAt(booking)).as("the fire ran %s", side).isAfterOrEqualTo(read).isAfter(end);
        boolean attended = evidence == Evidence.CHECK_IN;
        assertThat(results(booking)).containsExactly(attended ? "ATTENDED" : "ATTENDANCE_UNCONFIRMED");
        assertThat(trustOutcomes(booking))
                .containsExactly(attended ? "BOOKING_ATTENDED" : "BOOKING_ATTENDANCE_UNCONFIRMED");
        assertThat(entitlements(booking)).containsExactly(attended ? 200 : 0);
        assertThat(points(booking)).isEqualTo(attended ? 200 : 0);
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_COMPLETED,
                IntegrationEvents.ATTENDANCE_RESOLVED);
        assertThat(DueJobs.job(complete(booking)).status()).isEqualTo("DONE");

        assertThat(flows.fireCompletion(booking)).as("a second delivery: DONE never fires").isFalse();
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_COMPLETED,
                IntegrationEvents.ATTENDANCE_RESOLVED);
        assertThat(entitlements(booking)).hasSize(1);
    }

    /** An ACTIVE booking with its evidence recorded through the real attendance command (E7-59). */
    private Booking withEvidence(Evidence evidence) {
        if (evidence == Evidence.CHECK_IN) {
            Booking booking = flows.active(Duration.ofMinutes(5));
            attendance.checkIn(booking.venueAccountId(), booking.bookingId());
            return booking;
        }
        Booking booking = flows.active(Duration.ofMinutes(20));
        attendance.latePresent(booking.venueAccountId(), booking.bookingId());
        return booking;
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

    private static List<String> results(Booking booking) {
        return OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static Instant resolvedAt(Booking booking) {
        return OWNER.sql("SELECT resolved_at FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant();
    }

    /** The booking's Trust events: check-in and Mark Present - Late write none, so only the completion's. */
    private static List<String> trustOutcomes(Booking booking) {
        return OWNER.sql("SELECT event_type FROM trust.trust_event WHERE booking_id = :id ORDER BY event_sequence")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static List<Integer> entitlements(Booking booking) {
        return OWNER.sql("""
                        SELECT entitlement_after FROM loyalty.reward_application WHERE booking_id = :id
                         ORDER BY application_sequence""")
                .param("id", booking.bookingId()).query(Integer.class).list();
    }

    private static long points(Booking booking) {
        return OWNER.sql("SELECT COALESCE(sum(amount), 0) FROM loyalty.points_transaction WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single();
    }
}
