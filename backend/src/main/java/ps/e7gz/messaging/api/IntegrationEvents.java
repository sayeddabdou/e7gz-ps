package ps.e7gz.messaging.api;

import java.util.Set;

/**
 * The names of integration events ({@code technical.outbox_event.event_type}, arch 08 section 3.2) that a producer and
 * a consumer in different modules must agree on. Architecture-internal, never shown to anyone (arch 08 section 3.1).
 * A producer task adds its names here when it starts writing the event.
 */
public final class IntegrationEvents {

    /** {@code {areaId}}, aggregate {@code AREA}: an area was deactivated (arch 03 section 4; PO decision 3 Oct 2026). */
    public static final String AREA_DEACTIVATED = "AreaDeactivated";

    /** {@code {venueId, previousStatus, newStatus}}, aggregate {@code VENUE} (arch 08 section 3.2). */
    public static final String VENUE_MARKETPLACE_STATUS_CHANGED = "VenueMarketplaceStatusChanged";

    /**
     * {@code {challengeId, sealedCode}}, aggregate {@code OTP_CHALLENGE}: a phone OTP to send by SMS (FR-NTF-01, E7-41).
     * The code is encrypted (AES-GCM) in the payload and never in plain text (arch 10 section 2.2; PO decision 9 Oct
     * 2026).
     */
    public static final String PHONE_OTP_ISSUED = "PhoneOtpIssued";

