package ps.e7gz.identity.internal;

import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/** The audit row of a refused staff login for a known account (E7-56); nothing else changes. */
record RecordLoginRefusalCommand(LoginAudit audit) implements Command<Void> {

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;               // no Venue in scope (ADR-022)
    }

    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public Void execute(CommandContext context) {
        audit.write(context);
        return null;
    }
}
