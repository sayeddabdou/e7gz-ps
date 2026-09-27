package ps.e7gz.common.command.archfixture;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/** Positive control for {@code CommandBoundaryArchitectureTest}: a Command that tries to own its transaction. */
@Transactional
public class SelfTransactionalCommand implements Command<Void> {

    private final PlatformTransactionManager manager;

    public SelfTransactionalCommand(PlatformTransactionManager manager) {
        this.manager = manager;
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
    }

    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    @Transactional
    public Void execute(CommandContext context) {
        new TransactionTemplate(manager).executeWithoutResult(status -> { });
        return null;
    }
}
