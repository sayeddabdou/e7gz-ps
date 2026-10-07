package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.inventory.availability.internal.NearestAvailabilityCalculator.Result;
import ps.e7gz.inventory.ledger.api.OccupiedRanges;
import ps.e7gz.inventory.ledger.api.OccupiedRanges.Occupied;
import ps.e7gz.operations.api.DeclaredEmergencyIntervals;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;

/**
 * E7-33 - the calculation over faked owner reads. The canonical predicate is a fake that answers per Resource and
 * period, so these tests show the calculation takes every eligibility and acceptance decision from it.
 */
class NearestAvailabilityCalculatorTest {

    private static final UUID VENUE = UUID.fromString("e7330000-0000-4000-8000-0000000000aa");
    private static final UUID ROOM_1 = UUID.fromString("e7330000-0000-4000-8000-000000000001");
    private static final UUID ROOM_2 = UUID.fromString("e7330000-0000-4000-8000-000000000002");
    private static final UUID TABLE = UUID.fromString("e7330000-0000-4000-8000-000000000003");

    private final ResourceCatalog catalog = mock(ResourceCatalog.class);
    private final OperatingSessions sessions = mock(OperatingSessions.class);
    private final OccupiedRanges occupied = mock(OccupiedRanges.class);
    private final DeclaredEmergencyIntervals emergencies = mock(DeclaredEmergencyIntervals.class);
    private final HourlyPrices prices = mock(HourlyPrices.class);
    private final CommandContext command = mock(CommandContext.class);
    private final FakePredicate predicate = new FakePredicate();

    private final NearestAvailabilityCalculator calculator =
            new NearestAvailabilityCalculator(catalog, predicate, sessions, occupied, emergencies, prices);

    /** Answers per Resource: a fixed state failure (checks 1-4), or refusals of given candidate starts. */
    static final class FakePredicate implements AvailabilityQuery {

        final Map<UUID, AvailabilityCheck> stateFailure = new HashMap<>();
        final Map<UUID, List<Instant>> refusedStarts = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public PredicateResult isFree(CommandContext command, UUID resourceId, AvailabilityPeriod period, Instant at) {
            asked.add(resourceId + "@" + period.start());
            AvailabilityCheck failure = stateFailure.get(resourceId);
            if (failure != null) {
                return PredicateResult.notFree(failure);
            }
            if (refusedStarts.getOrDefault(resourceId, List.of()).contains(period.start())) {
                return PredicateResult.notFree(AvailabilityCheck.BLOCKING_RESERVATION);
            }
            return PredicateResult.free();
        }
    }

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    private static final Instant NOW = at("2031-02-03T11:00:00Z");

    @BeforeEach
    void playStationVenueWithOneSession() {
        when(catalog.ofVenue(command, VENUE)).thenReturn(List.of(
                new ResourceCatalog.Entry(ROOM_1, VENUE, Activity.PLAYSTATION, ConsoleType.PS5),
                new ResourceCatalog.Entry(ROOM_2, VENUE, Activity.PLAYSTATION, ConsoleType.PS4),
                new ResourceCatalog.Entry(TABLE, VENUE, Activity.BILLIARDS, null)));
        sessions(new SessionPeriod(LocalDate.parse("2031-02-03"), at("2031-02-03T10:00:00Z"),
                at("2031-02-03T21:00:00Z")));
        when(occupied.occupied(any(), anyCollection(), any(), any())).thenReturn(List.of());
        when(emergencies.blocking(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(prices.resolve(any(), any(), any())).thenReturn(Optional.empty());
    }

    private void sessions(SessionPeriod... periods) {
        when(sessions.endingAfter(any(), eq(VENUE), any())).thenReturn(List.of(periods));
    }

    private Result calculate(Activity activity) {
        return calculator.calculate(command, VENUE, activity, NOW).orElseThrow();
    }

    @Test
    void aPairWithoutResourcesHasNoProjection() {
        assertThat(calculator.calculate(command, VENUE, Activity.PING_PONG, NOW)).isEmpty();
    }

    @Test
    void theLeadGovernsInsideARunningSession() {
        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt()).isEqualTo(NOW.plusSeconds(15 * 60));
    }

    @Test
    void aLaterSessionStartGovernsOverTheLead() {
        sessions(new SessionPeriod(LocalDate.parse("2031-02-04"), at("2031-02-04T10:00:00Z"),
                at("2031-02-04T21:00:00Z")));

        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt()).isEqualTo(at("2031-02-04T10:00:00Z"));
    }

