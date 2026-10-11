package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;

/**
 * The manual predicate's list (arch 06 §1.4, FRS FR-MAN-02, Product v5.0 §30.2): exactly fifteen, numbered as §30.2,
 * each with its code (PO decision 10 Oct 2026, E7-48, D4), kept apart from the twenty online conditions.
 */
class ManualCreationConditionTest {

    @Test
    void thereAreExactlyFifteenNumberedAsTheProductLists() {
        assertThat(ManualCreationCondition.values()).hasSize(15);
        assertThat(Arrays.stream(ManualCreationCondition.values()).map(ManualCreationCondition::number))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 15).boxed().toList());
    }

    @Test
    void eachConditionAnswersItsApprovedCode() {
        Map<ManualCreationCondition, ApiErrorCode> expected = new EnumMap<>(ManualCreationCondition.class);
        expected.put(ManualCreationCondition.VENUE_ACCOUNT_ENABLED, ApiErrorCode.FORBIDDEN);
        expected.put(ManualCreationCondition.VENUE_NOT_ONBOARDING, ApiErrorCode.FORBIDDEN);
        expected.put(ManualCreationCondition.RESOURCE_ACTIVE, ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
        expected.put(ManualCreationCondition.RESOURCE_NOT_UNDER_OWNER_REVIEW, ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
        expected.put(ManualCreationCondition.NO_VENUE_SELLING_BLOCK, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
        expected.put(ManualCreationCondition.NO_RESOURCE_SELLING_BLOCK, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
        expected.put(ManualCreationCondition.ACTIVITY_SUPPORTED, ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
        expected.put(ManualCreationCondition.NO_BLOCKING_RESERVATION, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        expected.put(ManualCreationCondition.NO_CLOSURE, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        expected.put(ManualCreationCondition.NO_EMERGENCY_CLOSURE, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        expected.put(ManualCreationCondition.NO_RESOURCE_EMERGENCY_OUTAGE, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        expected.put(ManualCreationCondition.NO_MAINTENANCE, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        expected.put(ManualCreationCondition.START_NOW_OR_FUTURE, ApiErrorCode.MANUAL_BOOKING_BACKDATING_NOT_ALLOWED);
        expected.put(ManualCreationCondition.DURATION, ApiErrorCode.BOOKING_DURATION_INVALID);
        expected.put(ManualCreationCondition.PRICING_CONFIGURED,
                ApiErrorCode.MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING);

        for (ManualCreationCondition condition : ManualCreationCondition.values()) {
            assertThat(condition.code()).as(condition.name()).isEqualTo(expected.get(condition));
        }
    }

    /** D4: every overlap (closure, emergency, outage, maintenance, booking) is 409; a selling block now is its 422. */
    @Test
    void everyAvailabilityCheckMapsToAManualCondition() {
        for (AvailabilityCheck check : AvailabilityCheck.values()) {
            ManualCreationCondition condition = ManualCreationPredicate.conditionOf(check);
            switch (check) {
                case VENUE_SELLING_BLOCK -> assertThat(condition.code()).isEqualTo(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
                case RESOURCE_SELLING_BLOCK ->
                        assertThat(condition.code()).isEqualTo(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
                case RESOURCE_BASE_STATE, RESOURCE_OWNER_REVIEW ->
                        assertThat(condition.code()).isEqualTo(ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
                case EMERGENCY_INTERVAL, MAINTENANCE, CLOSURE, BLOCKING_RESERVATION ->
                        assertThat(condition.code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
            }
        }
    }

    @Test
    void theDurationIsOneToSixHoursInHalfHours() {
        assertThat(ManualCreationPredicate.durationValid(60)).isTrue();
        assertThat(ManualCreationPredicate.durationValid(90)).isTrue();
        assertThat(ManualCreationPredicate.durationValid(360)).isTrue();
        assertThat(ManualCreationPredicate.durationValid(30)).isFalse();
        assertThat(ManualCreationPredicate.durationValid(75)).isFalse();
        assertThat(ManualCreationPredicate.durationValid(390)).isFalse();
    }
}
