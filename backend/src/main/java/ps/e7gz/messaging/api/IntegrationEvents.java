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
     * {@code {bookingId, customerId, venueId, resourceId, startAt, endAt}}, aggregate {@code BOOKING}: an Online
     * Booking was created PENDING (arch 08 section 3.2, arch 01 section 11.1; E7-46, PO decision 9 Oct 2026).
     */
    public static final String BOOKING_CREATED = "BookingCreated";

    /**
     * {@code {reservationId, resourceId, venueId, period: {start, end}}}, aggregate {@code RESERVATION}: a ledger
     * row was inserted active (arch 08 section 3.2; E7-46, PO decision 9 Oct 2026).
     */
    public static final String RESERVATION_HELD = "ReservationHeld";

    /**
     * Every event that can change a venue's availability and so refreshes its Nearest Availability rows (arch 05
     * section 5.3, arch 08 section 7): reservation hold or release, Resource state, selling blocks (maintenance,
     * closures, emergencies), opening hours and prices. Each carries the {@code venueId} it touches;
     * {@link #AREA_DEACTIVATED} carries an {@code areaId} instead. Only the E7-23 events and {@link #RESERVATION_HELD}
     * (E7-46) are written so far (PO decision 9 Oct 2026); the other producers add theirs in their own tasks.
     */
    public static final Set<String> INVENTORY_AFFECTING = Set.of(
            RESERVATION_HELD, "ReservationReleased",
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
