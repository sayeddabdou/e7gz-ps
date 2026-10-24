package ps.e7gz.inventory.availability.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.inventory.availability.internal.FreeWindows.Span;

/**
 * The 15-minute grid of Nearest Availability's candidate starts (PO decision 12 Oct 2026, E7-115): :00, :15, :30 and
 * :45 of Africa/Cairo wall time. It applies to the projection only; the server still accepts a booking at any start
 * minute (no grid rule on creation) and the app offers only grid starts (E7-62). Pure, no I/O.
 */
final class StartGrid {

    /** The wall clock of the grid. */
    static final ZoneId ZONE = BusinessDates.FALLBACK_ZONE;

    static final int MINUTES = 15;

    private static final long STEP_SECONDS = MINUTES * 60L;

    private StartGrid() {
    }

    /**
     * The earliest grid instant at or after {@code t}: {@code t} itself when it is exactly on the grid, otherwise the
     * next quarter hour of its Cairo wall time. Counted from the wall minute and second, then added on the instant
     * timeline, so a DST change (whole hours in Cairo) never moves the grid off the wall clock's quarter hours.
     */
    static Instant ceil(Instant t) {
        ZonedDateTime wall = Objects.requireNonNull(t, "t").atZone(ZONE);
        long intoStep = (wall.getMinute() % MINUTES) * 60L + wall.getSecond();
        if (intoStep == 0 && wall.getNano() == 0) {
            return t;
        }
        return t.minusNanos(wall.getNano()).plusSeconds(STEP_SECONDS - intoStep);
    }

    /**
     * The earliest grid instant {@code g >= from} with {@code [g, g + length)} inside {@code [from, until)} and clear
     * of {@code busy} (a {@link FreeWindows#union}). Each step asks {@link FreeWindows#firstFit} from a grid instant;
     * a free-gap boundary off the grid moves the search to the next grid instant, so it only ever moves forward.
     */
    static Optional<Instant> firstFit(List<Span> busy, Instant from, Instant until, Duration length) {
        Instant t = ceil(from);
        while (true) {
            Optional<Instant> fit = FreeWindows.firstFit(busy, t, until, length);
            if (fit.isEmpty()) {
                return fit;
            }
            Instant onGrid = ceil(fit.get());
            if (onGrid.equals(fit.get())) {
                return fit;
            }
            t = onGrid;
        }
    }
}
