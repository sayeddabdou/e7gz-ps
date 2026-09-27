package ps.e7gz.common.command.archfixture;

import java.time.Instant;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/** Positive control for {@code CommandBoundaryArchitectureTest}: a Command that reads rule time from Instant.now(). */
public class InstantNowCommand implements Command<Instant> {

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
        return Instant.now();
    }
}
