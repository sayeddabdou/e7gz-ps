package ps.e7gz.inventory.availability.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.codes.ScheduleDisplayState;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.inventory.availability.api.BusinessDates.BusinessDay;
import ps.e7gz.inventory.availability.api.BusinessDates.Period;
import ps.e7gz.inventory.availability.api.ScheduleBookings;
import ps.e7gz.inventory.availability.internal.FreeWindows.Span;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.ledger.api.ScheduledReservations;
import ps.e7gz.operations.api.ScheduledOperations;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.resource.api.ResourceCatalog.Scheduled;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The Venue Dashboard availability schedule (FR-VEN-37, arch 09 section 4.1, E7-112): one business date of the
 * signed-in venue, each non-ARCHIVED Resource with its spans. A read, never a command, and never an availability
 * answer: it shows the owner modules' rows and does not ask the canonical predicate.
 *
 * <ul>
 *   <li>The business date is the requested one, else {@link BusinessDates#currentBusinessDate} (arch 04 section
 *       4.3).</li>
 *   <li>No Operating Session on it: {@code closed}, every Resource with no slots, and nothing else is read (PO decision
 *       3 Oct 2026).</li>
 *   <li>Source spans are read over the instants that belong to the date ({@link BusinessDates#businessDay}) and clipped
 *       to them, so a span outside the session (a 03:00 manual booking) is shown and widens the range.</li>
 *   <li>Overlapping source spans are all kept: nothing hides, splits or merges another.</li>
 *   <li>{@code FREE} is the session minus the union of the Resource's spans, and only on an ACTIVE-base-state
 *       Resource: a DISABLED one gets no FREE (PO decision 3 Oct 2026).</li>
 * </ul>
 */
@Component
public class VenueSchedules {

    private static final Comparator<Slot> ORDER = Comparator.comparing(Slot::startAt)
            .thenComparing(Slot::endAt).thenComparing(Slot::state);

    private final VenueAccountVenues accounts;
    private final BusinessDates businessDates;
    private final ResourceCatalog resources;
    private final ScheduledReservations reservations;
    private final ScheduledOperations operations;
    private final ScheduleBookings bookings;

    VenueSchedules(VenueAccountVenues accounts, BusinessDates businessDates, ResourceCatalog resources,
                   ScheduledReservations reservations, ScheduledOperations operations, ScheduleBookings bookings) {
        this.accounts = accounts;
        this.businessDates = businessDates;
        this.resources = resources;
        this.reservations = reservations;
        this.operations = operations;
        this.bookings = bookings;
    }

    /** One business date of the venue {@code venueAccountId} signs in for; {@code date} null is today's. */
    public Schedule of(UUID venueAccountId, LocalDate date) {
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        UUID venueId = accounts.venueOf(venueAccountId);
        LocalDate businessDate = date != null ? date : businessDates.currentBusinessDate(venueId);
        List<Scheduled> shown = resources.onSchedule(venueId);
        BusinessDay day = businessDates.businessDay(venueId, businessDate);
        if (day.closed() || shown.isEmpty()) {
            return new Schedule(businessDate, day.closed(),
                    shown.stream().map(r -> new ResourceSchedule(r, List.of())).toList());
        }

        List<UUID> ids = shown.stream().map(Scheduled::resourceId).toList();
        Instant from = day.instants().getFirst().start();
        Instant to = day.instants().getLast().end();
        List<ScheduledReservations.Row> rows = reservations.active(ids, from, to);
        List<ScheduledOperations.Interval> intervals = operations.overlapping(venueId, ids, from, to);
        Map<UUID, ScheduleBookings.Facts> facts = bookings.facts(rows.stream()
                .filter(row -> row.owner() instanceof ReservationOwner.Booking)
                .map(row -> row.owner().id()).distinct().toList());

        Map<UUID, List<Slot>> sources = new LinkedHashMap<>();
        ids.forEach(id -> sources.put(id, new ArrayList<>()));
        for (ScheduledReservations.Row row : rows) {
            UUID bookingId = row.owner() instanceof ReservationOwner.Booking
                    || row.owner() instanceof ReservationOwner.LateConfirmHold ? row.owner().id() : null;
            addClipped(sources.get(row.resourceId()), day, row.plannedStart(), row.plannedEnd(),
                    stateOf(row, facts), bookingId);
        }
        for (ScheduledOperations.Interval interval : intervals) {
            ScheduleDisplayState state = interval.kind() == ScheduledOperations.Kind.MAINTENANCE
                    ? ScheduleDisplayState.MAINTENANCE : ScheduleDisplayState.CLOSURE;
            List<UUID> on = interval.resourceId() == null ? ids : List.of(interval.resourceId());
            for (UUID id : on) {
                List<Slot> target = sources.get(id);
                if (target != null) {
                    addClipped(target, day, interval.start(), interval.end(), state, null);
                }
            }
        }

        Span session = new Span(day.session().start(), day.session().end());
        List<ResourceSchedule> schedule = new ArrayList<>();
        for (Scheduled resource : shown) {
            List<Slot> slots = sources.get(resource.resourceId());
            if (resource.baseState() == ResourceBaseState.ACTIVE) {
                List<Span> busy = FreeWindows.union(slots.stream().map(s -> new Span(s.startAt(), s.endAt())).toList());
                FreeWindows.minus(List.of(session), busy).forEach(free ->
                        slots.add(new Slot(free.start(), free.end(), ScheduleDisplayState.FREE, null)));
            }
            slots.sort(ORDER);
            schedule.add(new ResourceSchedule(resource, List.copyOf(slots)));
        }
        return new Schedule(businessDate, false, schedule);
    }

    /**
     * BOOKING: MANUAL by source, else the Booking's PENDING / CONFIRMED / ACTIVE status; LATE_CONFIRM_HOLD: AWAITING
     * by the row type; the Tournament holds: TOURNAMENT_HOLD (E7-112). Any other active BOOKING is a broken invariant
     * (an active row holds only a blocking Booking, arch 05 section 2.3), never mapped or dropped.
     */
    static ScheduleDisplayState stateOf(ScheduledReservations.Row row, Map<UUID, ScheduleBookings.Facts> facts) {
        return switch (row.owner()) {
            case ReservationOwner.Booking booking -> {
                ScheduleBookings.Facts fact = facts.get(booking.id());
                if (fact == null) {
                    throw new IllegalStateException("active BOOKING row " + row.reservationId()
                            + " has no Booking " + booking.id());
                }
                if (fact.source() == BookingSource.MANUAL) {
                    yield ScheduleDisplayState.MANUAL;
                }
                yield switch (fact.status()) {
                    case PENDING -> ScheduleDisplayState.PENDING;
                    case CONFIRMED -> ScheduleDisplayState.CONFIRMED;
                    case ACTIVE -> ScheduleDisplayState.ACTIVE;
                    default -> throw new IllegalStateException("active BOOKING row " + row.reservationId()
                            + " for a " + fact.status() + " Booking " + booking.id());
                };
            }
            case ReservationOwner.LateConfirmHold hold -> ScheduleDisplayState.AWAITING_CUSTOMER_CONFIRMATION;
            case ReservationOwner.TournamentApprovalHold hold -> ScheduleDisplayState.TOURNAMENT_HOLD;
            case ReservationOwner.TournamentHold hold -> ScheduleDisplayState.TOURNAMENT_HOLD;
            case ReservationOwner.Maintenance m -> throw new IllegalStateException("not a schedule row: " + m);
            case ReservationOwner.Closure c -> throw new IllegalStateException("not a schedule row: " + c);
        };
    }

    /** Only the parts of {@code [start, end)} that belong to the business date. */
    private static void addClipped(List<Slot> target, BusinessDay day, Instant start, Instant end,
                                   ScheduleDisplayState state, UUID bookingId) {
        for (Period part : day.instants()) {
            Instant from = start.isAfter(part.start()) ? start : part.start();
            Instant to = end.isBefore(part.end()) ? end : part.end();
            if (to.isAfter(from)) {
                target.add(new Slot(from, to, state, bookingId));
            }
        }
    }

    public record Schedule(LocalDate businessDate, boolean closed, List<ResourceSchedule> resources) {
    }

    public record ResourceSchedule(Scheduled resource, List<Slot> slots) {
    }

    /** One span {@code [startAt, endAt)}; {@code bookingId} only for BOOKING and LATE_CONFIRM_HOLD spans. */
    public record Slot(Instant startAt, Instant endAt, ScheduleDisplayState state, UUID bookingId) {
    }
}
