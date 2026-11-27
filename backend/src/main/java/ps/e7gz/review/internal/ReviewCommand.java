package ps.e7gz.review.internal;

import java.util.function.Function;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;

/**
 * One Review command. Category B (ADR-022): Reviews apply no venue-eligibility gate - a SUSPENDED or INACTIVE Venue
 * keeps and still receives Reviews (FR-REV-09) - so no new-activity (A) rule applies.
 */
record ReviewCommand<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    @Override
    public R execute(CommandContext context) {
        return body.apply(context);
    }
}
