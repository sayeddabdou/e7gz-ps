package ps.e7gz.booking.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The venue cancels a CONFIRMED Online Booking with the Owner Password, behind
 * {@code POST /venue/bookings/{bookingId}/cancel-confirmed} (FR-VEN-03, E7-114). One {@code CommandRunner} transaction
 * under the booking's lock set, wrapped by E7-42's {@link Idempotency} (ADR-015) and run through the
 * {@link OwnerPasswordGate}, which checks the password first inside that transaction (arch 09 section 4.3), before the
 * Idempotency-Key lookup (PO decision Q3, 24 Oct 2026); then the cancellation, its history, refund decision, job
 * withdrawals, outbox event, the SUCCESS audit row and the Idempotency-Key record commit together or not at all.
 *
 * <p>A missing password is {@code 423 OWNER_PASSWORD_REQUIRED}, nothing written; a wrong one rolls everything back and
 * the gate records the FAILURE audit row and the attempt in their own transaction ({@code 423 OWNER_PASSWORD_INVALID});
 * while the account is locked out every attempt is {@code 429 RATE_LIMITED} (FR-VEN-05, PCR-006). A right one whose
 * cancellation is then refused (a booking no longer CONFIRMED: 409) leaves one OWNER_PASSWORD_ACCEPTED row from the gate
 * (PO decision Q2). A refused request leaves no idempotency record, so the key may be retried. A replay is checked like
 * a first request - a wrong or missing password is answered as above, never with the stored answer - and with the right
 * one returns the stored answer without running anything else.
 *
 * <p>The venue is the signed-in account's own ({@link VenueAccountVenues}), never one named by the request: a booking
 * of another venue, or none, is {@code NOT_FOUND} before any password check or idempotency record.
 */
@Component
public class VenueConfirmedCancellations {

    /** The Owner Password matrix id (FRS section 2.4 "cancel CONFIRMED Online Booking"). */
    static final String PROTECTED_ACTION = "CANCEL_CONFIRMED_ONLINE_BOOKING";

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final VenueAnswerStore store;
    private final VenueConfirmedCancellation cancellation;
    private final OwnerPasswordGate gate;
    private final AuditWriter audit;

    VenueConfirmedCancellations(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts,
                                VenueAnswerStore store, VenueConfirmedCancellation cancellation, OwnerPasswordGate gate,
                                AuditWriter audit) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.store = store;
        this.cancellation = cancellation;
        this.gate = gate;
        this.audit = audit;
    }

    /** Cancels once per Idempotency-Key, or replays the stored response without running anything. */
    public <R> IdempotentResult<R> cancel(UUID venueAccountId, UUID bookingId, String ownerPassword,
                                          IdempotentRequest key, StoredResponse<R> response,
                                          Function<VenueBookingRow, R> view) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(view, "view");
        ProtectedCancel cancel = protectedCancel(venueAccountId, bookingId);
        return gate.run(new Protected<>(idempotency.wrap(key, response, new Mapped<>(cancel, view))),
                venueAccountId, ownerPassword, cancel.correlationId());
    }

    /** The same command without an Idempotency-Key record: package-private, for this package's tests. */
    VenueBookingRow cancel(UUID venueAccountId, UUID bookingId, String ownerPassword) {
        ProtectedCancel cancel = protectedCancel(venueAccountId, bookingId);
        return gate.run(cancel, venueAccountId, ownerPassword, cancel.correlationId());
    }

    private ProtectedCancel protectedCancel(UUID venueAccountId, UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId");
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        FindBooking.Keys keys = runner.run(new FindBooking(store, bookingId))
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return new ProtectedCancel(keys.scope(), bookingId, venueId, venueAccountId, CorrelationId.require(),
                cancellation, gate, audit);
    }

    /**
     * The protected command (FRS section 2.4 "cancel CONFIRMED Online Booking"), run by the gate after it checked the
     * Owner Password in this transaction (arch 10 section 3): the cancellation and its SUCCESS audit row (FR-VEN-05) -
     * written last, so it exists only if the cancellation commits. Category B: the management of an existing commitment
     * (FRS section 2.3), under the booking's Customer, Venue and Resource locks - the lock set of T4 too.
     */
    @OwnerProtected(PROTECTED_ACTION)
    record ProtectedCancel(CommandScope scope, UUID bookingId, UUID venueId, UUID venueAccountId, UUID correlationId,
                           VenueConfirmedCancellation cancellation, OwnerPasswordGate gate,
                           AuditWriter audit) implements Command<VenueBookingRow> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public VenueBookingRow execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verified(context, venueAccountId);
            VenueBookingRow cancelled = cancellation.cancel(context, bookingId, venueId, venueAccountId);
            audit.write(context, AuditRecord.builder(BookingAuditAction.VENUE_CANCELLED_CONFIRMED_BOOKING,
                            AuditActor.venue(venueAccountId), bookingId, correlationId)
                    .ownerPasswordCheck(check)
                    .stateChange(BookingStatus.CONFIRMED.name(), BookingStatus.CANCELLED.name())
                    .contextVenue(venueId)
                    .build());
            return cancelled;
        }

        /** The booking and the actor only, as before; the password is the gate's alone (NFR-21). */
        @Override
        public String toString() {
            return "ProtectedCancel[bookingId=" + bookingId + ", venueAccountId=" + venueAccountId + "]";
        }
    }

    /**
     * The command {@link OwnerPasswordGate#run} is given: the Idempotency-Key wrapper around a {@link ProtectedCancel}.
     * The gate checks the password before it, so before the Idempotency-Key lookup. It only carries the action id the
     * gate audits a refusal under, and adds nothing to the transaction.
     */
    @OwnerProtected(PROTECTED_ACTION)
    record Protected<R>(Command<R> command) implements Command<R> {

        Protected {
            Objects.requireNonNull(command, "command");
        }

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
            return command.execute(context);
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
