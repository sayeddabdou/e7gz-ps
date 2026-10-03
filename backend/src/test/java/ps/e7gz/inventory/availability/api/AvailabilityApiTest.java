package ps.e7gz.inventory.availability.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** E7-30 - the published Availability types: frozen check indices, the period and the result. */
class AvailabilityApiTest {

    @Test
    void theEightChecksCarryTheirFrozenIndices() {
        Map<AvailabilityCheck, Integer> expected = new LinkedHashMap<>();
        expected.put(AvailabilityCheck.VENUE_SELLING_BLOCK, 1);
        expected.put(AvailabilityCheck.RESOURCE_SELLING_BLOCK, 2);
        expected.put(AvailabilityCheck.RESOURCE_BASE_STATE, 3);
        expected.put(AvailabilityCheck.RESOURCE_OWNER_REVIEW, 4);
        expected.put(AvailabilityCheck.EMERGENCY_INTERVAL, 5);
        expected.put(AvailabilityCheck.MAINTENANCE, 6);
        expected.put(AvailabilityCheck.CLOSURE, 7);
        expected.put(AvailabilityCheck.BLOCKING_RESERVATION, 8);

        assertThat(AvailabilityCheck.values()).as("closed: exactly these eight").containsExactlyElementsOf(
                expected.keySet());
        expected.forEach((check, index) -> assertThat(check.index()).as(check.name()).isEqualTo(index));
        assertThat(Arrays.stream(AvailabilityCheck.values()).mapToInt(AvailabilityCheck::index).distinct().count())
                .isEqualTo(8);
    }

    @Test
    void freeHasNoFailingCheck() {
        PredicateResult free = PredicateResult.free();

        assertThat(free.isFree()).isTrue();
        assertThat(free.failingCheck()).isEmpty();
        assertThat(free.failingCheckIndex()).isEmpty();
        assertThat(free).isEqualTo(PredicateResult.free()).hasToString("FREE");
    }

    @ParameterizedTest
    @EnumSource(AvailabilityCheck.class)
    void notFreeExposesTheFailingCheckAndItsIndex(AvailabilityCheck check) {
        PredicateResult result = PredicateResult.notFree(check);

        assertThat(result.isFree()).isFalse();
        assertThat(result.failingCheck()).contains(check);
        assertThat(result.failingCheckIndex()).hasValue(check.index());
        assertThat(result).isEqualTo(PredicateResult.notFree(check)).isNotEqualTo(PredicateResult.free());
    }

    @Test
    void notFreeRequiresACheck() {
        assertThatThrownBy(() -> PredicateResult.notFree(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPeriodIsANonEmptyHalfOpenInterval() {
        Instant start = Instant.parse("2030-01-01T08:00:00Z");
        Instant end = Instant.parse("2030-01-01T09:00:00Z");

        assertThat(new AvailabilityPeriod(start, end)).extracting(AvailabilityPeriod::start, AvailabilityPeriod::end)
                .containsExactly(start, end);
        assertThatThrownBy(() -> new AvailabilityPeriod(start, start)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityPeriod(end, start)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityPeriod(null, end)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AvailabilityPeriod(start, null)).isInstanceOf(NullPointerException.class);
    }
}