    @Test
    void aSessionWithLessThanSixtyMinutesLeftAfterTheLeadIsSkipped() {
        // now + 15 min = 11:15; the session ends 12:14:59, so the next session's start is the answer.
        sessions(new SessionPeriod(LocalDate.parse("2031-02-03"), at("2031-02-03T10:00:00Z"),
                        at("2031-02-03T12:14:59Z")),
                new SessionPeriod(LocalDate.parse("2031-02-04"), at("2031-02-04T10:00:00Z"),
                        at("2031-02-04T21:00:00Z")));

        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt()).isEqualTo(at("2031-02-04T10:00:00Z"));
    }

    @Test
    void theGloballyEarliestCandidateOfTheEligibleResourcesWins() {
        when(occupied.occupied(any(), anyCollection(), any(), any())).thenReturn(List.of(
                new Occupied(ROOM_1, at("2031-02-03T11:00:00Z"), at("2031-02-03T14:00:00Z")),
                new Occupied(ROOM_2, at("2031-02-03T11:00:00Z"), at("2031-02-03T12:30:00Z"))));

        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt()).isEqualTo(at("2031-02-03T12:30:00Z"));
    }

    @Test
    void aDeclaredEmergencyIntervalIsABoundaryNotAReasonToDropTheSession() {
        when(emergencies.blocking(any(), eq(VENUE), any(), any(), any())).thenReturn(List.of(
                new DeclaredEmergencyIntervals.Interval(at("2031-02-03T11:00:00Z"), at("2031-02-03T16:00:00Z"))));

        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt()).isEqualTo(at("2031-02-03T16:00:00Z"));
    }

    @Test
    void aCandidateTheCanonicalPredicateRefusesIsNeverAccepted() {
        Instant lead = NOW.plusSeconds(15 * 60);
        predicate.refusedStarts.put(ROOM_1, List.of(lead));
        predicate.refusedStarts.put(ROOM_2, List.of(lead));

        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt())
                .as("both rooms' gap candidates refused in the only session").isNull();

        predicate.refusedStarts.remove(ROOM_2);
        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt()).isEqualTo(lead);
    }

    @Test
    void everyAcceptedCandidateWasAskedOfTheCanonicalPredicate() {
        Instant nearest = calculate(Activity.PLAYSTATION).nearestAvailableAt();

        assertThat(predicate.asked).contains(ROOM_1 + "@" + nearest, ROOM_2 + "@" + nearest);
    }

    @Test
    void resourcesFailingAStateCheckNeverContribute() {
        for (AvailabilityCheck state : List.of(AvailabilityCheck.RESOURCE_SELLING_BLOCK,
                AvailabilityCheck.RESOURCE_BASE_STATE, AvailabilityCheck.RESOURCE_OWNER_REVIEW)) {
            predicate.stateFailure.put(ROOM_1, state);
            predicate.stateFailure.put(ROOM_2, state);
            when(prices.resolve(any(), any(), any())).thenReturn(Optional.of(Money.of("40.00")));

            assertThat(calculate(Activity.PLAYSTATION)).as(state.name()).isEqualTo(Result.NONE);
        }
    }

    @Test
    void aSellingBlockedVenueHasNeitherNearestAvailabilityNorAStartingPrice() {
        predicate.stateFailure.put(ROOM_1, AvailabilityCheck.VENUE_SELLING_BLOCK);
        when(prices.resolve(any(), any(), any())).thenReturn(Optional.of(Money.of("40.00")));

        assertThat(calculate(Activity.PLAYSTATION)).isEqualTo(Result.NONE);
        verify(sessions, never()).endingAfter(any(), any(), any());
    }

    @Test
    void theStartingPriceIsTheLowestHourlyRateOverEligibleRoomsAndBothPlayModes() {
        predicate.stateFailure.put(ROOM_2, AvailabilityCheck.RESOURCE_BASE_STATE);
        when(prices.resolve(command, ROOM_1, PlayMode.SINGLE)).thenReturn(Optional.of(Money.of("60.00")));
        when(prices.resolve(command, ROOM_1, PlayMode.MULTIPLAYER)).thenReturn(Optional.of(Money.of("55.00")));
        when(prices.resolve(command, ROOM_2, PlayMode.SINGLE)).thenReturn(Optional.of(Money.of("10.00")));

        assertThat(calculate(Activity.PLAYSTATION).startingPrice())
                .as("ROOM_2 is DISABLED, so its cheaper rate does not count").isEqualTo(Money.of("55.00"));
    }

    @Test
    void anUnresolvablePriceIsSkippedAndNoResolvablePriceLeavesTheStartingPriceEmpty() {
        assertThat(calculate(Activity.PLAYSTATION).startingPrice()).isNull();
        assertThat(calculate(Activity.PLAYSTATION).nearestAvailableAt()).as("the row still has its availability")
                .isNotNull();

        when(prices.resolve(command, ROOM_2, PlayMode.MULTIPLAYER)).thenReturn(Optional.of(Money.of("70.00")));
        assertThat(calculate(Activity.PLAYSTATION).startingPrice()).isEqualTo(Money.of("70.00"));
    }

    @Test
    void playStationIsPricedForExactlyThePoDecidedVariants() {
        assertThat(NearestAvailabilityCalculator.PLAYSTATION_PRICE_VARIANTS)
                .containsExactly(PlayMode.SINGLE, PlayMode.MULTIPLAYER);

        calculate(Activity.PLAYSTATION);

        for (UUID room : List.of(ROOM_1, ROOM_2)) {
            verify(prices).resolve(command, room, PlayMode.SINGLE);
            verify(prices).resolve(command, room, PlayMode.MULTIPLAYER);
        }
        verifyNoMoreInteractions(prices);
    }

    @Test
    void resourcesAreReadOnTheCommandsTransaction() {
        calculate(Activity.PLAYSTATION);

        verify(catalog).ofVenue(command, VENUE);
        verify(catalog, never()).ofVenue(VENUE);
    }

    @Test
    void billiardsIsPricedWithoutAPlayMode() {
        when(prices.resolve(command, TABLE, null)).thenReturn(Optional.of(Money.of("50.00")));

        assertThat(calculate(Activity.BILLIARDS).startingPrice()).isEqualTo(Money.of("50.00"));
        verify(prices, never()).resolve(any(), eq(TABLE), eq(PlayMode.SINGLE));
        verify(prices).resolve(any(), eq(TABLE), isNull());
    }

    @Test
    void noSessionMeansNoNearestAvailabilityButStillAStartingPrice() {
        sessions();
        when(prices.resolve(command, ROOM_1, PlayMode.SINGLE)).thenReturn(Optional.of(Money.of("60.00")));

        Result result = calculate(Activity.PLAYSTATION);

        assertThat(result.nearestAvailableAt()).isNull();
        assertThat(result.startingPrice()).isEqualTo(Money.of("60.00"));
    }
}
