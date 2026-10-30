package ps.e7gz.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.money.Money;
import ps.e7gz.config.api.BookingFees;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-46, E7-68 - {@link BookingFees}: the database's {@code config.booking_fee_for(nominalMinutes, at)} as an EGP amount
 * at scale 2, on the command's own transaction - EGP 0 per booking at launch, and a change counts from its own instant
 * onwards. The per-hour mode of PCR-010 is {@code PointsBookingFeePaymentIntegrationTest}'s.
 */
class BookingFeesIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    BookingFees fees;

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

    private Money feeNow() {
        return runner.run(new TestCommand<>(ctx -> fees.forBooking(ctx, 60, ctx.commandNow()).amount()));
    }

    @Test
    void theLaunchFeeIsZeroAtScaleTwo() {
        Money fee = runner.run(new TestCommand<>(ctx -> fees.forBooking(ctx, 60, Instant.parse("2020-01-01T00:00:00Z")).amount()));

        assertThat(fee).isEqualTo(Money.ZERO);
        assertThat(fee.amount().scale()).isEqualTo(2);
        assertThat(feeNow()).isEqualTo(Money.ZERO);
    }

    @Test
    void aChangedFeeIsEffectiveFromItsChangeAndNotBefore() {
        Instant before = runner.run(new TestCommand<Instant>(CommandContext::commandNow));
        BookingFeeConfiguration.change(runner, "5.5");
        try {
            assertThat(feeNow()).isEqualTo(Money.of("5.50"));
            assertThat(feeNow().amount().scale()).isEqualTo(2);
            Money atBefore = runner.run(new TestCommand<>(ctx -> fees.forBooking(ctx, 60, before).amount()));
            assertThat(atBefore).isEqualTo(Money.ZERO);
        } finally {
            BookingFeeConfiguration.change(runner, "0");
        }
        assertThat(feeNow()).isEqualTo(Money.ZERO);
    }

    @Test
    void aFeeWithMoreThanTwoDecimalPlacesIsRefusedNotRounded() {
        BookingFeeConfiguration.change(runner, "1.005");
        try {
            assertThatThrownBy(this::feeNow).isInstanceOf(IllegalStateException.class).hasMessageContaining("booking.fee");
        } finally {
            BookingFeeConfiguration.change(runner, "0");
        }
    }
}
