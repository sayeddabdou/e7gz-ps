package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.SessionSnapshots.Session;
import ps.e7gz.venue.api.VenueEligibility;
import ps.e7gz.venue.api.VenueEligibility.Eligibility;

/**
 * E7-46 - the two Venue reads online booking creation adds (PO decision 9 Oct 2026), against the migrated database on
 * the command's own transaction: {@link VenueEligibility} (FR-BOK-11 conditions 5-7) and {@link SessionSnapshots} (the
 * Operating Session a Booking snapshots, condition 15).
 */
class VenueBookingReadsIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueEligibility eligibility;

    @Autowired
    SessionSnapshots snapshots;

    record TestCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private Optional<Eligibility> eligibility(UUID venueId) {
        return runner.run(new TestCommand<>(ctx -> eligibility.find(ctx, venueId)));
    }

    private Optional<Session> containing(UUID venueId, String at) {
        return runner.run(new TestCommand<>(ctx -> snapshots.containing(ctx, venueId, Instant.parse(at))));
    }

    @Test
    void anActiveVenueWithAnEnabledAccountReportsBothStatesAndItsArea() {
        Fixtures.Venue venue = aVenue().active().create();
        aVenueAccount().forVenue(venue).create();

        assertThat(eligibility(venue.id())).contains(
                new Eligibility(venue.id(), venue.areaId(), MarketplaceStatus.ACTIVE, VenueAccountStatus.ENABLED));
    }

    @Test
    void aDisabledAccountAndANonActiveMarketplaceStatusAreReportedAsTheyAre() {
        Fixtures.Venue venue = aVenue().suspended().create();
        aVenueAccount().forVenue(venue).disabled().create();

        Eligibility found = eligibility(venue.id()).orElseThrow();
        assertThat(found.marketplaceStatus()).isEqualTo(MarketplaceStatus.SUSPENDED);
        assertThat(found.accountStatus()).isEqualTo(VenueAccountStatus.DISABLED);
    }

    @Test
    void aVenueWithoutAnAccountHasNoAccountStatus() {
        Fixtures.Venue venue = aVenue().create();

        Eligibility found = eligibility(venue.id()).orElseThrow();
        assertThat(found.marketplaceStatus()).isEqualTo(MarketplaceStatus.ONBOARDING);
        assertThat(found.accountStatus()).isNull();
    }

    @Test
    void anUnknownVenueHasNoEligibility() {
        assertThat(eligibility(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theSessionHoldingAnInstantComesWithEverySnapshotColumnHalfOpen() {
        Fixtures.Venue venue = aVenue().create();
        // Cairo is UTC+2 in November (no DST): [16:00Z, 00:00Z)
        Fixtures.OperatingSession session = anOperatingSession().forVenue(venue)
                .on("2030-11-05", "2030-11-05T18:00", "2030-11-06T02:00").create();

        Session found = containing(venue.id(), "2030-11-05T16:00:00Z").orElseThrow();
        assertThat(found).isEqualTo(new Session(session.id(), LocalDate.parse("2030-11-05"),
                Instant.parse("2030-11-05T16:00:00Z"), Instant.parse("2030-11-06T00:00:00Z"),
                LocalDateTime.parse("2030-11-05T18:00"), LocalDateTime.parse("2030-11-06T02:00"), "Africa/Cairo"));
        assertThat(containing(venue.id(), "2030-11-05T23:59:59Z")).isPresent();
        assertThat(containing(venue.id(), "2030-11-06T00:00:00Z")).as("the end is outside").isEmpty();
        assertThat(containing(venue.id(), "2030-11-05T15:59:59Z")).isEmpty();
    }

    @Test
    void anotherVenuesSessionIsNeverReturned() {
        Fixtures.Venue venue = aVenue().create();
        Fixtures.Venue other = aVenue().create();
        anOperatingSession().forVenue(other).on("2030-11-05", "2030-11-05T18:00", "2030-11-06T02:00").create();

        assertThat(containing(venue.id(), "2030-11-05T17:00:00Z")).isEmpty();
    }
}
