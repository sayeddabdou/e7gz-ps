package ps.e7gz.config;

import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * Changes the Global Booking Fee ({@code booking.fee}) for a test, through its producer
 * {@code config.change_configuration} in a Platform Admin command, exactly as an admin change would (append-only,
 * effective from the command instant). The value is global: a test that changes it must set it back to {@code 0} in a
 * {@code finally}, so later tests see the launch value again (test classes run one after another).
 */
public final class BookingFeeConfiguration {

    private BookingFeeConfiguration() {
    }

    /** {@code fee} as a JSON number, e.g. {@code "5.50"}; must differ from the current value. */
    public static void change(CommandRunner runner, String fee) {
        UUID admin = anAdmin().create().id();
        runner.run(new Change(admin, fee));
    }

    private record Change(UUID admin, String fee) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(admin, UUID.randomUUID(), LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            context.jdbc().sql("SELECT config.change_configuration('booking.fee', CAST(:fee AS jsonb), 'E7-46 test')")
                    .param("fee", fee)
                    .query(UUID.class).single();
            return null;
        }
    }
}
