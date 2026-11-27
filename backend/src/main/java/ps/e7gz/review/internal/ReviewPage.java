package ps.e7gz.review.internal;

import java.util.List;

/** One cursor page (arch 09 section 1.2); {@code nextCursor} is null on the last page. */
public record ReviewPage<T>(List<T> items, String nextCursor) {
}
