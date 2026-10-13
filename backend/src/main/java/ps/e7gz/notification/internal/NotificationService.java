package ps.e7gz.notification.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationChannel;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;

/**
 * {@link Notifications} over {@code notification.notification} and {@code notification_delivery} (arch 08 section 4).
 *
 * <p>The catalogue rules stay in the database (ADR-026): the guard triggers refuse an unknown code, a recipient or a
 * reference the code does not allow and a secret in the params, and the deferred constraint trigger refuses, at commit,
 * a notification without a delivery for each required channel ({@code notification.assert_required_channels}). This
 * class only reads which channels the code requires, to create exactly those deliveries.
 *
 * <p>IN_APP is delivered by the commit itself (the row is in the recipient's list), so its delivery is recorded
 * {@code SENT} in the same transaction. PUSH and EMAIL stay {@code PENDING} and one outbox event asks the worker to send
 * them after commit (FR-NTF-12). SMS is OTP only and never goes through here: the OTP is sent from its own outbox event
 * with the code sealed in the payload (E7-41, PO decision 9 Oct 2026).
 */
@Component
class NotificationService implements Notifications {

    /** {@code provider_message_id} of an in-app delivery: the notification row is the message. */
    static final String IN_APP_MESSAGE_ID = "in-app";

    private final Outbox outbox;
    private final ObjectMapper json;

    NotificationService(Outbox outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public UUID notify(CommandContext context, NotificationRequest request) {
        UUID id = UUID.randomUUID();
        insert(context, id, request);
        return id;
    }

    /**
     * Creates the notification under {@code id} unless it exists already; returns whether it created it. The booking
     * notifications use it with an id derived from the outbox event (E7-55): a consumer runs again on every retry of
     * its event, and the second run finds the row and changes nothing. The dispatcher holds the event's row lock while
     * its consumers run, so two runs of one event never overlap; the primary key refuses a duplicate anyway.
     */
    boolean notifyOnce(CommandContext context, UUID id, NotificationRequest request) {
        Objects.requireNonNull(id, "id");
        boolean exists = context.jdbc().sql("SELECT EXISTS (SELECT 1 FROM notification.notification WHERE id = :id)")
                .param("id", id).query(Boolean.class).single();
        if (exists) {
            return false;
        }
        insert(context, id, request);
        return true;
    }

    private void insert(CommandContext context, UUID id, NotificationRequest request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        context.jdbc().sql("""
                        INSERT INTO notification.notification (id, recipient_type, recipient_id, code, booking_id,
                                                               tournament_id, params, created_at)
                        VALUES (:id, :recipientType, :recipientId, :code, :bookingId, :tournamentId,
                                CAST(:params AS jsonb), :createdAt)""")
                .param("id", id)
                .param("recipientType", request.recipientType().name())
                .param("recipientId", request.recipientId())
                .param("code", request.code())
                .param("bookingId", request.bookingId())
                .param("tournamentId", request.tournamentId())
                .param("params", params(request.params()))
                .param("createdAt", OffsetDateTime.ofInstant(context.commandNow(), ZoneOffset.UTC))
                .update();

        boolean toSend = false;
        for (NotificationChannel channel : requiredChannels(context, request.code())) {
            if (channel == NotificationChannel.SMS) {
                // FR-NTF-01: SMS is OTP only, and the OTP never becomes notification params (E7-41).
                throw new IllegalArgumentException(request.code() + " is an SMS code: the OTP is sent by PhoneOtpIssued");
            }
            UUID delivery = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO notification.notification_delivery (id, notification_id, channel, status, attempt)
                            VALUES (:id, :notification, :channel, 'PENDING', 0)""")
                    .param("id", delivery).param("notification", id).param("channel", channel.name()).update();
            if (channel == NotificationChannel.IN_APP) {
                DeliveryAttempts.record(context.jdbc(), delivery, DeliveryOutcome.sent(IN_APP_MESSAGE_ID));
            } else {
                toSend = true;
            }
        }
        if (toSend) {
            outbox.publish(context, new NewOutboxEvent(IntegrationEvents.NOTIFICATION_DELIVERY_REQUESTED,
                    "NOTIFICATION", id, Map.of("notificationId", id.toString())));
        }
    }

    /** The code's {@code required_channels}; an unknown code has none here and is refused by the insert above. */
    private static List<NotificationChannel> requiredChannels(CommandContext context, String code) {
        return context.jdbc().sql("""
                        SELECT unnest(required_channels) FROM notification.notification_catalogue WHERE code = :code""")
                .param("code", code)
                .query((rs, row) -> NotificationChannel.valueOf(rs.getString(1)))
                .list();
    }

    private String params(Map<String, Object> params) {
        try {
            return json.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("notification params must be plain JSON values", e);
        }
    }
}
