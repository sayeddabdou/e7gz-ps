package ps.e7gz.inventory.availability.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.inventory.availability.internal.FreeWindows.Span;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;

/**
 * {@link BusinessDates} over the Venue's materialized Operating Sessions, read through the Venue module's api (the
 * E7-30 owner-API boundary); this class holds no SQL.
 */
@Component
class SessionBusinessDates implements BusinessDates {

    private final OperatingSessions sessions;
    private final Clock clock;

    SessionBusinessDates(OperatingSessions sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public LocalDate businessDate(CommandContext command, UUID venueId, Instant startAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(startAt, "startAt");
        return resolve(sessions.operatingDayAt(command, venueId, startAt), startAt);
    }

    @Override
    public LocalDate currentBusinessDate(UUID venueId) {
        Objects.requireNonNull(venueId, "venueId");
        Instant now = clock.instant();
        return resolve(sessions.operatingDayAt(venueId, now), now);
    }

    /**
     * The date's session (rule 1) plus its calendar day less the neighbours' sessions (rule 2). A session lasts at most
     * 24 local hours from a start on its own day ({@code ck_session_len}), so only the days before and after can reach
     * into this calendar day.
     */
    @Override
    public BusinessDay businessDay(UUID venueId, LocalDate date) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(date, "date");
        Span calendarDay = new Span(date.atStartOfDay(FALLBACK_ZONE).toInstant(),
                date.plusDays(1).atStartOfDay(FALLBACK_ZONE).toInstant());
        Span own = null;
        List<Span> others = new ArrayList<>();
        for (SessionPeriod session : sessions.onOperatingDays(venueId, date.minusDays(1), date.plusDays(1))) {
            Span span = new Span(session.start(), session.end());
            if (session.operatingDay().equals(date)) {
                own = span;
            } else {
                others.add(span);
            }
        }
        List<Span> belonging = new ArrayList<>(FreeWindows.minus(List.of(calendarDay), FreeWindows.union(others)));
        if (own != null) {
            belonging.add(own);
        }
        return new BusinessDay(date, own == null ? null : period(own),
                FreeWindows.union(belonging).stream().map(SessionBusinessDates::period).toList());
    }

    private static Period period(Span span) {
        return new Period(span.start(), span.end());
    }

    /** Rule 1, else rule 2 (arch 04 section 4.3, FR-AVL-14). */
    static LocalDate resolve(Optional<LocalDate> operatingDay, Instant at) {
        return operatingDay.orElseGet(() -> LocalDate.ofInstant(at, FALLBACK_ZONE));
    }
}
