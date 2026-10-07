package ps.e7gz.inventory.availability.internal;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The customer venue list's opaque cursor (arch 09 section 1.2): the id of the previous page's last venue, URL-safe
 * Base64 without padding - the same form as the admin venue list's cursor. The database resolves that venue's sort key
 * (distance or name) itself, so the cursor carries no internal state.
 */
final class DiscoveryCursor {

    private DiscoveryCursor() {
    }

    static String encode(UUID venueId) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(venueId.getMostSignificantBits())
                .putLong(venueId.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static UUID decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length != 16) {
                throw new IllegalArgumentException("cursor length");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
