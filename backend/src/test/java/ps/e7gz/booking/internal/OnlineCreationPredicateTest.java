package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.support.boundary.CommandInstants;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.venue.api.OperatingSessions.SessionPeriod;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.SessionSnapshots.Session;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.VenueEligibility.Eligibility;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * E7-46 - the twenty creation conditions on fake owner reads, at an exact command instant: every condition failing
 * alone answers that condition and its approved code (PO decision 9 Oct 2026 - combinations are not a product rule),
 * and the timing boundaries hold to the second (FR-BOK-02, FR-BOK-09).
 */
class OnlineCreationPredicateTest {

    private static final Instant NOW = Instant.parse("2030-06-01T12:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("e7460000-0000-4000-8000-000000000001");
    private static final UUID VENUE = UUID.fromString("e7460000-0000-4000-8000-000000000002");
    private static final UUID AREA = UUID.fromString("e7460000-0000-4000-8000-000000000003");
    private static final UUID RESOURCE = UUID.fromString("e7460000-0000-4000-8000-000000000004");
    private static final UUID SESSION = UUID.fromString("e7460000-0000-4000-8000-000000000005");
    private static final ResourceCatalog.Entry ROOM =
            new ResourceCatalog.Entry(RESOURCE, VENUE, Activity.PLAYSTATION, ConsoleType.PS5);
    private static final BookingAmounts AMOUNTS =
            BookingPricing.amounts(Money.of("50.00"), 120, Money.of("0.00"));

    private final CommandContext command = CommandInstants.at(NOW);
    private final CustomerActivation activation = mock(CustomerActivation.class);
    private final ActiveBans bans = mock(ActiveBans.class);
    private final OnlineBookingStore store = mock(OnlineBookingStore.class);
    private final VenueEligibility venues = mock(VenueEligibility.class);
    private final AreaLookup areas = mock(AreaLookup.class);
    private final OperatingSessions sessions = mock(OperatingSessions.class);
    private final SessionSnapshots snapshots = mock(SessionSnapshots.class);
    private final AvailabilityQuery availability = mock(AvailabilityQuery.class);
    private final ResourceCatalog resources = mock(ResourceCatalog.class);
    private final BookingPricing pricing = mock(BookingPricing.class);

    private final OnlineCreationPredicate predicate = new OnlineCreationPredicate(activation, bans, store, venues, areas,
            sessions, snapshots, availability, resources, pricing);

    private OnlineBookingRequest request = request(NOW.plus(Duration.ofHours(1)), 120, PlayMode.SINGLE);

    /** Everything passes: an open session [now-2h, now+8h) holding the requested interval. */
    @BeforeEach
    void allConditionsPass() {
        when(bans.bannedAt(command, CUSTOMER, NOW)).thenReturn(false);
        when(store.countCommitments(command, CUSTOMER)).thenReturn(3L);
        when(store.overlapsCommitment(eq(command), eq(CUSTOMER), any(), any())).thenReturn(false);
        venue(MarketplaceStatus.ACTIVE, VenueAccountStatus.ENABLED);
        when(areas.isActive(command, AREA)).thenReturn(true);
        currentSession(NOW.minus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(8)));
        sessionHoldingStart(NOW.minus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(8)));
        when(availability.isFree(eq(command), eq(RESOURCE), any(), eq(NOW))).thenReturn(PredicateResult.free());
        when(pricing.price(eq(command), eq(RESOURCE), any(), anyInt())).thenReturn(Optional.of(AMOUNTS));
        when(resources.name(command, RESOURCE)).thenReturn(Optional.of("Room 1"));
    }

    @Test
    void whenAllTwentyPassItApprovesWithTheRealEndTheSessionTheAmountsAndTheName() {
        OnlineCreationPredicate.Approval approval = predicate.check(command, request, ROOM);

        assertThat(approval.endAt()).isEqualTo(request.startAt().plus(Duration.ofMinutes(120)));
        assertThat(approval.session().id()).isEqualTo(SESSION);
        assertThat(approval.amounts()).isEqualTo(AMOUNTS);
        assertThat(approval.resourceName()).isEqualTo("Room 1");
        verify(availability).isFree(command, RESOURCE,
                new AvailabilityPeriod(request.startAt(), approval.endAt()), NOW);
    }

    @ParameterizedTest
    @EnumSource(OnlineCreationCondition.class)
    void eachConditionFailingAloneRefusesWithItsCode(OnlineCreationCondition condition) {
        breakOnly(condition);

        assertRefused(condition, condition.code());
    }

    @Test
    void anAnonymisedAccountFailsConditionOneAsAccountDeleted() {
        doThrow(new ApiException(ApiErrorCode.ACCOUNT_DELETED)).when(activation).requirePhoneVerified(command, CUSTOMER);

        assertRefused(OnlineCreationCondition.PHONE_VERIFIED, ApiErrorCode.ACCOUNT_DELETED);
    }

    @Test
    void aVenueWithoutAnAccountFailsConditionSix() {
        venue(MarketplaceStatus.ACTIVE, null);

        assertRefused(OnlineCreationCondition.VENUE_ACCOUNT_ENABLED, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY);
    }

    @ParameterizedTest
    @EnumSource(value = AvailabilityCheck.class, names = {"EMERGENCY_INTERVAL", "MAINTENANCE", "CLOSURE",
            "BLOCKING_RESERVATION"})
    void anEmergencyIntervalMaintenanceClosureOrCommitmentIsConditionEighteen(AvailabilityCheck check) {
        notFree(check);

        assertRefused(OnlineCreationCondition.NO_BLOCKING_RESERVATION, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
    }

    // ---- FR-BOK-02: now <= startAt - 15 min ----

    @Test
    void aStartExactlyFifteenMinutesAheadIsAccepted() {
        request = request(NOW.plus(Duration.ofMinutes(15)), 120, PlayMode.SINGLE);

        assertThat(predicate.check(command, request, ROOM)).isNotNull();
    }

    @Test
    void aStartFourteenMinutesFiftyNineSecondsAheadIsRefused() {
        request = request(NOW.plus(Duration.ofMinutes(14)).plusSeconds(59), 120, PlayMode.SINGLE);

        assertRefused(OnlineCreationCondition.LEAD_TIME, ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT);
    }

    // ---- FR-BOK-09, §21: intake stops 10 minutes before the current session ends ----

    @Test
    void elevenMinutesBeforeTheCurrentSessionEndsIsAccepted() {
        currentSession(NOW.minus(Duration.ofHours(6)), NOW.plus(Duration.ofMinutes(11)));

        assertThat(predicate.check(command, request, ROOM)).isNotNull();
    }

    @Test
    void nineMinutesBeforeTheCurrentSessionEndsIsRefused() {
        currentSession(NOW.minus(Duration.ofHours(6)), NOW.plus(Duration.ofMinutes(9)));

        assertRefused(OnlineCreationCondition.BEFORE_INTAKE_CUTOFF, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED);
    }

    @Test
    void exactlyTenMinutesBeforeTheCurrentSessionEndsIsAlreadyRefused() {
        // §21: a session ending at 04:00 takes its last request before 03:50
        currentSession(NOW.minus(Duration.ofHours(6)), NOW.plus(Duration.ofMinutes(10)));

        assertRefused(OnlineCreationCondition.BEFORE_INTAKE_CUTOFF, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED);
    }

    @Test
    void aSessionStartingExactlyNowIsOpen() {
        currentSession(NOW, NOW.plus(Duration.ofHours(8)));

        assertThat(predicate.check(command, request, ROOM)).isNotNull();
    }

    // ---- FR-BOK-01: 1-6 hours in 30-minute steps ----

    @ParameterizedTest
    @ValueSource(ints = {60, 90, 360})
    void durationsFromOneToSixHoursInHalfHoursAreAccepted(int minutes) {
        request = request(request.startAt(), minutes, PlayMode.SINGLE);

        assertThat(predicate.check(command, request, ROOM)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 30, 59, 390})
    void durationsOutsideOneToSixHoursAreConditionThirteen(int minutes) {
        request = request(request.startAt(), minutes, PlayMode.SINGLE);

        assertRefused(OnlineCreationCondition.DURATION_RANGE, ApiErrorCode.BOOKING_DURATION_INVALID);
    }

    @Test
    void anIntervalEndingExactlyAtTheSessionEndFits() {
        sessionHoldingStart(NOW.minus(Duration.ofHours(2)), request.startAt().plus(Duration.ofMinutes(120)));

        assertThat(predicate.check(command, request, ROOM)).isNotNull();
    }

    @Test
    void thePeriodShapeIsCheckedBeforeTheAvailabilityPredicateIsAsked() {
        breakOnly(OnlineCreationCondition.WITHIN_ONE_SESSION);

        assertRefused(OnlineCreationCondition.WITHIN_ONE_SESSION, ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION);
        verify(availability, never()).isFree(any(), any(), any(), any());
    }

    @Test
    void billiardsWithoutAPlayModeIsCompatibleAndWithOneIsConditionNineteen() {
        ResourceCatalog.Entry table = new ResourceCatalog.Entry(RESOURCE, VENUE, Activity.BILLIARDS, null);
        assertThat(predicate.check(command, request(request.startAt(), 120, null), table)).isNotNull();

        assertThatThrownBy(() -> predicate.check(command, request(request.startAt(), 120, PlayMode.MULTIPLAYER), table))
                .isInstanceOfSatisfying(CreationRefused.class, refused ->
                        assertThat(refused.condition()).isEqualTo(OnlineCreationCondition.ACTIVITY_COMPATIBLE));
    }

    /** Makes exactly {@code condition} fail; every other condition still passes. */
    private void breakOnly(OnlineCreationCondition condition) {
        Instant start = request.startAt();
        switch (condition) {
            case PHONE_VERIFIED -> doThrow(new ApiException(ApiErrorCode.PHONE_VERIFICATION_REQUIRED))
                    .when(activation).requirePhoneVerified(command, CUSTOMER);
            case NOT_BANNED -> when(bans.bannedAt(command, CUSTOMER, NOW)).thenReturn(true);
            case BELOW_COMMITMENT_LIMIT -> when(store.countCommitments(command, CUSTOMER)).thenReturn(4L);
            case NO_OVERLAPPING_COMMITMENT -> when(store.overlapsCommitment(eq(command), eq(CUSTOMER), any(), any()))
                    .thenReturn(true);
            case VENUE_MARKETPLACE_ACTIVE -> venue(MarketplaceStatus.SUSPENDED, VenueAccountStatus.ENABLED);
            case VENUE_ACCOUNT_ENABLED -> venue(MarketplaceStatus.ACTIVE, VenueAccountStatus.DISABLED);
            case AREA_ACTIVE -> when(areas.isActive(command, AREA)).thenReturn(false);
            case VENUE_CURRENTLY_OPEN -> when(sessions.endingAfter(command, VENUE, NOW)).thenReturn(List.of(
                    new SessionPeriod(LocalDate.parse("2030-06-01"), NOW.plusSeconds(1), NOW.plus(Duration.ofHours(8)))));
            case BEFORE_INTAKE_CUTOFF -> currentSession(NOW.minus(Duration.ofHours(6)), NOW.plus(Duration.ofMinutes(9)));
            case NO_VENUE_SELLING_BLOCK -> notFree(AvailabilityCheck.VENUE_SELLING_BLOCK);
            case NO_RESOURCE_SELLING_BLOCK -> notFree(AvailabilityCheck.RESOURCE_SELLING_BLOCK);
            case LEAD_TIME -> request = request(NOW.plus(Duration.ofMinutes(10)), 120, PlayMode.SINGLE);
            case DURATION_RANGE -> request = request(start, 420, PlayMode.SINGLE);
            case DURATION_INCREMENT -> request = request(start, 75, PlayMode.SINGLE);
            case WITHIN_ONE_SESSION -> sessionHoldingStart(NOW.minus(Duration.ofHours(2)), start.plus(Duration.ofMinutes(119)));
            case RESOURCE_ACTIVE -> notFree(AvailabilityCheck.RESOURCE_BASE_STATE);
            case RESOURCE_NOT_UNDER_OWNER_REVIEW -> notFree(AvailabilityCheck.RESOURCE_OWNER_REVIEW);
            case NO_BLOCKING_RESERVATION -> notFree(AvailabilityCheck.BLOCKING_RESERVATION);
            case ACTIVITY_COMPATIBLE -> request = request(start, 120, null);
            case OTHER_PRODUCT_VALIDATION -> when(pricing.price(eq(command), eq(RESOURCE), any(), anyInt()))
                    .thenReturn(Optional.empty());
        }
    }

    private void assertRefused(OnlineCreationCondition condition, ApiErrorCode code) {
        assertThatThrownBy(() -> predicate.check(command, request, ROOM))
                .isInstanceOfSatisfying(CreationRefused.class, refused -> {
                    assertThat(refused.condition()).isEqualTo(condition);
                    assertThat(refused.code()).isEqualTo(code);
                });
    }

    private void venue(MarketplaceStatus marketplace, VenueAccountStatus account) {
        when(venues.find(command, VENUE)).thenReturn(Optional.of(new Eligibility(VENUE, AREA, marketplace, account)));
    }

    private void currentSession(Instant start, Instant end) {
        when(sessions.endingAfter(command, VENUE, NOW))
                .thenReturn(List.of(new SessionPeriod(LocalDate.parse("2030-06-01"), start, end)));
    }

    private void sessionHoldingStart(Instant start, Instant end) {
        when(snapshots.containing(eq(command), eq(VENUE), any())).thenReturn(Optional.of(new Session(SESSION,
                LocalDate.parse("2030-06-01"), start, end, LocalDateTime.parse("2030-06-01T13:00"),
                LocalDateTime.parse("2030-06-01T23:00"), "Africa/Cairo")));
    }

    private void notFree(AvailabilityCheck check) {
        when(availability.isFree(eq(command), eq(RESOURCE), any(), eq(NOW))).thenReturn(PredicateResult.notFree(check));
    }

    private static OnlineBookingRequest request(Instant startAt, int minutes, PlayMode playMode) {
        return new OnlineBookingRequest(CUSTOMER, RESOURCE, playMode, startAt, minutes);
    }
}
