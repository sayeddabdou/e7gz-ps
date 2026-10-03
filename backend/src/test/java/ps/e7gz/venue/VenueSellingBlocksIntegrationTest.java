package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.venue.api.VenueSellingBlocks;

/**
 * E7-30 - {@link VenueSellingBlocks} (check 1 of the availability predicate) against the migrated database: a selling
 * block is a state in force from {@code started_at} until it is ended, read on the command's own transaction.
 */
class VenueSellingBlocksIntegrationTest extends IntegrationTest {

    private static final UUID BLOCKED = id(0x002);
    private static final UUID BLOCK_ENDED = id(0x003);
    private static final UUID UNBLOCKED = id(0x004);
    private static final UUID IN_COMMAND = id(0x005);

    private static final String SCRIPT = "ps/e7gz/venue/venue-selling-block-fixtures.sql";
    private static boolean loaded;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueSellingBlocks blocks;

    record TestCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
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

    static final class RollBack extends RuntimeException {

        RollBack() {
            super("roll back on purpose", null, false, false);
        }
    }

    @BeforeEach
    void fixtures() {
        load();
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR)) {
            owner.setAutoCommit(false);
            ScriptUtils.executeSqlScript(owner, new ClassPathResource(SCRIPT));
            owner.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Venue selling block fixtures failed", e);
        }
        loaded = true;
    }

    private boolean blockedAt(UUID venue, String at) {
        return runner.run(new TestCommand<>(ctx -> blocks.venueBlocked(ctx, venue, Instant.parse(at))));
    }

    @Test
    void anActiveBlockIsInForceFromItsStartWithNoEnd() {
        assertThat(blockedAt(BLOCKED, "2030-01-01T09:59:59Z")).as("before started_at").isFalse();
        assertThat(blockedAt(BLOCKED, "2030-01-01T10:00:00Z")).as("at started_at").isTrue();
        assertThat(blockedAt(BLOCKED, "2031-06-01T00:00:00Z")).as("a state, not an interval: no end").isTrue();
    }

    @Test
    void anEndedBlockIsInForceOnlyUntilItsEnd() {
        assertThat(blockedAt(BLOCK_ENDED, "2030-01-01T10:00:00Z")).isTrue();
        assertThat(blockedAt(BLOCK_ENDED, "2030-01-02T09:59:59Z")).isTrue();
        assertThat(blockedAt(BLOCK_ENDED, "2030-01-02T10:00:00Z")).as("ended at that instant").isFalse();
        assertThat(blockedAt(BLOCK_ENDED, "2030-02-01T00:00:00Z")).isFalse();
    }

    @Test
    void aVenueWithoutABlockOrAnUnknownVenueIsNotBlocked() {
        assertThat(blockedAt(UNBLOCKED, "2030-01-01T12:00:00Z")).isFalse();
        assertThat(blockedAt(UUID.randomUUID(), "2030-01-01T12:00:00Z")).isFalse();
    }

    @Test
    void theReadSeesTheCommandsOwnUncommittedWrite() {
        Instant at = Instant.parse("2030-01-05T12:00:00Z");

        assertThatThrownBy(() -> runner.run(new TestCommand<>(ctx -> {
            ctx.jdbc().sql("""
                    INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
                    VALUES (:id, :venue, 'E7-30 in-command block', true, :started)
                    """)
                    .param("id", UUID.randomUUID()).param("venue", IN_COMMAND)
                    .param("started", OffsetDateTime.parse("2030-01-05T11:00:00Z"))
                    .update();
            assertThat(blocks.venueBlocked(ctx, IN_COMMAND, at)).as("visible inside the command").isTrue();
            throw new RollBack();
        }))).isInstanceOf(RollBack.class);

        assertThat(blockedAt(IN_COMMAND, "2030-01-05T12:00:00Z")).as("rolled back").isFalse();
    }

    private static UUID id(int n) {
        return UUID.fromString("e7300100-0000-4000-8000-%012x".formatted(n));
    }
}
