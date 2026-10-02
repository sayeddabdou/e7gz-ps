package ps.e7gz.venue.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One {@code venue.venue_image} row (v1.1.31 life cycle: started → uploaded (visible) → processed; removed hides it).
 * The original is stored at {@code objectKey}; the copies sit next to it ({@link #copyKey}).
 */
record VenuePhotoRow(UUID id, UUID venueId, String objectKey, String contentType, Integer sortOrder,
                     boolean uploaded, boolean processed, boolean removed) {

    static final String SELECT = """
            SELECT i.id, i.venue_id, i.object_key, i.content_type, i.sort_order,
                   i.uploaded_at IS NOT NULL AS uploaded, i.processed_at IS NOT NULL AS processed,
                   i.removed_at IS NOT NULL AS removed
              FROM venue.venue_image i""";

    /** Visible: confirmed and not removed, in the venue's order. */
    static final String VISIBLE = SELECT + """

             WHERE i.venue_id = :venue AND i.uploaded_at IS NOT NULL AND i.removed_at IS NULL
             ORDER BY i.sort_order, i.id""";

    static VenuePhotoRow map(ResultSet rs, int row) throws SQLException {
        return new VenuePhotoRow(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                rs.getString("object_key"), rs.getString("content_type"), (Integer) rs.getObject("sort_order"),
                rs.getBoolean("uploaded"), rs.getBoolean("processed"), rs.getBoolean("removed"));
    }

    boolean visible() {
        return uploaded && !removed;
    }

    /** {@code venues/{venueId}/{imageId}.{ext}} (E7-23 prompt). */
    static String originalKey(UUID venueId, UUID imageId, String contentType) {
        return "venues/%s/%s.%s".formatted(venueId, imageId, extension(contentType));
    }

    /** {@code venues/{venueId}/{imageId}-small.jpg}; a PNG keeps PNG, the rest become JPEG (PO decision 3 Oct 2026). */
    String copyKey(CopySize size) {
        return "venues/%s/%s-%s.%s".formatted(venueId, id, size.suffix(), copyContentType().equals("image/png") ? "png" : "jpg");
    }

    String copyContentType() {
        return "image/png".equals(contentType) ? "image/png" : "image/jpeg";
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Not an accepted photo type: " + contentType);
        };
    }

    enum CopySize {
        /** Venue card (FR-DIS-03). */
        SMALL("small"),
        /** Venue Details (FR-DIS-11). */
        MEDIUM("medium");

        private final String suffix;

        CopySize(String suffix) {
            this.suffix = suffix;
        }

        String suffix() {
            return suffix;
        }
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
