package ps.e7gz.venue.api;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Venue photos for other modules (PCR-005, arch 12 §2): the Venue Dashboard routes (E7-24) and customer discovery
 * (E7-34). Upload is two steps: {@link #startUpload} returns a signed PUT, the client uploads the bytes, and
 * {@link #confirm} makes the photo visible. Removal hides a photo; nothing is hard-deleted. Customers are shown only
 * the resized copies without GPS/EXIF (PO decision 3 Oct 2026).
 */
public interface VenuePhotos {

    /** Visible photos in the venue's order; the first is the venue-card photo. Unknown venue: NOT_FOUND. */
    List<Photo> visible(UUID venueId);

    /**
     * Checks type, size and the visible-photo limit, records the upload and returns a signed PUT (10 minutes).
     * Nothing is visible yet. VENUE_IMAGE_NOT_ACCEPTED, VENUE_IMAGE_LIMIT_REACHED, NOT_FOUND.
     */
    Upload startUpload(UUID venueId, Actor actor, String contentType, long sizeBytes);

    /**
     * The stored file is checked and the photo becomes visible, last in the order. Confirming a visible photo again
     * changes nothing. VENUE_IMAGE_NOT_UPLOADED, VENUE_IMAGE_NOT_ACCEPTED, VENUE_IMAGE_LIMIT_REACHED, NOT_FOUND.
     */
    Photo confirm(UUID venueId, UUID imageId, Actor actor);

    /** {@code imageIds} must name every visible photo exactly once (VALIDATION_FAILED on {@code imageIds}). */
    List<Photo> reorder(UUID venueId, List<UUID> imageIds, Actor actor);

    /** Hides a visible photo; its row and stored file are kept. NOT_FOUND when it is not visible. */
    void remove(UUID venueId, UUID imageId, Actor actor);

    /** Customer venue cards (FR-DIS-03): each venue's first photo that has its copies, as the small copy. */
    Map<UUID, SignedLink> cardImages(Collection<UUID> venueIds);

    /** Customer Venue Details (FR-DIS-11): the medium copies in the venue's order; photos without copies are left out. */
    List<SignedLink> gallery(UUID venueId);

    /**
     * A photo for the Venue Dashboard and the Platform Admin. {@code smallUrl} and {@code mediumUrl} are signed links to
     * the copies, valid until {@code urlsExpireAt}; all three are null until the copies are made.
     */
    record Photo(UUID id, String contentType, URI smallUrl, URI mediumUrl, Instant urlsExpireAt) {
    }

    record Upload(Photo photo, URI uploadUrl, Instant uploadExpiresAt) {
    }

    record SignedLink(URI url, Instant expiresAt) {
    }

    /** Who changes the photos. Each change is audited with this actor. */
    sealed interface Actor {

        /** The Platform Admin (arch 09 §5): on the admin connection, no Owner Password. */
        record Admin(UUID adminId, UUID correlationId) implements Actor {

            public Admin {
                Objects.requireNonNull(adminId, "adminId");
                Objects.requireNonNull(correlationId, "correlationId");
            }
        }

        /**
         * The venue's shared account (arch 09 §4.2), on the app connection. No photo action needs the Owner Password
         * (PCR-006 amends PCR-005; PO decision 3 Oct 2026).
         */
        record Venue(UUID venueAccountId, UUID correlationId) implements Actor {

            public Venue {
                Objects.requireNonNull(venueAccountId, "venueAccountId");
                Objects.requireNonNull(correlationId, "correlationId");
            }
        }
    }
}
