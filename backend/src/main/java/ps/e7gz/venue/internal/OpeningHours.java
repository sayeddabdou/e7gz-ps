package ps.e7gz.venue.internal;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * A venue's weekly opening hours in venue local wall-clock time: the Default Schedule plus one setting per weekday
 * ({@code venue.venue_opening_template} + {@code venue.venue_opening_day}, FR-VEN-17). Weekday 0 = Monday … 6 = Sunday
 * (SPRINT-1-CONTRACT §2), so {@link #weekdayOf} is {@code DayOfWeek.getValue() - 1}.
 *
 * <p>A close time at or before the open time ends on the next day (cross-midnight, FR-VEN-17). The Default Schedule
 * declares a full day explicitly ({@code default_full_day}): open = close and 24 local hours. A CUSTOM day with open =
 * close is also 24 local hours. Lengths are local wall-clock lengths (at most 24 hours, FR-VEN-18); the real length of
 * a session may be 23 or 25 hours across a DST change (FR-VEN-21), which {@link SessionGenerator} resolves.
 */
record OpeningHours(LocalTime defaultOpen, LocalTime defaultClose, boolean defaultFullDay, Map<Integer, Day> days) {

    static final int WEEKDAYS = 7;
    private static final Duration FULL_DAY = Duration.ofHours(24);

    OpeningHours {
        days = Collections.unmodifiableMap(new TreeMap<>(days));
    }

    /** One weekday: DEFAULT, CUSTOM with both times, or CLOSED ({@code ck_opening_day_custom_times}). */
    record Day(int weekday, OpeningDayMode mode, LocalTime customOpen, LocalTime customClose) {
    }

    /** A session's local wall-clock bounds on its operating day. */
    record LocalWindow(LocalDateTime start, LocalDateTime end) {
    }

    /**
     * The hours as the venue proposed them, checked (FR-VEN-17…FR-VEN-19); a refusal is {@code VALIDATION_FAILED} on the
     * field at fault, with the weekday in {@code details} for a day problem. A weekday that is not sent uses the
     * default.
     */
    static OpeningHours validated(LocalTime defaultOpen, LocalTime defaultClose, boolean defaultFullDay,
                                  List<Day> proposedDays) {
        if (defaultFullDay != defaultOpen.equals(defaultClose)) {
            // A full day is declared, never implied by equal times; equal times without the flag have no length.
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "defaultClose");
        }
        Map<Integer, Day> days = new TreeMap<>();
        for (Day day : proposedDays) {
            boolean custom = day.mode() == OpeningDayMode.CUSTOM;
            boolean timesSent = day.customOpen() != null || day.customClose() != null;
            boolean bothTimes = day.customOpen() != null && day.customClose() != null;
            if (day.weekday() < 0 || day.weekday() >= WEEKDAYS || days.containsKey(day.weekday())
                    || (custom ? !bothTimes : timesSent)) {
                throw dayRefused(day.weekday());
            }
            days.put(day.weekday(), day);
        }
        OpeningHours hours = stored(defaultOpen, defaultClose, defaultFullDay, List.copyOf(days.values()));
        hours.requireNoOverlapAcrossTheWeek();
        return hours;
    }

    /** Hours as saved: read back without re-checking them; a weekday without a row uses the default. */
    static OpeningHours stored(LocalTime defaultOpen, LocalTime defaultClose, boolean defaultFullDay,
                               List<Day> storedDays) {
        Map<Integer, Day> days = new TreeMap<>();
        storedDays.forEach(day -> days.put(day.weekday(), day));
        for (int weekday = 0; weekday < WEEKDAYS; weekday++) {
            days.putIfAbsent(weekday, new Day(weekday, OpeningDayMode.DEFAULT, null, null));
        }
        return new OpeningHours(defaultOpen, defaultClose, defaultFullDay, days);
    }

    static int weekdayOf(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }

    /** The session that starts on this operating day, in local time; empty when the weekday is CLOSED (FR-VEN-20). */
    Optional<LocalWindow> windowOn(LocalDate operatingDay) {
        return opening(weekdayOf(operatingDay)).map(open -> {
            LocalDateTime start = operatingDay.atTime(open.time());
            return new LocalWindow(start, start.plus(open.length()));
        });
    }

    /** The days in weekday order, all seven. */
    List<Day> dayList() {
        return new ArrayList<>(days.values());
    }

    private record Opening(LocalTime time, Duration length) {
    }

    private Optional<Opening> opening(int weekday) {
        Day day = days.get(weekday);
        return switch (day.mode()) {
            case CLOSED -> Optional.empty();
            case DEFAULT -> Optional.of(new Opening(defaultOpen, defaultFullDay ? FULL_DAY
                    : length(defaultOpen, defaultClose)));
            case CUSTOM -> Optional.of(new Opening(day.customOpen(), length(day.customOpen(), day.customClose())));
        };
    }

    /** Local length: a close at or before the open is on the next day, so equal times are 24 hours. */
    private static Duration length(LocalTime open, LocalTime close) {
        Duration sameDay = Duration.between(open, close);
        return sameDay.isPositive() ? sameDay : sameDay.plus(FULL_DAY);
    }

    /**
     * Sessions may touch but not overlap (FR-VEN-18), checked cyclically over the week, including Sunday into Monday
     * (FR-VEN-19). A session is at most 24 hours, so it can only reach the next weekday's session.
     */
    private void requireNoOverlapAcrossTheWeek() {
        for (int weekday = 0; weekday < WEEKDAYS; weekday++) {
            int next = (weekday + 1) % WEEKDAYS;
            Optional<Opening> today = opening(weekday);
            Optional<Opening> tomorrow = opening(next);
            if (today.isEmpty() || tomorrow.isEmpty()) {
                continue;
            }
            Duration end = Duration.ofSeconds(today.get().time().toSecondOfDay()).plus(today.get().length());
            Duration nextStart = FULL_DAY.plusSeconds(tomorrow.get().time().toSecondOfDay());
            if (end.compareTo(nextStart) > 0) {
                throw dayRefused(weekday);
            }
        }
    }

    private static ApiException dayRefused(int weekday) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, "days", Map.of("weekday", weekday));
    }
}
