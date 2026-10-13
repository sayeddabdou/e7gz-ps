package ps.e7gz.booking.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmBookings;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * The {@code booking.booking} and {@code booking.booking_history} SQL of a Late Confirmation (E7-58), on the command's
 * own transaction. Writes go through the same guards as every other booking command; nothing here re-checks them.
 */
@Component
class JdbcLateConfirmBookings implements LateConfirmBookings {

    static final String FIND = """
            SELECT b.id, b.venue_id, b.resource_id, b.customer_id, b.source, b.status, b.start_at, b.end_at,
                   b.late_confirm_pause_started_at
              FROM booking.booking b
             WHERE b.id = :id
            """;

    /**
     * FR-LCF-15: the customer schedule conflict, on the column {@code ex_customer_no_overlap} uses, half-open: the
     * customer's PENDING, CONFIRMED and ACTIVE commitments. Other AWAITING bookings are left out (E7-58 Q1, 13 Oct
     * 2026; FR-LCF-10: simultaneous Late Confirmations never interfere): the first overlapping Accept wins, and the
     * next one then conflicts with that CONFIRMED booking - the same pairs the constraint, which exempts AWAITING (D1),
     * would refuse. AWAITING still counts towards the 4-commitment limit and blocks a new Online Booking (FR-BOK-04).
     */
    static final String OVERLAPS_ANOTHER = """
            SELECT EXISTS (
                SELECT 1
                  FROM booking.booking b
                 WHERE b.customer_id = :customerId
                   AND b.id <> :bookingId
                   AND b.status <> 'AWAITING_CUSTOMER_CONFIRMATION'
                   AND b.commitment_period && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'))
            """;

    /** VENUE_RESPONSE_EXPIRED -> AWAITING_CUSTOMER_CONFIRMATION: non-terminal; the database derives the commitment. */
    static final String AWAIT_CUSTOMER = """
            UPDATE booking.booking
               SET status = 'AWAITING_CUSTOMER_CONFIRMATION', version = version + 1
             WHERE id = :id AND status = 'VENUE_RESPONSE_EXPIRED'
            """;

    /** AWAITING_CUSTOMER_CONFIRMATION -> CONFIRMED: same Booking ID, price and fee (FR-LCF-08). */
    static final String CONFIRM = """
            UPDATE booking.booking
               SET status = 'CONFIRMED', version = version + 1
             WHERE id = :id AND status = 'AWAITING_CUSTOMER_CONFIRMATION'
            """;

    /**
     * AWAITING_CUSTOMER_CONFIRMATION -> CANCELLED with one reason (NFR-26): CUSTOMER_DECLINED_LATE_CONFIRM (Decline) or
     * CUSTOMER_RECONFIRM_TIMEOUT (T2), neither a penalty (FR-LCF-13).
     */
    static final String CANCEL_AWAITING = """
            UPDATE booking.booking
               SET status = 'CANCELLED', terminal_reason = :reason, terminal_at = :at,
                   fee_treatment = booking.expected_fee_treatment(:reason), version = version + 1
             WHERE id = :id AND status = 'AWAITING_CUSTOMER_CONFIRMATION'
            """;

    /** FR-OPS-37: {@code ck_booking_pause_counters} keeps the counter within 30 minutes. */
    static final String END_LATE_CONFIRM_PAUSE = """
            UPDATE booking.booking
               SET cumulative_late_confirm_emergency_pause_duration =
                       COALESCE(cumulative_late_confirm_emergency_pause_duration, interval '0')
                       + (CAST(:at AS timestamptz) - late_confirm_pause_started_at),
                   late_confirm_pause_started_at = NULL, version = version + 1
             WHERE id = :id AND status = 'AWAITING_CUSTOMER_CONFIRMATION' AND late_confirm_pause_started_at IS NOT NULL
            RETURNING EXTRACT(EPOCH FROM cumulative_late_confirm_emergency_pause_duration)
            """;

