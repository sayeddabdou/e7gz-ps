package ps.e7gz.inventory.availability.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.inventory.availability.internal.NearestAvailabilityCalculator.Result;
import ps.e7gz.venue.api.VenueActivities;

/**
 * Refresh and rebuild of {@code inventory.venue_activity_availability} (arch 05 section 5.3, arch 08 section 7). Each
 * pair is one command: the database samples the command instant, which is both the {@code now} of the calculation and
 * {@code refreshed_at} (arch 08 section 8: workers use the database clock, never the JVM's).
 */
@Component
class NearestAvailabilityProjectionService implements NearestAvailabilityProjection {

    private static final Logger log = LoggerFactory.getLogger(NearestAvailabilityProjectionService.class);

    private final CommandRunner runner;
    private final NearestAvailabilityCalculator calculator;
    private final JdbcAvailabilityProjectionStore store;
    private final VenueActivities venueActivities;

    NearestAvailabilityProjectionService(CommandRunner runner, NearestAvailabilityCalculator calculator,
                                         JdbcAvailabilityProjectionStore store, VenueActivities venueActivities) {
        this.runner = runner;
        this.calculator = calculator;
        this.store = store;
        this.venueActivities = venueActivities;
    }

    @Override
    public Optional<Row> refresh(UUID venueId, Activity activity) {
        return runner.run(new Refresh(Objects.requireNonNull(venueId, "venueId"),
                Objects.requireNonNull(activity, "activity")));
    }

    /**
     * Every pair of {@code venue.venue_activity}. A projection row can only belong to one of them (its foreign key), so
     * refreshing every pair also removes every row left without a Resource.
     */
    @Override
    public RebuildReport rebuild() {
        int pairs = 0;
        int written = 0;
        for (VenueActivities.Offering offering : venueActivities.all()) {
            pairs++;
            if (refresh(offering.venueId(), offering.activity()).isPresent()) {
                written++;
            }
        }
        RebuildReport report = new RebuildReport(pairs, written, pairs - written);
        log.info("Nearest Availability rebuilt: {} pairs, {} rows written, {} without a Resource", report.pairs(),
                report.written(), report.withoutResources());
        return report;
    }

    /** What one worker pass did: pairs looked at, and how many of them failed and were skipped. */
    record RefreshPass(int pairs, int failed) {
    }

    /**
     * The worker's periodic pass ({@link NearestAvailabilityJobs}): every pair, each in its own command, as
     * {@link #rebuild()} does - but a pair that fails is logged and skipped, so one bad pair never stops the others
     * and the next pass tries it again.
     */
    RefreshPass refreshEveryPair() {
        int pairs = 0;
        int failed = 0;
        for (VenueActivities.Offering offering : venueActivities.all()) {
            pairs++;
            try {
                refresh(offering.venueId(), offering.activity());
            } catch (RuntimeException e) {
                failed++;
                log.warn("Nearest Availability refresh failed for venue {} / {}", offering.venueId(),
                        offering.activity(), e);
            }
        }
        return new RefreshPass(pairs, failed);
    }

    /**
     * Reads only, then writes the derived row; it locks no business row. Category C: a technical refresh, not a
     * marketplace or commitment action.
     */
    private final class Refresh implements Command<Optional<Row>> {

        private final UUID venueId;
        private final Activity activity;

        Refresh(UUID venueId, Activity activity) {
            this.venueId = venueId;
            this.activity = activity;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Optional<Row> execute(CommandContext context) {
            Instant now = context.commandNow();
            Optional<Result> result = calculator.calculate(context, venueId, activity, now);
            if (result.isEmpty()) {
                store.remove(context, venueId, activity, now);       // PO decision 7 Oct 2026: no Resource, no row
                return Optional.empty();
            }
            store.write(context, venueId, activity, result.get().nearestAvailableAt(),
                    result.get().startingPrice(), now);
            return store.find(context, venueId, activity);
        }
    }
}
