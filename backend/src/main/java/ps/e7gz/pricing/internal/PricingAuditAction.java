package ps.e7gz.pricing.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Pricing changes. Each is an Owner-Password-protected action (FRS §2.4 "Venue pricing changes", "room override
 * set/reset"), so its row carries the successful check (FR-VEN-05); a refused one is audited by the Owner Password
 * gate. The previous and new prices are kept, so the history of a venue's prices can be read back (FR-AUD-01: a change
 * affects future bookings only; each booking stores its own price).
 */
enum PricingAuditAction implements AuditAction {

    /** The venue default prices were replaced (FR-VEN-43, FR-VEN-44); the subject is the venue. */
    VENUE_PRICING_CHANGED("VENUE", AuditPayloadAllowlist.of(
            "previous[].activity", "previous[].consoleType", "previous[].playMode", "previous[].hourlyPrice",
            "defaults[].activity", "defaults[].consoleType", "defaults[].playMode", "defaults[].hourlyPrice")),
    /** A PlayStation room's overrides were set for its console (FR-VEN-43); the subject is the room. */
    ROOM_PRICE_OVERRIDE_SET("RESOURCE", AuditPayloadAllowlist.of("venueId", "consoleType",
            "previous[].playMode", "previous[].hourlyPrice", "prices[].playMode", "prices[].hourlyPrice")),
    /** The room went back to the venue default prices. */
    ROOM_PRICE_OVERRIDE_RESET("RESOURCE", AuditPayloadAllowlist.of("venueId", "consoleType",
            "previous[].playMode", "previous[].hourlyPrice"));

    private final String subjectType;
    private final AuditPayloadAllowlist payload;

    PricingAuditAction(String subjectType, AuditPayloadAllowlist payload) {
        this.subjectType = subjectType;
        this.payload = payload;
    }

    @Override
    public String subjectType() {
        return subjectType;
    }

    @Override
    public AuditPayloadAllowlist payloadAllowlist() {
        return payload;
    }
}
