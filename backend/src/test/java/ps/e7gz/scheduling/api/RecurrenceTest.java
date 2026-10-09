package ps.e7gz.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** E7-40: when recurring system jobs run, and the key of each run (PO decision 4 Oct 2026). */
class RecurrenceTest {

    private final Recurrence nightly = Recurrence.nightlyAt(LocalTime.of(4, 0));

    @Test
    void aNightlyJobRunsAtItsLocalTimeInCairoStrictlyAfterNow() {
        // 9 Oct 2026 is summer time in Egypt (UTC+3): 04:00 local is 01:00 UTC.
        assertThat(nightly.next(Instant.parse("2026-10-08T22:00:00Z"))).isEqualTo(Instant.parse("2026-10-09T01:00:00Z"));
        assertThat(nightly.next(Instant.parse("2026-10-09T01:00:00Z"))).as("not the run that is due right now")
                .isEqualTo(Instant.parse("2026-10-10T01:00:00Z"));
        assertThat(nightly.next(Instant.parse("2026-10-09T05:00:00Z"))).isEqualTo(Instant.parse("2026-10-10T01:00:00Z"));
    }

    @Test
    void aNightlyJobKeepsItsLocalTimeAcrossTheEndOfSummerTime() {
        // Egypt leaves summer time at the end of Thursday 29 Oct 2026; 04:00 on 30 Oct is UTC+2, 02:00 UTC.
        assertThat(nightly.next(Instant.parse("2026-10-29T02:00:00Z"))).isEqualTo(Instant.parse("2026-10-30T02:00:00Z"));
    }

    @Test
    void aNightlyRunIsKeyedByItsBusinessDateInCairo() {
        // 23:30 UTC on 8 Oct is already 9 Oct in Cairo.
        assertThat(nightly.occurrence(Instant.parse("2026-10-08T23:30:00Z"))).isEqualTo("2026-10-09");
    }

    @Test
    void anIntervalJobRunsAtTheNextMultipleOfItsInterval() {
        Recurrence everyMinute = Recurrence.every(Duration.ofMinutes(1));

        assertThat(everyMinute.next(Instant.parse("2026-10-09T10:15:42.123Z")))
                .isEqualTo(Instant.parse("2026-10-09T10:16:00Z"));
        assertThat(everyMinute.next(Instant.parse("2026-10-09T10:16:00Z"))).isEqualTo(Instant.parse("2026-10-09T10:17:00Z"));
        assertThat(everyMinute.occurrence(Instant.parse("2026-10-09T10:16:00Z"))).isEqualTo("2026-10-09T10:16:00Z");
        assertThatThrownBy(() -> Recurrence.every(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRecurringJobIsASystemJobWithAFixedSubjectAndOneKeyPerRun() {
        RecurringJob job = new RecurringJob("INVARIANT_SWEEP", nightly, () -> { });

        assertThat(job.subjectId()).isEqualTo(UUID.nameUUIDFromBytes("INVARIANT_SWEEP".getBytes(StandardCharsets.UTF_8)));
        assertThat(RecurringJob.SUBJECT_TYPE).isEqualTo("SYSTEM");
        assertThat(job.idempotencyKey(Instant.parse("2026-10-09T01:00:00Z"))).isEqualTo("INVARIANT_SWEEP:2026-10-09");
        assertThat(job.run(null)).isEqualTo(JobOutcome.done());
    }

    @Test
    void aJobScheduledByACommandHasTheNaturalKeyOfItsTypeAndSubject() {
        UUID booking = UUID.randomUUID();

        assertThat(NewJob.of("VENUE_RESPONSE_EXPIRY", "BOOKING", booking, Instant.EPOCH).idempotencyKey())
                .isEqualTo("VENUE_RESPONSE_EXPIRY:" + booking);
    }
}
