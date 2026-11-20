package ps.e7gz.operations.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;

/**
 * The optimistic feasibility test and the online intake window of a paused venue-response timer (E7-83; FR-OPS-39,
 * FR-OPS-40, FR-BOK-09; arch 08 section 2.5). Pure over the stored state it is given - the instant, the remaining
 * response time, the booking's {@code startAt} and the venue's materialized Operating Sessions - so the same state always
 * gives the same decision (FR-OPS-40, the PRD's Phase 5 exit criterion). It assumes the current selling block could
 * resolve at {@code now}; it never predicts how long a block lasts (Spec v5 section 44.5).
 */
final class TimerFeasibility {

    /** FR-BOK-09: online intake stops 10 minutes before the Operating Session ends. */
    static final Duration INTAKE_CUTOFF = Duration.ofMinutes(10);

    private TimerFeasibility() {
    }

    /**
     * The latest instant, at or after {@code now}, at which the timer could resume and still run its whole
     * {@code remaining} time inside one online intake window before {@code startAt}:
     *
     * <pre>
     * for each session s with s.start &lt; startAt:
     *     window = [max(now, s.start), min(s.end - 10 min, startAt))
     *     fits while it starts no later than min(s.end - 10 min, startAt) - remaining
     * </pre>
     *
     * Empty when no such window exists even if the block resolved now: CANCEL_NOW (FR-OPS-40). Present: FEASIBLE, and the
     * booking may wait, paused, until this instant at most - after it no window fits any more.
     */
    static Optional<Instant> lastFeasibleResume(Instant now, Duration remaining, Instant startAt,
                                                List<SessionPeriod> sessions) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(startAt, "startAt");
        Instant latest = null;
        for (SessionPeriod session : sessions) {
            if (!session.start().isBefore(startAt)) {
                continue;
            }
            Instant windowEnd = min(session.end().minus(INTAKE_CUTOFF), startAt);
            Instant windowStart = max(now, session.start());
            Instant lastStart = windowEnd.minus(remaining);
            if (!lastStart.isBefore(windowStart) && (latest == null || lastStart.isAfter(latest))) {
                latest = lastStart;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** FR-OPS-40: could the remaining time fit a valid window before {@code startAt}, if the block resolved now? */
    static boolean feasible(Instant now, Duration remaining, Instant startAt, List<SessionPeriod> sessions) {
        return lastFeasibleResume(now, remaining, startAt, sessions).isPresent();
    }

    /** FR-OPS-39 conditions 4 and 5: {@code now} inside an Operating Session, before its 10-minute intake cutoff. */
    static boolean insideIntakeWindow(Instant now, List<SessionPeriod> sessions) {
        return sessions.stream().anyMatch(s -> !now.isBefore(s.start())
                && now.isBefore(s.end().minus(INTAKE_CUTOFF)));
    }

    /** The start of the next online intake window after {@code now} (FR-OPS-39: "the next valid intake window"). */
    static Optional<Instant> nextIntakeWindow(Instant now, List<SessionPeriod> sessions) {
        return sessions.stream()
                .filter(s -> s.start().isAfter(now) && s.start().isBefore(s.end().minus(INTAKE_CUTOFF)))
                .map(SessionPeriod::start)
                .min(Instant::compareTo);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
