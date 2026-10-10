package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.reservation;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * T1, the venue response expiry (arch 08 section 1; FR-BOK-18), fired by the real deadline worker at its deadline and
 * one second either side, late and twice (arch 08 section 9; E7-45 boundary pack). E7-47's
 * {@code VenueAnswerIntegrationTest} covers the answers racing it at the same instants.
 */
class VenueResponseExpiryBoundaryTest extends IntegrationTest {

    private static final String DEADLINE = "booking.booking.venue_response_expires_at";

    @Autowired
    ApplicationContext context;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueAnswers answers;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    CommandRunner runner;

    private DueJobs due;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void worker() {
        correlation = CorrelationId.open(CorrelationId.generate());
        due = DueJobs.of(context, new VenueResponseExpiryHandler(runner, store, answers));
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void t1ExpiresTheRequestAtItsDeadlineAndNotASecondBefore(Boundary side) {
        CreatedBooking created = pending();

        Instant deadline = fireT1(created, side);

        if (side.beforeDeadline()) {
            assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
            assertThat(reservation(created.reservationId()).active()).isTrue();
            assertThat(DueJobs.job(t1(created.bookingId()))).satisfies(job -> {
                assertThat(job.status()).as("back to wait for its deadline").isEqualTo("PENDING");
                assertThat(job.dueAt()).isEqualTo(deadline);
            });
        } else {
            assertExpiredAsOf(created, deadline);
            assertThat(DueJobs.job(t1(created.bookingId())).status()).isEqualTo("DONE");
        }
    }

    @Test
    void t1FiredLateExpiresAsOfTheDeadlineNotOfWhenItRan() {
        CreatedBooking created = pending();
        Instant deadline = runner.run(TestCommands.under(scope(created), command -> {
            // the request was made seconds ago: its deadline can go back no further than its creation
            Instant at = created.createdAt();
            Deadlines.move(command, DEADLINE, created.bookingId(), at);
            due.makeDue(command, t1(created.bookingId()), at);
            return at;
        }));

        assertThat(due.fire(t1(created.bookingId()))).isTrue();

        assertExpiredAsOf(created, deadline);
    }

    @Test
    void t1FiredTwiceExpiresOnce() {
        CreatedBooking created = pending();
        fireT1(created, Boundary.AT);

        assertThat(due.fire(t1(created.bookingId()))).as("a DONE job never fires again").isFalse();
        assertThat(history(created.bookingId())).hasSize(2);
        assertThat(VenueAnswerDb.outboxTypes(created.bookingId())).containsOnlyOnce(IntegrationEvents.VENUE_RESPONSE_EXPIRED);
    }

    private Instant fireT1(CreatedBooking created, Boundary side) {
        // the reservation cannot be released before it was made: let the request be a second old first
        Deadlines.awaitDatabaseClockPast(created.createdAt().plus(Duration.ofSeconds(1)));
        return due.fireAt(scope(created), t1(created.bookingId()), side,
                (command, at) -> Deadlines.move(command, DEADLINE, created.bookingId(), at));
    }

    private static void assertExpiredAsOf(CreatedBooking created, Instant deadline) {
        assertThat(booking(created.bookingId()).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(reservation(created.reservationId())).satisfies(r -> {
            assertThat(r.active()).isFalse();
            assertThat(r.releasedAt()).isEqualTo(deadline);
        });
        assertThat(history(created.bookingId()).getLast().occurredAt()).isEqualTo(deadline);
    }

    private CommandScope scope(CreatedBooking created) {
        return runner.run(new FindBooking(store, created.bookingId())).orElseThrow().scope();
    }

    private CreatedBooking pending() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        return creation.create(world.request(world.start(), 60));
    }
}
