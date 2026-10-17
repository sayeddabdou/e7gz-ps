package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ps.e7gz.booking.internal.ManualCreationPredicate.Approval;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.CommandInstants;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.SessionSnapshots.Session;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * Roadmap Phase 2 exit (O4, EC-AVAI-005, FR-MAN-09): a manual booking outside opening hours carries the business date it
 * belongs to. Enabled by E7-48 (E7-45 pending pack). The comment E7-45 wrote here said the 03:00 walk-in's business date
 * is "the date the 14:00 session opened on" - wrong: PO decision 10 Oct 2026, E7-48 (D1) - a booking outside every
 * session has no snapshot and its business date is the Africa/Cairo calendar date of startAt, the Saturday, as
 * {@code tr_20_booking_snapshot_integrity} also requires.
 *
 * <p>The instant is exact (D3: a controlled command clock, {@link CommandInstants}); the reads are mocked to one venue
 * whose Friday session runs 14:00-02:00 Africa/Cairo. The same rules against the real database are in
 * {@code ManualBookingIntegrationTest}.
 */
class ManualBookingBusinessDateBoundaryTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate SATURDAY = FRIDAY.plusDays(1);
    private static final Instant OPENS = cairo(FRIDAY, 14, 0);
    private static final Instant CLOSES = cairo(SATURDAY, 2, 0);
    private static final UUID VENUE = UUID.randomUUID();
    private static final ResourceCatalog.Entry TABLE =
            new ResourceCatalog.Entry(UUID.randomUUID(), VENUE, Activity.BILLIARDS, null);
    private static final Session SESSION = new Session(UUID.randomUUID(), FRIDAY, OPENS, CLOSES,
            LocalDateTime.of(FRIDAY, LocalTime.of(14, 0)),
            LocalDateTime.of(SATURDAY, LocalTime.of(2, 0)), "Africa/Cairo");

    private final VenueEligibility venues = mock(VenueEligibility.class);
    private final AvailabilityQuery availability = mock(AvailabilityQuery.class);
    private final SessionSnapshots snapshots = mock(SessionSnapshots.class);
    private final HourlyPrices prices = mock(HourlyPrices.class);
    private final ResourceCatalog resources = mock(ResourceCatalog.class);
    private final ManualCreationPredicate predicate =
            new ManualCreationPredicate(venues, availability, snapshots, prices, resources);

    @BeforeEach
    void venueWithAFridaySessionClosingAtTwo() {
        when(venues.find(any(), eq(VENUE))).thenReturn(Optional.of(new VenueEligibility.Eligibility(VENUE,
                UUID.randomUUID(), MarketplaceStatus.ACTIVE, VenueAccountStatus.ENABLED)));
        when(availability.isFree(any(), eq(TABLE.resourceId()), any(), any())).thenReturn(PredicateResult.free());
        when(prices.resolve(any(), eq(TABLE.resourceId()), eq(null))).thenReturn(Optional.of(Money.of("100.00")));
        when(resources.name(any(), eq(TABLE.resourceId()))).thenReturn(Optional.of("Table 1"));
        // [14:00 Friday, 02:00 Saturday): the session containing an instant, as the venue module answers
        when(snapshots.containing(any(), eq(VENUE), any())).thenAnswer(call -> {
            Instant at = call.getArgument(2);
            return !at.isBefore(OPENS) && at.isBefore(CLOSES) ? Optional.of(SESSION) : Optional.empty();
        });
    }

    @Test
    void aManualBookingAtThreeAmForAVenueClosingAtTwoAmSucceedsWithABusinessDate() {
        // A walk-in at 03:00 Saturday, an hour after the 14:00-02:00 session closed: ACTIVE at the command instant
        Instant threeAm = cairo(SATURDAY, 3, 0);

        Approval approval = predicate.check(CommandInstants.at(threeAm), walkIn(60), TABLE);

        assertThat(approval.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(approval.startAt()).isEqualTo(threeAm);
        assertThat(approval.session()).as("no session snapshot outside published hours").isNull();
        assertThat(approval.businessDate()).as("the Cairo date of startAt, not the day the session opened on")
                .isEqualTo(SATURDAY);
    }

    /** D2: 01:00-03:00 starts inside the session but ends after it - no snapshot, the Saturday. */
    @Test
    void aBookingStraddlingTheSessionEndHasNoSnapshotAndTheSaturday() {
        Approval approval = predicate.check(CommandInstants.at(cairo(FRIDAY, 23, 0)),
                future(cairo(SATURDAY, 1, 0), 120), TABLE);

        assertThat(approval.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(approval.session()).isNull();
        assertThat(approval.businessDate()).isEqualTo(SATURDAY);
    }

    /** To the second: ending at or before 02:00 fits the session (its operating day, Friday); one second later does not. */
    @Test
    void theSnapshotHoldsExactlyWhileTheWholeIntervalFits() {
        for (Boundary side : Boundary.values()) {
            // a 60-minute booking whose end is one second before, at or one second after the session end
            Instant start = side.now(CLOSES).minusSeconds(3600);

            Approval approval = predicate.check(CommandInstants.at(cairo(FRIDAY, 22, 0)), future(start, 60), TABLE);

            if (side == Boundary.ONE_SECOND_AFTER) {
                assertThat(approval.session()).as(side.name()).isNull();
                assertThat(approval.businessDate()).as(side.name()).isEqualTo(SATURDAY);
            } else {
                assertThat(approval.session()).as(side.name()).isEqualTo(SESSION);
                assertThat(approval.businessDate()).as(side.name()).isEqualTo(FRIDAY);
            }
        }
    }

    /** Inside the session after midnight: the operating day, Friday - the snapshot's own business date. */
    @Test
    void aWalkInAfterMidnightInsideTheSessionBelongsToItsOperatingDay() {
        Approval approval = predicate.check(CommandInstants.at(cairo(SATURDAY, 0, 30)), walkIn(90), TABLE);

        assertThat(approval.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(approval.session()).isEqualTo(SESSION);
        assertThat(approval.businessDate()).isEqualTo(FRIDAY);
    }

    private static ManualBookingRequest walkIn(int minutes) {
        return ManualBookingRequest.of(TABLE.resourceId(), null, null, minutes, null, null);
    }

    private static ManualBookingRequest future(Instant startAt, int minutes) {
        return ManualBookingRequest.of(TABLE.resourceId(), null, startAt, minutes, null, null);
    }

    private static Instant cairo(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date.atTime(hour, minute), CAIRO).toInstant();
    }
}
