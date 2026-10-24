package ps.e7gz.inventory.availability.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.inventory.availability.internal.FreeWindows.Span;
import ps.e7gz.inventory.ledger.api.OccupiedRanges;
import ps.e7gz.operations.api.DeclaredEmergencyIntervals;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;

/**
 * The one Nearest Availability calculation (arch 05 section 5.2, {@code FR-DIS-04}, {@code FR-DIS-05}) and the
 * starting price (PO decision 7 Oct 2026), from the authoritative state, on the caller's command transaction. It holds
 * no SQL and no availability rule of its own:
 * <ul>
 *   <li>which Resources may contribute, and whether the Venue is selling-blocked, is the canonical
 *       {@link AvailabilityQuery}'s answer (checks 1-4 are state lookups that do not depend on the period, arch 05
 *       section 2.1);</li>
 *   <li>candidate starts come from free-gap boundaries ({@link FreeWindows}) over the ledger's merged occupied ranges
 *       ({@code range_agg}) and the declared Emergency intervals of check 5 - never a minute-by-minute scan - rounded
 *       up to the 15-minute Cairo wall-time {@link StartGrid} (PO decision 12 Oct 2026, E7-115);</li>
 *   <li>every candidate is accepted only when {@link AvailabilityQuery#isFree} says FREE.</li>
 * </ul>
 */
@Component
class NearestAvailabilityCalculator {

    /** {@code FR-BOK-02}: a requested start is at least 15 minutes ahead. */
    static final Duration LEAD = Duration.ofMinutes(15);

    /** {@code FR-DIS-04}: Nearest Availability is for the 1-hour minimum duration. */
    static final Duration MINIMUM = Duration.ofMinutes(60);

    /**
     * The PlayStation price variants the starting price compares (PO decision 7 Oct 2026), named explicitly so that a
     * new {@link PlayMode} never changes the projection by itself.
     */
    static final List<PlayMode> PLAYSTATION_PRICE_VARIANTS = List.of(PlayMode.SINGLE, PlayMode.MULTIPLAYER);

    private static final Logger log = LoggerFactory.getLogger(NearestAvailabilityCalculator.class);

    private final ResourceCatalog catalog;
    private final AvailabilityQuery availability;
    private final OperatingSessions sessions;
    private final OccupiedRanges occupied;
    private final DeclaredEmergencyIntervals emergencies;
    private final HourlyPrices prices;

    NearestAvailabilityCalculator(ResourceCatalog catalog, AvailabilityQuery availability, OperatingSessions sessions,
                                  OccupiedRanges occupied, DeclaredEmergencyIntervals emergencies, HourlyPrices prices) {
        this.catalog = catalog;
        this.availability = availability;
        this.sessions = sessions;
        this.occupied = occupied;
        this.emergencies = emergencies;
        this.prices = prices;
    }

    /** The projected values; both absent when nothing is sellable. */
    record Result(Instant nearestAvailableAt, Money startingPrice) {

        static final Result NONE = new Result(null, null);
    }

