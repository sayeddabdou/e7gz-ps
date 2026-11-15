package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.trust.BanFixtures;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.trust.api.BanHistory;

/**
 * E7-69 "banned then" (arch 07 section 3.4): whether a Ban Instance spanned a past instant, from
 * {@code trust.ban_instance.activated_at / ends_at} alone, whatever became of the Ban afterwards. No command creates
 * Bans before E7-71, so the rows are planted ({@link BanFixtures}); the read is the runtime one, inside a command.
 */
class BanHistoryIntegrationTest extends IntegrationTest {

    private static final Instant ACTIVATED = Instant.parse("2030-01-10T10:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);

    @Autowired CommandRunner runner;
    @Autowired BanHistory history;
    @Autowired ActiveBans bans;

    @Test
    void aUserWithoutABanWasNeverBanned() {
        assertThat(bannedAt(aUser().verified().create().id(), ACTIVATED)).isFalse();
    }

    @Test
    void aBanSpansFromItsActivationUntilJustBeforeItsEndWhateverItsStatusNow() {
        for (String status : new String[] {"ACTIVE", "EXPIRED", "LIFTED", "CORRECTED"}) {
            UUID user = aUser().verified().create().id();
            BanFixtures.ban(user, status, ACTIVATED, WEEK);

            assertThat(bannedAt(user, ACTIVATED.minusSeconds(1))).as(status).isFalse();
            assertThat(bannedAt(user, ACTIVATED)).as(status + ": inclusive at activation").isTrue();
            assertThat(bannedAt(user, ACTIVATED.plus(WEEK).minusSeconds(1))).as(status).isTrue();
            assertThat(bannedAt(user, ACTIVATED.plus(WEEK))).as(status + ": exclusive at the end").isFalse();
        }
    }

    /** The historical answer and the eligibility answer differ on purpose: an ended Ban was real, but blocks nothing. */
    @Test
    void anEndedBanWasInForceThenButIsNotAnActiveBan() {
        UUID user = aUser().verified().create().id();
        BanFixtures.ban(user, "EXPIRED", ACTIVATED, WEEK);
        Instant during = ACTIVATED.plus(Duration.ofDays(1));

        assertThat(bannedAt(user, during)).isTrue();
        assertThat(runner.<Boolean>run(TestCommands.under(CommandScope.shared(LockSet.empty()),
                command -> bans.bannedAt(command, user, during)))).isFalse();
    }

    @Test
    void anotherUsersBanNeverCounts() {
        UUID banned = aUser().verified().create().id();
        UUID other = aUser().verified().create().id();
        BanFixtures.ban(banned, "EXPIRED", ACTIVATED, WEEK);

        assertThat(bannedAt(other, ACTIVATED.plus(Duration.ofDays(1)))).isFalse();
    }

    private boolean bannedAt(UUID user, Instant at) {
        return runner.<Boolean>run(TestCommands.under(CommandScope.shared(LockSet.empty()),
                command -> history.bannedAt(command, user, at)));
    }
}
