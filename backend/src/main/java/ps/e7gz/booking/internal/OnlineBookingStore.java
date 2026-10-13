package ps.e7gz.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;

/**
 * The {@code booking.booking} and {@code booking.booking_history} SQL of online booking creation, on the command's own
 * transaction. The database checks every row it is given (snapshots, intake window, lead time, fee, Venue ACTIVE, Ban,
 * {@code ex_customer_no_overlap}, arithmetic); nothing here re-checks them.
 */
@Component
class OnlineBookingStore {

    /**
     * The Customer's counted Online commitments (FR-BOK-03): {@code commitment_period} is set exactly for an ONLINE
     * Booking that is PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED or ACTIVE
     * ({@code ck_booking_commitment_period_shape}); VENUE_RESPONSE_EXPIRED, terminal and Manual Bookings do not count.
     */
    static final String COUNT_COMMITMENTS = """
            SELECT count(*)
              FROM booking.booking b
             WHERE b.customer_id = :customerId
               AND b.commitment_period IS NOT NULL
            """;

    /**
     * FR-BOK-04 on the same column {@code ex_customer_no_overlap} uses; half-open, so back-to-back does not overlap.
     * Every counted commitment, AWAITING_CUSTOMER_CONFIRMATION included: the constraint exempts AWAITING (E7-58 D1).
     */
    static final String OVERLAPS_COMMITMENT = """
            SELECT EXISTS (
                SELECT 1
                  FROM booking.booking b
                 WHERE b.customer_id = :customerId
                   AND b.commitment_period && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'))
            """;

    /**
     * {@code commitment_period} is omitted: {@code tr_00_booking_commitment_period} derives it. {@code fee_treatment},
     * {@code terminal_*} and the pause columns stay NULL while PENDING.
     */
    static final String INSERT_PENDING = """
            INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type,
                                         play_mode, resource_name_snapshot, start_at, end_at, nominal_booked_minutes,
                                         hourly_price_snapshot, venue_price_total, booking_fee_snapshot, total_amount,
                                         business_date, operating_day, operating_session_id, session_local_start,
                                         session_local_end, session_timezone, venue_response_expires_at, created_at)
            VALUES (:id, :venueId, :resourceId, :customerId, 'ONLINE', 'PENDING', :activity, :consoleType,
                    :playMode, :resourceName, :startAt, :endAt, :nominalMinutes,
                    :hourlyPrice, :venuePriceTotal, :bookingFee, :totalAmount,
                    :operatingDay, :operatingDay, :sessionId, :sessionLocalStart,
                    :sessionLocalEnd, :sessionTimezone, :venueResponseExpiresAt, :createdAt)
            """;

    /** The first history row: Booking Created → PENDING (Product v5.0 §23.5), as a status transition from nothing. */
    static final String INSERT_CREATED_HISTORY = """
            INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind, from_status,
                                                 to_status, actor_type, actor_id, correlation_id)
            VALUES (:id, :bookingId, 1, :occurredAt, 'STATUS_TRANSITION', NULL, 'PENDING', 'CUSTOMER', :customerId,
                    :correlationId)
            """;

    long countCommitments(CommandContext command, UUID customerId) {
        return command.jdbc().sql(COUNT_COMMITMENTS)
                .param("customerId", Objects.requireNonNull(customerId, "customerId"))
                .query(Long.class).single();
    }

    boolean overlapsCommitment(CommandContext command, UUID customerId, Instant start, Instant end) {
        return command.jdbc().sql(OVERLAPS_COMMITMENT)
                .param("customerId", Objects.requireNonNull(customerId, "customerId"))
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Boolean.class).single();
    }

    void insertPending(CommandContext command, PendingBooking booking) {
        BookingAmounts amounts = booking.amounts();
        command.jdbc().sql(INSERT_PENDING)
                .param("id", booking.id())
                .param("venueId", booking.venueId())
                .param("resourceId", booking.resourceId())
                .param("customerId", booking.customerId())
                .param("activity", booking.activity().name())
                .param("consoleType", booking.consoleType() == null ? null : booking.consoleType().name())
                .param("playMode", booking.playMode() == null ? null : booking.playMode().name())
                .param("resourceName", booking.resourceName())
                .param("startAt", utc(booking.startAt()))
                .param("endAt", utc(booking.endAt()))
                .param("nominalMinutes", amounts.nominalBookedMinutes())
                .param("hourlyPrice", amounts.hourlyPrice().amount())
                .param("venuePriceTotal", amounts.venuePriceTotal().amount())
                .param("bookingFee", amounts.bookingFee().amount())
                .param("totalAmount", amounts.totalAmount().amount())
                .param("operatingDay", booking.operatingDay())
                .param("sessionId", booking.sessionId())
                .param("sessionLocalStart", booking.sessionLocalStart())
                .param("sessionLocalEnd", booking.sessionLocalEnd())
                .param("sessionTimezone", booking.sessionTimezone())
                .param("venueResponseExpiresAt", utc(booking.venueResponseExpiresAt()))
                .param("createdAt", utc(booking.createdAt()))
                .update();
    }

    void insertCreatedHistory(CommandContext command, UUID bookingId, UUID customerId, Instant occurredAt,
                              UUID correlationId) {
        command.jdbc().sql(INSERT_CREATED_HISTORY)
                .param("id", UUID.randomUUID())
                .param("bookingId", bookingId)
                .param("occurredAt", utc(occurredAt))
                .param("customerId", customerId)
                .param("correlationId", Objects.requireNonNull(correlationId, "correlationId"))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    /**
     * A new PENDING Online Booking with its immutable snapshots: Resource name, activity, console and play mode, the
     * amounts, and the Operating Session the interval fits in (whose operating day is the business date, FR-AVL-14).
     */
    record PendingBooking(UUID id, UUID venueId, UUID resourceId, UUID customerId, Activity activity,
                          ConsoleType consoleType, PlayMode playMode, String resourceName, Instant startAt,
                          Instant endAt, BookingAmounts amounts, LocalDate operatingDay, UUID sessionId,
                          LocalDateTime sessionLocalStart, LocalDateTime sessionLocalEnd, String sessionTimezone,
                          Instant venueResponseExpiresAt, Instant createdAt) {

        PendingBooking {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(resourceName, "resourceName");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            Objects.requireNonNull(amounts, "amounts");
            Objects.requireNonNull(operatingDay, "operatingDay");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(venueResponseExpiresAt, "venueResponseExpiresAt");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
