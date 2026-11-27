package ps.e7gz.review.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * A keyset cursor over a list ordered by {@code (instant DESC, id DESC)} (arch 09 section 1.2): the last row's instant
 * and id, Base64url. Null or blank is the first page; anything else that is not one is {@code VALIDATION_FAILED} on
 * {@code cursor}, an instant outside years 1-9999 included (it could not be bound as a timestamptz; E7-126 lesson).
 */
public record ReviewCursor(Instant at, UUID id) {

    private static final Instant FIRST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LAST = Instant.parse("9999-12-31T23:59:59.999999Z");

    public String encode() {
        String plain = at + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    public static ReviewCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = plain.indexOf('|');
            if (bar < 0) {
                throw new IllegalArgumentException("no separator");
            }
            Instant at = Instant.parse(plain.substring(0, bar));
            if (at.isBefore(FIRST) || at.isAfter(LAST)) {
                throw new IllegalArgumentException("instant out of range");
            }
            return new ReviewCursor(at, UUID.fromString(plain.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
