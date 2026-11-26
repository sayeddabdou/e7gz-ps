package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.tournament.api.TournamentResourceAvailability.Blocker;

/** E7-91: every failing check of the canonical predicate reads as exactly one Tournament blocker. */
class CanonicalTournamentResourceAvailabilityTest {

    @Test
    void eachFailingCheckIsOneBlocker() {
        Map<AvailabilityCheck, Blocker> expected = new EnumMap<>(AvailabilityCheck.class);
        expected.put(AvailabilityCheck.VENUE_SELLING_BLOCK, Blocker.VENUE_SELLING_BLOCK);
        expected.put(AvailabilityCheck.RESOURCE_SELLING_BLOCK, Blocker.RESOURCE_SELLING_BLOCK);
        expected.put(AvailabilityCheck.RESOURCE_BASE_STATE, Blocker.NOT_ELIGIBLE);
        expected.put(AvailabilityCheck.RESOURCE_OWNER_REVIEW, Blocker.NOT_ELIGIBLE);
        // FR-TRN-08: whatever already holds the interval wins
        expected.put(AvailabilityCheck.EMERGENCY_INTERVAL, Blocker.ALREADY_RESERVED);
        expected.put(AvailabilityCheck.MAINTENANCE, Blocker.ALREADY_RESERVED);
        expected.put(AvailabilityCheck.CLOSURE, Blocker.ALREADY_RESERVED);
        expected.put(AvailabilityCheck.BLOCKING_RESERVATION, Blocker.ALREADY_RESERVED);

        assertThat(expected).hasSize(AvailabilityCheck.values().length);
        expected.forEach((check, blocker) ->
                assertThat(CanonicalTournamentResourceAvailability.blockerOf(check)).as(check.name()).isEqualTo(blocker));
    }
}
