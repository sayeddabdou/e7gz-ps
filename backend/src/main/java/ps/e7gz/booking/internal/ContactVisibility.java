package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * The counterpart phone visibility resolver of a booking (arch 10 section 5.1, FR-DIS-09, FR-PTS-20, FR-PTS-21,
 * FR-LCF-18, NFR-18), decided server-side from the booking's <i>current</i> row and history at the command instant.
 * The same rule serves both directions - the customer's view of the venue phone and the venue's view of the customer
 * phone - on every booking read and every idempotent replay (E7-46 creation, E7-47 venue answers, E7-48 manual
 * booking, E7-49 customer reads and cancel). Hidden means the phone is left out of the JSON, never null or masked.
 *
 * <table>
 *   <tr><th>Booking</th><th>Visible</th></tr>
 *   <tr><td>MANUAL</td><td>always: the venue typed the walk-in contact itself (FR-DIS-09)</td></tr>
 *   <tr><td>PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE</td><td>yes</td></tr>
 *   <tr><td>terminal before {@code startAt}</td><td>until {@code terminalAt + 2h}</td></tr>
 *   <tr><td>terminal at or after {@code startAt}, NO_SHOW included</td><td>until {@code endAt + 2h}</td></tr>
 *   <tr><td>VENUE_RESPONSE_EXPIRED</td><td>until {@code venueResponseExpiredAt + 2h}</td></tr>
 *   <tr><td>terminal after VENUE_RESPONSE_EXPIRED with no Late Confirm since</td>
 *       <td>until {@code min(venueResponseExpiredAt + 2h, the terminal row's point)}</td></tr>
 * </table>
 *
 * <p>PO decision 11 Oct 2026, E7-49 (D1): once {@code venueResponseExpiredAt + 2h} has passed, the phones stay hidden
 * permanently, even if the booking later becomes REJECTED or CANCELLED, unless a Late Confirm reopens them through
 * AWAITING_CUSTOMER_CONFIRMATION (FR-LCF-18); a booking that ends before that point is visible until
 * {@code min(expiry + 2h, terminalAt + 2h)}. A Late Confirm is the only way back: a booking reopened through AWAITING
 * follows the ordinary rows again (restored on Accept).
 */
@Component
public class ContactVisibility {

    /** FR-PTS-20: every retention point is the event plus 2 hours. */
    static final Duration RETENTION = Duration.ofHours(2);

    /**
     * {@code venue_response_expired_at} is the latest transition into VENUE_RESPONSE_EXPIRED: the booking row keeps no
     * column for it ({@code terminal_at} stays NULL, {@code ck_booking_terminal_at}, as FR-PTS-21 keeps it revivable).
     * {@code late_confirm_reopened_at} is the latest transition into AWAITING_CUSTOMER_CONFIRMATION (FR-LCF-18).
     */
    static final String HISTORY_FACTS = """
                   (SELECT max(h.occurred_at)
                      FROM booking.booking_history h
                     WHERE h.booking_id = b.id
                       AND h.entry_kind = 'STATUS_TRANSITION'
                       AND h.to_status = 'VENUE_RESPONSE_EXPIRED') AS venue_response_expired_at,
                   (SELECT max(h.occurred_at)
                      FROM booking.booking_history h
                     WHERE h.booking_id = b.id
                       AND h.entry_kind = 'STATUS_TRANSITION'
                       AND h.to_status = 'AWAITING_CUSTOMER_CONFIRMATION') AS late_confirm_reopened_at
            """;

    static final String FACTS = "SELECT b.source, b.status, b.start_at, b.end_at, b.terminal_at,\n"
            + HISTORY_FACTS
            + "  FROM booking.booking b\n WHERE b.id = :bookingId\n";

    /** Whether the customer may see the venue phone of {@code bookingId} at the command instant; hidden if unknown. */
    public boolean venuePhoneVisible(CommandContext command, UUID bookingId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(bookingId, "bookingId");
        return command.jdbc().sql(FACTS)
                .param("bookingId", bookingId)
                .query((rs, n) -> new Facts(BookingSource.valueOf(rs.getString("source")),
                        BookingStatus.valueOf(rs.getString("status")),
                        instant(rs.getObject("start_at", OffsetDateTime.class)),
                        instant(rs.getObject("end_at", OffsetDateTime.class)),
                        instant(rs.getObject("terminal_at", OffsetDateTime.class)),
                        instant(rs.getObject("venue_response_expired_at", OffsetDateTime.class)),
                        instant(rs.getObject("late_confirm_reopened_at", OffsetDateTime.class))))
                .optional()
                .map(facts -> visibleAt(facts, command.commandNow()))
                .orElse(false);
    }

    /** The rule itself; {@code now} before the retention point means visible. */
    static boolean visibleAt(Facts facts, Instant now) {
        return facts.visibleUntil().map(now::isBefore).orElse(true);
    }

    static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * What the rule reads from the booking; {@code terminalAt}, {@code venueResponseExpiredAt} and
     * {@code lateConfirmReopenedAt} may be null.
     */
    record Facts(BookingSource source, BookingStatus status, Instant startAt, Instant endAt, Instant terminalAt,
                 Instant venueResponseExpiredAt, Instant lateConfirmReopenedAt) {

        Facts {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
        }

        /** Empty while the booking keeps the phone open (no retention point yet). */
        Optional<Instant> visibleUntil() {
            if (source == BookingSource.MANUAL) {
                return Optional.empty();                                                   // FR-DIS-09: exempt
            }
            Optional<Instant> own = switch (status) {
                case PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE -> Optional.empty();
                case VENUE_RESPONSE_EXPIRED -> Optional.of(
                        Objects.requireNonNull(venueResponseExpiredAt, "venueResponseExpiredAt").plus(RETENTION));
                case COMPLETED, REJECTED, CANCELLED, NO_SHOW -> {
                    Instant terminal = Objects.requireNonNull(terminalAt, "terminalAt");
                    yield Optional.of((terminal.isBefore(startAt) ? terminal : endAt).plus(RETENTION));
                }
            };
            // PO decision 11 Oct 2026, E7-49 (D1): an expiry not reopened by a Late Confirm caps every later point
            Optional<Instant> expiryCap = expiredWithoutReopening()
                    ? Optional.of(venueResponseExpiredAt.plus(RETENTION)) : Optional.empty();
            if (own.isEmpty()) {
                return expiryCap;
            }
            return expiryCap.filter(cap -> cap.isBefore(own.get())).or(() -> own);
        }

        /** The booking went VENUE_RESPONSE_EXPIRED and no Late Confirm has reopened it since (FR-LCF-18). */
        private boolean expiredWithoutReopening() {
            return venueResponseExpiredAt != null
                    && (lateConfirmReopenedAt == null || lateConfirmReopenedAt.isBefore(venueResponseExpiredAt));
        }
    }
}
