package ps.e7gz.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.trust.api.ActiveBans;

/**
 * E7-46 - {@link ActiveBans} (FR-BOK-11 condition 2, FR-ACC-17) against the migrated database, on the command's own
 * transaction: only an ACTIVE Ban whose {@code [activated_at, ends_at)} holds the instant counts.
 */
class ActiveBansIntegrationTest extends IntegrationTest {

    private static final Instant ACTIVATED = Instant.parse("2030-01-10T10:00:00Z");

    @Autowired
    CommandRunner runner;

    @Autowired
    ActiveBans bans;

    record TestCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private boolean bannedAt(UUID user, Instant at) {
        return runner.run(new TestCommand<>(ctx -> bans.bannedAt(ctx, user, at)));
    }

    @Test
    void aCustomerWithoutABanIsNotBanned() {
        assertThat(bannedAt(aUser().verified().create().id(), ACTIVATED)).isFalse();
    }

    @Test
    void anActiveBanCountsFromItsActivationUntilJustBeforeItsEnd() {
        UUID user = aUser().verified().create().id();
        BanFixtures.ban(user, "ACTIVE", ACTIVATED, Duration.ofDays(7));

        assertThat(bannedAt(user, ACTIVATED.minusSeconds(1))).isFalse();
        assertThat(bannedAt(user, ACTIVATED)).isTrue();
        assertThat(bannedAt(user, ACTIVATED.plus(Duration.ofDays(7)).minusSeconds(1))).isTrue();
        assertThat(bannedAt(user, ACTIVATED.plus(Duration.ofDays(7)))).isFalse();
    }

    @Test
    void anExpiredLiftedOrCorrectedBanNeverCounts() {
        for (String status : new String[] {"EXPIRED", "LIFTED", "CORRECTED"}) {
            UUID user = aUser().verified().create().id();
            BanFixtures.ban(user, status, ACTIVATED, Duration.ofDays(7));

            assertThat(bannedAt(user, ACTIVATED.plus(Duration.ofDays(1)))).as(status).isFalse();
        }
    }

    @Test
    void anotherCustomersBanDoesNotCount() {
        UUID banned = aUser().verified().create().id();
        UUID other = aUser().verified().create().id();
        BanFixtures.ban(banned, "ACTIVE", ACTIVATED, Duration.ofDays(7));

        assertThat(bannedAt(other, ACTIVATED.plus(Duration.ofDays(1)))).isFalse();
    }
}