    /**
     * The pair's projection as of {@code now} - the command instant in production, so every replica and the guards
     * share the database clock (arch 08 section 8). Empty when the pair has no Resource at all.
     */
    Optional<Result> calculate(CommandContext command, UUID venueId, Activity activity, Instant now) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(now, "now");
        List<UUID> resources = catalog.ofVenue(command, venueId).stream()
                .filter(entry -> entry.activity() == activity)
                .map(ResourceCatalog.Entry::resourceId)
                .toList();
        if (resources.isEmpty()) {
            return Optional.empty();
        }
        Instant lead = now.plus(LEAD);
        Optional<List<UUID>> eligible = eligible(command, resources, lead, now);
        if (eligible.isEmpty() || eligible.get().isEmpty()) {
            return Optional.of(Result.NONE);              // a selling-blocked Venue, or no Resource may contribute
        }
        return Optional.of(new Result(nearest(command, venueId, eligible.get(), lead, now),
                startingPrice(command, activity, eligible.get())));
    }

    /**
     * The Resources that may contribute ({@code FR-DIS-05}), by the canonical predicate's own answer: a Resource whose
     * first failing check is 2-4 (selling block, base state, owner review) is out; any other answer leaves it in. Checks
     * 1-4 read states at {@code now} and ignore the period, so the probe period changes nothing for them. Empty when
     * check 1 fails: the Venue is selling-blocked and nothing contributes.
     */
    private Optional<List<UUID>> eligible(CommandContext command, List<UUID> resources, Instant lead, Instant now) {
        AvailabilityPeriod probe = new AvailabilityPeriod(lead, lead.plus(MINIMUM));
        List<UUID> eligible = new ArrayList<>();
        for (UUID resource : resources) {
            AvailabilityCheck failed = availability.isFree(command, resource, probe, now).failingCheck().orElse(null);
            if (failed == null) {
                eligible.add(resource);
                continue;
            }
            switch (failed) {
                case VENUE_SELLING_BLOCK -> {
                    return Optional.empty();
                }
                case RESOURCE_SELLING_BLOCK, RESOURCE_BASE_STATE, RESOURCE_OWNER_REVIEW -> {
                    // never contributes (FR-DIS-05)
                }
                case EMERGENCY_INTERVAL, MAINTENANCE, CLOSURE, BLOCKING_RESERVATION -> eligible.add(resource);
            }
        }
        return Optional.of(eligible);
    }

    /**
     * arch 05 section 5.2: session by session from {@code max(now + 15 min, session start)} rounded up to the
     * {@link StartGrid}, the earliest grid start of a 60-minute window inside the session that any eligible Resource
     * has free; the first session with one wins. {@code null} when no materialized session has one.
     */
    private Instant nearest(CommandContext command, UUID venueId, List<UUID> resources, Instant lead, Instant now) {
        List<SessionPeriod> upcoming = sessions.endingAfter(command, venueId, lead).stream()
                .filter(session -> !firstStart(lead, session).plus(MINIMUM).isAfter(session.end()))
                .toList();
        if (upcoming.isEmpty()) {
            return null;
        }
        Instant from = firstStart(lead, upcoming.get(0));
        Instant to = upcoming.get(upcoming.size() - 1).end();
        Map<UUID, List<Span>> busy = busy(command, venueId, resources, from, to);
        for (SessionPeriod session : upcoming) {
            Instant earliest = null;
            for (UUID resource : resources) {
                Optional<Instant> found = earliestIn(command, session, resource, busy.get(resource), lead, now);
                if (found.isPresent() && (earliest == null || found.get().isBefore(earliest))) {
                    earliest = found.get();
                }
            }
            if (earliest != null) {
                return earliest;
            }
        }
        return null;
    }

    /** Per Resource: its merged ledger ranges and the declared Emergency intervals check 5 counts against it. */
    private Map<UUID, List<Span>> busy(CommandContext command, UUID venueId, List<UUID> resources, Instant from,
                                      Instant to) {
        Map<UUID, List<Span>> spans = new HashMap<>();
        resources.forEach(resource -> spans.put(resource, new ArrayList<>()));
        occupied.occupied(command, resources, from, to).forEach(range ->
                spans.get(range.resourceId()).add(new Span(range.start(), range.end())));
        for (UUID resource : resources) {
            emergencies.blocking(command, venueId, resource, from, to).forEach(interval ->
                    spans.get(resource).add(new Span(interval.start(), interval.end())));
        }
        Map<UUID, List<Span>> merged = new HashMap<>();
        spans.forEach((resource, list) -> merged.put(resource, FreeWindows.union(list)));
        return merged;
    }

    /**
     * The first free-gap candidate on the {@link StartGrid} of the Resource in the session, if the canonical predicate
     * confirms it.
     *
     * <p>A refusal here is a defensive path only: Maintenance and Closures always have their ledger guard rows (arch 05
     * section 3.2) and Emergency intervals are explicit boundaries, so the boundaries normally match the predicate. A
     * refusal means the state changed concurrently or is inconsistent. The Resource is then left out of this session -
     * no retry, no scan - which may under-report it but never publishes a start the predicate refused.
     */
    private Optional<Instant> earliestIn(CommandContext command, SessionPeriod session, UUID resource, List<Span> busy,
                                         Instant lead, Instant now) {
        Optional<Instant> candidate = StartGrid.firstFit(busy, latest(lead, session.start()), session.end(), MINIMUM);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Instant start = candidate.get();
        PredicateResult result = availability.isFree(command, resource, new AvailabilityPeriod(start,
                start.plus(MINIMUM)), now);
        if (result.isFree()) {
            return candidate;
        }
        log.debug("Nearest Availability candidate {} on Resource {} refused by {}", start, resource, result);
        return Optional.empty();
    }

    /**
     * The minimum hourly price the canonical {@link HourlyPrices} resolves over the eligible Resources and, for
     * PlayStation, {@link #PLAYSTATION_PRICE_VARIANTS}; unresolvable combinations are skipped. {@code null} when none
     * resolves.
     */
    private Money startingPrice(CommandContext command, Activity activity, List<UUID> resources) {
        List<PlayMode> modes = new ArrayList<>();
        if (activity == Activity.PLAYSTATION) {
            modes.addAll(PLAYSTATION_PRICE_VARIANTS);
        } else {
            modes.add(null);                              // Billiards and Ping Pong: the venue rate, no play mode
        }
        Money lowest = null;
        for (UUID resource : resources) {
            for (PlayMode mode : modes) {
                Optional<Money> price = prices.resolve(command, resource, mode);
                if (price.isPresent() && (lowest == null || price.get().compareTo(lowest) < 0)) {
                    lowest = price.get();
                }
            }
        }
        return lowest;
    }

    /** The session's first possible grid start: {@code max(now + 15 min, session start)} rounded up to the grid. */
    private static Instant firstStart(Instant lead, SessionPeriod session) {
        return StartGrid.ceil(latest(lead, session.start()));
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
