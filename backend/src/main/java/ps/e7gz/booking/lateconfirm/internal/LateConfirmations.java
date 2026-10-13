package ps.e7gz.booking.lateconfirm.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmBookings;
import ps.e7gz.booking.api.LateConfirmBookings.Booking;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * O3 behind its three routes (arch 09 sections 3.3 and 4.1; E7-58): the venue sends a Late Confirmation, the customer
 * accepts or declines it. Each runs in one {@code CommandRunner} transaction wrapped by E7-42's {@link Idempotency}
 * (ADR-015): the transition, hold, attempt, history, jobs, outbox event and the Idempotency-Key record commit together
 * or not at all. Initiate and Accept go through {@link Idempotency#wrapAnswer}, so a refusal whose history event must
 * commit (the 4-commitment limit, D4; a conflicting Accept, D1) is stored as the response and replayed as the same 4xx.
 * Category B for all three (FR-LCF-01), under the booking's Customer, Venue and Resource locks.
 *
 * <p>The venue is the signed-in account's own ({@link VenueAccountVenues}); the customer is the signed-in customer. A
 * booking that does not exist, or is another venue's or another customer's, is {@code NOT_FOUND} before any
 * idempotency record is written.
 */
@Component
public class LateConfirmations {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final LateConfirmBookings bookings;
    private final LateConfirmCommands commands;

    LateConfirmations(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts,
                      LateConfirmBookings bookings, LateConfirmCommands commands) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.bookings = bookings;
        this.commands = commands;
    }

    /** The venue sends it once per Idempotency-Key; the result may carry the committed neutral 422. */
    public <R> IdempotentResult<R> initiate(UUID venueAccountId, UUID bookingId, IdempotentRequest key,
                                            StoredResponse<R> response, Function<SentLateConfirmation, R> view) {
        return runner.run(idempotency.wrapAnswer(Objects.requireNonNull(key, "key"), response,
                mapped(initiateCommand(venueAccountId, bookingId), view)));
    }

    /** The customer accepts once per Idempotency-Key; the result may carry a committed 409. */
    public <R> IdempotentResult<R> accept(UUID customerId, UUID bookingId, IdempotentRequest key,
                                          StoredResponse<R> response, Function<LateConfirmAnswer, R> view) {
        return runner.run(idempotency.wrapAnswer(Objects.requireNonNull(key, "key"), response,
                mapped(acceptCommand(customerId, bookingId), view)));
    }

    /** The customer declines once per Idempotency-Key. */
    public <R> IdempotentResult<R> decline(UUID customerId, UUID bookingId, IdempotentRequest key,
                                           StoredResponse<R> response, Function<LateConfirmAnswer, R> view) {
        Objects.requireNonNull(view, "view");
        Command<LateConfirmAnswer> decline = declineCommand(customerId, bookingId);
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Body<>(decline.scope(), context -> view.apply(decline.execute(context)))));
    }

    /**
     * The same commands without an Idempotency-Key record, for tests: every route requires the key (ADR-015), so no
     * production caller uses these.
     */
    public CommandAnswer<SentLateConfirmation> initiate(UUID venueAccountId, UUID bookingId) {
        return runner.run(initiateCommand(venueAccountId, bookingId));
    }

    public CommandAnswer<LateConfirmAnswer> accept(UUID customerId, UUID bookingId) {
        return runner.run(acceptCommand(customerId, bookingId));
    }

    public LateConfirmAnswer decline(UUID customerId, UUID bookingId) {
        return runner.run(declineCommand(customerId, bookingId));
    }

    /**
     * The lock set of T2 and T3 (the deadline worker names it before the fire command): the booking's Customer, Venue
     * and Resource, as the three commands lock them. Empty when the booking does not exist - the fire then supersedes.
     */
    public LockSet lockSetOf(UUID bookingId) {
        Objects.requireNonNull(bookingId, "bookingId");
        return runner.run(new Body<Optional<Booking>>(CommandScope.shared(LockSet.empty()),
                        context -> bookings.find(context, bookingId)))
                .map(LateConfirmations::locksOf)
                .orElse(LockSet.empty());
    }

    /** T2's fire, inside the worker's command (see {@link LateConfirmCommands#expire}). */
    public JobOutcome expire(CommandContext context, ScheduledJob job) {
        return commands.expire(context, job);
    }

    /** T3's fire, inside the worker's command (see {@link LateConfirmCommands#warn}). */
    public JobOutcome warn(CommandContext context, ScheduledJob job) {
        return commands.warn(context, job);
    }

    private Command<CommandAnswer<SentLateConfirmation>> initiateCommand(UUID venueAccountId, UUID bookingId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        CommandScope scope = scopeOf(bookingId, booking -> booking.venueId().equals(venueId));
        return new Body<>(scope, context -> commands.initiate(context, bookingId, venueId, venueAccountId));
    }

    private Command<CommandAnswer<LateConfirmAnswer>> acceptCommand(UUID customerId, UUID bookingId) {
        CommandScope scope = scopeOf(bookingId, customersOwn(customerId));
        return new Body<>(scope, context -> commands.accept(context, bookingId, customerId));
    }

    private Command<LateConfirmAnswer> declineCommand(UUID customerId, UUID bookingId) {
        CommandScope scope = scopeOf(bookingId, customersOwn(customerId));
        return new Body<>(scope, context -> commands.decline(context, bookingId, customerId));
    }

    private static Predicate<Booking> customersOwn(UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        return booking -> booking.source() == BookingSource.ONLINE && customerId.equals(booking.customerId());
    }

    /**
     * The lock set of a booking the caller may act on - its Customer (with their Bookings, commitment guard, Bans and
     * Late Confirmations), Venue and Resource - read before the command, because {@code begin_command} takes them up
     * front (arch 05 section 4.2). A booking never changes Customer, Venue or Resource, so the answer still holds when
     * the command re-reads the booking under the locks. Any other booking is NOT_FOUND (no cross-tenant access).
     */
    private CommandScope scopeOf(UUID bookingId, Predicate<Booking> callerMay) {
        Objects.requireNonNull(bookingId, "bookingId");
        Booking booking = runner.run(new Body<Optional<Booking>>(CommandScope.shared(LockSet.empty()),
                        context -> bookings.find(context, bookingId)))
                .filter(callerMay)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return CommandScope.shared(locksOf(booking));
    }

    private static LockSet locksOf(Booking booking) {
        LockSet.Builder locks = LockSet.builder().venues(booking.venueId()).resources(booking.resourceId());
        if (booking.customerId() != null) {
            locks.users(booking.customerId());
        }
        return locks.build();
    }

    private static <T, R> Command<CommandAnswer<R>> mapped(Command<CommandAnswer<T>> command, Function<T, R> view) {
        Objects.requireNonNull(view, "view");
        return new Body<>(command.scope(), context -> switch (command.execute(context)) {
            case CommandAnswer.Ok<T> ok -> CommandAnswer.ok(view.apply(ok.value()));
            case CommandAnswer.Refused<T> refused -> CommandAnswer.refused(refused.refusal());
        });
    }

    /** A Late Confirm command: category B (FR-LCF-01, ADR-022) under the booking's lock set. */
    private record Body<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
