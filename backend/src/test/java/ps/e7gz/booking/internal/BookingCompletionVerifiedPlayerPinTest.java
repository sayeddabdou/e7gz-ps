package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;

/**
 * PINNED - TODAY'S BEHAVIOUR UNTIL E7-72 (carry-forward from the E7-59/E7-60 review, PO decisions 19 and 20 Oct 2026).
 *
 * <p>A customer's tenth ATTENDED completion makes them qualify for Verified Player in replay
 * ({@code trust.replay_timeline}: ten {@code BOOKING_ATTENDED} make {@code vp_qualification} QUALIFIED), and
 * {@code trust.assert_user_integrity} then refuses to commit any Trust write for a qualified user without an initial
 * VERIFIED transition ("Qualified User requires initial VERIFIED or automatic correction regain before commit"). That
 * transition is E7-72's (Verified Player), not built yet. So today T5 of that tenth booking is refused whole by the
 * database at commit on every attempt: the booking stays ACTIVE with its reservation, nothing of the completion is
 * written, and after its configured attempts (E7-40, {@code e7gz.scheduler.max-attempts} = 5) the BOOKING_COMPLETE job is
 * FAILED. E7-61 may still merge with this (PO decision 19 Oct 2026); the guard is not bypassed or weakened here.
 *
 * <p><b>E7-72 replaces this test</b> with the initial VERIFIED transition and the tenth completion succeeding, and must
 * be merged before E7-101 (deployment). FAILED BOOKING_COMPLETE jobs left on dev databases are fixed by hand - re-seed
 * or re-arm (PO decision 20 Oct 2026).
 */
@ExtendWith(OutputCaptureExtension.class)
class BookingCompletionVerifiedPlayerPinTest extends IntegrationTest {

    private static final String REFUSAL = "Qualified User requires initial VERIFIED";

    /** {@code e7gz.scheduler.max-attempts} (application.yml): the attempt that fails it is the last. */
    private static final int MAX_ATTEMPTS = 5;

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

    @Test
    void theTenthAttendedCompletionIsRefusedUntilE772AndItsJobEndsFailed(CapturedOutput output) {
        BookingFlows.Customer customer = flows.customer();
        for (int i = 1; i <= 9; i++) {
            Booking attended = attendedPastItsEnd(customer);
            assertThat(flows.fireCompletion(attended)).isTrue();
            assertThat(booking(attended.bookingId()).status()).as("completion %d", i).isEqualTo("COMPLETED");
        }
        Booking tenth = attendedPastItsEnd(customer);
        assertThat(completedAttended(tenth)).as("nine attended completions, not yet qualified").isEqualTo(9);
        int historyBefore = history(tenth.bookingId()).size();
        List<String> outboxBefore = outboxTypes(tenth.bookingId());

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertThat(flows.fireCompletion(tenth)).as("attempt %d settled (backed off)", attempt).isTrue();
            DueJobs.JobRow job = DueJobs.job(BookingFlows.bookingCompleteKey(tenth.bookingId()));
            assertThat(job.attempt()).isEqualTo(attempt);
            assertThat(job.status()).isEqualTo(attempt < MAX_ATTEMPTS ? "PENDING" : "FAILED");
            assertThat(lastError(tenth)).as("refused at commit, by a deferred database guard")
                    .contains("JDBC commit failed");
            assertNothingOfTheCompletion(tenth, historyBefore, outboxBefore);
        }
        // the worker logs each failed attempt with its cause chain: the guard's own message (E7-40 logFailure)
        assertThat(output.getAll()).as("refused by trust.assert_user_integrity").contains(REFUSAL);
        assertThat(flows.fireCompletion(tenth)).as("FAILED: never fires again by itself").isFalse();
        assertThat(completedAttended(tenth)).as("still nine").isEqualTo(9);
    }

    /** A new ACTIVE booking of the customer, checked in through the real command, then moved past its end. */
    private Booking attendedPastItsEnd(BookingFlows.Customer customer) {
        Booking booking = customer.nextActive(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        return flows.pastItsEnd(booking, Duration.ofMinutes(10));
    }

    /** The refused fire rolled back whole: still ACTIVE and reserved, no result, Trust outcome, reward or event. */
    private static void assertNothingOfTheCompletion(Booking booking, int historyBefore, List<String> outboxBefore) {
        VenueAnswerDb.BookingRow row = booking(booking.bookingId());
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.terminalAt()).isNull();
        assertThat(history(booking.bookingId())).hasSize(historyBefore);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single()).isOne();
        assertThat(OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list()).containsOnlyNulls();
        assertThat(OWNER.sql("SELECT count(*) FROM trust.trust_event WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM loyalty.reward_application WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM loyalty.points_transaction WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
        assertThat(outboxTypes(booking.bookingId())).isEqualTo(outboxBefore)
                .doesNotContain(IntegrationEvents.BOOKING_COMPLETED, IntegrationEvents.ATTENDANCE_RESOLVED);
    }

    private static String lastError(Booking booking) {
        return OWNER.sql("SELECT last_error FROM technical.scheduled_job WHERE idempotency_key = :key")
                .param("key", BookingFlows.bookingCompleteKey(booking.bookingId())).query(String.class).single();
    }

    private static int completedAttended(Booking booking) {
        return OWNER.sql("SELECT completed_attended_count FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single();
    }
}
