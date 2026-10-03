package ps.e7gz.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.resource.api.ResourceStates.ResourceState;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-30 - {@link ResourceStates} (venue resolution and checks 2-4 of the availability predicate) against the migrated
 * database, on the command's own transaction.
 */
class ResourceStatesIntegrationTest extends IntegrationTest {

    private static final UUID VENUE = id(0x002);
    private static final UUID ACTIVE = id(0x101);
    private static final UUID DISABLED = id(0x102);
    private static final UUID ARCHIVED = id(0x103);
    private static final UUID REVIEW = id(0x104);
    private static final UUID BLOCKED = id(0x105);
    private static final UUID BLOCK_ENDED = id(0x106);
    private static final UUID IN_COMMAND = id(0x107);

    private static final String SCRIPT = "ps/e7gz/resource/resource-states-fixtures.sql";
    private static boolean loaded;

    @Autowired
    CommandRunner runner;

    @Autowired
    ResourceStates resources;

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
            throw new IllegalStateException("Resource states fixtures failed", e);
        }
        loaded = true;
    }

    private <R> R run(Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(body));
    }

    private Optional<ResourceState> find(UUID resource) {
        return run(ctx -> resources.find(ctx, resource));
    }

    private boolean blockedAt(UUID resource, String at) {
        return run(ctx -> resources.resourceBlocked(ctx, resource, Instant.parse(at)));
    }

    @Test
    void findReturnsTheVenueAndBothStateDomainsSeparately() {
        assertThat(find(ACTIVE)).contains(
                new ResourceState(ACTIVE, VENUE, ResourceBaseState.ACTIVE, OperationalRestriction.NONE));
        assertThat(find(DISABLED)).contains(
                new ResourceState(DISABLED, VENUE, ResourceBaseState.DISABLED, OperationalRestriction.NONE));
        assertThat(find(ARCHIVED)).contains(
                new ResourceState(ARCHIVED, VENUE, ResourceBaseState.ARCHIVED, OperationalRestriction.NONE));
        assertThat(find(REVIEW)).as("EC-AVAI-002: ACTIVE base state with an owner-review restriction").contains(
                new ResourceState(REVIEW, VENUE, ResourceBaseState.ACTIVE,
                        OperationalRestriction.OWNER_REVIEW_REQUIRED));
    }

    @Test
    void findOfAnUnknownResourceIsEmpty() {
        assertThat(find(UUID.randomUUID())).isEmpty();
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
    }

    @Test
    void aResourceWithoutABlockIsNotBlocked() {
        assertThat(blockedAt(ACTIVE, "2030-01-01T12:00:00Z")).isFalse();
        assertThat(blockedAt(UUID.randomUUID(), "2030-01-01T12:00:00Z")).isFalse();
    }

    @Test
    void theReadsSeeTheCommandsOwnUncommittedWrites() {
        Instant at = Instant.parse("2030-01-05T12:00:00Z");

        assertThatThrownBy(() -> run(ctx -> {
            ctx.jdbc().sql("""
                    INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
                    VALUES (:id, :resource, 'E7-30 in-command block', true, :started)
                    """)
                    .param("id", UUID.randomUUID()).param("resource", IN_COMMAND)
                    .param("started", OffsetDateTime.parse("2030-01-05T11:00:00Z"))
                    .update();
            assertThat(resources.resourceBlocked(ctx, IN_COMMAND, at)).as("visible inside the command").isTrue();
            throw new RollBack();
        })).isInstanceOf(RollBack.class);

        assertThat(blockedAt(IN_COMMAND, "2030-01-05T12:00:00Z")).as("rolled back").isFalse();
    }

    private static UUID id(int n) {
        return UUID.fromString("e7300200-0000-4000-8000-%012x".formatted(n));
    }
}
