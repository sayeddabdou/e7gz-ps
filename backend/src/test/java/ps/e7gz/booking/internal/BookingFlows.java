package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.TestCommands;

/**
 * Test support for other modules (E7-55): real Online Bookings driven through this package's own commands - create,
 * Accept, Reject, T1 expiry, Reject After Expiry, customer cancel, an Accept stopped by a venue emergency - so a
 * consumer of their integration events can be tested against what the booking module really writes. Read-only for the
 * booking module: it adds no behaviour, only reaches the package-private entry points the booking tests use.
 */
public final class BookingFlows {

    /** One booking and the world it lives in. */
    public record Booking(UUID bookingId, UUID customerId, UUID venueId, String venueName, UUID venueAccountId,
                          UUID resourceId, Instant startAt, Instant venueResponseExpiresAt) {
    }

    private final OnlineBookingCreation creation;
    private final VenueResponses responses;
    private final VenueAnswers answers;
    private final VenueAnswerStore store;
    private final CustomerCancellation cancellation;
    private final CommandRunner runner;

    private BookingFlows(ApplicationContext context) {
        this.creation = context.getBean(OnlineBookingCreation.class);
        this.responses = context.getBean(VenueResponses.class);
        this.answers = context.getBean(VenueAnswers.class);
        this.store = context.getBean(VenueAnswerStore.class);
        this.cancellation = context.getBean(CustomerCancellation.class);
        this.runner = context.getBean(CommandRunner.class);
    }

    public static BookingFlows of(ApplicationContext context) {
        return new BookingFlows(context);
    }

    /** A PENDING Online Booking of a new venue and customer, starting about an hour from now, 60 minutes long. */
    public Booking pending() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        return pending(world, world.start());
    }

    /** As {@link #pending()}, starting {@code startIn} from now (whole minutes; at least the 15-minute lead time). */
    public Booking pendingStartingIn(Duration startIn) {
        if (startIn.compareTo(OnlineCreationPredicate.MINIMUM_LEAD_TIME) < 0) {
            throw new IllegalArgumentException("A booking starts at least " + OnlineCreationPredicate.MINIMUM_LEAD_TIME
                    + " from now (FR-BOK-02)");
        }
        OnlineBookingWorld world = OnlineBookingWorld.create();
        return pending(world, world.t0.plus(startIn).truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1)));
    }

    private Booking pending(OnlineBookingWorld world, Instant start) {
        CreatedBooking created = creation.create(world.request(world.customer.id(), world.room, start, 60));
        return new Booking(created.bookingId(), created.customerId(), world.venue.id(), world.venue.name(),
                world.account.id(), world.room.id(), created.startAt(), created.venueResponseExpiresAt());
    }

    public void accept(Booking booking) {
        assertThat(responses.accept(booking.venueAccountId(), booking.bookingId()))
                .isInstanceOf(CommandAnswer.Ok.class);
    }

    /** FR-BOK-16: an unresolved venue emergency makes the Accept cancel the booking (VENUE_EMERGENCY_CLOSURE). */
    public void acceptStoppedByVenueEmergency(Booking booking) {
        VenueAnswerDb.venueEmergency(booking.venueId());
        assertThat(responses.accept(booking.venueAccountId(), booking.bookingId()))
                .isInstanceOf(CommandAnswer.Refused.class);
    }

    public void reject(Booking booking) {
        assertThat(responses.reject(booking.venueAccountId(), booking.bookingId(), "Room under maintenance").status())
                .isEqualTo(BookingStatus.REJECTED);
    }

    public void rejectAfterExpiry(Booking booking) {
        assertThat(responses.rejectAfterExpiry(booking.venueAccountId(), booking.bookingId()).status())
                .isEqualTo(BookingStatus.REJECTED);
    }

    /** T1 at its deadline: the deadline is moved to the command's own instant, then the expiry runs. */
    public void expire(Booking booking) {
        JobOutcome outcome = runner.run(TestCommands.under(scope(booking), context -> {
            moveResponseDeadline(context, booking, context.commandNow());
            return answers.expire(context, new ScheduledJob(UUID.randomUUID(),
                    CreateOnlineBookingCommand.VENUE_RESPONSE_EXPIRY, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                    booking.bookingId(), context.commandNow(), 1, VenueAnswers.venueResponseExpiryKey(
                    booking.bookingId()), CorrelationId.generate()));
        }));
        assertThat(outcome).isInstanceOf(JobOutcome.Done.class);
    }

    public void customerCancels(Booking booking) {
        runner.run(TestCommands.under(scope(booking), context -> {
            cancellation.cancel(context, booking.bookingId(), booking.customerId());
            return null;
        }));
    }

    /** The booking's Customer, Venue and Resource: the locks of every answer to it. */
    public CommandScope scope(Booking booking) {
        return runner.run(new FindBooking(store, booking.bookingId())).orElseThrow().scope();
    }

    /** Moves the stored {@code venue_response_expires_at} on the command's connection (needs {@link #scope}). */
    public static void moveResponseDeadline(CommandContext context, Booking booking, Instant at) {
        Deadlines.move(context, "booking.booking.venue_response_expires_at", booking.bookingId(), at);
    }
}
