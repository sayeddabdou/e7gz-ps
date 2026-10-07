package ps.e7gz.inventory.availability.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The canonical business date, the revenue-day key every Booking and Tournament stores (arch 04 section 4.3,
 * {@code FR-AVL-14}). Exactly two rules:
 * <ol>
 *   <li>an instant inside one of the Venue's materialized Operating Sessions belongs to that session's
 *       {@code operating_day};</li>
 *   <li>an instant outside every session belongs to its {@link #FALLBACK_ZONE} calendar date.</li>
 * </ol>
 * Never null; no cutoff hour, no third rule, and the weekly opening-hours template is never consulted.
 */
public interface BusinessDates {

    /** The calendar of rule 2. */
    ZoneId FALLBACK_ZONE = ZoneId.of("Africa/Cairo");

    /**
     * The business date of something starting at {@code startAt} at the Venue, for the creating command. Reads on the
     * command's own transaction; no transaction is opened here.
     */
    LocalDate businessDate(CommandContext command, UUID venueId, Instant startAt);

    /** The Venue's business date now, the instant taken from the injected {@code Clock}. */
    LocalDate currentBusinessDate(UUID venueId);

    /**
     * Every instant that belongs to {@code date} at the Venue under the same two rules, for a read outside any command
     * (the Venue Dashboard schedule, FR-VEN-37, E7-112): the date's own Operating Session, if it has one (rule 1), and
     * the instants of its {@link #FALLBACK_ZONE} calendar day that no other day's session covers (rule 2). So the
     * after-midnight part of a session crossing midnight belongs to the session's day, not to the next calendar date.
     */
    BusinessDay businessDay(UUID venueId, LocalDate date);

    /**
     * {@code session} is the date's Operating Session, null when it has none; {@code instants} are the disjoint,
     * ordered periods that belong to the date (never empty: the calendar day is never fully covered by other days).
     */
    record BusinessDay(LocalDate date, Period session, List<Period> instants) {

        public BusinessDay {
            Objects.requireNonNull(date, "date");
            instants = List.copyOf(instants);
        }

        /** No Operating Session on this business date. */
        public boolean closed() {
            return session == null;
        }
    }

    /** A half-open interval {@code [start, end)}. */
    record Period(Instant start, Instant end) {

        public Period {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end must be after start: [" + start + ", " + end + ")");
            }
        }
    }
}
