package ps.e7gz.notification.internal;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.NotifiedBookings.NotifiedBooking;
import ps.e7gz.notification.internal.BookingNotificationRules.Recipient;
import ps.e7gz.notification.internal.BookingNotificationRules.Send;

/**
 * Creates one booking notification, at most once per {@code origin} (E7-55): the notification id is derived from the
 * origin - the outbox event or the reminder job - the code and the recipient, so running the same origin again finds the
 * row and changes nothing ({@link NotificationService#notifyOnce}).
 *
 * <p>Params: a customer notification carries {@code venueName}, the venue's name when the notification is created
 * (the customer app reads it for BOOKING_CONFIRMED, BOOKING_REJECTED and BOOKING_STARTS_SOON; E7-51). A venue
 * notification needs none: the Dashboard opens the booking from its Booking ID (FR-NTF-04).
 */
@Component
class BookingNotifier {

    static final String VENUE_NAME = "venueName";

    private final NotificationService notifications;

    BookingNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * @param origin e.g. {@code outbox:{eventId}} or a reminder's job key
     * @return whether a notification was created now; never one for the customer of a Manual Booking (there is none)
     */
    boolean send(CommandContext context, String origin, Send send, NotifiedBooking booking) {
        boolean toCustomer = send.recipient() == Recipient.CUSTOMER;
        UUID recipient = toCustomer ? booking.customerId() : booking.venueId();
        if (recipient == null) {
            return false;
        }
        NotificationRecipientType type = toCustomer ? NotificationRecipientType.CUSTOMER
                : NotificationRecipientType.VENUE;
        Map<String, Object> params = toCustomer ? Map.of(VENUE_NAME, booking.venueName()) : Map.of();
        return notifications.notifyOnce(context, id(origin, send.code(), recipient),
                NotificationRequest.aboutBooking(send.code(), type, recipient, booking.bookingId(), params));
    }

    static UUID id(String origin, String code, UUID recipient) {
        return UUID.nameUUIDFromBytes(("E7-55 booking notification|" + origin + "|" + code + "|" + recipient)
                .getBytes(StandardCharsets.UTF_8));
    }
}
