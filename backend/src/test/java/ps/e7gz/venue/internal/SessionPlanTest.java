package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.venue.internal.SessionGenerator.PlannedSession;
import ps.e7gz.venue.internal.SessionPlan.ExistingSession;
import ps.e7gz.venue.internal.SessionPlan.Interval;

/**
 * E7-25 step 3: referenced sessions are immutable and only unreferenced ones are regenerated; a referenced day keeps
 * its session (PO decision 3 Oct 2026). Monday 5 Oct 2026, Africa/Cairo (+03:00).
 */
class SessionPlanTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final UUID TEMPLATE = UUID.randomUUID();
    private static final LocalDate MON = LocalDate.of(2026, 10, 5);
    /** Monday 10:00 local. */
    private static final Instant NOW = Instant.parse("2026-10-05T07:00:00Z");

    private static final OpeningHours OLD = OpeningHours.validated(LocalTime.of(12, 0), LocalTime.of(23, 0), false,
            List.of());
    private static final OpeningHours NEW = OpeningHours.validated(LocalTime.of(14, 0), LocalTime.of(2, 0), false,
            List.of());

    private static List<PlannedSession> generate(OpeningHours hours, int days) {
        return SessionGenerator.generate(hours, CAIRO, MON, MON.plusDays(days));
    }

    private static ExistingSession row(PlannedSession session, long version, boolean referenced) {
        return new ExistingSession(UUID.randomUUID(), session.operatingDay(), session.localStart(),
                session.localEnd(), session.start(), session.end(), CAIRO.getId(), TEMPLATE, version, referenced);
    }

    @Test
    void theNightlyRunOnlyAddsTheMissingDays() {
        List<ExistingSession> existing = generate(OLD, 3).stream().map(s -> row(s, 1, false)).toList();

        SessionPlan plan = SessionPlan.of(existing, generate(OLD, 4), TEMPLATE, 1, CAIRO.getId(), NOW);

        assertThat(plan.delete()).isEmpty();
        assertThat(plan.restamp()).isEmpty();
        assertThat(plan.insert()).extracting(PlannedSession::operatingDay).containsExactly(MON.plusDays(3));
    }

    @Test
    void anHoursChangeReplacesUnreferencedFutureSessions() {
        List<ExistingSession> existing = generate(OLD, 3).stream().map(s -> row(s, 1, false)).toList();

        SessionPlan plan = SessionPlan.of(existing, generate(NEW, 3), TEMPLATE, 2, CAIRO.getId(), NOW);

        assertThat(plan.delete()).containsExactlyElementsOf(existing.stream().map(ExistingSession::id).toList());
        assertThat(plan.insert()).hasSize(3);
        assertThat(plan.pinnedDays()).isEmpty();
    }

    @Test
    void aSessionThatAlreadyMatchesIsKeptAndOnlyRestamped() {
        OpeningHours mondayClosed = OpeningHours.validated(LocalTime.of(12, 0), LocalTime.of(23, 0), false,
                List.of(new OpeningHours.Day(0, OpeningDayMode.CLOSED, null, null)));
        List<ExistingSession> existing = generate(OLD, 2).stream().map(s -> row(s, 1, false)).toList();

        SessionPlan plan = SessionPlan.of(existing, generate(mondayClosed, 2), TEMPLATE, 2, CAIRO.getId(), NOW);

        assertThat(plan.delete()).containsExactly(existing.get(0).id());          // Monday is now closed
        assertThat(plan.restamp()).containsExactly(existing.get(1).id());         // Tuesday is unchanged
        assertThat(plan.insert()).isEmpty();
    }

    @Test
    void aReferencedSessionIsNeverDeletedAndItsDayKeepsIt() {
        List<PlannedSession> old = generate(OLD, 2);
        ExistingSession referenced = row(old.get(0), 1, true);
        ExistingSession free = row(old.get(1), 1, false);

        SessionPlan plan = SessionPlan.of(List.of(referenced, free), generate(NEW, 2), TEMPLATE, 2, CAIRO.getId(),
                NOW);

        assertThat(plan.delete()).containsExactly(free.id());
        assertThat(plan.kept()).containsExactly(referenced);
        assertThat(plan.pinnedDays()).containsExactly(MON);
        assertThat(plan.insert()).extracting(PlannedSession::operatingDay).containsExactly(MON.plusDays(1));
        // The new hours still decide which reservations are affected on that day.
        PlannedSession newMonday = generate(NEW, 1).getFirst();
        assertThat(plan.containers()).contains(new Interval(newMonday.start(), newMonday.end()));
    }

    @Test
    void aStartedSessionIsLeftAsItIs() {
        Instant mondayNoonThirty = Instant.parse("2026-10-05T09:30:00Z");
        List<ExistingSession> existing = generate(OLD, 2).stream().map(s -> row(s, 1, false)).toList();

        SessionPlan plan = SessionPlan.of(existing, generate(NEW, 2), TEMPLATE, 2, CAIRO.getId(), mondayNoonThirty);

        assertThat(plan.kept()).containsExactly(existing.get(0));
        assertThat(plan.delete()).containsExactly(existing.get(1).id());
        assertThat(plan.insert()).extracting(PlannedSession::operatingDay).containsExactly(MON.plusDays(1));
        assertThat(plan.containers()).contains(new Interval(existing.get(0).start(), existing.get(0).end()));
    }

    @Test
    void aNewSessionThatWouldOverlapAKeptOneIsNotCreated() {
        // Monday's referenced session runs to 02:00 Tuesday; new hours open Tuesday at 01:00.
        PlannedSession lateMonday = generate(NEW, 1).getFirst();
        ExistingSession referenced = row(lateMonday, 1, true);
        OpeningHours early = OpeningHours.validated(LocalTime.of(1, 0), LocalTime.of(12, 0), false, List.of());

        SessionPlan plan = SessionPlan.of(List.of(referenced), generate(early, 2), TEMPLATE, 2, CAIRO.getId(), NOW);

        assertThat(plan.skippedDays()).containsExactly(MON.plusDays(1));
        assertThat(plan.insert()).isEmpty();
    }
}
