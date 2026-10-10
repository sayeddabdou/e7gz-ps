package ps.e7gz.support.boundary;

import java.util.function.Function;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;

/** A test's own command body under a given scope, e.g. to move a deadline before acting (E7-45). */
public final class TestCommands {

    private TestCommands() {
    }

    /** {@code body} as a command under {@code scope}: the locks the guards of the rows it writes need. */
    public static <R> Command<R> under(CommandScope scope, Function<CommandContext, R> body) {
        return new Command<>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return scope;
            }

            @Override
            public R execute(CommandContext context) {
                return body.apply(context);
            }
        };
    }
}
