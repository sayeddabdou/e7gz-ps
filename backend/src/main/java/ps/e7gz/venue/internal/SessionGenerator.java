package ps.e7gz.venue.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns weekly opening hours into concrete Operating Sessions (ADR-009): one per open operating day, with its local
 * wall-clock bounds and its absolute instants. DST is resolved here, once (FR-VEN-21, arch 01 D12):
 * <ul>
 *   <li>a local time that does not exist (the spring-forward gap) resolves to the instant the gap ends, so a session
 *       never starts or ends at a time that is not offered; a session that lies wholly inside a gap has no real time and
 *       is not generated;</li>
 *   <li>a local time that occurs twice (the fall-back repeat) resolves to its first occurrence, the same rule for
 *       starts and ends, so sessions that touch in local time still touch and never overlap, and the repeated hour is
 *       ordinary real time inside one session, never a duplicate;</li>
 *   <li>a full-day session therefore lasts 23 or 25 real hours across a change and stays valid; its local length is
 *       still 24 hours ({@code ck_session_len}).</li>
 * </ul>
 * The local bounds stay the declared wall-clock times of the hours (the snapshot a Booking keeps, FR-VEN-22), so their
 * difference is the declared length, at most 24 hours ({@code ck_session_len}) even when the real length is 23 or 25;
 * only the absolute instants are resolved.
 */
final class SessionGenerator {

    private SessionGenerator() {
    }

    /** One generated session. {@code start}/{@code end} are the inventory authority (NFR-07). */
    record PlannedSession(LocalDate operatingDay, LocalDateTime localStart, LocalDateTime localEnd, Instant start,
                          Instant end) {
    }

    /** Sessions for the operating days {@code [from, toExclusive)}, in day order. */
    static List<PlannedSession> generate(OpeningHours hours, ZoneId zone, LocalDate from, LocalDate toExclusive) {
        List<PlannedSession> sessions = new ArrayList<>();
        for (LocalDate day = from; day.isBefore(toExclusive); day = day.plusDays(1)) {
            LocalDate operatingDay = day;
            hours.windowOn(operatingDay).ifPresent(window -> {
                Instant start = resolve(window.start(), zone);
                Instant end = resolve(window.end(), zone);
                if (end.isAfter(start)) {
                    sessions.add(new PlannedSession(operatingDay, window.start(), window.end(), start, end));
                }
            });
        }
        return sessions;
    }

    /** The instant of a local wall-clock time, with the gap and repeat rules above. Monotonic in {@code local}. */
    static Instant resolve(LocalDateTime local, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.size() == 1) {
            return local.toInstant(offsets.getFirst());
        }
        ZoneOffsetTransition transition = rules.getTransition(local);
        if (offsets.isEmpty()) {
            return transition.getInstant();                      // gap: the first instant that exists again
        }
        return local.toInstant(transition.getOffsetBefore());    // repeat: the first occurrence
    }
}
