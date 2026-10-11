package ps.e7gz.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.venue.api.SessionSnapshots.Session;

/**
 * The {@code booking.booking} and {@code booking.booking_history} SQL of Manual Booking creation (E7-48), on the
 * command's own transaction. The database checks every row it is given ({@code tr_22_booking_creation_venue}: never
 * ONBOARDING, never back-dated, ACTIVE exactly when startAt = creation; {@code tr_20_booking_snapshot_integrity}: the
 * session snapshot or the out-of-hours shape and its business date; {@code ck_booking_manual_fee_zero}; the price
 * arithmetic); nothing here re-checks them. Cancellation reuses {@link VenueAnswerStore}'s transition SQL.
 */
@Component
class ManualBookingStore {

    /**
     * {@code customer_id} stays NULL (FR-MAN-01: no registered customer), so {@code commitment_period} stays NULL too
     * (FR-MAN-07). {@code fee_treatment}, {@code terminal_*}, the response deadline and the pause columns stay NULL.
     */
    static final String INSERT = """
            INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type,
                                         play_mode, resource_name_snapshot, start_at, end_at, nominal_booked_minutes,
                                         hourly_price_snapshot, venue_price_total, booking_fee_snapshot, total_amount,
                                         business_date, operating_day, operating_session_id, session_local_start,
                                         session_local_end, session_timezone, manual_customer_name,
                                         manual_customer_phone, created_at)
            VALUES (:id, :venueId, :resourceId, NULL, 'MANUAL', :status, :activity, :consoleType,
                    :playMode, :resourceName, :startAt, :endAt, :nominalMinutes,
                    :hourlyPrice, :venuePriceTotal, :bookingFee, :totalAmount,
                    :businessDate, :operatingDay, :sessionId, :sessionLocalStart,
                    :sessionLocalEnd, :sessionTimezone, :customerName,
                    :customerPhone, :createdAt)
            """;

    /** The first history row: Manual Booking created CONFIRMED or ACTIVE (FR-MAN-04), from nothing, by the venue. */
    static final String INSERT_CREATED_HISTORY = """
            INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind, from_status,
                                                 to_status, actor_type, actor_id, correlation_id)
            VALUES (:id, :bookingId, 1, :occurredAt, 'STATUS_TRANSITION', NULL, :status, :actorType, :actorId,
                    :correlationId)
            """;

    void insert(CommandContext command, NewManualBooking booking) {
        Session session = booking.session();
        command.jdbc().sql(INSERT)
                .param("id", booking.id())
                .param("venueId", booking.venueId())
                .param("resourceId", booking.resourceId())
                .param("status", booking.status().name())
                .param("activity", booking.activity().name())
                .param("consoleType", booking.consoleType() == null ? null : booking.consoleType().name())
                .param("playMode", booking.playMode() == null ? null : booking.playMode().name())
                .param("resourceName", booking.resourceName())
                .param("startAt", utc(booking.startAt()))
                .param("endAt", utc(booking.endAt()))
                .param("nominalMinutes", booking.amounts().nominalBookedMinutes())
                .param("hourlyPrice", booking.amounts().hourlyPrice().amount())
                .param("venuePriceTotal", booking.amounts().venuePriceTotal().amount())
                .param("bookingFee", booking.amounts().bookingFee().amount())
                .param("totalAmount", booking.amounts().totalAmount().amount())
                .param("businessDate", booking.businessDate())
                .param("operatingDay", session == null ? null : session.operatingDay())
                .param("sessionId", session == null ? null : session.id())
                .param("sessionLocalStart", session == null ? null : session.localStart())
                .param("sessionLocalEnd", session == null ? null : session.localEnd())
                // FR-MAN-09: out of hours the session fields are NULL and the timezone is Africa/Cairo
                .param("sessionTimezone", session == null ? BusinessDates.FALLBACK_ZONE.getId() : session.timezone())
                .param("customerName", booking.customerName())
                .param("customerPhone", booking.customerPhone())
                .param("createdAt", utc(booking.createdAt()))
                .update();
    }

    void insertCreatedHistory(CommandContext command, UUID bookingId, BookingStatus status, Instant occurredAt,
                              UUID venueAccountId, UUID correlationId) {
        command.jdbc().sql(INSERT_CREATED_HISTORY)
                .param("id", UUID.randomUUID())
                .param("bookingId", bookingId)
                .param("occurredAt", utc(occurredAt))
                .param("status", status.name())
                .param("actorType", ActorType.VENUE.name())
                .param("actorId", Objects.requireNonNull(venueAccountId, "venueAccountId"))
                .param("correlationId", Objects.requireNonNull(correlationId, "correlationId"))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    /**
     * A new Manual Booking with its immutable snapshots.
     *
     * @param session the Operating Session the whole interval fits in; {@code null} outside published hours
     */
    record NewManualBooking(UUID id, UUID venueId, UUID resourceId, BookingStatus status, Activity activity,
                            ConsoleType consoleType, PlayMode playMode, String resourceName, Instant startAt,
                            Instant endAt, BookingAmounts amounts, LocalDate businessDate, Session session,
                            String customerName, String customerPhone, Instant createdAt) {

        NewManualBooking {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(resourceName, "resourceName");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            Objects.requireNonNull(amounts, "amounts");
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
