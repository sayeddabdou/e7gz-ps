package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.venue.internal.OpeningHours.Day;
import ps.e7gz.venue.internal.SessionGenerator.PlannedSession;

/**
 * E7-25 done-when: the DST cases (FR-VEN-21, arch 14 Phase 0 exit: non-existent start, ambiguous repeat, 23/25-hour
 * day). Africa/Cairo in 2026: clocks go forward at 00:00 on Friday 24 April (00:00–00:59 does not exist) and back at
 * 24:00 on Thursday 29 October (23:00–23:59 happens twice).
 */
class SessionGeneratorTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final LocalDate THU_23_APR = LocalDate.of(2026, 4, 23);
    private static final LocalDate FRI_24_APR = LocalDate.of(2026, 4, 24);
    private static final LocalDate WED_28_OCT = LocalDate.of(2026, 10, 28);
    private static final LocalDate THU_29_OCT = LocalDate.of(2026, 10, 29);
    private static final LocalDate FRI_30_OCT = LocalDate.of(2026, 10, 30);
    private static final int WEDNESDAY = 2;
    private static final int THURSDAY = 3;
    private static final int FRIDAY = 4;

    private static LocalTime t(String time) {
        return LocalTime.parse(time);
    }

    private static Day custom(int weekday, String open, String close) {
        return new Day(weekday, OpeningDayMode.CUSTOM, t(open), t(close));
    }

    private static Day closed(int weekday) {
        return new Day(weekday, OpeningDayMode.CLOSED, null, null);
    }

    private static PlannedSession only(OpeningHours hours, LocalDate day) {
        List<PlannedSession> sessions = SessionGenerator.generate(hours, CAIRO, day, day.plusDays(1));
        assertThat(sessions).hasSize(1);
        return sessions.getFirst();
    }

    private static Duration real(PlannedSession session) {
        return Duration.between(session.start(), session.end());
    }

    @Test
    void aNonExistentStartBeginsWhenTheGapEnds() {
        OpeningHours hours = OpeningHours.validated(t("12:00"), t("20:00"), false,
                List.of(custom(FRIDAY, "00:30", "10:00")));

        PlannedSession friday = only(hours, FRI_24_APR);

        // 00:30 never happens; the first instant that exists is 01:00 (+03:00), 2026-04-23T22:00Z.
        assertThat(friday.start()).isEqualTo(Instant.parse("2026-04-23T22:00:00Z"));
        assertThat(friday.localStart()).isEqualTo(FRI_24_APR.atTime(0, 30));          // the declared time
        assertThat(friday.end()).isEqualTo(Instant.parse("2026-04-24T07:00:00Z"));
        assertThat(friday.localEnd()).isEqualTo(FRI_24_APR.atTime(10, 0));
        assertThat(real(friday)).isEqualTo(Duration.ofHours(9));
    }

    @Test
    void sessionsTouchingInsideTheGapStillTouch() {
        // Thursday 18:00 → 00:30 and Friday 00:30 → 10:00 both meet at the gap's end.
        OpeningHours hours = OpeningHours.validated(t("12:00"), t("20:00"), false,
                List.of(custom(THURSDAY, "18:00", "00:30"), custom(FRIDAY, "00:30", "10:00")));

        List<PlannedSession> sessions = SessionGenerator.generate(hours, CAIRO, THU_23_APR, FRI_24_APR.plusDays(1));

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).end()).isEqualTo(sessions.get(1).start());
        assertThat(sessions.get(0).end()).isEqualTo(Instant.parse("2026-04-23T22:00:00Z"));
    }

    @Test
    void aSessionWhollyInsideTheGapHasNoRealTimeAndIsNotGenerated() {
        OpeningHours hours = OpeningHours.validated(t("12:00"), t("20:00"), false,
                List.of(custom(FRIDAY, "00:00", "00:45")));

        assertThat(SessionGenerator.generate(hours, CAIRO, FRI_24_APR, FRI_24_APR.plusDays(1))).isEmpty();
    }

    @Test
    void aFullDayAcrossSpringForwardLasts23RealHours() {
        OpeningHours hours = OpeningHours.validated(t("12:00"), t("12:00"), true, List.of());

        PlannedSession thursday = only(hours, THU_23_APR);

        assertThat(real(thursday)).isEqualTo(Duration.ofHours(23));
        assertThat(Duration.between(thursday.localStart(), thursday.localEnd())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aFullDayAcrossFallBackLasts25RealHours() {
        OpeningHours hours = OpeningHours.validated(t("23:00"), t("23:00"), true, List.of());

        PlannedSession thursday = only(hours, THU_29_OCT);

        // Starts at the first 23:00 (+03:00) and ends at Friday 23:00 (+02:00).
        assertThat(thursday.start()).isEqualTo(Instant.parse("2026-10-29T20:00:00Z"));
        assertThat(thursday.end()).isEqualTo(Instant.parse("2026-10-30T21:00:00Z"));
        assertThat(real(thursday)).isEqualTo(Duration.ofHours(25));
        assertThat(Duration.between(thursday.localStart(), thursday.localEnd())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void anAmbiguousTimeIsItsFirstOccurrenceForStartsAndEndsAlike() {
        // Wednesday 23:30 → Thursday 23:30 ends, and Thursday 23:30 → Friday 08:00 starts, in the repeated hour.
        OpeningHours hours = OpeningHours.validated(t("12:00"), t("20:00"), false,
                List.of(custom(WEDNESDAY, "23:30", "23:30"), custom(THURSDAY, "23:30", "08:00"), closed(FRIDAY)));

        List<PlannedSession> sessions = SessionGenerator.generate(hours, CAIRO, WED_28_OCT, FRI_30_OCT);

        assertThat(sessions).hasSize(2);
        PlannedSession wednesday = sessions.get(0);
        PlannedSession thursday = sessions.get(1);
        assertThat(wednesday.end()).isEqualTo(Instant.parse("2026-10-29T20:30:00Z"));
        assertThat(thursday.start()).isEqualTo(wednesday.end());       // touching, never overlapping
        // The repeated hour is ordinary time inside Thursday's session, not a second 23:30 slot: 9.5 real hours.
        assertThat(real(thursday)).isEqualTo(Duration.ofMinutes(9 * 60 + 30));
        assertThat(thursday.localStart()).isEqualTo(THU_29_OCT.atTime(23, 30));
    }

    @Test
    void sessionsNeverOverlapAndKeepTheirLocalLengthAcrossAWholeYear() {
        List<OpeningHours> templates = List.of(
                OpeningHours.validated(t("00:00"), t("00:00"), true, List.of()),
                OpeningHours.validated(t("23:30"), t("23:30"), true, List.of()),
                OpeningHours.validated(t("12:00"), t("02:00"), false, List.of(custom(FRIDAY, "14:00", "00:30"))),
                OpeningHours.validated(t("00:30"), t("23:15"), false, List.of(closed(0))));
        for (OpeningHours hours : templates) {
            List<PlannedSession> sessions = SessionGenerator.generate(hours, CAIRO, LocalDate.of(2026, 1, 1),
                    LocalDate.of(2028, 1, 1));
            for (int i = 0; i < sessions.size(); i++) {
                PlannedSession session = sessions.get(i);
                assertThat(session.end()).isAfter(session.start());
                assertThat(session.localEnd()).isAfter(session.localStart())
                        .isBeforeOrEqualTo(session.localStart().plusHours(24));
                assertThat(SessionGenerator.resolve(session.localStart(), CAIRO)).isEqualTo(session.start());
                assertThat(real(session)).isBetween(Duration.ZERO, Duration.ofHours(25));
                if (i > 0) {
                    assertThat(session.start()).as(hours + " on " + session.operatingDay())
                            .isAfterOrEqualTo(sessions.get(i - 1).end());
                }
            }
        }
    }

    @Test
    void oneSessionPerOpenOperatingDay() {
        OpeningHours hours = OpeningHours.validated(t("12:00"), t("02:00"), false, List.of(closed(1)));

        List<PlannedSession> sessions = SessionGenerator.generate(hours, CAIRO, LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 19));

        assertThat(sessions).hasSize(12);
        assertThat(sessions).extracting(PlannedSession::operatingDay).doesNotHaveDuplicates()
                .noneMatch(day -> OpeningHours.weekdayOf(day) == 1);
    }
}
