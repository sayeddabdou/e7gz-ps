package ps.e7gz.scheduling.internal;

import java.time.Instant;
import ps.e7gz.scheduling.api.Recurrence;

/** Test fixtures for {@link Recurrence.Nightly} runs. */
final class NightlyRuns {

    private NightlyRuns() {
    }

    /**
     * The latest real run of {@code nightly} at or before {@code now}: its local time in {@link Recurrence#ZONE} on the
     * business date before the next run's. Its key is therefore never the key of {@code nightly.next(now)} or of any
     * later run, whether {@code now} is before or after the local time and across Egypt's summer-time changes.
     */
    static Instant lastRunAtOrBefore(Recurrence.Nightly nightly, Instant now) {
        return nightly.next(now).atZone(Recurrence.ZONE).toLocalDate().minusDays(1)
                .atTime(nightly.at()).atZone(Recurrence.ZONE).toInstant();
    }
}
