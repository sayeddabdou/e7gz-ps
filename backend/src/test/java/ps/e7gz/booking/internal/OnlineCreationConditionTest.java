package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;

/**
 * E7-46 - the one ordered list of FR-BOK-11's twenty conditions (arch 06 §1.3, risk I2): exactly twenty, numbered as
 * arch 06, each cross-referenced to Product v5.0 §22, each answering the code of the PO decision of 9 Oct 2026.
 */
class OnlineCreationConditionTest {

    /** arch 06 §1.3 number → Product v5.0 §22 number (BUILD-PLAN E7-46, PO decision 9 Oct 2026). */
    private static final int[][] ARCH_TO_SPEC = {
            {1, 1}, {2, 2}, {3, 3}, {4, 4}, {5, 5}, {6, 6}, {7, 7}, {8, 8}, {9, 9}, {10, 10},
            {11, 17}, {12, 11}, {13, 12}, {14, 13}, {15, 14}, {16, 15}, {17, 16}, {18, 18}, {19, 19}, {20, 20}};

    /** Step 11 of the E7-46 PO decisions of 9 Oct 2026. */
    private static final Map<OnlineCreationCondition, ApiErrorCode> CODES = new EnumMap<>(Map.ofEntries(
            Map.entry(OnlineCreationCondition.PHONE_VERIFIED, ApiErrorCode.PHONE_VERIFICATION_REQUIRED),
            Map.entry(OnlineCreationCondition.NOT_BANNED, ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION),
            Map.entry(OnlineCreationCondition.BELOW_COMMITMENT_LIMIT, ApiErrorCode.COMMITMENT_LIMIT_REACHED),
            Map.entry(OnlineCreationCondition.NO_OVERLAPPING_COMMITMENT, ApiErrorCode.OVERLAPPING_COMMITMENT),
            Map.entry(OnlineCreationCondition.VENUE_MARKETPLACE_ACTIVE, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY),
            Map.entry(OnlineCreationCondition.VENUE_ACCOUNT_ENABLED, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY),
            Map.entry(OnlineCreationCondition.AREA_ACTIVE, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY),
            Map.entry(OnlineCreationCondition.VENUE_CURRENTLY_OPEN, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED),
            Map.entry(OnlineCreationCondition.BEFORE_INTAKE_CUTOFF, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED),
            Map.entry(OnlineCreationCondition.NO_VENUE_SELLING_BLOCK, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE),
            Map.entry(OnlineCreationCondition.NO_RESOURCE_SELLING_BLOCK, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE),
            Map.entry(OnlineCreationCondition.LEAD_TIME, ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT),
            Map.entry(OnlineCreationCondition.DURATION_RANGE, ApiErrorCode.BOOKING_DURATION_INVALID),
            Map.entry(OnlineCreationCondition.DURATION_INCREMENT, ApiErrorCode.BOOKING_DURATION_INVALID),
            Map.entry(OnlineCreationCondition.WITHIN_ONE_SESSION, ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION),
            Map.entry(OnlineCreationCondition.RESOURCE_ACTIVE, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
            Map.entry(OnlineCreationCondition.RESOURCE_NOT_UNDER_OWNER_REVIEW, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
            Map.entry(OnlineCreationCondition.NO_BLOCKING_RESERVATION, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),
            Map.entry(OnlineCreationCondition.ACTIVITY_COMPATIBLE, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
            Map.entry(OnlineCreationCondition.OTHER_PRODUCT_VALIDATION, ApiErrorCode.RESOURCE_NOT_ELIGIBLE)));

    @Test
    void thereAreExactlyTwentyConditionsAndNoTwentyFirst() {
        assertThat(OnlineCreationCondition.values()).hasSize(20);
    }

    @Test
    void theyAreNumberedOneToTwentyInArch06OrderNeverByOrdinalAlone() {
        assertThat(Arrays.stream(OnlineCreationCondition.values()).mapToInt(OnlineCreationCondition::number).toArray())
                .containsExactly(IntStream.rangeClosed(1, 20).toArray());
    }

    @Test
    void eachCitesItsProductSpec22NumberAndTheSpecNumbersCoverOneToTwentyOnce() {
        for (int[] pair : ARCH_TO_SPEC) {
            OnlineCreationCondition condition = OnlineCreationCondition.values()[pair[0] - 1];
            assertThat(condition.number()).isEqualTo(pair[0]);
            assertThat(condition.specNumber()).as("Spec §22 number of arch 06 #%d", pair[0]).isEqualTo(pair[1]);
        }
        assertThat(Arrays.stream(OnlineCreationCondition.values()).mapToInt(OnlineCreationCondition::specNumber).toArray())
                .containsExactlyInAnyOrder(IntStream.rangeClosed(1, 20).toArray());
    }

    @ParameterizedTest
    @EnumSource(OnlineCreationCondition.class)
    void eachAnswersTheApprovedCode(OnlineCreationCondition condition) {
        assertThat(condition.code()).isEqualTo(CODES.get(condition));
    }

    @Test
    void missingPricingIsConditionTwentyAndAnswersResourceNotEligible() {
        assertThat(OnlineCreationCondition.OTHER_PRODUCT_VALIDATION.number()).isEqualTo(20);
        assertThat(OnlineCreationCondition.OTHER_PRODUCT_VALIDATION.code()).isEqualTo(ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
    }

    @Test
    void theEightAvailabilityChecksMapToConditionsTenElevenSixteenSeventeenAndEighteen() {
        Map<AvailabilityCheck, Integer> expected = Map.of(
                AvailabilityCheck.VENUE_SELLING_BLOCK, 10,
                AvailabilityCheck.RESOURCE_SELLING_BLOCK, 11,
                AvailabilityCheck.RESOURCE_BASE_STATE, 16,
                AvailabilityCheck.RESOURCE_OWNER_REVIEW, 17,
                AvailabilityCheck.EMERGENCY_INTERVAL, 18,
                AvailabilityCheck.MAINTENANCE, 18,
                AvailabilityCheck.CLOSURE, 18,
                AvailabilityCheck.BLOCKING_RESERVATION, 18);
        for (AvailabilityCheck check : AvailabilityCheck.values()) {
            assertThat(OnlineCreationPredicate.conditionOf(check).number()).as(check.name())
                    .isEqualTo(expected.get(check));
        }
    }
}
