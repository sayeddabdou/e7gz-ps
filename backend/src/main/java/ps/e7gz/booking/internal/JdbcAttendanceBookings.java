package ps.e7gz.booking.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceBookings;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * The {@code booking.booking} and {@code booking.booking_history} SQL of the venue's attendance actions (E7-59), on
 * the command's own transaction. Writes go through the same guards as every other booking command; nothing here
 * re-checks them.
 */
@Component
class JdbcAttendanceBookings implements AttendanceBookings {

    static final String FIND = """
            SELECT b.id, b.venue_id, b.resource_id, b.customer_id, b.source, b.status, b.start_at, b.end_at
              FROM booking.booking b
             WHERE b.id = :id
            """;

    /**
     * ACTIVE -> NO_SHOW (FR-ATT-04): terminal with no Booking Reason Code and
     * no fee treatment (NO_SHOW is outside the reason map); the database derives the empty commitment. {@code start_at}
     * and {@code end_at} stay as booked.
     */
    static final String NO_SHOW = """
            UPDATE booking.booking
               SET status = 'NO_SHOW', terminal_at = :at, version = version + 1
             WHERE id = :id AND status = :from AND source = 'ONLINE'
            """;

    /**
     * E7-70: NO_SHOW -> COMPLETED after the original endAt (FR-ATT-12): terminal with no Booking Reason Code and no fee
     * treatment, dated at the reversal; the planned interval is not rewritten.
     */
    static final String COMPLETE_REVERSED_NO_SHOW = """
            UPDATE booking.booking
               SET status = 'COMPLETED', terminal_at = :at, version = version + 1
             WHERE id = :id AND status = 'NO_SHOW' AND source = 'ONLINE'
            """;

    private final VenueAnswerStore answers;
    private final ScheduledJobs jobs;
    private final DelayedStartResolver delayedStart;

    JdbcAttendanceBookings(VenueAnswerStore answers, ScheduledJobs jobs, DelayedStartResolver delayedStart) {
        this.answers = answers;
        this.jobs = jobs;
        this.delayedStart = delayedStart;
    }

    @Override
    public Optional<Booking> find(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(FIND)
                .param("id", Objects.requireNonNull(bookingId, "bookingId"))
                .query((rs, n) -> new Booking(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                        rs.getObject("resource_id", UUID.class), rs.getObject("customer_id", UUID.class),
                        BookingSource.valueOf(rs.getString("source")), BookingStatus.valueOf(rs.getString("status")),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public boolean markNoShow(CommandContext command, UUID bookingId, BookingStatus from, Instant at,
                              UUID venueAccountId) {
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        if (from != BookingStatus.ACTIVE) {
            throw new IllegalArgumentException("A no-show leaves ACTIVE - a delayed start is started first - not "
                    + from);
        }
        if (command.jdbc().sql(NO_SHOW).param("id", Objects.requireNonNull(bookingId, "bookingId"))
                .param("from", from.name()).param("at", utc(at)).update() != 1) {
            return false;
        }
        answers.insertTransition(command, bookingId, at, from, BookingStatus.NO_SHOW, null,
                ActorType.VENUE, venueAccountId, CorrelationId.require());
        // T5 has nothing left to complete; a job a worker holds LEASED is left to it - its fire re-reads the booking
        jobs.supersede(command, ManualBookingCommand.bookingCompleteKey(bookingId));
        return true;
    }

    @Override
    public DelayedStart resolveDelayedStart(CommandContext command, UUID bookingId) {
        return delayedStart.resolve(command, Objects.requireNonNull(bookingId, "bookingId"));
    }

    @Override
    public void recordVenueEvent(CommandContext command, UUID bookingId, Instant at, BookingEventType event,
                                 UUID venueAccountId) {
        insertEvent(command, bookingId, at, event, ActorType.VENUE,
                Objects.requireNonNull(venueAccountId, "venueAccountId"));
    }

    @Override
    public Optional<Reversal> reverseNoShow(CommandContext command, UUID bookingId, Instant at, UUID adminId) {
        Objects.requireNonNull(adminId, "adminId");
        Booking booking = find(command, bookingId).orElse(null);
        if (booking == null || booking.status() != BookingStatus.NO_SHOW) {
            return Optional.empty();
        }
        boolean beforeEnd = reversalTarget(at, booking.endAt()) == BookingStatus.CANCELLED;
        boolean moved = beforeEnd
                ? answers.terminate(command, bookingId, BookingStatus.NO_SHOW, BookingStatus.CANCELLED,
                        BookingTerminalReason.ADMIN_NO_SHOW_REVERSAL, at, null)
                : command.jdbc().sql(COMPLETE_REVERSED_NO_SHOW).param("id", bookingId).param("at", utc(at))
                        .update() == 1;
        if (!moved) {
            return Optional.empty();
        }
        BookingStatus to = beforeEnd ? BookingStatus.CANCELLED : BookingStatus.COMPLETED;
        UUID history = answers.insertTransition(command, bookingId, at, BookingStatus.NO_SHOW, to,
                beforeEnd ? BookingTerminalReason.ADMIN_NO_SHOW_REVERSAL : null, ActorType.ADMIN, adminId,
                CorrelationId.require());
        return Optional.of(new Reversal(to, history));
    }

    /**
     * FRS section 2.1 ({@code tr_03_booking_transition_guard}): a no-show reversed before the original endAt is
     * CANCELLED, at or after it COMPLETED - endAt itself belongs to "after" (half-open, as every deadline, FR-AVL-15).
     */
    static BookingStatus reversalTarget(Instant at, Instant endAt) {
        return at.isBefore(endAt) ? BookingStatus.CANCELLED : BookingStatus.COMPLETED;
    }

    @Override
    public Optional<BookingTerminalReason> terminalReason(CommandContext command, UUID bookingId) {
        return command.jdbc().sql("SELECT b.terminal_reason FROM booking.booking b WHERE b.id = :id")
                .param("id", Objects.requireNonNull(bookingId, "bookingId"))
                .query((rs, n) -> Optional.ofNullable(rs.getString("terminal_reason")))
                .optional()
                .flatMap(reason -> reason.map(BookingTerminalReason::valueOf));
    }

    @Override
    public UUID recordAdminEvent(CommandContext command, UUID bookingId, Instant at, BookingEventType event,
                                 UUID adminId) {
        return insertEvent(command, bookingId, at, event, ActorType.ADMIN, Objects.requireNonNull(adminId, "adminId"));
    }

    private static UUID insertEvent(CommandContext command, UUID bookingId, Instant at, BookingEventType event,
                                    ActorType actorType, UUID actorId) {
        UUID id = UUID.randomUUID();
        command.jdbc().sql(JdbcLateConfirmBookings.INSERT_EVENT)
                .param("id", id)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("occurredAt", utc(at))
                .param("eventType", Objects.requireNonNull(event, "event").name())
                .param("actorType", actorType.name())
                .param("actorId", actorId)
                .param("correlationId", CorrelationId.require())
                .update();
        return id;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }
}
