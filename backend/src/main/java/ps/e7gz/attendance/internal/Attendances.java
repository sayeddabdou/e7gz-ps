package ps.e7gz.attendance.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceBookings;
import ps.e7gz.booking.api.AttendanceBookings.Booking;
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
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * O13 behind its three routes (arch 09 section 4.1; E7-59): Check-in, Mark Present - Late and no-show. Each runs in
 * one {@code CommandRunner} transaction wrapped by E7-42's {@link Idempotency} (ADR-015): the evidence, history, audit,
 * outbox event - for a no-show the transition, release and Trust outcome too - and the Idempotency-Key record commit
 * together or not at all. Category B (an existing commitment's operation, ADR-022), under the booking's Customer, Venue
 * and Resource locks: concurrent actions on one booking are serialised, so evidence, release and events are written
 * once.
 *
 * <p>The venue is the signed-in account's own ({@link VenueAccountVenues}). A booking that does not exist, or belongs to
 * another venue, is {@code NOT_FOUND} before any idempotency record is written.
 */
@Component
public class Attendances {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final AttendanceBookings bookings;
    private final AttendanceCommands commands;

    Attendances(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts,
                AttendanceBookings bookings, AttendanceCommands commands) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.bookings = bookings;
        this.commands = commands;
    }

    /** Check-in once per Idempotency-Key (FR-ATT-01, FR-ATT-02). */
    public <R> IdempotentResult<R> checkIn(UUID venueAccountId, UUID bookingId, IdempotentRequest key,
                                           StoredResponse<R> response, Function<RecordedAttendance, R> view) {
        return run(AttendanceAction.CHECK_IN, venueAccountId, bookingId, key, response, view);
    }

    /** Mark Present - Late once per Idempotency-Key (FR-ATT-03). */
    public <R> IdempotentResult<R> markLatePresent(UUID venueAccountId, UUID bookingId, IdempotentRequest key,
                                                   StoredResponse<R> response, Function<RecordedAttendance, R> view) {
        return run(AttendanceAction.LATE_PRESENT, venueAccountId, bookingId, key, response, view);
    }

    /** No-show once per Idempotency-Key (FR-ATT-04, FR-ATT-05). */
    public <R> IdempotentResult<R> markNoShow(UUID venueAccountId, UUID bookingId, IdempotentRequest key,
                                              StoredResponse<R> response, Function<RecordedAttendance, R> view) {
        return run(AttendanceAction.NO_SHOW, venueAccountId, bookingId, key, response, view);
    }

    /**
     * The same command without an Idempotency-Key record: package-private, for this package's tests. A committed
     * refusal (a no-show whose delayed start cancelled the booking) is thrown after its commit.
     */
    RecordedAttendance record(AttendanceAction action, UUID venueAccountId, UUID bookingId) {
        return switch (runner.run(command(action, venueAccountId, bookingId))) {
            case CommandAnswer.Ok<RecordedAttendance> ok -> ok.value();
            case CommandAnswer.Refused<RecordedAttendance> refused -> throw refused.refusal();
        };
    }

    /**
     * Through {@link Idempotency#wrapAnswer}: a refusal that must still commit - the start-time cancellation behind a
     * delayed-start no-show - is stored with its writes, so the first answer and every replay are the same 409.
     */
    private <R> IdempotentResult<R> run(AttendanceAction action, UUID venueAccountId, UUID bookingId,
                                        IdempotentRequest key, StoredResponse<R> response,
                                        Function<RecordedAttendance, R> view) {
        Objects.requireNonNull(view, "view");
        Command<CommandAnswer<RecordedAttendance>> command = command(action, venueAccountId, bookingId);
        return runner.run(idempotency.wrapAnswer(Objects.requireNonNull(key, "key"), response,
                new Body<>(command.scope(), context -> switch (command.execute(context)) {
                    case CommandAnswer.Ok<RecordedAttendance> ok -> CommandAnswer.ok(view.apply(ok.value()));
                    case CommandAnswer.Refused<RecordedAttendance> refused ->
                            CommandAnswer.<R>refused(refused.refusal());
                })));
    }

    private Command<CommandAnswer<RecordedAttendance>> command(AttendanceAction action, UUID venueAccountId, UUID bookingId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        Objects.requireNonNull(bookingId, "bookingId");
        Booking booking = runner.run(new Body<Optional<Booking>>(CommandScope.shared(LockSet.empty()),
                        context -> bookings.find(context, bookingId)))
                .filter(found -> found.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return new Body<>(CommandScope.shared(locksOf(booking)),
                context -> commands.record(context, action, bookingId, venueId, venueAccountId));
    }

    /**
     * The booking's Customer (with their Bookings, commitment guard and Bans - the Trust write of a no-show needs every
     * Booking of the Customer locked, {@code trust.command_now}), Venue and Resource, read before the command because
     * {@code begin_command} takes them up front (arch 05 section 4.2). A booking never changes Customer, Venue or
     * Resource, so the answer still holds when the command re-reads the booking under the locks.
     */
    private static LockSet locksOf(Booking booking) {
        LockSet.Builder locks = LockSet.builder().venues(booking.venueId()).resources(booking.resourceId());
        if (booking.customerId() != null) {
            locks.users(booking.customerId());
        }
        return locks.build();
    }

    /** An attendance command: category B (ADR-022) under the booking's lock set. */
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
