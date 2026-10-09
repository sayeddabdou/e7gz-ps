package ps.e7gz.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;

/**
 * E7-40: the past nightly run that {@code DeadlineWorkerTest} fails on purpose is a real 04:00 Cairo run whose key
 * differs from the next night's, at fixed instants before and after 04:00 and across Egypt's summer-time changes.
 */
class NightlyRunsTest {

    private final Recurrence.Nightly nightly = new Recurrence.Nightly(LocalTime.of(4, 0));
    private final RecurringJob job = new RecurringJob("T_NIGHT", nightly, () -> { });

    @Test
    void beforeFourInCairoTheLastRunWasYesterdayMorning() {
        Instant now = Instant.parse("2026-10-09T00:30:00Z");        // 03:30 Cairo (UTC+3)

        assertLastRun(now, Instant.parse("2026-10-08T01:00:00Z"), "2026-10-08", "2026-10-09");
        assertThat(job.idempotencyKey(now.minusSeconds(1))).as("the old fixture took the next night's key")
                .isEqualTo(job.idempotencyKey(nightly.next(now)));
    }

    @Test
    void afterFourInCairoTheLastRunWasThisMorning() {
        Instant now = Instant.parse("2026-10-09T02:00:00Z");        // 05:00 Cairo (UTC+3)

        assertLastRun(now, Instant.parse("2026-10-09T01:00:00Z"), "2026-10-09", "2026-10-10");
    }

    @Test
    void atFourInCairoTheRunDueNowIsTheLastRun() {
        Instant now = Instant.parse("2026-10-09T01:00:00Z");        // 04:00 Cairo (UTC+3)

        assertLastRun(now, now, "2026-10-09", "2026-10-10");
    }

    @Test
    void theLastRunKeepsFourInCairoAcrossTheEndOfSummerTime() {
        // Egypt leaves summer time at the end of Thursday 29 Oct 2026: 04:00 on 29 Oct is UTC+3, on 30 Oct UTC+2.
        assertLastRun(Instant.parse("2026-10-30T01:30:00Z"), Instant.parse("2026-10-29T01:00:00Z"),
                "2026-10-29", "2026-10-30");                         // 03:30 Cairo on 30 Oct
        assertLastRun(Instant.parse("2026-10-30T03:00:00Z"), Instant.parse("2026-10-30T02:00:00Z"),
                "2026-10-30", "2026-10-31");                         // 05:00 Cairo on 30 Oct
    }

    @Test
    void theLastRunKeepsFourInCairoAcrossTheStartOfSummerTime() {
        // Egypt starts summer time at the start of Friday 24 Apr 2026: 04:00 on 23 Apr is UTC+2, on 24 Apr UTC+3.
        assertLastRun(Instant.parse("2026-04-24T00:30:00Z"), Instant.parse("2026-04-23T02:00:00Z"),
                "2026-04-23", "2026-04-24");                         // 03:30 Cairo on 24 Apr
        assertLastRun(Instant.parse("2026-04-24T02:00:00Z"), Instant.parse("2026-04-24T01:00:00Z"),
                "2026-04-24", "2026-04-25");                         // 05:00 Cairo on 24 Apr
    }

    private void assertLastRun(Instant now, Instant lastRun, String lastDate, String nextDate) {
        Instant run = NightlyRuns.lastRunAtOrBefore(nightly, now);

        assertThat(run).isEqualTo(lastRun).isBeforeOrEqualTo(now);
        assertThat(run.atZone(Recurrence.ZONE).toLocalTime()).isEqualTo(LocalTime.of(4, 0));
        assertThat(job.idempotencyKey(run)).isEqualTo("T_NIGHT:" + lastDate);
        assertThat(job.idempotencyKey(nightly.next(now))).isEqualTo("T_NIGHT:" + nextDate);
        assertThat(job.idempotencyKey(nightly.next(run))).as("the worker's next night, run on time")
                .isEqualTo("T_NIGHT:" + nextDate);
    }
}
