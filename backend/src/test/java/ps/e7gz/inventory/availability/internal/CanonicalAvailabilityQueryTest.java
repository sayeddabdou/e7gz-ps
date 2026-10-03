package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.resource.api.ResourceStates.ResourceState;
import ps.e7gz.venue.api.VenueSellingBlocks;

/**
 * E7-30 - the order of the eight checks (arch 05 section 2.2), on fake owner reads. For each check {@code k}, every
 * check from {@code k} to 8 fails at once: the result must be {@code k}, and no read of a later check may run.
 */
class CanonicalAvailabilityQueryTest {

    private static final UUID RESOURCE = UUID.fromString("e7300000-0000-4000-8000-00000000f101");
    private static final UUID VENUE = UUID.fromString("e7300000-0000-4000-8000-00000000f002");
    private static final Instant START = Instant.parse("2030-01-01T08:00:00Z");
    private static final Instant END = Instant.parse("2030-01-01T09:00:00Z");
    private static final AvailabilityPeriod PERIOD = new AvailabilityPeriod(START, END);
    private static final Instant AT = Instant.parse("2030-01-01T07:00:00Z");

    private final VenueSellingBlocks venues = mock(VenueSellingBlocks.class);
    private final ResourceStates resources = mock(ResourceStates.class);
    private final OperationalOverlaps operations = mock(OperationalOverlaps.class);
    private final ReservationLedger ledger = mock(ReservationLedger.class);
    private final CommandContext command = mock(CommandContext.class);

    private final CanonicalAvailabilityQuery query =
            new CanonicalAvailabilityQuery(venues, resources, operations, ledger);

    /** Checks {@code firstFailing..8} fail; earlier checks pass. {@code firstFailing = 9} means all clear. */
    private void failFrom(int firstFailing) {
        ResourceBaseState baseState = firstFailing <= 3 ? ResourceBaseState.DISABLED : ResourceBaseState.ACTIVE;
        OperationalRestriction restriction =
                firstFailing <= 4 ? OperationalRestriction.OWNER_REVIEW_REQUIRED : OperationalRestriction.NONE;
        when(resources.find(command, RESOURCE))
                .thenReturn(Optional.of(new ResourceState(RESOURCE, VENUE, baseState, restriction)));
        when(venues.venueBlocked(command, VENUE, AT)).thenReturn(firstFailing <= 1);
        when(resources.resourceBlocked(command, RESOURCE, AT)).thenReturn(firstFailing <= 2);
        when(operations.declaredEmergencyOverlaps(command, VENUE, RESOURCE, START, END)).thenReturn(firstFailing <= 5);
        when(operations.maintenanceOverlaps(command, RESOURCE, START, END)).thenReturn(firstFailing <= 6);
        when(operations.closureOverlaps(command, VENUE, START, END)).thenReturn(firstFailing <= 7);
        when(ledger.hasBlockingOverlap(command, RESOURCE, START, END)).thenReturn(firstFailing <= 8);
    }

    private PredicateResult isFree() {
        return query.isFree(command, RESOURCE, PERIOD, AT);
    }

    @ParameterizedTest
    @EnumSource(AvailabilityCheck.class)
    void theFirstFailingCheckWinsAndNoLaterCheckIsRead(AvailabilityCheck check) {
        failFrom(check.index());

        PredicateResult result = isFree();

        assertThat(result.isFree()).isFalse();
        assertThat(result.failingCheck()).contains(check);
        assertThat(result.failingCheckIndex()).hasValue(check.index());
        verifyReadsUpTo(check.index());
    }

    /** The reads of checks 1..{@code last}, in order, and nothing else. */
    private void verifyReadsUpTo(int last) {
        InOrder order = inOrder(resources, venues, operations, ledger);
        order.verify(resources).find(command, RESOURCE);
        if (last >= 1) {
            order.verify(venues).venueBlocked(command, VENUE, AT);
        }
        if (last >= 2) {
            order.verify(resources).resourceBlocked(command, RESOURCE, AT);
        }
        // 3 and 4 use the Resource row read first.
        if (last >= 5) {
            order.verify(operations).declaredEmergencyOverlaps(command, VENUE, RESOURCE, START, END);
        }
        if (last >= 6) {
            order.verify(operations).maintenanceOverlaps(command, RESOURCE, START, END);
        }
        if (last >= 7) {
            order.verify(operations).closureOverlaps(command, VENUE, START, END);
        }
        if (last >= 8) {
            order.verify(ledger).hasBlockingOverlap(command, RESOURCE, START, END);
        }
        verifyNoMoreInteractions(resources, venues, operations, ledger);
    }

    @Test
    void allClearIsFreeAfterAllEightChecksInOrder() {
        failFrom(9);

        PredicateResult result = isFree();

        assertThat(result.isFree()).isTrue();
        assertThat(result.failingCheck()).isEmpty();
        verifyReadsUpTo(8);
    }

    @Test
    void ecAvai002ActiveBaseStateWithOwnerReviewFailsCheck4() {
        failFrom(9);
        when(resources.find(command, RESOURCE)).thenReturn(Optional.of(new ResourceState(RESOURCE, VENUE,
                ResourceBaseState.ACTIVE, OperationalRestriction.OWNER_REVIEW_REQUIRED)));

        PredicateResult result = isFree();

        assertThat(result.failingCheck()).contains(AvailabilityCheck.RESOURCE_OWNER_REVIEW);
        assertThat(result.failingCheckIndex()).hasValue(4);
    }

    @ParameterizedTest
    @EnumSource(value = ResourceBaseState.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void aBaseStateOtherThanActiveFailsCheck3EvenWithNoRestriction(ResourceBaseState baseState) {
        failFrom(9);
        when(resources.find(command, RESOURCE)).thenReturn(Optional.of(new ResourceState(RESOURCE, VENUE, baseState,
                OperationalRestriction.NONE)));

        assertThat(isFree().failingCheckIndex()).hasValue(3);
    }

    @Test
    void aSellingBlockIsAStateReadAtTheGivenInstantNotAnOverlapOfThePeriod() {
        // EC-AVAI-003: the period lies far outside any declared Emergency interval; the block state alone refuses.
        failFrom(9);
        when(venues.venueBlocked(command, VENUE, AT)).thenReturn(true);

        assertThat(isFree().failingCheck()).contains(AvailabilityCheck.VENUE_SELLING_BLOCK);
        verify(venues).venueBlocked(command, VENUE, AT);
        verifyNoInteractions(operations, ledger);
    }

    @Test
    void everyReadRunsOnTheCallersCommand() {
        failFrom(9);

        isFree();

        verify(resources).find(same(command), any());
        verify(venues).venueBlocked(same(command), any(), any());
        verify(resources).resourceBlocked(same(command), any(), any());
        verify(operations).declaredEmergencyOverlaps(same(command), any(), any(), any(), any());
        verify(operations).maintenanceOverlaps(same(command), any(), any(), any());
        verify(operations).closureOverlaps(same(command), any(), any(), any());
        verify(ledger).hasBlockingOverlap(same(command), any(), any(), any());
    }

    @Test
    void anUnknownResourceIsNotFoundAndNothingElseIsRead() {
        when(resources.find(command, RESOURCE)).thenReturn(Optional.empty());

        assertThatThrownBy(this::isFree).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ApiErrorCode.NOT_FOUND);
        verifyNoInteractions(venues, operations, ledger);
    }
}
