package ps.e7gz.inventory.availability.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Free-gap boundaries for Nearest Availability (arch 05 section 5.2): candidate starts are the start of the search and
 * the ends of busy intervals, never a minute-by-minute walk. Pure, no I/O. A candidate found here is only a candidate:
 * {@code AvailabilityQuery.isFree} decides.
 */
final class FreeWindows {

    private FreeWindows() {
    }

    /** A half-open interval {@code [start, end)}. */
    record Span(Instant start, Instant end) {

        Span {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end must be after start: [" + start + ", " + end + ")");
            }
        }
    }

    /**
     * The union of {@code spans}, in start order: overlapping and back-to-back spans become one, since a zero-length
     * gap holds no window.
     */
    static List<Span> union(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparing(Span::start).thenComparing(Span::end));
        List<Span> merged = new ArrayList<>();
        for (Span span : sorted) {
            int last = merged.size() - 1;
            if (last >= 0 && !span.start().isAfter(merged.get(last).end())) {
                Span previous = merged.get(last);
                if (span.end().isAfter(previous.end())) {
                    merged.set(last, new Span(previous.start(), span.end()));
                }
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    /**
     * The earliest {@code t >= from} with {@code [t, t + length)} inside {@code [from, until)} and clear of every span
     * of {@code busy}, which must be a {@link #union}. Each busy span is looked at once: one that overlaps the window
     * moves {@code t} to its end, the next free-gap boundary.
     */
    static Optional<Instant> firstFit(List<Span> busy, Instant from, Instant until, Duration length) {
        Instant t = from;
        for (Span span : busy) {
            if (!span.end().isAfter(t)) {
                continue;                                   // ended before the window
            }
            if (!span.start().isBefore(t.plus(length))) {
                break;                                      // starts after the window: [t, t + length) is free
            }
            t = span.end();                                 // overlaps: the gap after it is the next candidate
        }
        return t.plus(length).isAfter(until) ? Optional.empty() : Optional.of(t);
    }
}