    /** The next Booking Event row; {@code uq_booking_history_seq} refuses a duplicate sequence. */
    static final String INSERT_EVENT = """
            INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind, event_type,
                                                 actor_type, actor_id, correlation_id)
            SELECT :id, :bookingId, COALESCE(max(h.sequence), 0) + 1, :occurredAt, 'BOOKING_EVENT', :eventType,
                   :actorType, :actorId, :correlationId
              FROM booking.booking_history h
             WHERE h.booking_id = :bookingId
            """;

    private final VenueAnswerStore answers;
    private final OnlineBookingStore online;
    private final ScheduledJobs jobs;

    JdbcLateConfirmBookings(VenueAnswerStore answers, OnlineBookingStore online, ScheduledJobs jobs) {
        this.answers = answers;
        this.online = online;
        this.jobs = jobs;
    }

    @Override
    public Optional<Booking> find(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(FIND)
                .param("id", Objects.requireNonNull(bookingId, "bookingId"))
                .query((rs, n) -> new Booking(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                        rs.getObject("resource_id", UUID.class), rs.getObject("customer_id", UUID.class),
                        BookingSource.valueOf(rs.getString("source")), BookingStatus.valueOf(rs.getString("status")),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("late_confirm_pause_started_at", OffsetDateTime.class) != null))
                .optional();
    }

    @Override
    public long countCommitments(CommandContext command, UUID customerId) {
        return online.countCommitments(command, customerId);
    }

    @Override
    public boolean overlapsAnotherCommitment(CommandContext command, UUID customerId, UUID bookingId, Instant start,
                                             Instant end) {
        return Boolean.TRUE.equals(command.jdbc().sql(OVERLAPS_ANOTHER)
                .param("customerId", Objects.requireNonNull(customerId, "customerId"))
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Boolean.class).single());
    }

    @Override
    public boolean awaitCustomer(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(AWAIT_CUSTOMER).param("id", bookingId).update() == 1;
    }

    @Override
    public boolean confirm(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(CONFIRM).param("id", bookingId).update() == 1;
    }

    @Override
    public boolean decline(CommandContext command, UUID bookingId, Instant at) {
        return cancelAwaiting(command, bookingId, BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM, at);
    }

    @Override
    public boolean timeOut(CommandContext command, UUID bookingId, Instant at) {
        return cancelAwaiting(command, bookingId, BookingTerminalReason.CUSTOMER_RECONFIRM_TIMEOUT, at);
    }

    private static boolean cancelAwaiting(CommandContext command, UUID bookingId, BookingTerminalReason reason,
                                          Instant at) {
        return command.jdbc().sql(CANCEL_AWAITING)
                .param("id", bookingId).param("reason", reason.name()).param("at", utc(at))
                .update() == 1;
    }

    @Override
    public Optional<Duration> endLateConfirmPause(CommandContext command, UUID bookingId, Instant at) {
        return command.jdbc().sql(END_LATE_CONFIRM_PAUSE)
                .param("id", bookingId)
                .param("at", utc(at))
                .query(BigDecimal.class)
                .optional()
                .map(seconds -> Duration.ofNanos(seconds.movePointRight(9).longValue()));
    }

    @Override
    public UUID recordTransition(CommandContext command, Transition transition) {
        Objects.requireNonNull(transition, "transition");
        return answers.insertTransition(command, transition.bookingId(), transition.at(), transition.from(),
                transition.to(), transition.terminalReason(), transition.actorType(), transition.actorId(),
                transition.correlationId());
    }

    @Override
    public void recordEvent(CommandContext command, UUID bookingId, Instant at, BookingEventType event,
                            ActorType actorType, UUID actorId) {
        command.jdbc().sql(INSERT_EVENT)
                .param("id", UUID.randomUUID())
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("occurredAt", utc(at))
                .param("eventType", Objects.requireNonNull(event, "event").name())
                .param("actorType", Objects.requireNonNull(actorType, "actorType").name())
                .param("actorId", actorId)
                .param("correlationId", CorrelationId.require())
                .update();
    }

    @Override
    public void scheduleStart(CommandContext command, UUID bookingId, Instant startAt) {
        jobs.schedule(command, NewJob.of(VenueAnswers.BOOKING_START, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                bookingId, startAt));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }
}
