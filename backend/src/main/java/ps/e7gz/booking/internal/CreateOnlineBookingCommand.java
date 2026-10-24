package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.booking.internal.OnlineBookingStore.PendingBooking;
import ps.e7gz.booking.internal.OnlineCreationPredicate.Approval;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeePayments;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.SessionSnapshots.Session;
import ps.e7gz.venue.api.VenueContacts;

/**
 * — → PENDING (arch 06 §1.2, FR-BOK-11, FR-BOK-13, arch 01 §11.1): in one {@code READ COMMITTED} command transaction,
 * the twenty conditions, then the PENDING Online Booking with its snapshots, its BOOKING reservation (and the ledger's
 * {@code ReservationHeld}), the first history row, the venue response deadline job (T1) and the {@code BookingCreated}
 * event. All commit together or not at all (I2, EC-AVAI-001). When the stored Booking Fee is above zero, the Customer's
 * Wallet pays it in the same transaction if it covers the whole fee (PCR-009, E7-66 PR 3); at the launch fee of EGP 0
 * there is nothing to pay and Loyalty is not called.
 *
 * <p>Write order follows the database: {@code fk_resource_reservation_booking_id__booking_id} is not deferrable, so
 * the Booking row comes before its reservation. The database refuses what raced past the conditions - another
 * Booking on the slot ({@code ex_no_double_booking} / {@code gd_reservation_conflict_booking}, {@code SlotTaken} →
 * 409), an overlapping commitment ({@code ex_customer_no_overlap} → {@code OVERLAPPING_COMMITMENT}) and its creation
 * guards - and the whole transaction rolls back.
 *
 * <p>The route runs it wrapped by E7-42's {@code Idempotency} (see {@link OnlineBookingCreation}), so the
 * Idempotency-Key record is begun and completed in this same transaction (PO decision 9 Oct 2026).
 */
final class CreateOnlineBookingCommand implements Command<CreatedBooking> {

    /** FR-BOK-13, Product v5.0 §23.1: the venue has 10 minutes; {@code tr_21_booking_online_intake} requires exactly this. */
    static final Duration VENUE_RESPONSE_WINDOW = Duration.ofMinutes(10);

    /** T1 (arch 08 §2): the job E7-47 fires at {@code venue_response_expires_at}. */
    static final String VENUE_RESPONSE_EXPIRY = "VENUE_RESPONSE_EXPIRY";
    static final String BOOKING_SUBJECT = "BOOKING";
    static final String BOOKING_AGGREGATE = "BOOKING";

    private final OnlineBookingRequest request;
    private final ResourceCatalog.Entry resource;
    private final OnlineCreationPredicate predicate;
    private final OnlineBookingStore store;
    private final ReservationLedger ledger;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final VenueContacts venues;
    private final BookingFeePayments payments;

    CreateOnlineBookingCommand(OnlineBookingRequest request, ResourceCatalog.Entry resource,
                               OnlineCreationPredicate predicate, OnlineBookingStore store, ReservationLedger ledger,
                               ScheduledJobs jobs, Outbox outbox, VenueContacts venues, BookingFeePayments payments) {
        this.request = Objects.requireNonNull(request, "request");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.predicate = predicate;
        this.store = store;
        this.ledger = ledger;
        this.jobs = jobs;
        this.outbox = outbox;
        this.venues = venues;
        this.payments = payments;
    }

    /** A new marketplace activity (ADR-022, arch 06 §1.4). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    /**
     * arch 05 §4.2: the Customer (with their commitment guard, Bookings and Bans), the Venue (with its account) and the
     * Resource; {@code begin_command} discovers and locks the rest in its fixed order.
     */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.builder()
                .users(request.customerId())
                .venues(resource.venueId())
                .resources(resource.resourceId())
                .build());
    }

    @Override
    public CreatedBooking execute(CommandContext context) {
        Instant now = context.commandNow();
        Approval approval = predicate.check(context, request, resource);
        Session session = approval.session();
        UUID bookingId = UUID.randomUUID();
        Instant expiresAt = now.plus(VENUE_RESPONSE_WINDOW);

        store.insertPending(context, new PendingBooking(bookingId, resource.venueId(), resource.resourceId(),
                request.customerId(), resource.activity(), resource.consoleType(), request.playMode(),
                approval.resourceName(), request.startAt(), approval.endAt(), approval.amounts(),
                session.operatingDay(), session.id(), session.localStart(), session.localEnd(), session.timezone(),
                expiresAt, now));
        UUID reservationId = ledger.create(context, new NewReservation(resource.resourceId(),
                ReservationOwner.booking(bookingId), request.startAt(), approval.endAt()));
        store.insertCreatedHistory(context, bookingId, request.customerId(), now, CorrelationId.require());
        // PCR-009: the Wallet pays the stored fee when it covers all of it; the Customer is in this command's lock set
        if (!approval.amounts().bookingFee().isZero()) {
            payments.payFromWallet(context, request.customerId(), bookingId);
        }
        // T1 at the stored deadline; the job's own fire re-reads it (arch 08 §2.2)
        jobs.schedule(context, NewJob.of(VENUE_RESPONSE_EXPIRY, BOOKING_SUBJECT, bookingId, expiresAt));
        outbox.publish(context, bookingCreated(bookingId, approval.endAt()));
        // The Venue is locked by begin_command, so its name and phone are those of this booking's creation.
        VenueContacts.Contact venue = venues.find(context, resource.venueId())
                .orElseThrow(() -> new IllegalStateException("Booked Venue not found: " + resource.venueId()));

        return new CreatedBooking(bookingId, reservationId, request.customerId(), resource.venueId(),
                resource.resourceId(), request.startAt(), approval.endAt(), expiresAt, approval.amounts(), now,
                venue.name(), venue.phone(), approval.resourceName(), resource.activity(), resource.consoleType(),
                request.playMode());
    }

    /**
     * {@code BookingCreated {bookingId, customerId, venueId, resourceId, startAt, endAt}}; the outbox adds
     * {@code correlationId} and {@code occurredAt} (arch 08 section 3.2, arch 01 section 11.1; PO decision 9 Oct 2026).
     */
    private NewOutboxEvent bookingCreated(UUID bookingId, Instant endAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", bookingId);
        payload.put("customerId", request.customerId());
        payload.put("venueId", resource.venueId());
        payload.put("resourceId", resource.resourceId());
        payload.put("startAt", request.startAt().toString());
        payload.put("endAt", endAt.toString());
        return new NewOutboxEvent(IntegrationEvents.BOOKING_CREATED, BOOKING_AGGREGATE, bookingId, payload);
    }
}
