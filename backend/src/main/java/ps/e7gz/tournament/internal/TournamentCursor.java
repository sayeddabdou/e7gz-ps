package ps.e7gz.tournament.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The opaque cursor of the tournament lists (E7-126; arch 09 section 1.2: {@code ?cursor=&limit=}, {@code nextCursor}
 * in the response): the {@code (instant, tournamentId)} of the last item of the page, where the instant is the one
 * the list orders by ({@code startAt}, or {@code createdAt} for the Admin list). The next page is the rows strictly
 * after it in the list's own order (keyset paging), so a Tournament created meanwhile never shifts or repeats a page.
 */
final class TournamentCursor {

    static final int DEFAULT_PAGE_SIZE = 20;

    /** Every instant a Tournament row can hold lies well inside these bounds (years 1 to 9999). */
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private TournamentCursor() {
    }

    /** The position of one row in a list's {@code (instant, id)} order. */
    record Position(Instant at, UUID tournamentId) {

        Position {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(tournamentId, "tournamentId");
        }
    }

    static String encode(Position position) {
        String plain = position.at() + "|" + position.tournamentId();
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
            Instant at = Instant.parse(plain.substring(0, bar));
            if (at.isBefore(EARLIEST) || at.isAfter(LATEST)) {
                // Instant reaches far beyond PostgreSQL's timestamptz: such a cursor was never one of ours
                throw new IllegalArgumentException("instant out of range");
            }
            return new Position(at, UUID.fromString(plain.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }

    /** {@code limit} as asked (1-100, checked at the edge) or the default page size. */
    static int size(Integer limit) {
        return limit == null ? DEFAULT_PAGE_SIZE : limit;
    }
}
