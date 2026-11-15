package ps.e7gz.booking.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.loyalty.api.BookingFeePayments;
import ps.e7gz.resource.api.ResourceCatalog;

/**
 * {@code GET /customer/bookings/quote} (PO decision 5 Oct 2026, E7-39): the amounts a booking created now would store,
 * computed by the same {@link BookingPricing} as creation, and how the Customer's Points and Wallet would pay its fee
 * now (PCR-010 item 6, {@link BookingFeePayments#preview}). Not an availability check and not a hold: one read-only
 * command that writes nothing; the created booking's own amounts are authoritative.
 */
@Service
public class BookingQuotes {

    private final CommandRunner runner;
    private final ResourceCatalog resources;
    private final BookingPricing pricing;
    private final BookingFeePayments payments;

    BookingQuotes(CommandRunner runner, ResourceCatalog resources, BookingPricing pricing,
                  BookingFeePayments payments) {
        this.runner = runner;
        this.resources = resources;
        this.pricing = pricing;
        this.payments = payments;
    }

    /**
     * @throws ApiException {@code NOT_FOUND} for an unknown Resource (PO decision 9 Oct 2026),
     *         {@code BOOKING_DURATION_INVALID} as creation's conditions 13-14, {@code RESOURCE_NOT_ELIGIBLE} when the
     *         venue has no price for it (condition 20, PO decision 5 Oct 2026) or the play mode does not fit the activity
     */
    public BookingAmounts quote(UUID resourceId, PlayMode playMode, int durationMinutes) {
        return runner.run(new Quote<>(resourceId, playMode, durationMinutes, (context, amounts) -> amounts));
    }

    /** The quote of {@code customerId}, with how their Points and Wallet would pay its fee (same failures). */
    public BookingQuote quote(UUID customerId, UUID resourceId, PlayMode playMode, int durationMinutes) {
        Objects.requireNonNull(customerId, "customerId");
        return runner.run(new Quote<>(resourceId, playMode, durationMinutes, (context, amounts) ->
                new BookingQuote(amounts, amounts.bookingFee().isZero()
                        ? BookingFeePayments.FeePayment.unpaid(amounts.bookingFee())
                        : payments.preview(context, customerId, durationMinutes, context.commandNow()))));
    }

    private final class Quote<R> implements Command<R> {

        private final UUID resourceId;
        private final PlayMode playMode;
        private final int durationMinutes;
        private final BiFunction<CommandContext, BookingAmounts, R> view;

        Quote(UUID resourceId, PlayMode playMode, int durationMinutes,
              BiFunction<CommandContext, BookingAmounts, R> view) {
            this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
            this.playMode = playMode;
            this.durationMinutes = durationMinutes;
            this.view = view;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        /** Locks nothing: reads only, and the answer is no promise. */
        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            if (resources.find(context, resourceId).isEmpty()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            OnlineCreationPredicate.durationFailure(durationMinutes).ifPresent(failed -> {
                throw new ApiException(failed.code());
            });
            BookingAmounts amounts = pricing.price(context, resourceId, playMode, durationMinutes)
                    .orElseThrow(() -> new ApiException(OnlineCreationCondition.OTHER_PRODUCT_VALIDATION.code()));
            return view.apply(context, amounts);
        }
    }
}
