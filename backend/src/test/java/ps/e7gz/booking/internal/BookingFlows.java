package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.TestCommands;

/**
 * Test support for other modules (E7-55): real Online Bookings driven through this package's own commands - create,
 * Accept, Reject, T1 expiry, Reject After Expiry, customer cancel, an Accept stopped by a venue emergency, and the Late
 * Confirm's Initiate, Accept, Decline, T2 and T3 (E7-58) - so a
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
    private final LateConfirmations lateConfirmations;
    private final LateConfirmDb lateConfirms;

    private BookingFlows(ApplicationContext context) {
        this.creation = context.getBean(OnlineBookingCreation.class);
        this.responses = context.getBean(VenueResponses.class);
        this.answers = context.getBean(VenueAnswers.class);
        this.store = context.getBean(VenueAnswerStore.class);
        this.cancellation = context.getBean(CustomerCancellation.class);
        this.runner = context.getBean(CommandRunner.class);
        this.lateConfirmations = context.getBean(LateConfirmations.class);
        this.lateConfirms = new LateConfirmDb(runner, creation, store, answers);
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

    // ---------------------------------------------------------------------------- E7-58 Late Confirm (O3)

    /**
     * An Online Booking the venue let expire (T1), revived by the venue's Late Confirm through the real Initiate:
     * AWAITING_CUSTOMER_CONFIRMATION with a five-minute window, its T2 job and its T3 warning job.
     */
    public Booking lateConfirmed() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        return initiate(world, lateConfirms.expired(world, world.start(), 60));
    }

    /**
     * As {@link #lateConfirmed()}, for a booking that starts {@code startIn} from now: the window ends at the start,
     * so a {@code startIn} of 60 seconds or less makes a window of 60 seconds or less, with no T3 job (FR-LCF-12).
     */
    public Booking lateConfirmedStartingIn(Duration startIn) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID bookingId = lateConfirms.expired(world, world.start(), 60);
        BookingStates.plant(bookingId, BookingStates.Plant.of("VENUE_RESPONSE_EXPIRED")
                .startAt(OnlineBookingWorld.dbNow().plus(startIn)));
        return initiate(world, bookingId);
    }

    /**
     * As {@link #lateConfirmed()}, while the same customer holds another booking, on another room, overlapping it: the
     * venue may still send it, and only the customer's Accept fails (D1, FR-LCF-15).
     */
    public Booking lateConfirmedOverlappingAnotherBooking() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID bookingId = lateConfirms.expired(world, world.start(), 120);
        creation.create(world.request(world.customer.id(), world.anotherRoom(room -> room),
                world.start().plus(Duration.ofMinutes(30)), 60));
        return initiate(world, bookingId);
    }

    public void acceptLateConfirm(Booking booking) {
        assertThat(lateConfirmations.accept(booking.customerId(), booking.bookingId()))
                .isInstanceOf(CommandAnswer.Ok.class);
    }

    /** The customer's Accept meets their own overlapping booking: the 409 only, the booking stays AWAITING (D1). */
    public void acceptLateConfirmConflicting(Booking booking) {
        assertThat(lateConfirmations.accept(booking.customerId(), booking.bookingId()))
                .isInstanceOfSatisfying(CommandAnswer.Refused.class, refused ->
                        assertThat(refused.refusal().code()).isEqualTo(ApiErrorCode.LATE_CONFIRM_CUSTOMER_CONFLICT));
    }

    public void declineLateConfirm(Booking booking) {
        assertThat(lateConfirmations.decline(booking.customerId(), booking.bookingId()).status())
                .isEqualTo(BookingStatus.CANCELLED);
    }

    /**
     * T2 past its deadline: the stored deadline is shortened to just after the initiation (only possible with the
     * triggers off, {@link LateConfirmDb#deadlineAt}), the database clock passes it, then the real expiry runs.
     */
    public void timeOutLateConfirm(Booking booking) {
        Instant deadline = LateConfirmDb.attempt(booking.bookingId()).orElseThrow().initiatedAt().plusMillis(1);
        LateConfirmDb.deadlineAt(booking.bookingId(), deadline);
        Deadlines.awaitDatabaseClockPast(deadline.plusSeconds(1));
        assertThat(fireLateConfirmJob(booking, LateConfirmDb.t2(booking.bookingId())))
                .isInstanceOf(JobOutcome.Done.class);
    }

    /** T3 inside its last minute: the stored deadline is 30 seconds away, then the real warning runs. */
    public void warnLateConfirm(Booking booking) {
        LateConfirmDb.deadlineAt(booking.bookingId(), OnlineBookingWorld.dbNow().plusSeconds(30));
        assertThat(fireLateConfirmJob(booking, LateConfirmDb.t3(booking.bookingId())))
                .isInstanceOf(JobOutcome.Done.class);
    }

    /** The idempotency key of the booking's T3 job ({@code LATE_CONFIRM_WARNING:{bookingId}}). */
    public static String lateConfirmWarningKey(Booking booking) {
        return LateConfirmDb.t3(booking.bookingId());
    }

    /** TEST-ONLY: the venue is renamed now (no venue command does it yet), to tell an event's name from today's. */
    public static void renameVenue(UUID venueId, String name) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("UPDATE venue.venue SET name = :name WHERE id = :id")
                .param("name", name).param("id", venueId).update());
    }

    private Booking initiate(OnlineBookingWorld world, UUID bookingId) {
        assertThat(lateConfirmations.initiate(world.account.id(), bookingId)).isInstanceOf(CommandAnswer.Ok.class);
        return OnlineBookingWorld.OWNER.sql("""
                        SELECT start_at, venue_response_expires_at FROM booking.booking WHERE id = :id""")
                .param("id", bookingId)
                .query((rs, n) -> new Booking(bookingId, world.customer.id(), world.venue.id(), world.venue.name(),
                        world.account.id(), world.room.id(), rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("venue_response_expires_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    /** The handler's decision for the job, in one command under the booking's locks, as the deadline worker runs it. */
    private JobOutcome fireLateConfirmJob(Booking booking, String key) {
        String type = key.substring(0, key.indexOf(':'));
        ScheduledJob job = new ScheduledJob(UUID.randomUUID(), type, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                booking.bookingId(), Instant.EPOCH, 1, key, CorrelationId.generate());
        return runner.run(TestCommands.under(CommandScope.shared(lateConfirmations.lockSetOf(booking.bookingId())),
                context -> type.equals("LATE_CONFIRM_EXPIRY") ? lateConfirmations.expire(context, job)
                        : lateConfirmations.warn(context, job)));
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
