package ps.e7gz.loyalty.internal;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The opaque cursors of the points and wallet pages (arch 09 section 1.2: {@code ?cursor=&limit=}, {@code nextCursor}
 * in the response; keyset paging, so a row added meanwhile never shifts or repeats a page). A ledger history is newest
 * first by {@code ledger_sequence}: its cursor is the id of the last transaction of the page. An account list is
 * highest balance first, then by user id: its cursor is that {@code (balance, userId)} position.
 */
final class LedgerCursor {

    static final int DEFAULT_PAGE_SIZE = 20;

    private LedgerCursor() {
    }

    /** One account's place in a list ordered by {@code balance DESC, user_id DESC}. */
    record Position(BigDecimal balance, UUID userId) {

        Position {
            Objects.requireNonNull(balance, "balance");
            Objects.requireNonNull(userId, "userId");
        }
    }

    /** {@code limit} as asked (1-100, checked at the edge) or the default page size. */
    static int size(Integer limit) {
        return limit == null ? DEFAULT_PAGE_SIZE : limit;
    }

    static String encode(UUID transactionId) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(transactionId.getMostSignificantBits())
                .putLong(transactionId.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static UUID transaction(String cursor) {
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

    static String encode(Position position) {
        String plain = position.balance().toPlainString() + "|" + position.userId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** As {@link #transaction}, for an account list. */
    static Position position(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = plain.indexOf('|');
            if (bar < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return new Position(new BigDecimal(plain.substring(0, bar)), UUID.fromString(plain.substring(bar + 1)));
        } catch (IllegalArgumentException malformed) {
            // NumberFormatException is an IllegalArgumentException
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
