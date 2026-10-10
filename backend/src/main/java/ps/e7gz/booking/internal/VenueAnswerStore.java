package ps.e7gz.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * The {@code booking.booking} and {@code booking.booking_history} SQL of the venue's answer to an Online Booking and of
 * its response expiry (E7-47, arch 06 section 1.2), on the command's own transaction. The database checks every
 * transition ({@code tr_03_booking_transition_guard}), the terminal shape ({@code ck_booking_terminal_reason},
 * {@code ck_booking_terminal_at}, {@code ck_booking_reject_reason_text}), the fee treatment of each reason
 * ({@code tr_booking_fee_treatment_integrity}, fed from the database's own {@code booking.expected_fee_treatment}) and
 * re-derives {@code commitment_period}; nothing here re-checks them. Each transition names the status it leaves, so a
 * row that moved on changes nothing and the caller sees it.
 */
@Component
class VenueAnswerStore {

    /** What a venue answer or T1 decides from, read under the command's locks. */
    static final String FACTS = """
            SELECT b.id, b.venue_id, b.resource_id, b.customer_id, b.source, b.status, b.start_at, b.end_at,
                   b.venue_response_expires_at, b.response_pause_started_at
              FROM booking.booking b
             WHERE b.id = :id
            """;

    /** PENDING -> CONFIRMED (FR-BOK-14): the reservation stays as it is. */
    static final String CONFIRM = """
            UPDATE booking.booking
               SET status = 'CONFIRMED', version = version + 1
             WHERE id = :id AND status = 'PENDING'
            """;

    /** PENDING -> VENUE_RESPONSE_EXPIRED (FR-BOK-18): non-terminal, so no reason, no terminal_at (FR-BOK-12). */
    static final String EXPIRE = """
            UPDATE booking.booking
               SET status = 'VENUE_RESPONSE_EXPIRED', version = version + 1
             WHERE id = :id AND status = 'PENDING'
            """;

    /** A terminal REJECTED or CANCELLED from {@code :from}, with exactly one reason (NFR-26). */
    static final String TERMINATE = """
            UPDATE booking.booking
               SET status = :to, terminal_reason = :reason, terminal_at = :at,
                   fee_treatment = booking.expected_fee_treatment(:reason),
                   venue_reject_reason_text = :rejectText, version = version + 1
             WHERE id = :id AND status = :from
            """;

    /** The next history row; {@code uq_booking_history_seq} refuses a duplicate sequence. */
    static final String INSERT_TRANSITION = """
            INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind, from_status,
                                                 to_status, terminal_reason, actor_type, actor_id, correlation_id)
            SELECT :id, :bookingId, COALESCE(max(h.sequence), 0) + 1, :occurredAt, 'STATUS_TRANSITION', :from, :to,
                   :reason, :actorType, :actorId, :correlationId
              FROM booking.booking_history h
             WHERE h.booking_id = :bookingId
            """;

    /**
     * The venue's view of its bookings, with what the phone retention rule needs: {@code venue_response_expired_at} is
     * the latest transition into VENUE_RESPONSE_EXPIRED, as {@link ContactVisibility#FACTS} reads it.
     */
    static final String VIEW = """
            SELECT b.id, b.venue_id, b.source, b.status, b.terminal_reason, b.venue_reject_reason_text, b.resource_id,
                   b.resource_name_snapshot, b.activity, b.console_type, b.play_mode, b.start_at, b.end_at,
                   b.business_date, b.nominal_booked_minutes, b.venue_price_total, b.venue_response_expires_at,
                   b.response_pause_started_at, b.customer_id, b.manual_customer_name, b.manual_customer_phone,
                   b.created_at, b.terminal_at,
                   (SELECT max(h.occurred_at)
                      FROM booking.booking_history h
                     WHERE h.booking_id = b.id
                       AND h.entry_kind = 'STATUS_TRANSITION'
                       AND h.to_status = 'VENUE_RESPONSE_EXPIRED') AS venue_response_expired_at
              FROM booking.booking b
            """;

    static final String VIEW_ONE = VIEW + " WHERE b.id = :id";

    /** FR-VEN-34: the requests waiting for an answer, soonest deadline first. */
    static final String PENDING_OF_VENUE = VIEW + """
             WHERE b.venue_id = :venueId AND b.status = 'PENDING'
             ORDER BY b.venue_response_expires_at, b.created_at, b.id
            """;

    /** FR-BOK-20, FR-BOK-21: the missed requests the venue can still act on - strictly before their start. */
    static final String MISSED_OF_VENUE = VIEW + """
             WHERE b.venue_id = :venueId AND b.status = 'VENUE_RESPONSE_EXPIRED' AND b.start_at > :now
             ORDER BY b.start_at, b.created_at, b.id
            """;

    Optional<Facts> facts(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(FACTS)
                .param("id", Objects.requireNonNull(bookingId, "bookingId"))
                .query((rs, n) -> new Facts(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                        rs.getObject("resource_id", UUID.class), rs.getObject("customer_id", UUID.class),
                        BookingSource.valueOf(rs.getString("source")), BookingStatus.valueOf(rs.getString("status")),
                        instant(rs, "start_at"), instant(rs, "end_at"), instant(rs, "venue_response_expires_at"),
                        instant(rs, "response_pause_started_at") != null))
                .optional();
    }

    /** @return false when the booking is no longer PENDING */
    boolean confirm(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(CONFIRM).param("id", bookingId).update() == 1;
    }

    /** @return false when the booking is no longer PENDING */
    boolean expire(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(EXPIRE).param("id", bookingId).update() == 1;
    }

    /**
     * @param rejectText the venue's own reason, with {@code VENUE_REJECTED} only
     *                   ({@code ck_booking_reject_reason_text})
     * @return false when the booking is no longer in {@code from}
     */
    boolean terminate(CommandContext command, UUID bookingId, BookingStatus from, BookingStatus to,
                      BookingTerminalReason reason, Instant at, String rejectText) {
        return command.jdbc().sql(TERMINATE)
                .param("id", bookingId)
                .param("from", from.name())
                .param("to", to.name())
                .param("reason", reason.name())
                .param("at", utc(at))
                .param("rejectText", rejectText)
                .update() == 1;
    }

    /**
     * @param actorId the Venue Account for a VENUE actor; {@code null} for SYSTEM
     * @return the history row id (a terminal row is the basis of the Booking Fee decision)
     */
    UUID insertTransition(CommandContext command, UUID bookingId, Instant occurredAt, BookingStatus from,
                          BookingStatus to, BookingTerminalReason reason, ActorType actorType, UUID actorId,
                          UUID correlationId) {
        UUID id = UUID.randomUUID();
        command.jdbc().sql(INSERT_TRANSITION)
                .param("id", id)
                .param("bookingId", bookingId)
                .param("occurredAt", utc(occurredAt))
                .param("from", from.name())
                .param("to", to.name())
                .param("reason", reason == null ? null : reason.name())
                .param("actorType", actorType.name())
                .param("actorId", actorId)
                .param("correlationId", Objects.requireNonNull(correlationId, "correlationId"))
                .update();
        return id;
    }

    Optional<ViewRow> view(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(VIEW_ONE).param("id", bookingId).query(VenueAnswerStore::viewRow).optional();
    }

    List<ViewRow> pending(CommandContext command, UUID venueId) {
        return command.jdbc().sql(PENDING_OF_VENUE).param("venueId", venueId).query(VenueAnswerStore::viewRow).list();
    }

    List<ViewRow> missed(CommandContext command, UUID venueId, Instant now) {
        return command.jdbc().sql(MISSED_OF_VENUE).param("venueId", venueId).param("now", utc(now))
                .query(VenueAnswerStore::viewRow).list();
    }

    private static ViewRow viewRow(ResultSet rs, int n) throws SQLException {
        BookingStatus status = BookingStatus.valueOf(rs.getString("status"));
        String reason = rs.getString("terminal_reason");
        String console = rs.getString("console_type");
        String playMode = rs.getString("play_mode");
        return new ViewRow(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                BookingSource.valueOf(rs.getString("source")), status,
                reason == null ? null : BookingTerminalReason.valueOf(reason), rs.getString("venue_reject_reason_text"),
                rs.getObject("resource_id", UUID.class), rs.getString("resource_name_snapshot"),
                Activity.valueOf(rs.getString("activity")),
                console == null ? null : ConsoleType.valueOf(console),
                playMode == null ? null : PlayMode.valueOf(playMode),
                instant(rs, "start_at"), instant(rs, "end_at"), rs.getObject("business_date", LocalDate.class),
                rs.getInt("nominal_booked_minutes"), Money.of(rs.getBigDecimal("venue_price_total")),
                instant(rs, "venue_response_expires_at"), instant(rs, "response_pause_started_at") != null,
                rs.getObject("customer_id", UUID.class), rs.getString("manual_customer_name"),
                rs.getString("manual_customer_phone"), instant(rs, "created_at"),
                new ContactVisibility.Facts(status, instant(rs, "start_at"), instant(rs, "end_at"),
                        instant(rs, "terminal_at"), instant(rs, "venue_response_expired_at")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    /** The decision facts of one booking; {@code venueResponseExpiresAt} is null for a Manual Booking. */
    record Facts(UUID bookingId, UUID venueId, UUID resourceId, UUID customerId, BookingSource source,
                 BookingStatus status, Instant startAt, Instant endAt, Instant venueResponseExpiresAt,
                 boolean responsePaused) {
    }

    /** A booking row before the customer's contact and the phone rule are applied ({@link VenueBookings}). */
    record ViewRow(UUID bookingId, UUID venueId, BookingSource source, BookingStatus status,
                   BookingTerminalReason terminalReason, String rejectReasonText, UUID resourceId,
                   String resourceName, Activity activity,
                   ConsoleType consoleType, PlayMode playMode,
                   Instant startAt, Instant endAt, LocalDate businessDate, int nominalBookedMinutes,
                   Money venuePriceTotal, Instant venueResponseExpiresAt,
                   boolean venueResponsePaused, UUID customerId, String walkInName, String walkInPhone,
                   Instant createdAt, ContactVisibility.Facts contactFacts) {
    }
}
