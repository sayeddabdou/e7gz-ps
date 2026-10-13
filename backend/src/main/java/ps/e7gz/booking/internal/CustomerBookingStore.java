package ps.e7gz.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * The customer's reads of their own bookings (E7-49, arch 09 section 3.3): My Bookings by display bucket (FR-BOK-19)
 * and one booking. Only the Customer's own Online Bookings: a Manual Booking has no Customer ({@code customer_id}
 * NULL, FR-MAN-01), so it never appears here. Every row carries what the phone retention rule needs, read as
 * {@link ContactVisibility#FACTS} reads it.
 */
@Component
class CustomerBookingStore {

    static final String VIEW = """
            SELECT b.id, b.venue_id, b.source, b.status, b.terminal_reason, b.resource_name_snapshot, b.activity,
                   b.console_type, b.play_mode, b.start_at, b.end_at, b.nominal_booked_minutes, b.venue_price_total,
                   b.booking_fee_snapshot, b.total_amount, b.venue_response_expires_at, b.response_pause_started_at,
                   b.late_confirm_pause_started_at, b.terminal_at,
            """ + ContactVisibility.HISTORY_FACTS + """
              FROM booking.booking b
             WHERE b.customer_id = :customerId
            """;

    static final String ONE = VIEW + " AND b.id = :bookingId";

    Optional<Row> one(CommandContext command, UUID customerId, UUID bookingId) {
        return command.jdbc().sql(ONE)
                .param("customerId", Objects.requireNonNull(customerId, "customerId"))
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .query(CustomerBookingStore::row)
                .optional();
    }

    /**
     * One page of the bucket, after {@code after} (null for the first page) in the bucket's order: Upcoming soonest
     * first, Completed and Cancelled most recent first, then by id. Fetches up to {@code fetch} rows.
     */
    List<Row> page(CommandContext command, UUID customerId, DisplayBucket bucket, BookingCursor.Position after,
                   int fetch) {
        String direction = bucket.soonestFirst() ? "ASC" : "DESC";
        String keyset = after == null ? ""
                : bucket.soonestFirst() ? " AND (b.start_at, b.id) > (:afterStart, :afterId)"
                : " AND (b.start_at, b.id) < (:afterStart, :afterId)";
        var sql = command.jdbc().sql(VIEW + " AND b.status IN (:statuses)" + keyset
                        + " ORDER BY b.start_at " + direction + ", b.id " + direction + " LIMIT :fetch")
                .param("customerId", Objects.requireNonNull(customerId, "customerId"))
                .param("statuses", bucket.statuses().stream().map(Enum::name).toList())
                .param("fetch", fetch);
        if (after != null) {
            sql = sql.param("afterStart", OffsetDateTime.ofInstant(after.startAt(), ZoneOffset.UTC))
                    .param("afterId", after.bookingId());
        }
        return sql.query(CustomerBookingStore::row).list();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        BookingStatus status = BookingStatus.valueOf(rs.getString("status"));
        String reason = rs.getString("terminal_reason");
        String console = rs.getString("console_type");
        String playMode = rs.getString("play_mode");
        Instant startAt = instant(rs, "start_at");
        Instant endAt = instant(rs, "end_at");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class), status,
                reason == null ? null : BookingTerminalReason.valueOf(reason), rs.getString("resource_name_snapshot"),
                Activity.valueOf(rs.getString("activity")),
                console == null ? null : ConsoleType.valueOf(console),
                playMode == null ? null : PlayMode.valueOf(playMode),
                startAt, endAt, rs.getInt("nominal_booked_minutes"), Money.of(rs.getBigDecimal("venue_price_total")),
                Money.of(rs.getBigDecimal("booking_fee_snapshot")), Money.of(rs.getBigDecimal("total_amount")),
                instant(rs, "venue_response_expires_at"), instant(rs, "response_pause_started_at") != null,
                instant(rs, "late_confirm_pause_started_at") != null,
                new ContactVisibility.Facts(BookingSource.valueOf(rs.getString("source")), status, startAt, endAt,
                        instant(rs, "terminal_at"), instant(rs, "venue_response_expired_at"),
                        instant(rs, "late_confirm_reopened_at")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return ContactVisibility.instant(rs.getObject(column, OffsetDateTime.class));
    }

    /** A booking row before the venue's contact and the phone rule are applied ({@link CustomerBookings}). */
    record Row(UUID bookingId, UUID venueId, BookingStatus status, BookingTerminalReason terminalReason,
               String resourceName, Activity activity, ConsoleType consoleType, PlayMode playMode, Instant startAt,
               Instant endAt, int nominalBookedMinutes, Money venuePriceTotal, Money bookingFee, Money totalAmount,
               Instant venueResponseExpiresAt, boolean venueResponsePaused, boolean lateConfirmPaused,
               ContactVisibility.Facts contactFacts) {
    }
}
