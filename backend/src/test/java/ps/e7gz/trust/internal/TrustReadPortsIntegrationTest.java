package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.support.fixtures.TrustStates;
import ps.e7gz.trust.api.AccountStandings;
import ps.e7gz.trust.api.AccountStandings.Standing;
import ps.e7gz.trust.api.VerifiedPlayers;
import ps.e7gz.trust.api.VerifiedPlayers.VerifiedPlayer;

/**
 * E7-66 PR 2 (PO decision D4) - the read-only trust ports the admin user pages use: the projection's values, the start
 * of the ladder for a Customer without trust history, and the one Ban Instance in force (status ACTIVE and inside its
 * interval, as {@code trust.guard_booking_ban_eligibility} decides).
 */
class TrustReadPortsIntegrationTest extends IntegrationTest {

    @Autowired
    AccountStandings standings;

    @Autowired
    VerifiedPlayers verifiedPlayers;

    @Test
    void aCustomerWithoutTrustHistoryStandsAtTheStartOfTheLadder() {
        UUID user = Fixtures.aUser().verified().create().id();

        assertThat(standings.of(List.of(user))).containsExactlyEntriesOf(Map.of(user,
                new Standing(user, 0, 0, 3, null)));
        assertThat(verifiedPlayers.of(List.of(user))).containsEntry(user, new VerifiedPlayer(user,
                VerifiedPlayerQualification.NOT_QUALIFIED, 0, VerifiedPlayerStatus.NOT_VERIFIED));
    }

    @Test
    void theProjectionAndTheBanInForceAreRead() {
        UUID user = Fixtures.aUser().verified().create().id();
        TrustStates.projection(user, 1, 1, 12, "SUSPENDED");
        Instant now = TrustStates.dbNow();
        Instant activated = now.minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
        Instant ends = now.plus(Duration.ofDays(6)).truncatedTo(ChronoUnit.MICROS);
        UUID ban = TrustStates.ban(user, 1, "ACTIVE", activated, ends);

        Standing standing = standings.of(List.of(user)).get(user);
        assertThat(standing.consecutiveNoShows()).isEqualTo(1);
        assertThat(standing.penaltyStage()).isEqualTo(1);
        assertThat(standing.currentThreshold()).as("trust.penalty_threshold(1)").isEqualTo(2);
        assertThat(standing.activeBan()).isEqualTo(new AccountStandings.Ban(ban, 1, activated, ends));
        assertThat(standing.banned()).isTrue();
        assertThat(verifiedPlayers.of(List.of(user)).get(user)).isEqualTo(
                new VerifiedPlayer(user, VerifiedPlayerQualification.QUALIFIED, 12, VerifiedPlayerStatus.SUSPENDED));
    }

    @Test
    void onlyAnActiveBanInsideItsIntervalIsInForce() {
        Instant now = TrustStates.dbNow();
        UUID elapsed = Fixtures.aUser().verified().create().id();
        TrustStates.ban(elapsed, 0, "ACTIVE", now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(1)));
        UUID lifted = Fixtures.aUser().verified().create().id();
        TrustStates.ban(lifted, 0, "LIFTED", now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(6)));

        assertThat(standings.of(List.of(elapsed, lifted)).values())
                .extracting(Standing::activeBan).containsOnlyNulls().hasSize(2);
    }

    @Test
    void noIdsReadNothing() {
        assertThat(standings.of(List.of())).isEmpty();
        assertThat(verifiedPlayers.of(List.of())).isEmpty();
    }
}
