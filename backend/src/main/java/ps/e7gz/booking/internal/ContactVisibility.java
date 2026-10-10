package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * Counterpart phone visibility of an Online Booking (arch 10 section 5.1, FR-DIS-09, FR-PTS-20, FR-PTS-21, NFR-18),
 * decided server-side from the booking's <i>current</i> row. E7-46 uses it as the {@code ReplayProjection} of
 * {@code POST /customer/bookings}, so a replayed creation never shows a venue phone the rule hides now; the customer
 * booking reads (E7-49) are meant to call the same rule.
 *
 * <table>
 *   <tr><th>Booking</th><th>Visible</th></tr>
 *   <tr><td>PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE</td><td>yes</td></tr>
 *   <tr><td>VENUE_RESPONSE_EXPIRED</td><td>until {@code venueResponseExpiredAt + 2h}</td></tr>
 *   <tr><td>terminal before {@code startAt}</td><td>until {@code terminalAt + 2h}</td></tr>
 *   <tr><td>terminal at or after {@code startAt}, NO_SHOW included</td><td>until {@code endAt + 2h}</td></tr>
 * </table>
 * A Late Confirm that reopens an expired booking moves it to AWAITING_CUSTOMER_CONFIRMATION, covered by the first
 * row.
 */
@Component
public class ContactVisibility {

    /** FR-PTS-20: every retention point is the event plus 2 hours. */
    static final Duration RETENTION = Duration.ofHours(2);

    /**
     * {@code venueResponseExpiredAt} is the latest transition into VENUE_RESPONSE_EXPIRED: the booking row keeps no
     * column for it ({@code terminal_at} stays NULL, {@code ck_booking_terminal_at}, as FR-PTS-21 keeps it revivable).
     */
    static final String FACTS = """
            SELECT b.status, b.start_at, b.end_at, b.terminal_at,
                   (SELECT max(h.occurred_at)
                      FROM booking.booking_history h
                     WHERE h.booking_id = b.id
                       AND h.entry_kind = 'STATUS_TRANSITION'
                       AND h.to_status = 'VENUE_RESPONSE_EXPIRED') AS venue_response_expired_at
              FROM booking.booking b
             WHERE b.id = :bookingId
            """;

    /** Whether the customer may see the venue phone of {@code bookingId} at the command instant; hidden if unknown. */
    public boolean venuePhoneVisible(CommandContext command, UUID bookingId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(bookingId, "bookingId");
        return command.jdbc().sql(FACTS)
                .param("bookingId", bookingId)
                .query((rs, n) -> new Facts(BookingStatus.valueOf(rs.getString("status")),
                        instant(rs.getObject("start_at", OffsetDateTime.class)),
                        instant(rs.getObject("end_at", OffsetDateTime.class)),
                        instant(rs.getObject("terminal_at", OffsetDateTime.class)),
                        instant(rs.getObject("venue_response_expired_at", OffsetDateTime.class))))
                .optional()
                .map(facts -> visibleAt(facts, command.commandNow()))
                .orElse(false);
    }

    /** The rule itself; {@code now} before the retention point means visible. */
    static boolean visibleAt(Facts facts, Instant now) {
        return facts.visibleUntil().map(now::isBefore).orElse(true);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** What the rule reads from the booking; {@code terminalAt} and {@code venueResponseExpiredAt} may be null. */
    record Facts(BookingStatus status, Instant startAt, Instant endAt, Instant terminalAt,
                 Instant venueResponseExpiredAt) {

        Facts {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
        }

        /** Empty while the booking keeps the phone open (no retention point yet). */
        Optional<Instant> visibleUntil() {
            return switch (status) {
                case PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE -> Optional.empty();
                case VENUE_RESPONSE_EXPIRED -> Optional.of(
                        Objects.requireNonNull(venueResponseExpiredAt, "venueResponseExpiredAt").plus(RETENTION));
                case COMPLETED, REJECTED, CANCELLED, NO_SHOW -> {
                    Instant terminal = Objects.requireNonNull(terminalAt, "terminalAt");
                    yield Optional.of((terminal.isBefore(startAt) ? terminal : endAt).plus(RETENTION));
                }
            };
        }
    }
}
