package ps.e7gz.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aResource;

import java.util.Optional;
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
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.support.IntegrationTest;

/** E7-46 - {@link ResourceCatalog#name}: the name a Booking snapshots, read on the command's own transaction. */
class ResourceCatalogNameIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    ResourceCatalog catalog;

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

    private Optional<String> name(UUID resourceId) {
        return runner.run(new TestCommand<>(ctx -> catalog.name(ctx, resourceId)));
    }

    @Test
    void theResourceNameIsItsCurrentName() {
        assertThat(name(aResource().named("Lounge PS5").create().id())).contains("Lounge PS5");
        assertThat(name(aResource().billiards().named("Pool Table 9").archived().create().id()))
                .contains("Pool Table 9");
    }

    @Test
    void anUnknownResourceHasNoName() {
        assertThat(name(UUID.randomUUID())).isEmpty();
    }
}
