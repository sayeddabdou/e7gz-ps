package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.venue.internal.OpeningHours.Day;
import ps.e7gz.venue.internal.OpeningHours.LocalWindow;

/** E7-25: the weekly opening-hours model (FR-VEN-17…FR-VEN-20). 5 Oct 2026 is a Monday (weekday 0). */
class OpeningHoursTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    private static LocalTime t(String time) {
        return LocalTime.parse(time);
    }

    private static Day custom(int weekday, String open, String close) {
        return new Day(weekday, OpeningDayMode.CUSTOM, t(open), t(close));
    }

    private static Day closed(int weekday) {
        return new Day(weekday, OpeningDayMode.CLOSED, null, null);
    }

    private static void refusedOn(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
            assertThat(e.field()).isEqualTo(field);
        });
    }

    @Test
    void weekdayZeroIsMondayAndSixIsSunday() {
        assertThat(OpeningHours.weekdayOf(MONDAY)).isZero();
        assertThat(OpeningHours.weekdayOf(MONDAY.plusDays(6))).isEqualTo(6);
    }

    @Test
    void defaultCustomAndClosedDaysEachGiveTheirOwnSession() {
        OpeningHours hours = OpeningHours.validated(t("12:00"), t("23:00"), false,
                List.of(custom(4, "14:00", "03:00"), closed(1)));

        assertThat(hours.windowOn(MONDAY)).contains(new LocalWindow(MONDAY.atTime(12, 0), MONDAY.atTime(23, 0)));
        assertThat(hours.windowOn(MONDAY.plusDays(1))).isEmpty();
        LocalDate friday = MONDAY.plusDays(4);
        assertThat(hours.windowOn(friday))
                .contains(new LocalWindow(friday.atTime(14, 0), friday.plusDays(1).atTime(3, 0)));
        assertThat(hours.dayList()).hasSize(7);
    }

    @Test
    void aFullDayIsDeclaredAndLastsTwentyFourLocalHours() {
        OpeningHours hours = OpeningHours.validated(t("10:00"), t("10:00"), true, List.of());

        assertThat(hours.windowOn(MONDAY)).contains(new LocalWindow(MONDAY.atTime(10, 0),
                MONDAY.plusDays(1).atTime(10, 0)));
        refusedOn(() -> OpeningHours.validated(t("10:00"), t("10:00"), false, List.of()), "defaultClose");
        refusedOn(() -> OpeningHours.validated(t("10:00"), t("22:00"), true, List.of()), "defaultClose");
    }

    @Test
    void aCustomDayWithEqualTimesIsTwentyFourHours() {
        OpeningHours hours = OpeningHours.validated(t("10:00"), t("12:00"), false,
                List.of(custom(0, "08:00", "08:00"), closed(1)));

        LocalWindow window = hours.windowOn(MONDAY).orElseThrow();
        assertThat(window.end()).isEqualTo(LocalDateTime.of(MONDAY.plusDays(1), LocalTime.of(8, 0)));
    }

    @Test
    void sessionsMayTouchButNotOverlap() {
        // Monday 20:00 → Tuesday 02:00 touches Tuesday's 02:00 open: allowed (FR-VEN-18).
        OpeningHours.validated(t("02:00"), t("10:00"), false, List.of(custom(0, "20:00", "02:00")));

        // One minute later it overlaps Tuesday's session.
        assertThatThrownBy(() -> OpeningHours.validated(t("02:00"), t("10:00"), false,
                List.of(custom(0, "20:00", "02:01"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.field()).isEqualTo("days");
                    assertThat(e.details()).containsEntry("weekday", 0);
                });
    }

    @Test
    void overlapIsCheckedAcrossTheSundayToMondayWrap() {
        // Sunday 22:00 → Monday 04:00 against Monday's 03:00 open (FR-VEN-19).
        assertThatThrownBy(() -> OpeningHours.validated(t("03:00"), t("12:00"), false,
                List.of(custom(6, "22:00", "04:00"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.details()).containsEntry("weekday", 6));
        // Closing Monday makes it valid: a closed day stops new sessions only (FR-VEN-20).
        OpeningHours hours = OpeningHours.validated(t("03:00"), t("12:00"), false,
                List.of(custom(6, "22:00", "04:00"), closed(0)));
        assertThat(hours.windowOn(MONDAY)).isEmpty();
        LocalDate sunday = MONDAY.minusDays(1);
        assertThat(hours.windowOn(sunday).orElseThrow().end()).isEqualTo(MONDAY.atTime(4, 0));
    }

    @Test
    void aDefaultFullDayEveryDayTouchesItselfAroundTheWeek() {
        OpeningHours.validated(t("06:00"), t("06:00"), true, List.of());
    }

    @Test
    void dayEntriesMustBeWellFormed() {
        refusedOn(() -> OpeningHours.validated(t("10:00"), t("12:00"), false,
                List.of(closed(2), closed(2))), "days");
        refusedOn(() -> OpeningHours.validated(t("10:00"), t("12:00"), false,
                List.of(new Day(3, OpeningDayMode.CUSTOM, t("10:00"), null))), "days");
        refusedOn(() -> OpeningHours.validated(t("10:00"), t("12:00"), false,
                List.of(new Day(3, OpeningDayMode.CLOSED, t("10:00"), t("11:00")))), "days");
        refusedOn(() -> OpeningHours.validated(t("10:00"), t("12:00"), false,
                List.of(new Day(7, OpeningDayMode.CLOSED, null, null))), "days");
    }
}
