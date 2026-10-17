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
 * E7-25 step 3: referenced sessions are immutable and only unreferenced ones are regenerated. E7-113 (v1.1.36): a
 * referenced future session the hours no longer generate is superseded and its day gets the current session beside it;
 * a started one is left as it is. Monday 5 Oct 2026, Africa/Cairo (+03:00).
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
        assertThat(plan.supersede()).isEmpty();
        assertThat(plan.supersededDays()).isEmpty();
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
    void aReferencedSessionTheHoursNoLongerGenerateIsSupersededAndItsDayGetsTheCurrentSession() {
        List<PlannedSession> old = generate(OLD, 2);
        ExistingSession referenced = row(old.get(0), 1, true);
        ExistingSession free = row(old.get(1), 1, false);

        SessionPlan plan = SessionPlan.of(List.of(referenced, free), generate(NEW, 2), TEMPLATE, 2, CAIRO.getId(),
                NOW);

        assertThat(plan.delete()).as("a referenced session is never deleted").containsExactly(free.id());
        assertThat(plan.supersede()).containsExactly(referenced.id());
        assertThat(plan.supersededDays()).containsExactly(MON);
        assertThat(plan.kept()).as("superseded history is not a current session").isEmpty();
        assertThat(plan.insert()).as("Monday gets its current session beside the superseded one")
                .extracting(PlannedSession::operatingDay).containsExactly(MON, MON.plusDays(1));
        PlannedSession newMonday = generate(NEW, 1).getFirst();
        assertThat(plan.containers()).contains(new Interval(newMonday.start(), newMonday.end()))
                .doesNotContain(new Interval(referenced.start(), referenced.end()));
    }

    @Test
    void aReferencedSessionThatStillMatchesStaysCurrentAndIsRestamped() {
        ExistingSession referenced = row(generate(OLD, 1).getFirst(), 1, true);

        SessionPlan plan = SessionPlan.of(List.of(referenced), generate(OLD, 1), TEMPLATE, 2, CAIRO.getId(), NOW);

        assertThat(plan.supersede()).isEmpty();
        assertThat(plan.kept()).containsExactly(referenced);
        assertThat(plan.restamp()).containsExactly(referenced.id());
        assertThat(plan.insert()).isEmpty();
    }

    @Test
    void aReferencedSessionOfADayNowClosedIsSupersededAndTheDayHasNoSession() {
        OpeningHours mondayClosed = OpeningHours.validated(LocalTime.of(12, 0), LocalTime.of(23, 0), false,
                List.of(new OpeningHours.Day(0, OpeningDayMode.CLOSED, null, null)));
        ExistingSession referenced = row(generate(OLD, 1).getFirst(), 1, true);

        SessionPlan plan = SessionPlan.of(List.of(referenced), generate(mondayClosed, 1), TEMPLATE, 2,
                CAIRO.getId(), NOW);

        assertThat(plan.supersede()).containsExactly(referenced.id());
        assertThat(plan.insert()).isEmpty();
        assertThat(plan.containers()).isEmpty();
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
    void aNewSessionThatWouldOverlapAStartedOneIsNotCreated() {
        // Monday's started session runs to 02:00 Tuesday; new hours open Tuesday at 01:00.
        PlannedSession lateMonday = generate(NEW, 1).getFirst();
        ExistingSession started = row(lateMonday, 1, true);
        Instant mondayEvening = lateMonday.start().plusSeconds(3600);
        OpeningHours early = OpeningHours.validated(LocalTime.of(1, 0), LocalTime.of(12, 0), false, List.of());

        SessionPlan plan = SessionPlan.of(List.of(started), generate(early, 2), TEMPLATE, 2, CAIRO.getId(),
                mondayEvening);

        assertThat(plan.supersede()).as("a started session is left as it is").isEmpty();
        assertThat(plan.kept()).containsExactly(started);
        assertThat(plan.skippedDays()).containsExactly(MON.plusDays(1));
        assertThat(plan.insert()).isEmpty();
    }
}
