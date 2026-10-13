package ps.e7gz.booking.lateconfirm.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;

/**
 * The {@code booking.late_confirmation} SQL (arch 03 section 12: this module's table), on the command's own
 * transaction. The database checks every row: the initial deadline is exactly MIN(initiated_at + 5 min,
 * booking.start_at), one open attempt per booking ({@code ux_lc_open}), the response shape
 * ({@code ck_late_confirmation_response_shape}: a customer response only while {@code responded_at < expires_at}),
 * the immutable lifecycle facts, and - deferred to commit - that the attempt, its booking and its
 * {@code LATE_CONFIRM_HOLD} agree ({@code tr_late_confirmation_integrity}, {@code tr_lc_integrity_from_booking},
 * {@code tr_lc_integrity_from_reservation}). Nothing here re-checks them.
 */
@Component
class LateConfirmStore {

    /** A new attempt, numbered after any earlier one of the booking ({@code uq_late_confirm_attempt}). */
    static final String INSERT = """
            INSERT INTO booking.late_confirmation (id, booking_id, attempt_no, initiated_at, expires_at,
                                                   hold_reservation_id)
            SELECT :id, :bookingId, COALESCE(max(l.attempt_no), 0) + 1, :initiatedAt, :expiresAt, :holdId
              FROM booking.late_confirmation l
             WHERE l.booking_id = :bookingId
            """;

    /** The booking's open attempt; {@code begin_command} has locked it with the Customer's bookings. */
    static final String OPEN = """
            SELECT l.id, l.booking_id, l.initiated_at, l.expires_at, l.warning_sent_at, l.hold_reservation_id
              FROM booking.late_confirmation l
             WHERE l.booking_id = :bookingId AND l.closed_at IS NULL
            """;

    /** The customer's response closes the attempt in the same write (guard_late_confirmation_mutation). */
    static final String RESPOND = """
            UPDATE booking.late_confirmation
               SET customer_response = :response, responded_at = :at, closed_at = :at
             WHERE id = :id AND closed_at IS NULL
            """;

    /**
     * T3 (FR-LCF-12): the warning is recorded once - only on the open attempt and only while {@code warning_sent_at}
     * is still NULL, so a retried or second fire changes nothing ({@code guard_late_confirmation_mutation} would refuse
     * a second value anyway). {@code ck_late_confirmation_warning_time} needs {@code at < expires_at}.
     */
    static final String MARK_WARNED = """
            UPDATE booking.late_confirmation
               SET warning_sent_at = :at
             WHERE id = :id AND closed_at IS NULL AND warning_sent_at IS NULL
            """;

    /** The open attempts' deadlines of several bookings in one query (the booking lists, E7-58 Phase 2C). */
    static final String OPEN_DEADLINES = """
            SELECT l.booking_id, l.expires_at
              FROM booking.late_confirmation l
             WHERE l.booking_id IN (:bookingIds) AND l.closed_at IS NULL
            """;

    UUID insert(CommandContext command, UUID bookingId, Instant initiatedAt, Instant expiresAt, UUID holdId) {
        UUID id = UUID.randomUUID();
        command.jdbc().sql(INSERT)
                .param("id", id)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("initiatedAt", utc(initiatedAt))
                .param("expiresAt", utc(expiresAt))
                .param("holdId", Objects.requireNonNull(holdId, "holdId"))
                .update();
        return id;
    }

    Optional<Open> open(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(OPEN)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .query((rs, n) -> new Open(rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class),
                        rs.getObject("initiated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("warning_sent_at", OffsetDateTime.class)),
                        rs.getObject("hold_reservation_id", UUID.class)))
                .optional();
    }

    /** @return false when the attempt is no longer open */
    boolean respond(CommandContext command, UUID lateConfirmationId, Response response, Instant at) {
        return command.jdbc().sql(RESPOND)
                .param("id", Objects.requireNonNull(lateConfirmationId, "lateConfirmationId"))
                .param("response", response.name())
                .param("at", utc(at))
                .update() == 1;
    }

    /** @return false when the attempt is closed or was already warned: nothing to send */
    boolean markWarned(CommandContext command, UUID lateConfirmationId, Instant at) {
        return command.jdbc().sql(MARK_WARNED)
                .param("id", Objects.requireNonNull(lateConfirmationId, "lateConfirmationId"))
                .param("at", utc(at))
                .update() == 1;
    }

    Map<UUID, Instant> openDeadlines(CommandContext command, Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Instant> deadlines = new HashMap<>();
        command.jdbc().sql(OPEN_DEADLINES)
                .param("bookingIds", List.copyOf(bookingIds))
                .query(rs -> {
                    deadlines.put(rs.getObject("booking_id", UUID.class),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant());
                });
        return Map.copyOf(deadlines);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    /** {@code ck_late_confirmation_customer_response}. */
    enum Response {
        ACCEPTED,
        DECLINED,
        TIMED_OUT
    }

    /** The open attempt of a booking. */
    record Open(UUID id, UUID bookingId, Instant initiatedAt, Instant expiresAt, Instant warningSentAt,
                UUID holdReservationId) {
    }
}
