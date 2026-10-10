package ps.e7gz.booking.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * Walk-in / Manual Booking (O4, E7-48) behind {@code POST /venue/bookings} and
 * {@code POST /venue/bookings/{bookingId}/cancel-manual}. Each runs in one {@code CommandRunner} transaction wrapped by
 * E7-42's {@link Idempotency} (ADR-015): the booking or its cancellation, the reservation hold or release, history, jobs,
 * outbox events and the Idempotency-Key record commit together or not at all. No Owner Password (FR-MAN-08).
 *
 * <p>The venue is the signed-in account's own ({@link VenueAccountVenues}), never one named by the request: a Resource or
 * booking of another venue, or none, is {@code NOT_FOUND} before any idempotency record is written.
 */
@Component
public class ManualBookings {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final ResourceCatalog resources;
    private final ManualCreationPredicate predicate;
    private final ManualBookingStore store;
    private final ReservationLedger ledger;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final VenueBookings bookings;
    private final VenueAnswerStore answers;
    private final ManualCancellation cancellation;

    ManualBookings(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts, ResourceCatalog resources,
                   ManualCreationPredicate predicate, ManualBookingStore store, ReservationLedger ledger,
                   ScheduledJobs jobs, Outbox outbox, VenueBookings bookings, VenueAnswerStore answers,
                   ManualCancellation cancellation) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.resources = resources;
        this.predicate = predicate;
        this.store = store;
        this.ledger = ledger;
        this.jobs = jobs;
        this.outbox = outbox;
        this.bookings = bookings;
        this.answers = answers;
        this.cancellation = cancellation;
    }

    /** Creates the booking once per Idempotency-Key, or replays the stored response without running anything else. */
    public <R> IdempotentResult<R> create(UUID venueAccountId, ManualBookingRequest request, IdempotentRequest key,
                                          StoredResponse<R> response, Function<VenueBookingRow, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(createCommand(venueAccountId, request), view)));
    }

    /** Cancels the booking once per Idempotency-Key (PCR-007), or replays the stored response. */
    public <R> IdempotentResult<R> cancel(UUID venueAccountId, UUID bookingId, IdempotentRequest key,
                                          StoredResponse<R> response, Function<VenueBookingRow, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(cancelCommand(venueAccountId, bookingId), view)));
    }

    /** The same commands without an Idempotency-Key record: package-private, for this package's tests. */
    VenueBookingRow create(UUID venueAccountId, ManualBookingRequest request) {
        return runner.run(createCommand(venueAccountId, request));
    }

    VenueBookingRow cancel(UUID venueAccountId, UUID bookingId) {
        return runner.run(cancelCommand(venueAccountId, bookingId));
    }

    /** The Resource is read first, in its own read-only command, because the creation command's lock set names it. */
    private ManualBookingCommand createCommand(UUID venueAccountId, ManualBookingRequest request) {
        Objects.requireNonNull(request, "request");
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        ResourceCatalog.Entry resource = runner.run(new FindResource(resources, request.resourceId()));
        if (!resource.venueId().equals(venueId)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
        return new ManualBookingCommand(request, resource, venueAccountId, predicate, store, ledger, jobs, outbox,
                bookings);
    }

    private Command<VenueBookingRow> cancelCommand(UUID venueAccountId, UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId");
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        FindBooking.Keys keys = runner.run(new FindBooking(answers, bookingId))
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return new Body<>(keys.scope(), context -> cancellation.cancel(context, bookingId, venueId, venueAccountId));
    }

    /** An internal venue operation (category C) under the booking's lock set. */
    private record Body<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /** A command with its result mapped to the response, in the same transaction and lock set. */
    private record Mapped<T, R>(Command<T> command, Function<T, R> view) implements Command<R> {

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
