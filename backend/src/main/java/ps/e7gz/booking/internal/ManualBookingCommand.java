package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.booking.internal.ManualBookingStore.NewManualBooking;
import ps.e7gz.booking.internal.ManualCreationPredicate.Approval;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * — → CONFIRMED (future) or — → ACTIVE (walk-in at the command instant), a Manual Booking (O4; arch 06 §1.1 and §1.4,
 * FR-MAN-01…FR-MAN-10, F-16): in one {@code READ COMMITTED} command transaction, the fifteen conditions, then the
 * booking with its snapshots, its BOOKING reservation (and the ledger's {@code ReservationHeld}), the first history row,
 * the start or completion job and the booking event. All commit together or not at all. It never passes through
 * PENDING, holds no customer and counts toward no commitment limit; attendance and the fee stay NOT_APPLICABLE and no
 * Trust or points effect is written (FR-MAN-06, FR-MAN-08; attendance is E7-61's, PO decision 10 Oct 2026, E7-48, D9).
 *
 * <p>Write order follows the database: the booking row comes before its reservation (non-deferrable FK). The database
 * refuses what raced past the conditions - another booking on the slot ({@code SlotTaken}, 409
 * SLOT_NO_LONGER_AVAILABLE) and its creation guards - and the whole transaction rolls back.
 */
final class ManualBookingCommand implements Command<VenueBookingRow> {

    /**
     * T5 (arch 08 §1, arch 06 §2.3): fired at {@code end_at}; its handler is E7-61's (PO decision 10 Oct 2026, E7-48,
     * D8 - the name is BOOKING_COMPLETE, not BOOKING_COMPLETION). A walk-in skips T4, so creation schedules it.
     */
    static final String BOOKING_COMPLETE = "BOOKING_COMPLETE";

    private final ManualBookingRequest request;
    private final ResourceCatalog.Entry resource;
    private final UUID venueAccountId;
    private final ManualCreationPredicate predicate;
    private final ManualBookingStore store;
    private final ReservationLedger ledger;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final VenueBookings bookings;

    ManualBookingCommand(ManualBookingRequest request, ResourceCatalog.Entry resource, UUID venueAccountId,
                         ManualCreationPredicate predicate, ManualBookingStore store, ReservationLedger ledger,
                         ScheduledJobs jobs, Outbox outbox, VenueBookings bookings) {
        this.request = Objects.requireNonNull(request, "request");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.venueAccountId = Objects.requireNonNull(venueAccountId, "venueAccountId");
        this.predicate = predicate;
        this.store = store;
        this.ledger = ledger;
        this.jobs = jobs;
        this.outbox = outbox;
        this.bookings = bookings;
    }

    /** An internal venue operation (ADR-022, arch 06 §1.4). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
    }

    /** The Venue (with its account) and the Resource; there is no customer to lock (FR-MAN-01). */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.builder().venues(resource.venueId()).resources(resource.resourceId()).build());
    }

    @Override
    public VenueBookingRow execute(CommandContext context) {
        Approval approval = predicate.check(context, request, resource);
        UUID bookingId = UUID.randomUUID();
        store.insert(context, new NewManualBooking(bookingId, resource.venueId(), resource.resourceId(),
                approval.status(), resource.activity(), resource.consoleType(), request.playMode(),
                approval.resourceName(), approval.startAt(), approval.endAt(), approval.amounts(),
                approval.businessDate(), approval.session(), request.customerName(), request.customerPhone(),
                context.commandNow()));
        ledger.create(context, new NewReservation(resource.resourceId(), ReservationOwner.booking(bookingId),
                approval.startAt(), approval.endAt()));
        store.insertCreatedHistory(context, bookingId, approval.status(), context.commandNow(), venueAccountId,
                CorrelationId.require());

        Facts facts = new Facts(bookingId, resource.venueId(), resource.resourceId(), null, BookingSource.MANUAL,
                approval.status(), approval.startAt(), approval.endAt(), null, false);
        // PO decision 10 Oct 2026, E7-48 (D8, D6): a future booking starts at T4 (E7-60's handler, same key form as
        // the Accept's); a walk-in is already ACTIVE, so it waits for T5 (E7-61's handler) at its end
        if (approval.status() == BookingStatus.CONFIRMED) {
            jobs.schedule(context, NewJob.of(VenueAnswers.BOOKING_START, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                    bookingId, approval.startAt()));
            outbox.publish(context, BookingEvents.manualConfirmed(facts));
        } else {
            jobs.schedule(context, NewJob.of(BOOKING_COMPLETE, CreateOnlineBookingCommand.BOOKING_SUBJECT, bookingId,
                    approval.endAt()));
            outbox.publish(context, BookingEvents.manualStarted(facts));
        }
        return bookings.current(context, bookingId);
    }

    /** {@code BOOKING_START:<id>} - the key {@link NewJob#of} gives T4 of this booking. */
    static String bookingStartKey(UUID bookingId) {
        return NewJob.of(VenueAnswers.BOOKING_START, CreateOnlineBookingCommand.BOOKING_SUBJECT, bookingId,
                Instant.EPOCH).idempotencyKey();
    }

    /** {@code BOOKING_COMPLETE:<id>} - the key {@link NewJob#of} gives T5 of this booking. */
    static String bookingCompleteKey(UUID bookingId) {
        return NewJob.of(BOOKING_COMPLETE, CreateOnlineBookingCommand.BOOKING_SUBJECT, bookingId,
                Instant.EPOCH).idempotencyKey();
    }
}
