package ps.e7gz.scheduling.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * When a {@link RecurringJob} runs, and how its runs are stored:
 * <ul>
 *   <li>{@link Nightly} - one row per night, keyed {@code TYPE:{business date}} (PO decision 4 Oct 2026); workers that
 *       start together or run late always agree on the next row, so inserting it twice changes nothing;</li>
 *   <li>{@link Every} - a frequent job keeps ONE row, keyed {@code TYPE:EVERY}, that moves to its next run after each run
 *       (PO decision 9 Oct 2026): no row per run piles up, and nothing is ever deleted.</li>
 * </ul>
 */
public sealed interface Recurrence {

    /** The calendar of system jobs: a nightly job's key is its business date here (PO decision 4 Oct 2026). */
    ZoneId ZONE = ZoneId.of("Africa/Cairo");

    /** Once a night at a local time of {@link #ZONE}. */
    static Recurrence nightlyAt(LocalTime at) {
        return new Nightly(at);
    }

    /** At every multiple of {@code interval} since the epoch. */
    static Recurrence every(Duration interval) {
        return new Every(interval);
    }

    /** The first due instant strictly after {@code after}. */
    Instant next(Instant after);

    /** The occurrence part of the idempotency key of the run due at {@code dueAt}. */
    String occurrence(Instant dueAt);

    /** True when every run reuses the job's one row ({@link Every}); false for one row per run ({@link Nightly}). */
    boolean movesOneRow();

    record Nightly(LocalTime at) implements Recurrence {

        public Nightly {
            Objects.requireNonNull(at, "at");
        }

        @Override
        public Instant next(Instant after) {
            ZonedDateTime candidate = after.atZone(ZONE).toLocalDate().atTime(at).atZone(ZONE);
            while (!candidate.toInstant().isAfter(after)) {
                candidate = candidate.toLocalDate().plusDays(1).atTime(at).atZone(ZONE);
            }
            return candidate.toInstant();
        }

        /** The business date (Africa/Cairo) the run is due on. */
        @Override
        public String occurrence(Instant dueAt) {
            return dueAt.atZone(ZONE).toLocalDate().toString();
        }

        @Override
        public boolean movesOneRow() {
            return false;
        }
    }

    record Every(Duration interval) implements Recurrence {

        public Every {
            Objects.requireNonNull(interval, "interval");
            if (interval.toMillis() < 1) {
                throw new IllegalArgumentException("A recurring job's interval must be at least one millisecond");
            }
        }

        @Override
        public Instant next(Instant after) {
            long step = interval.toMillis();
            return Instant.ofEpochMilli((Math.floorDiv(after.toEpochMilli(), step) + 1) * step);
        }

        /** Always {@code EVERY}: the job's one row serves every run. */
        @Override
        public String occurrence(Instant dueAt) {
            return "EVERY";
        }

        @Override
        public boolean movesOneRow() {
            return true;
        }
    }
}
