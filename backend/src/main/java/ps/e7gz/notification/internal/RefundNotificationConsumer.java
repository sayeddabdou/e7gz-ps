package ps.e7gz.notification.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.notification.api.NotifiedBookings.NotifiedBooking;
import ps.e7gz.notification.api.RefundedBookings;
import ps.e7gz.notification.internal.RefundNotificationRules.Refund;

/**
 * The customer's refund confirmation ({@link RefundNotificationRules}; E7-80), from the loyalty ledger events after
 * their command committed (FR-NTF-12). One per ledger event, so exactly once: the id is derived from the event
 * ({@link NotificationService#notifyOnce}). A BOOKING code (FR-NTF-04), with params, never prose (ADR-021):
 * {@code amount} and {@code refundedAs} (WALLET or POINTS). The Points-movement notifications stay as E7-67 decided:
 * none for a refund ({@code PointsNotificationRules}); this confirmation is the one message (R5).
 */
@Component
@Profile("worker")
class RefundNotificationConsumer implements OutboxConsumer {

    private final CommandRunner runner;
    private final NotificationService notifications;
    private final NotifiedBookings bookings;
    private final RefundedBookings refunds;

    RefundNotificationConsumer(CommandRunner runner, NotificationService notifications, NotifiedBookings bookings,
                               RefundedBookings refunds) {
        this.runner = runner;
        this.notifications = notifications;
        this.bookings = bookings;
        this.refunds = refunds;
    }

    @Override
    public Set<String> eventTypes() {
        return RefundNotificationRules.eventTypes();
    }

    @Override
    public void handle(OutboxEvent event) {
        RefundNotificationRules.refundOf(event).ifPresent(refund -> runner.run(new Deliver(event, refund)));
    }

    /** One notification; it locks nothing and writes only its own row. */
    private final class Deliver implements Command<Void> {

        private final OutboxEvent event;
        private final Refund refund;

        private Deliver(OutboxEvent event, Refund refund) {
            this.event = event;
            this.refund = refund;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            UUID bookingId = refund.bookingId() != null ? refund.bookingId()
                    : refunds.bookingOfPointsRefund(context, refund.pointsTransactionId())
                    .orElseThrow(() -> new PermanentDeliveryFailure(event.eventType() + " " + event.id()
                            + " is a Points refund no refund decision names"));
            Optional<NotifiedBooking> found = bookings.find(context, bookingId);
            NotifiedBooking booking = found.orElseThrow(() -> new PermanentDeliveryFailure(event.eventType() + " "
                    + event.id() + " names a booking that does not exist"));
            if (booking.customerId() == null || booking.terminalReason() == null) {
                throw new PermanentDeliveryFailure(event.eventType() + " " + event.id()
                        + " refunds a booking with no customer or no terminal reason");
            }
            String code = RefundNotificationRules.code(booking.terminalReason());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("amount", refund.amount());
            params.put("refundedAs", refund.refundedAs().name());
            notifications.notifyOnce(context,
                    OperationalNotificationConsumer.id("outbox:" + event.id(), code, booking.customerId()),
                    NotificationRequest.aboutBooking(code, NotificationRecipientType.CUSTOMER, booking.customerId(),
                            bookingId, params));
            return null;
        }
    }
}
