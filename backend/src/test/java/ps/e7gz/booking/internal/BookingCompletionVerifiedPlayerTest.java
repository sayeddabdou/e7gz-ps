package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;

/**
 * Replaces E7-61's pinned test (carry-forward from the E7-59/E7-60 review, PO decisions 19 and 20 Oct 2026; E7-72).
 *
 * <p>A customer's tenth ATTENDED completion makes them QUALIFIED in replay ({@code trust.replay_state}), and
 * {@code trust.assert_user_integrity} refuses to commit any Trust write for a qualified user without an initial
 * VERIFIED transition. Until E7-72 the reconciler refused it and T5 of that tenth booking ended FAILED. Now the tenth
 * completion commits whole, in one command, with that transition: COMPLETED / ATTENDED, its Trust outcome, its
 * reward, its events, and the initial NOT_VERIFIED -> VERIFIED (TRUST_QUALIFICATION) - and its job is DONE on the first
 * attempt.
 */
class BookingCompletionVerifiedPlayerTest extends IntegrationTest {

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
    void theTenthAttendedCompletionCommitsWithItsVerifiedTransition() {
        BookingFlows.Customer customer = flows.customer();
        for (int i = 1; i <= 9; i++) {
            Booking attended = attendedPastItsEnd(customer);
            assertThat(flows.fireCompletion(attended)).isTrue();
            assertThat(booking(attended.bookingId()).status()).as("completion %d", i).isEqualTo("COMPLETED");
        }
        Booking tenth = attendedPastItsEnd(customer);
        assertThat(transitions(tenth.customerId())).as("nine: not yet").isZero();

        assertThat(flows.fireCompletion(tenth)).isTrue();

        DueJobs.JobRow job = DueJobs.job(BookingFlows.bookingCompleteKey(tenth.bookingId()));
        assertThat(job.status()).isEqualTo("DONE");
        assertThat(job.attempt()).as("first attempt").isEqualTo(1);
        assertThat(booking(tenth.bookingId()).status()).isEqualTo("COMPLETED");
        assertThat(OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", tenth.bookingId()).query(String.class).single()).isEqualTo("ATTENDED");
        assertThat(OWNER.sql("SELECT count(*) FROM trust.trust_event WHERE booking_id = :id")
                .param("id", tenth.bookingId()).query(Long.class).single()).isOne();
        assertThat(OWNER.sql("SELECT count(*) FROM loyalty.reward_application WHERE booking_id = :id")
                .param("id", tenth.bookingId()).query(Long.class).single()).isOne();
        assertThat(outboxTypes(tenth.bookingId()))
                .contains(IntegrationEvents.BOOKING_COMPLETED, IntegrationEvents.ATTENDANCE_RESOLVED);
        assertThat(OWNER.sql("""
                        SELECT completed_attended_count || '|' || vp_qualification || '|' || vp_operational_status
                          FROM trust.trust_projection WHERE user_id = :id""")
                .param("id", tenth.customerId()).query(String.class).single()).isEqualTo("10|QUALIFIED|VERIFIED");
        assertThat(OWNER.sql("""
                        SELECT from_status || '>' || to_status || '|' || cause
                          FROM trust.verified_player_transition WHERE user_id = :id""")
                .param("id", tenth.customerId()).query(String.class).list())
                .containsExactly("NOT_VERIFIED>VERIFIED|TRUST_QUALIFICATION");
    }

    /** A new ACTIVE booking of the customer, checked in through the real command, then moved past its end. */
    private Booking attendedPastItsEnd(BookingFlows.Customer customer) {
        Booking booking = customer.nextActive(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        return flows.pastItsEnd(booking, Duration.ofMinutes(10));
    }

    private static long transitions(UUID customerId) {
        return OWNER.sql("SELECT count(*) FROM trust.verified_player_transition WHERE user_id = :id")
                .param("id", customerId).query(Long.class).single();
    }
}
