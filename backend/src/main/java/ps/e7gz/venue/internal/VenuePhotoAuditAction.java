package ps.e7gz.venue.internal;

import ps.e7gz.audit.api.AuditAction;
import ps.e7gz.audit.api.AuditPayloadAllowlist;

/**
 * Venue photo changes (PCR-005: the removal is audited; E7-23 "each change has an audit row"). Starting an upload is
 * not audited: nothing is visible until confirm.
 */
enum VenuePhotoAuditAction implements AuditAction {

    /** Confirm: the photo became visible. */
    VENUE_PHOTO_ADDED("VENUE_IMAGE", AuditPayloadAllowlist.of("venueId", "contentType")),
    VENUE_PHOTO_REMOVED("VENUE_IMAGE", AuditPayloadAllowlist.of("venueId")),
    /** Subject is the venue; the payload is the new order. */
    VENUE_PHOTOS_REORDERED("VENUE", AuditPayloadAllowlist.of("imageIds[]"));

    private final String subjectType;
    private final AuditPayloadAllowlist payload;

    VenuePhotoAuditAction(String subjectType, AuditPayloadAllowlist payload) {
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

    @Override
    public boolean requiresReason() {
        return false;
    }
}
