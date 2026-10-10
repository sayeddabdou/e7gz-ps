package ps.e7gz.geography;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.anArea;

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
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.support.IntegrationTest;

/** E7-46 - {@link AreaLookup#isActive(CommandContext, UUID)} (FR-BOK-11 condition 7) on the command's own transaction. */
class AreaLookupInCommandIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    AreaLookup areas;

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

    private boolean isActive(UUID areaId) {
        return runner.run(new TestCommand<>(ctx -> areas.isActive(ctx, areaId)));
    }

    @Test
    void anActiveAreaIsActiveAndAnInactiveOrUnknownOneIsNot() {
        assertThat(isActive(anArea().create().id())).isTrue();
        assertThat(isActive(anArea().inactive().create().id())).isFalse();
        assertThat(isActive(UUID.randomUUID())).isFalse();
    }

    @Test
    void theCommandReadAgreesWithTheReadOutsideACommand() {
        UUID active = anArea().create().id();
        UUID inactive = anArea().inactive().create().id();

        assertThat(isActive(active)).isEqualTo(areas.isActive(active));
        assertThat(isActive(inactive)).isEqualTo(areas.isActive(inactive));
    }
}
