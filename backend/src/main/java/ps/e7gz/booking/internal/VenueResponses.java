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
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The venue's Accept, Reject and Reject After Expiry behind {@code POST /venue/bookings/{bookingId}/...} (O2, E7-47).
 * Each runs in one {@code CommandRunner} transaction wrapped by E7-42's {@link Idempotency} (ADR-015): the transition,
 * its reservation release, history, scheduled-job changes, outbox events and the Idempotency-Key record commit together
 * or not at all. Accept goes through {@link Idempotency#wrapAnswer}, so an FR-BOK-16 cancellation commits with its 422
 * stored as the response (PO decision D2 10 Oct 2026). No Owner Password (FR-VEN-04).
 *
 * <p>The venue is the signed-in account's own ({@link VenueAccountVenues}), never one named by the request. A booking
 * that does not exist, or belongs to another venue, is {@code NOT_FOUND} before any idempotency record is written.
 */
@Component
public class VenueResponses {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final VenueAnswerStore store;
    private final VenueAnswers answers;

    VenueResponses(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts, VenueAnswerStore store,
                   VenueAnswers answers) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.store = store;
        this.answers = answers;
    }

    /** Accept once per Idempotency-Key; the result may carry a committed FR-BOK-16 refusal. */
    public <R> IdempotentResult<R> accept(UUID venueAccountId, UUID bookingId, IdempotentRequest key,
                                          StoredResponse<R> response, Function<VenueBookingRow, R> view) {
        Objects.requireNonNull(view, "view");
        Command<CommandAnswer<VenueBookingRow>> accept = acceptCommand(venueAccountId, bookingId);
        return runner.run(idempotency.wrapAnswer(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(accept, answer -> switch (answer) {
                    case CommandAnswer.Ok<VenueBookingRow> ok -> CommandAnswer.ok(view.apply(ok.value()));
                    case CommandAnswer.Refused<VenueBookingRow> refused -> CommandAnswer.refused(refused.refusal());
                })));
    }

    /** Reject once per Idempotency-Key, with the venue's own reason text. */
    public <R> IdempotentResult<R> reject(UUID venueAccountId, UUID bookingId, String reasonText,
                                          IdempotentRequest key, StoredResponse<R> response,
                                          Function<VenueBookingRow, R> view) {
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(rejectCommand(venueAccountId, bookingId, reasonText), view)));
    }

    /** Reject After Expiry once per Idempotency-Key. */
    public <R> IdempotentResult<R> rejectAfterExpiry(UUID venueAccountId, UUID bookingId, IdempotentRequest key,
                                                     StoredResponse<R> response, Function<VenueBookingRow, R> view) {
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(rejectAfterExpiryCommand(venueAccountId, bookingId), view)));
    }

    /** The same commands without an Idempotency-Key record: package-private, for this package's tests. */
    CommandAnswer<VenueBookingRow> accept(UUID venueAccountId, UUID bookingId) {
        return runner.run(acceptCommand(venueAccountId, bookingId));
    }

    VenueBookingRow reject(UUID venueAccountId, UUID bookingId, String reasonText) {
        return runner.run(rejectCommand(venueAccountId, bookingId, reasonText));
    }

    VenueBookingRow rejectAfterExpiry(UUID venueAccountId, UUID bookingId) {
        return runner.run(rejectAfterExpiryCommand(venueAccountId, bookingId));
    }

    private Command<CommandAnswer<VenueBookingRow>> acceptCommand(UUID venueAccountId, UUID bookingId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return new Answer<>(keysOf(bookingId, venueId).scope(),
                context -> answers.accept(context, bookingId, venueId, venueAccountId));
    }

    private Command<VenueBookingRow> rejectCommand(UUID venueAccountId, UUID bookingId, String reasonText) {
        Objects.requireNonNull(reasonText, "reasonText");
        UUID venueId = accounts.venueOf(venueAccountId);
        return new Answer<>(keysOf(bookingId, venueId).scope(),
                context -> answers.reject(context, bookingId, venueId, venueAccountId, reasonText));
    }

    private Command<VenueBookingRow> rejectAfterExpiryCommand(UUID venueAccountId, UUID bookingId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return new Answer<>(keysOf(bookingId, venueId).scope(),
                context -> answers.rejectAfterExpiry(context, bookingId, venueId, venueAccountId));
    }

    /** The lock set of a booking of this venue; another venue's booking is not found (no cross-venue access). */
    private FindBooking.Keys keysOf(UUID bookingId, UUID venueId) {
        Objects.requireNonNull(bookingId, "bookingId");
        return runner.run(new FindBooking(store, bookingId))
                .filter(keys -> keys.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /** A venue answer: category B (FR-BOK-15, ADR-022) under the booking's lock set. */
    private record Answer<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
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
