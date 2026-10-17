package ps.e7gz.venue.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.venue.internal.SessionGenerator.PlannedSession;

/**
 * What a regeneration does to a venue's {@code venue.operating_session} rows (ADR-009), decided without touching the
 * database so the rules are testable on their own:
 * <ul>
 *   <li>A session a Booking or Tournament references is a historical snapshot (FR-VEN-22): it is never deleted and its
 *       bounds never change ({@code venue.guard_operating_session}). When the hours no longer generate it, it is
 *       superseded ({@code superseded_at}, v1.1.36) and its day gets the session the hours generate, beside it - the
 *       row and every reference to it stay, but no new booking is ever placed in it ({@link
 *       OperatingSessionStore#EFFECTIVE}; Amr's E7-113 decision). Its day is reported in {@link #supersededDays}. A
 *       referenced session that still matches the hours is restamped like any other and stays current.</li>
 *   <li>A session that has already started, or an earlier day's, is left as it is: a change applies to sessions still
 *       to come.</li>
 *   <li>Every other session is regenerated: kept when it is already exactly what the hours generate (only its
 *       generation version is brought up to date), otherwise deleted and replaced.</li>
 *   <li>A new session that would overlap a current session left in place is not created ({@code ex_session_no_overlap},
 *       which exempts superseded history); its day is reported in {@link #skippedDays}.</li>
 * </ul>
 *
 * @param delete     unreferenced sessions to delete
 * @param restamp    sessions still to come that already match (referenced or not), whose {@code generated_from_version}
 *                   is brought up to date
 * @param insert     new sessions
 * @param supersede  referenced sessions still to come that the hours no longer generate: kept as history, superseded
 * @param kept       current sessions left in place (started or earlier, or referenced and still generated)
 * @param supersededDays operating days whose referenced session is superseded
 * @param skippedDays operating days whose new session was not created because it overlaps a session left in place
 * @param containers the sessions an existing reservation must fit into after the change: the started sessions that
 *                   stay, and what the hours generate for every later day that gets or keeps a session. A superseded
 *                   day counts with its new hours, so a Booking the new hours no longer cover is found as affected
 *                   (FR-VEN-24) even though its referenced session row stays. A skipped day has no container.
 */
record SessionPlan(List<UUID> delete, List<UUID> restamp, List<PlannedSession> insert, List<UUID> supersede,
                   List<ExistingSession> kept, List<LocalDate> supersededDays, List<LocalDate> skippedDays,
                   List<Interval> containers) {

    /** An existing row, and whether a Booking or Tournament references it. */
    record ExistingSession(UUID id, LocalDate operatingDay, LocalDateTime localStart, LocalDateTime localEnd,
                           Instant start, Instant end, String timezone, UUID templateId, long version,
                           boolean referenced) {
    }

    record Interval(Instant start, Instant end) {

        boolean contains(Instant otherStart, Instant otherEnd) {
            return !otherStart.isBefore(start) && !otherEnd.isAfter(end);
        }

        boolean overlaps(Instant otherStart, Instant otherEnd) {
            return otherStart.isBefore(end) && start.isBefore(otherEnd);
        }
    }

    /**
     * @param existing every current (not superseded) session of the venue that may still matter: those of
     *                 {@code today - 1} and later
     * @param desired  what the hours generate for {@code [today, today + horizon)}
     * @param now      the command instant
     */
    static SessionPlan of(List<ExistingSession> existing, List<PlannedSession> desired, UUID templateId, long version,
                          String timezone, Instant now) {
        Map<LocalDate, PlannedSession> wanted = new HashMap<>();
        desired.forEach(session -> wanted.put(session.operatingDay(), session));

        List<UUID> delete = new ArrayList<>();
        List<UUID> restamp = new ArrayList<>();
        List<UUID> supersede = new ArrayList<>();
        List<ExistingSession> kept = new ArrayList<>();
        List<LocalDate> supersededDays = new ArrayList<>();
        Map<LocalDate, ExistingSession> occupied = new HashMap<>();
        for (ExistingSession row : existing) {
            PlannedSession want = wanted.get(row.operatingDay());
            boolean started = !row.start().isAfter(now);
            if (row.referenced() && !started && !same(row, want, templateId, timezone)) {
                supersede.add(row.id());                         // history stays; its day gets the current session
                supersededDays.add(row.operatingDay());
            } else if (row.referenced() || started) {
                kept.add(row);
                occupied.put(row.operatingDay(), row);
                if (row.referenced() && !started && row.version() != version) {
                    restamp.add(row.id());                       // the guard allows the generation stamp to move
                }
            } else if (same(row, want, templateId, timezone)) {
                occupied.put(row.operatingDay(), row);
                if (row.version() != version) {
                    restamp.add(row.id());
                }
            } else {
                delete.add(row.id());
            }
        }

        List<PlannedSession> insert = new ArrayList<>();
        List<LocalDate> skippedDays = new ArrayList<>();
        List<Interval> containers = new ArrayList<>();
        for (ExistingSession row : kept) {
            if (!row.start().isAfter(now)) {
                containers.add(new Interval(row.start(), row.end()));
            }
        }
        for (PlannedSession want : desired) {
            ExistingSession there = occupied.get(want.operatingDay());
            if (!want.end().isAfter(now)) {
                continue;                                    // already over: nothing to create or fit into
            }
            if (there != null) {
                if (there.start().isAfter(now)) {
                    containers.add(new Interval(want.start(), want.end()));
                }
                continue;                                    // the day keeps its row
            }
            boolean clashes = kept.stream().anyMatch(row ->
                    new Interval(row.start(), row.end()).overlaps(want.start(), want.end()));
            if (clashes) {
                skippedDays.add(want.operatingDay());
            } else {
                insert.add(want);
                containers.add(new Interval(want.start(), want.end()));
            }
        }
        return new SessionPlan(List.copyOf(delete), List.copyOf(restamp), List.copyOf(insert), List.copyOf(supersede),
                List.copyOf(kept), List.copyOf(supersededDays), List.copyOf(skippedDays), List.copyOf(containers));
    }

    /** Whether the row is exactly the session the hours generate for its day. */
    private static boolean same(ExistingSession row, PlannedSession want, UUID templateId, String timezone) {
        return want != null && row.templateId().equals(templateId) && row.timezone().equals(timezone)
                && row.localStart().equals(want.localStart()) && row.localEnd().equals(want.localEnd())
                && row.start().equals(want.start()) && row.end().equals(want.end());
    }
}
