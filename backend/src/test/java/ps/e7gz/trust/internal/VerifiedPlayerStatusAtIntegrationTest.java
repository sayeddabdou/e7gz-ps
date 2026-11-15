package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.trust.api.VerifiedPlayerStatusAt;

/**
 * E7-69 "held privileges then": the operational Verified Player status at a past instant, from the transition history
 * alone. No command creates transitions before E7-72, so they are planted past the triggers (every CHECK still
 * applies); the read is the runtime one, inside a command.
 */
class VerifiedPlayerStatusAtIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired CommandRunner runner;
    @Autowired VerifiedPlayerStatusAt statuses;

    @Test
    void withoutTransitionsAUserIsNotVerified() {
        UUID user = aUser().verified().create().id();
        assertThat(statusAt(user, Instant.parse("2026-10-18T12:00:00Z")))
                .isEqualTo(VerifiedPlayerStatus.NOT_VERIFIED);
    }

    @Test
    void theStatusAtAnInstantIsTheLatestTransitionAtOrBeforeIt() {
        UUID user = aUser().verified().create().id();
        Instant verified = Instant.parse("2026-09-01T10:00:00Z");
        Instant suspended = verified.plus(Duration.ofDays(3));
        plant(user, "NOT_VERIFIED", "VERIFIED", verified, "TRUST_QUALIFICATION", null);
        plant(user, "VERIFIED", "SUSPENDED", suspended, "ADMIN", aUser().create().id());

        assertThat(statusAt(user, verified.minusSeconds(1))).isEqualTo(VerifiedPlayerStatus.NOT_VERIFIED);
        assertThat(statusAt(user, verified)).as("inclusive at the transition").isEqualTo(VerifiedPlayerStatus.VERIFIED);
        assertThat(statusAt(user, suspended.minusSeconds(1))).isEqualTo(VerifiedPlayerStatus.VERIFIED);
        assertThat(statusAt(user, suspended)).isEqualTo(VerifiedPlayerStatus.SUSPENDED);
        assertThat(statusAt(user, suspended.plus(Duration.ofDays(30)))).isEqualTo(VerifiedPlayerStatus.SUSPENDED);
    }

    @Test
    void anotherUsersHistoryNeverCounts() {
        UUID user = aUser().verified().create().id();
        UUID other = aUser().verified().create().id();
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        plant(other, "NOT_VERIFIED", "VERIFIED", at, "TRUST_QUALIFICATION", null);

        assertThat(statusAt(user, at.plusSeconds(60))).isEqualTo(VerifiedPlayerStatus.NOT_VERIFIED);
    }

    private VerifiedPlayerStatus statusAt(UUID user, Instant at) {
        return runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()),
                command -> statuses.statusAt(command, user, at)));
    }

    /** One transition row past the triggers ({@code session_replication_role = replica}); every CHECK still applies. */
    private static void plant(UUID user, String from, String to, Instant at, String cause, UUID admin) {
        OWNER.sql("""
                        SET session_replication_role = replica;
                        INSERT INTO trust.verified_player_transition (id, user_id, transition_id, from_status, to_status,
                                                                      occurred_at, cause, admin_id)
                        VALUES (gen_random_uuid(), '%s', gen_random_uuid(), '%s', '%s', '%s', '%s', %s);
                        """.formatted(user, from, to, at, cause, admin == null ? "NULL" : "'" + admin + "'"))
                .update();
    }
}
