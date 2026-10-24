package ps.e7gz.booking.internal;

import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.loyalty.api.BookingFeePayments;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.VenueContacts;

/**
 * Online booking creation (O1, FR-BOK-11, FR-BOK-13) behind {@code POST /customer/bookings}. The creation command runs
 * wrapped by E7-42's {@link Idempotency} (ADR-015, arch 08 section 6): {@code idempotency_begin}, the command (only for
 * a new request), {@code idempotency_complete} and the outbox events commit in one {@code CommandRunner} transaction,
 * so no booking commits without its {@code BookingCreated} event or its Idempotency-Key record (PO decision 9 Oct
 * 2026), and a failure rolls all of it back.
 */
@Component
public class OnlineBookingCreation {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final ResourceCatalog resources;
    private final OnlineCreationPredicate predicate;
    private final OnlineBookingStore store;
    private final ReservationLedger ledger;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final VenueContacts venues;
    private final BookingFeePayments payments;

    OnlineBookingCreation(CommandRunner runner, Idempotency idempotency, ResourceCatalog resources,
                          OnlineCreationPredicate predicate, OnlineBookingStore store, ReservationLedger ledger,
                          ScheduledJobs jobs, Outbox outbox, VenueContacts venues, BookingFeePayments payments) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.resources = resources;
        this.predicate = predicate;
        this.store = store;
        this.ledger = ledger;
        this.jobs = jobs;
        this.outbox = outbox;
        this.venues = venues;
        this.payments = payments;
    }

    /**
     * Creates the booking once per Idempotency-Key, or replays the stored response through {@code response}'s
     * {@code ReplayProjection} without running anything else.
     *
     * @param view the stored response built from the committed booking, inside the command transaction
     * @throws ps.e7gz.common.error.ApiException {@code NOT_FOUND} for an unknown Resource (before any idempotency
     *         record); {@link CreationRefused} for a failed condition; {@code SLOT_NO_LONGER_AVAILABLE} when the
     *         database finds the slot taken; {@code IDEMPOTENCY_KEY_REUSED} for the same key with another request
     */
    public <R> IdempotentResult<R> create(OnlineBookingRequest request, IdempotentRequest key,
                                          StoredResponse<R> response, Function<CreatedBooking, R> view) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(key, response, new Viewed<>(command(request), view)));
    }

    /**
     * The same command without an Idempotency-Key record: package-private, for this package's tests of the twenty
     * conditions. Production code goes through the idempotent {@link #create(OnlineBookingRequest, IdempotentRequest,
     * StoredResponse, Function)}.
     */
    CreatedBooking create(OnlineBookingRequest request) {
        return runner.run(command(request));
    }

    /** The Resource is read first, in its own read-only command, because the creation command's lock set names it. */
    private CreateOnlineBookingCommand command(OnlineBookingRequest request) {
        ResourceCatalog.Entry resource = runner.run(new FindResource(resources, request.resourceId()));
        return new CreateOnlineBookingCommand(request, resource, predicate, store, ledger, jobs, outbox, venues,
                payments);
    }

    /** The creation command with its result mapped to the response, in the same transaction and lock set. */
    private record Viewed<R>(CreateOnlineBookingCommand command, Function<CreatedBooking, R> view)
            implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return command.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return command.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return view.apply(command.execute(context));
        }
    }
}
