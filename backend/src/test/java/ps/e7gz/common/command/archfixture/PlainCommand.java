package ps.e7gz.common.command.archfixture;

import java.time.Instant;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Negative control for {@code CommandBoundaryArchitectureTest}: a Command that leaves the transaction to the runner
 * and takes rule time from the command instant (using {@link Instant} values is allowed).
 */
public class PlainCommand implements Command<Instant> {

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
    }

    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public Instant execute(CommandContext context) {
        Instant now = context.commandNow();
        return now.plusSeconds(context.jdbc().sql("SELECT 600").query(Integer.class).single());
    }
}
