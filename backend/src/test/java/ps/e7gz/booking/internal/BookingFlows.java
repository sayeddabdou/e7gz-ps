package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * Test support for other modules (E7-55): real Online Bookings driven through this package's own commands - create,
 * Accept, Reject, T1 expiry, Reject After Expiry, customer cancel, an Accept stopped by a venue emergency, and the Late
 * Confirm's Initiate, Accept, Decline, T2 and T3 (E7-58), a Manual Booking (E7-48) and the hours-change
 * {@code BookingCancelled} (E7-113) - so a
 * consumer of their integration events can be tested against what the booking module really writes. Test support
 * only: it adds no behaviour to the booking module, only reaches the package-private entry points the booking tests
 * use. One deliberate exception, {@link #publishCancelledByHoursChange}, publishes a synthetic event without the state
 * change behind it (see there).
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
    private final ManualBookings manualBookings;
    private final Outbox outbox;
    private final ScheduledJobs jobs;
    private final ApplicationContext context;
    private DueJobs starts;
    private DueJobs completions;

    private BookingFlows(ApplicationContext context) {
        this.context = context;
        this.creation = context.getBean(OnlineBookingCreation.class);
        this.responses = context.getBean(VenueResponses.class);
        this.answers = context.getBean(VenueAnswers.class);
        this.store = context.getBean(VenueAnswerStore.class);
        this.cancellation = context.getBean(CustomerCancellation.class);
        this.runner = context.getBean(CommandRunner.class);
        this.lateConfirmations = context.getBean(LateConfirmations.class);
        this.lateConfirms = new LateConfirmDb(runner, creation, store, answers);
        this.manualBookings = context.getBean(ManualBookings.class);
        this.outbox = context.getBean(Outbox.class);
        this.jobs = context.getBean(ScheduledJobs.class);
    }

    /** The worker profile is off in tests: E7-60's T4 handler built by hand, as the deadline worker runs it. */
    private synchronized DueJobs starts() {
        if (starts == null) {
            starts = DueJobs.of(context, new BookingStartHandler(runner, store, context.getBean(BookingStarts.class)));
        }
        return starts;
    }

    /** As {@link #starts()}, for E7-61's T5 handler. */
    private synchronized DueJobs completions() {
        if (completions == null) {
            completions = DueJobs.of(context, new BookingCompleteHandler(runner, store,
                    context.getBean(BookingCompletions.class)));
        }
        return completions;
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

    /** Two PENDING Online Bookings of ONE customer at one venue, an hour long, two hours apart (E7-113). */
    public List<Booking> twoPendingOfOneCustomer() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        return List.of(pending(world, world.start()), pending(world, world.start().plus(Duration.ofHours(2))));
    }

    /** A future Manual Booking, created CONFIRMED through the real command (E7-48): no customer. */
    public Booking manualConfirmed() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        VenueBookingRow created = manualBookings.create(world.account.id(),
                ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE, world.start(), 60, null, null));
        return new Booking(created.bookingId(), null, world.venue.id(), world.venue.name(), world.account.id(),
                world.room.id(), created.startAt(), null);
    }

    // ---------------------------------------------------------------------------- E7-59 attendance (O13)

    /**
     * An ACTIVE Online Booking, 60 minutes long, that started {@code startedAgo} before the database clock: created and
     * accepted through the real commands, then started as T4 would start it ({@link BookingStates#started}; the start
     * is E7-60's). Its reservation covers exactly the booked interval.
     */
    public Booking active(Duration startedAgo) {
        return started(pending(), startedAgo);
    }

    /**
     * One customer at one venue, for a series of their bookings (E7-59: consecutive no-shows). Each
     * {@link Customer#nextActive} is a new ACTIVE Online Booking of the same customer and room, made as {@link #active}
     * makes one; the previous one must no longer hold a commitment (a no-show ends it).
     */
    public Customer customer() {
        return new Customer(OnlineBookingWorld.create());
    }

    public final class Customer {

        private final OnlineBookingWorld world;

        private Customer(OnlineBookingWorld world) {
            this.world = world;
        }

        public Booking nextActive(Duration startedAgo) {
            return started(pending(world, world.start()), startedAgo);
        }
    }

    /**
     * The delayed-start race (Amr Heiba, 17 Oct 2026): an Online Booking accepted through the real command, whose
     * startAt lies {@code startedAgo} before the database clock, still CONFIRMED - its Accept's BOOKING_START job left
     * PENDING, as if the start (T4, E7-60) had not been processed yet ({@link BookingStates#startOverdue}).
     */
    public Booking confirmedWithOverdueStart(Duration startedAgo) {
        Booking booking = pending();
        accept(booking);
        Instant start = OnlineBookingWorld.dbNow().minus(startedAgo);
        BookingStates.startOverdue(booking.bookingId(), start);
        return new Booking(booking.bookingId(), booking.customerId(), booking.venueId(), booking.venueName(),
                booking.venueAccountId(), booking.resourceId(), start, booking.venueResponseExpiresAt());
    }

    /** As {@link #confirmedWithOverdueStart}, for a future Manual Booking created CONFIRMED (E7-48): no customer. */
    public Booking manualConfirmedWithOverdueStart(Duration startedAgo) {
        Booking booking = manualConfirmed();
        Instant start = OnlineBookingWorld.dbNow().minus(startedAgo);
        BookingStates.startOverdue(booking.bookingId(), start);
        return new Booking(booking.bookingId(), null, booking.venueId(), booking.venueName(),
                booking.venueAccountId(), booking.resourceId(), start, null);
    }

    // ---------------------------------------------------------------------------- E7-60 start (O11, T4)

    /**
     * Fires the booking's BOOKING_START (T4) once through the real deadline worker and E7-60's handler: the job, still
     * PENDING, is made due now (its stored startAt already passed, {@link #confirmedWithOverdueStart}). Returns whether
     * the worker settled it (DONE, SUPERSEDED or backed off); false when it was not PENDING or a worker holds it.
     */
    public boolean fireStart(Booking booking) {
        String key = VenueAnswerDb.bookingStart(booking.bookingId());
        boolean due = runner.run(TestCommands.under(scope(booking), context -> {
            int pending = context.jdbc().sql("""
                            SELECT count(*) FROM technical.scheduled_job WHERE idempotency_key = :key
                               AND status = 'PENDING'""")
                    .param("key", key).query(Integer.class).single();
            if (pending == 1) {
                starts().makeDue(context, key, context.commandNow());
            }
            return pending == 1;
        }));
        return due && starts().fire(key);
    }

    /** Fires T4 without making it due first: for a race whose other side may withdraw it in between. */
    public boolean fireStartIfDue(Booking booking) {
        return starts().fire(VenueAnswerDb.bookingStart(booking.bookingId()));
    }

    /** Makes the booking's PENDING BOOKING_START due now, for {@link #fireStartIfDue}. */
    public void makeStartDue(Booking booking) {
        runner.run(TestCommands.under(scope(booking), context -> {
            starts().makeDue(context, VenueAnswerDb.bookingStart(booking.bookingId()), context.commandNow());
            return null;
        }));
    }

    // ---------------------------------------------------------------------------- E7-61 completion (O12, T5)

    /**
     * The ACTIVE booking moved back whole so its stored endAt lies {@code endedAgo} (whole seconds) before the database
     * clock ({@link BookingStates#shiftBack}): evidence the real attendance commands recorded stays inside its window.
     * Returns the booking with its new startAt.
     */
    public Booking pastItsEnd(Booking booking, Duration endedAgo) {
        Duration by = Duration.ofSeconds(Duration.between(OnlineBookingWorld.dbNow(), endAt(booking.bookingId()))
                .plus(endedAgo).toSeconds());
        BookingStates.shiftBack(booking.bookingId(), by);
        Instant start = OnlineBookingWorld.OWNER.sql("SELECT start_at FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant();
        return new Booking(booking.bookingId(), booking.customerId(), booking.venueId(), booking.venueName(),
                booking.venueAccountId(), booking.resourceId(), start, booking.venueResponseExpiresAt());
    }

    /**
     * As {@link #pastItsEnd}, to an exact stored endAt - microseconds, the database's precision - before or after the
     * database clock, for the boundary tests. Returns the booking with its new startAt.
     */
    public Booking endingAt(Booking booking, Instant end) {
        Instant target = end.truncatedTo(ChronoUnit.MICROS);
        BookingStates.shiftBack(booking.bookingId(), Duration.between(target, endAt(booking.bookingId())));
        assertThat(endAt(booking.bookingId())).as("the stored endAt").isEqualTo(target);
        Instant start = OnlineBookingWorld.OWNER.sql("SELECT start_at FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(OffsetDateTime.class).single().toInstant();
        return new Booking(booking.bookingId(), booking.customerId(), booking.venueId(), booking.venueName(),
                booking.venueAccountId(), booking.resourceId(), start, booking.venueResponseExpiresAt());
    }

    /** The booking's stored {@code end_at}. */
    public static Instant endAt(UUID bookingId) {
        return OnlineBookingWorld.OWNER.sql("SELECT end_at FROM booking.booking WHERE id = :id")
                .param("id", bookingId).query(OffsetDateTime.class).single().toInstant();
    }

    /**
     * Fires the booking's BOOKING_COMPLETE (T5) once through the real deadline worker and E7-61's handler. A booking
     * started by {@link BookingStates#started} has no T5 (only a real start schedules one), so it is scheduled first at
     * the stored endAt, as the start schedules it; while PENDING, it is made due now. Returns whether the worker settled
     * it (DONE, SUPERSEDED, rescheduled or backed off); false when it was not PENDING or a worker holds it.
     */
    public boolean fireCompletion(Booking booking) {
        return makeCompletionDue(booking) && completions().fire(bookingCompleteKey(booking.bookingId()));
    }

    /** Fires T5 without making it due first ({@link #makeCompletionDue}): for a boundary or a race. */
    public boolean fireCompletionIfDue(Booking booking) {
        return completions().fire(bookingCompleteKey(booking.bookingId()));
    }

    /**
     * The scheduling half of {@link #fireCompletion}:the booking's T5, scheduled at the stored endAt when it has none,
     * made due now while PENDING - for a test that fires it itself (a race, a gated worker). Returns whether it is
     * PENDING and due.
     */
    public boolean makeCompletionDue(Booking booking) {
        String key = bookingCompleteKey(booking.bookingId());
        return runner.run(TestCommands.under(scope(booking), context -> {
            jobs.schedule(context, NewJob.of(ManualBookingCommand.BOOKING_COMPLETE,
                    CreateOnlineBookingCommand.BOOKING_SUBJECT, booking.bookingId(),
                    store.facts(context, booking.bookingId()).orElseThrow().endAt()));
            int pending = context.jdbc().sql("""
                            SELECT count(*) FROM technical.scheduled_job WHERE idempotency_key = :key
                               AND status = 'PENDING'""")
                    .param("key", key).query(Integer.class).single();
            if (pending == 1) {
                completions().makeDue(context, key, context.commandNow());
            }
            return pending == 1;
        }));
    }

    // ------------------------------------- start-time state at startAt (delayed-start no-show, Amr Heiba 17 Oct 2026)

    /** An Owner Review of the booking's room entered at {@code enteredAt}, unresolved; the restriction set with it. */
    public void ownerReviewSince(Booking booking, Instant enteredAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> {
            jdbc.sql("""
                            INSERT INTO operations.owner_review (id, resource_id, entered_at, entry_cause, correlation_id)
                            VALUES (gen_random_uuid(), :room, :at, 'E7-59 test', gen_random_uuid())""")
                    .param("room", booking.resourceId()).param("at", OffsetDateTime.ofInstant(enteredAt,
                            java.time.ZoneOffset.UTC)).update();
            jdbc.sql("UPDATE resource.resource SET operational_restriction = 'OWNER_REVIEW_REQUIRED' WHERE id = :room")
                    .param("room", booking.resourceId()).update();
        });
    }

    /**
     * A Resource Emergency of the booking's room declared at {@code declaredAt} and ENDED at {@code endedAt} - so a
     * decision taken now sees none unresolved, but one at startAt does.
     */
    public void resourceEmergencyEnded(Booking booking, Instant declaredAt, Instant endedAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                          expected_end_at, declared_period, reason, ended_at)
                        VALUES (gen_random_uuid(), 'RESOURCE', :venue, :room, 'ENDED', :declared,
                                CAST(:declared AS timestamptz) + interval '10 minutes',
                                tstzrange(:declared, CAST(:declared AS timestamptz) + interval '10 minutes', '[)'),
                                'E7-59 test', :ended)""")
                .param("venue", booking.venueId()).param("room", booking.resourceId())
                .param("declared", OffsetDateTime.ofInstant(declaredAt, java.time.ZoneOffset.UTC))
                .param("ended", OffsetDateTime.ofInstant(endedAt, java.time.ZoneOffset.UTC)).update());
    }

    /**
     * A Resource Emergency of the booking's room declared at {@code declaredAt}, still unresolved (ACTIVE), with the
     * selling block it puts in force from then.
     */
    public void resourceEmergencySince(Booking booking, Instant declaredAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        WITH e AS (
                            INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                              expected_end_at, declared_period, reason)
                            VALUES (gen_random_uuid(), 'RESOURCE', :venue, :room, 'ACTIVE', :declared,
                                    CAST(:declared AS timestamptz) + interval '2 hours',
                                    tstzrange(:declared, CAST(:declared AS timestamptz) + interval '2 hours', '[)'),
                                    'E7-59 test')
                            RETURNING id, declared_at)
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active,
                                                                     started_at)
                        SELECT gen_random_uuid(), :room, 'EMERGENCY', e.id, true, e.declared_at FROM e""")
                .param("venue", booking.venueId()).param("room", booking.resourceId())
                .param("declared", OffsetDateTime.ofInstant(declaredAt, java.time.ZoneOffset.UTC)).update());
    }

    /** The booking's room DISABLED behind the guards - the E7-60 integrity anomaly (FR-BOK-28). */
    public void resourceDisabledBehindTheGuards(Booking booking) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("UPDATE resource.resource SET base_state = 'DISABLED' WHERE id = :room")
                .param("room", booking.resourceId()).update());
    }

    /** A Venue Operational Selling Block with no Emergency behind it, started at {@code startedAt}. */
    public void venueSellingBlockSince(Booking booking, Instant startedAt) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
                        VALUES (gen_random_uuid(), :venue, 'E7-59 test block', true, :at)""")
                .param("venue", booking.venueId())
                .param("at", OffsetDateTime.ofInstant(startedAt, java.time.ZoneOffset.UTC)).update());
    }

    /** The booking's BOOKING_START held LEASED by a worker right now, as during its fire. */
    public void startLeased(Booking booking) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE technical.scheduled_job
                           SET status = 'LEASED', leased_at = now(), leased_until = now() + interval '1 minute',
                               leased_by = 'E7-59 test worker'
                         WHERE idempotency_key = :key AND status = 'PENDING'""")
                .param("key", VenueAnswerDb.bookingStart(booking.bookingId())).update());
    }

    /** The idempotency key of the booking's BOOKING_START (T4) job. */
    public static String bookingStartKey(UUID bookingId) {
        return VenueAnswerDb.bookingStart(bookingId);
    }

    /** The idempotency key of the booking's BOOKING_COMPLETE (T5) job. */
    public static String bookingCompleteKey(UUID bookingId) {
        return ManualBookingCommand.bookingCompleteKey(bookingId);
    }

    /** Withdraws the booking's BOOKING_START job, as an already processed start would have left it. */
    public void withdrawStart(Booking booking) {
        runner.run(TestCommands.under(scope(booking),
                context -> jobs.supersede(context, VenueAnswerDb.bookingStart(booking.bookingId()))));
    }

    private Booking started(Booking booking, Duration startedAgo) {
        accept(booking);
        runner.run(TestCommands.under(scope(booking),
                context -> jobs.supersede(context, VenueAnswerDb.bookingStart(booking.bookingId()))));
        Instant start = OnlineBookingWorld.dbNow().minus(startedAgo);
        BookingStates.started(booking.bookingId(), start);
        return new Booking(booking.bookingId(), booking.customerId(), booking.venueId(), booking.venueName(),
                booking.venueAccountId(), booking.resourceId(), start, booking.venueResponseExpiresAt());
    }

    /**
     * Two ACTIVE Online Bookings of ONE venue, of two customers on two rooms, both started {@code startedAgo} before the
     * database clock (as {@link #active}).
     */
    public List<Booking> twoActiveAtOneVenue(Duration startedAgo) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        List<Booking> bookings = List.of(pending(world, world.start()),
                pendingOf(world, world.anotherCustomer(), world.anotherRoom(room -> room), world.start()));
        return bookings.stream().map(booking -> started(booking, startedAgo)).toList();
    }

    private Booking pendingOf(OnlineBookingWorld world, UUID customerId, Fixtures.Resource room, Instant start) {
        CreatedBooking created = creation.create(world.request(customerId, room, start, 60));
        return new Booking(created.bookingId(), created.customerId(), world.venue.id(), world.venue.name(),
                world.account.id(), room.id(), created.startAt(), created.venueResponseExpiresAt());
    }

    /** A walk-in Manual Booking, created ACTIVE at the command instant through the real command (E7-48). */
    public Booking walkIn() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        VenueBookingRow created = manualBookings.create(world.account.id(),
                ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE, null, 60, null, null));
        return new Booking(created.bookingId(), null, world.venue.id(), world.venue.name(), world.account.id(),
                world.room.id(), created.startAt(), null);
    }

    /**
     * A walk-in of {@code minutes} on the booking's room, starting now, through the real command: the room must be free
     * now under the normal rules (FR-ATT-05: the resource may be sold again manually). @return its Booking ID
     */
    public UUID walkInOnRoomOf(Booking booking, int minutes) {
        return manualBookings.create(booking.venueAccountId(),
                ManualBookingRequest.of(booking.resourceId(), PlayMode.SINGLE, null, minutes, null, null)).bookingId();
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

    /**
     * TEST-ONLY (E7-113 Phase 1): a SYNTHETIC {@code BookingCancelled} with {@code terminalReason}
     * VENUE_OPENING_HOURS_CHANGED, built by the production builders - {@link BookingEvents#cancelled} for an Online
     * Booking, {@link BookingEvents#manualCancelled} for a Manual one - and published through the real outbox under the
     * booking's locks.
     *
     * <p>It deliberately does NOT change the booking: the row keeps its status and its reservation, and no history,
     * refund, hold release or Late Confirm closing is written, because the cancellation itself (O5) is not built
     * yet. It therefore tests notification consumption only - the consumer reads only the event and the booking's
     * parties - and never the complete cancellation transaction or its atomicity with the event (FR-NTF-12).
     */
    public void publishCancelledByHoursChange(Booking booking) {
        runner.run(TestCommands.under(scope(booking), context -> {
            VenueAnswerStore.Facts facts = store.facts(context, booking.bookingId()).orElseThrow();
            BookingTerminalReason reason = BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED;
            return outbox.publish(context, facts.source() == BookingSource.MANUAL
                    ? BookingEvents.manualCancelled(facts, reason) : BookingEvents.cancelled(facts, reason));
        }));
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
