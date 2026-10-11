package ps.e7gz.booking.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The opaque cursor of the booking lists (arch 09 section 1.2: {@code ?cursor=&limit=}, {@code nextCursor} in the
 * response): the {@code (startAt, bookingId)} of the last item of the page, which every list orders by. The next page
 * is the rows strictly after it in the list's own order (keyset paging), so a booking created meanwhile never shifts
 * or repeats a page.
 */
final class BookingCursor {

    static final int DEFAULT_PAGE_SIZE = 20;

    private BookingCursor() {
    }

    /** The position of one row in a list's {@code (start_at, id)} order. */
    record Position(Instant startAt, UUID bookingId) {

        Position {
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(bookingId, "bookingId");
        }
    }

    static String encode(Position position) {
        String plain = position.startAt() + "|" + position.bookingId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = plain.indexOf('|');
            if (bar < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return new Position(Instant.parse(plain.substring(0, bar)), UUID.fromString(plain.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }

    /** {@code limit} as asked (1-100, checked at the edge) or the default page size. */
    static int size(Integer limit) {
        return limit == null ? DEFAULT_PAGE_SIZE : limit;
    }
}
