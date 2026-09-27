package ps.e7gz.common.command.archfixture;

import org.springframework.transaction.annotation.Transactional;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Positive control for {@code CommandBoundaryArchitectureTest}: a Command whose only fault is a read-only
 * {@code @Transactional} - still rejected, because CommandRunner is the sole transaction boundary of every Command.
 */
@Transactional(readOnly = true)
public class ReadOnlyTransactionalCommand implements Command<Integer> {

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
    }

    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    @Transactional(readOnly = true)
    public Integer execute(CommandContext context) {
        return context.jdbc().sql("SELECT 1").query(Integer.class).single();
    }
}