    /**
     * {@code {notificationId}}, aggregate {@code NOTIFICATION}: a notification has PUSH or EMAIL deliveries to send
     * (E7-43). Written by {@code Notifications.notify} in the command that creates the notification, and again by the
     * admin "try again" of a dead-lettered delivery; the notification worker sends every delivery still due.
     */
    public static final String NOTIFICATION_DELIVERY_REQUESTED = "NotificationDeliveryRequested";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt}}, aggregate {@code BOOKING}: an Online
     * Booking was created PENDING (arch 08 section 3.2, arch 01 section 11.1; E7-46, PO decision 9 Oct 2026).
     */
    public static final String BOOKING_CREATED = "BookingCreated";

    /**
     * {@code {venueAccountId, venueId}}, aggregate {@code VENUE_ACCOUNT}: the Platform Admin disabled the venue's
     * account (arch 08 sections 3.2 and 5, FR-VEN-08): the venue live channel closes its open streams at once (E7-44,
     * PO decision 10 Oct 2026).
     */
    public static final String VENUE_ACCOUNT_DISABLED = "VenueAccountDisabled";

    /**
     * {@code {reservationId, resourceId, venueId, period: {start, end}}}, aggregate {@code RESERVATION}: a ledger
     * row was inserted active (arch 08 section 3.2; E7-46, PO decision 9 Oct 2026).
     */
    public static final String RESERVATION_HELD = "ReservationHeld";

    /**
     * {@code {reservationId, resourceId, venueId, period: {start, end}}}, aggregate {@code RESERVATION}: an active ledger
     * row was released (arch 08 section 3.2; E7-47, the payload of {@link #RESERVATION_HELD}).
     */
    public static final String RESERVATION_RELEASED = "ReservationReleased";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt}}, aggregate {@code BOOKING}: a booking is now
     * CONFIRMED - the venue accepted a PENDING Online Booking (arch 06 section 2.1, arch 08 section 3.2; E7-47, PO
     * decision D4), or a future Manual Booking was created CONFIRMED, with {@code customerId} null and
     * {@code source: MANUAL} (PO decision 10 Oct 2026, E7-48, D6).
     */
    public static final String BOOKING_CONFIRMED = "BookingConfirmed";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, source}}, aggregate {@code BOOKING}: a booking
     * is now ACTIVE (arch 08 section 3.2). Written since E7-48 for a walk-in Manual Booking created ACTIVE at the command
     * instant, with {@code customerId} null and {@code source: MANUAL} (PO decision 10 Oct 2026, E7-48, D6); the
     * CONFIRMED -> ACTIVE start is E7-60's.
     */
    public static final String BOOKING_STARTED = "BookingStarted";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, terminalReason}}, aggregate {@code BOOKING}:
     * the venue rejected the booking - {@code VENUE_REJECTED} from PENDING or {@code REJECTED_AFTER_EXPIRY} from
     * VENUE_RESPONSE_EXPIRED (FR-BOK-17, FR-BOK-21; E7-47, PO decision D4). The venue's reason text is not in it.
     */
    public static final String BOOKING_REJECTED = "BookingRejected";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, venueResponseExpiresAt}}, aggregate
     * {@code BOOKING}: T1 moved a PENDING booking to the non-terminal VENUE_RESPONSE_EXPIRED (FR-BOK-18; E7-47, PO
     * decision D4).
     */
    public static final String VENUE_RESPONSE_EXPIRED = "VenueResponseExpired";

    /**
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt, terminalReason}}, aggregate {@code BOOKING}: a
     * booking was CANCELLED under {@code terminalReason} - from E7-47 the operational cause that stopped a venue's
     * Accept (FR-BOK-16, PO decision D4); from E7-48 the venue's cancellation of a CONFIRMED or ACTIVE Manual Booking,
     * VENUE_CANCELLED_MANUAL_BOOKING, with {@code customerId} null and {@code source: MANUAL} (PCR-007; PO decision 10
     * Oct 2026, E7-48, D6).
     */
    public static final String BOOKING_CANCELLED = "BookingCancelled";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, lateConfirmExpiresAt}},
     * aggregate {@code BOOKING}: the venue sent a Late Confirmation - VENUE_RESPONSE_EXPIRED ->
     * AWAITING_CUSTOMER_CONFIRMATION with its hold, until {@code lateConfirmExpiresAt} = MIN(initiation + 5 min,
     * startAt) (arch 06 section 2.2, arch 08 section 3.2; FR-LCF-06, FR-LCF-09; E7-58, PO decision D2 12 Oct 2026).
     * {@code venueName} is the venue's display name at the event (E7-58 Q4, 13 Oct 2026), for the notification copy.
     */
    public static final String LATE_CONFIRM_INITIATED = "LateConfirmInitiated";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt}}, aggregate {@code BOOKING}:
     * the customer accepted - AWAITING_CUSTOMER_CONFIRMATION -> CONFIRMED, same Booking ID, price and fee (FR-LCF-08,
     * FR-LCF-13; E7-58, D2, Q4). O3 writes no {@code BookingConfirmed}.
     */
    public static final String LATE_CONFIRM_ACCEPTED = "LateConfirmAccepted";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, terminalReason}}, aggregate
     * {@code BOOKING}: the customer declined - CANCELLED with {@code terminalReason} CUSTOMER_DECLINED_LATE_CONFIRM
     * (FR-LCF-13; E7-58, D2, Q4). O3 writes no {@code BookingCancelled}.
     */
    public static final String LATE_CONFIRM_DECLINED = "LateConfirmDeclined";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, lateConfirmExpiresAt,
     * terminalReason}}, aggregate {@code BOOKING}: T2 cancelled the booking as of its stored deadline, CANCELLED with
     * {@code terminalReason} CUSTOMER_RECONFIRM_TIMEOUT (FR-LCF-13; E7-58, D2, Q4). O3 writes no
     * {@code BookingCancelled}.
     */
    public static final String LATE_CONFIRM_TIMED_OUT = "LateConfirmTimedOut";

    /**
     * {@code {bookingId, customerId, venueId, venueName, resourceId, startAt, endAt, lateConfirmExpiresAt}}, aggregate
     * {@code BOOKING}: T3, the Late Confirmation expires in about a minute - written once per attempt and only for a
     * window longer than 60 seconds (FR-LCF-12; E7-58, D2, Q4). For the customer only (no venue live signal).
     */
    public static final String LATE_CONFIRM_WARNING_DUE = "LateConfirmWarningDue";

    /**
     * Every event that can change a venue's availability and so refreshes its Nearest Availability rows (arch 05
     * section 5.3, arch 08 section 7): reservation hold or release, Resource state, selling blocks (maintenance,
     * closures, emergencies), opening hours and prices. Each carries the {@code venueId} it touches;
     * {@link #AREA_DEACTIVATED} carries an {@code areaId} instead. Only the E7-23 events, {@link #RESERVATION_HELD}
     * (E7-46) and {@link #RESERVATION_RELEASED} (E7-47) are written so far (PO decision 9 Oct 2026); the other producers add theirs in their own tasks.
     */
    public static final Set<String> INVENTORY_AFFECTING = Set.of(
            RESERVATION_HELD, RESERVATION_RELEASED,
            "ResourceDisabled", "ResourceReEnabled", "ResourceArchived", "ResourceRestored",
            "ResourceConsoleChanged", "ResourceRestrictionChanged",
            "MaintenanceCreated", "MaintenanceEdited", "MaintenanceDeleted",
            "ClosureCreated",
            "EmergencyDeclared", "EmergencyExtended", "EmergencyEnded", "EmergencyConverted",
            "OpeningHoursChanged",
            "VenuePricingChanged", "RoomOverrideChanged",
            VENUE_MARKETPLACE_STATUS_CHANGED,
            AREA_DEACTIVATED);

    private IntegrationEvents() {
    }
}
