package ps.e7gz.inventory.availability.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;
import ps.e7gz.venue.api.VenueDiscovery;

/**
 * Find Available (FR-DIS-12, F-03, arch 05 section 5.4): the area's marketplace-ACTIVE venues offering the activity, each
 * with its Resources that are free for the <em>whole</em> requested interval - evaluated live by the canonical
 * predicate, one {@link AvailabilityQuery#isFree} call per candidate Resource, never from the Nearest Availability
 * projection (a snapshot, arch 05 section 5.3). There is no price filter (FR-DIS-12).
 *
 * <p>PO decisions 8 Oct 2026 (Sprint 1 review):
 * <ul>
 *   <li>the interval must also lie inside one materialized Operating Session of the venue (ADR-009): the predicate does
 *       not read opening hours, and a room of a closed venue is not available;</li>
 *   <li>the duration follows FR-BOK-01 - 60 to 360 minutes in 30-minute steps, otherwise VALIDATION_FAILED on
 *       {@code durationMinutes} - and a start less than 15 minutes ahead (FR-BOK-02), or in the past, finds nothing.</li>
 * </ul>
 * The date and start time are Africa/Cairo wall-clock time (SPRINT-1-CONTRACT): a time the clock skips at a DST change
 * is VALIDATION_FAILED on {@code startTime}; a time it repeats is its first occurrence.
 *
 * <p>Candidate Resources are the ones customers see on Venue Details ({@link ResourceCatalog#customerVisible}):
 * DISABLED and ARCHIVED ones are left out there and could never be free here (check 3).
 */
@Service
public class FindAvailable {

    /** FR-BOK-01: the shortest, longest and step of an online booking's duration. */
    static final int MIN_DURATION_MINUTES = 60;
    static final int MAX_DURATION_MINUTES = 360;
    static final int DURATION_STEP_MINUTES = 30;

    /** The request's wall-clock time zone (SPRINT-1-CONTRACT: local date and time, Africa/Cairo). */
    static final ZoneId ZONE = ZoneId.of("Africa/Cairo");

    private final CommandRunner runner;
    private final VenueDiscovery venues;
    private final ResourceCatalog resources;
    private final OperatingSessions sessions;
    private final AvailabilityQuery availability;

    FindAvailable(CommandRunner runner, VenueDiscovery venues, ResourceCatalog resources, OperatingSessions sessions,
                  AvailabilityQuery availability) {
        this.runner = runner;
        this.venues = venues;
        this.resources = resources;
        this.sessions = sessions;
        this.availability = availability;
    }

    /** A venue and its Resources free for the whole interval, by name then id. */
    public record Result(UUID venueId, String venueName, List<ResourceCatalog.Shown> resources) {
    }

    /** Venues by name then id; a venue with no free Resource is left out. */
    public List<Result> search(UUID areaId, Activity activity, LocalDate date, LocalTime startTime,
                               int durationMinutes) {
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(startTime, "startTime");
        if (durationMinutes < MIN_DURATION_MINUTES || durationMinutes > MAX_DURATION_MINUTES
                || durationMinutes % DURATION_STEP_MINUTES != 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "durationMinutes");
        }
        LocalDateTime local = LocalDateTime.of(date, startTime);
        if (ZONE.getRules().getValidOffsets(local).isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "startTime");
        }
        Instant start = ZonedDateTime.of(local, ZONE).toInstant();
        AvailabilityPeriod period = new AvailabilityPeriod(start, start.plus(Duration.ofMinutes(durationMinutes)));

        List<Candidate> candidates = new ArrayList<>();
        for (VenueDiscovery.Listed venue : venues.inArea(areaId, activity)) {
            List<ResourceCatalog.Shown> shown = resources.customerVisible(venue.venueId(), activity);
            if (!shown.isEmpty()) {
                candidates.add(new Candidate(venue, shown));
            }
        }
        return candidates.isEmpty() ? List.of() : runner.run(new Search(candidates, period));
    }

    /** {@code period} lies inside one of the sessions (half-open: it may end exactly at the session's end). */
    static boolean insideOneSession(List<SessionPeriod> sessions, AvailabilityPeriod period) {
        return sessions.stream().anyMatch(session -> !session.start().isAfter(period.start())
                && !period.end().isAfter(session.end()));
    }

    private record Candidate(VenueDiscovery.Listed venue, List<ResourceCatalog.Shown> shown) {
    }

    /**
     * Reads only: every predicate call on one transaction and one command instant, the {@code now} of the 15-minute rule
     * and of checks 1-2 (arch 08 section 8: the database clock). It locks nothing and writes nothing. Category A: the
     * customer's marketplace search for a new booking.
     */
    private final class Search implements Command<List<Result>> {

        private final List<Candidate> candidates;
        private final AvailabilityPeriod period;

        Search(List<Candidate> candidates, AvailabilityPeriod period) {
            this.candidates = candidates;
            this.period = period;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public List<Result> execute(CommandContext context) {
            Instant now = context.commandNow();
            if (period.start().isBefore(now.plus(NearestAvailabilityCalculator.LEAD))) {
                return List.of();                               // FR-BOK-02: nothing could be booked
            }
            List<Result> found = new ArrayList<>();
            for (Candidate candidate : candidates) {
                UUID venueId = candidate.venue().venueId();
                if (!insideOneSession(sessions.endingAfter(context, venueId, period.start()), period)) {
                    continue;
                }
                List<ResourceCatalog.Shown> free = candidate.shown().stream()
                        .filter(resource -> availability.isFree(context, resource.resourceId(), period, now).isFree())
                        .toList();
                if (!free.isEmpty()) {
                    found.add(new Result(venueId, candidate.venue().name(), free));
                }
            }
            return List.copyOf(found);
        }
    }
}
