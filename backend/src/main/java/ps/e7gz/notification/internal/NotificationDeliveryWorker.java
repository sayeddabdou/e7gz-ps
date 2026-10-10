package ps.e7gz.notification.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationChannel;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxAttempts;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.notification.internal.email.EmailSender;
import ps.e7gz.notification.internal.push.PushSender;
import ps.e7gz.notification.internal.webpush.WebPushSender;

/**
 * Sends the PUSH and EMAIL deliveries of a notification after its command committed (arch 08 sections 4.1-4.3,
 * FR-NTF-12). One {@code NotificationDeliveryRequested} event per notification: every delivery still {@code PENDING} or
 * {@code FAILED} is tried once per attempt of the event, and each attempt is recorded through
 * {@code notification.record_delivery_attempt}. A delivery already {@code SENT} or {@code DEAD_LETTER} is never sent
 * again, so a retried event repeats only what failed ("never twice, visibly").
 *
 * <ul>
 *   <li>Sent: {@code SENT}.</li>
 *   <li>Transient failure: {@code FAILED}, and the event is retried on the outbox back-off (5 s ... 1 h); on the event's
 *       last attempt {@code DEAD_LETTER} (ERROR, Sev-2), which only the admin "try again" reopens.</li>
 *   <li>Permanent failure: {@code DEAD_LETTER} at once. An invalid push token is revoked, never deleted.</li>
 *   <li>A customer with no active push device: {@code DEAD_LETTER NO_ACTIVE_DEVICE}, logged at INFO only - the in-app
 *       notification is still in their list (PO decision 10 Oct 2026).</li>
 * </ul>
 *
 * <p>Customers get English text (the app is English at launch, FRS section 3.1). A venue's PUSH is Web Push to every
 * active Dashboard browser of its account, in the account's language (E7-44; the deliveries left {@code PENDING} before
 * it are sent too); a subscription the push service no longer knows is revoked, never deleted. A venue has no email
 * address. Admin deliveries wait {@code PENDING} for the admin notification surface. SMS never comes here (OTP only,
 * E7-41).
 */
@Component
@Profile("worker")
class NotificationDeliveryWorker implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    static final String NO_ACTIVE_DEVICE = "NO_ACTIVE_DEVICE";
    static final String NO_EMAIL_ADDRESS = "NO_EMAIL_ADDRESS";
    static final String TEMPLATE_MISSING = "TEMPLATE_MISSING";

    private final JdbcClient jdbc;
    private final NotificationTemplates templates;
    private final PushSender push;
    private final WebPushSender webPush;
    private final EmailSender email;
    private final OutboxAttempts attempts;
    private final ObjectMapper json;

    NotificationDeliveryWorker(DataSource appDataSource, NotificationTemplates templates, PushSender push,
                               WebPushSender webPush, EmailSender email, OutboxAttempts attempts, ObjectMapper json) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.templates = templates;
        this.push = push;
        this.webPush = webPush;
        this.email = email;
        this.attempts = attempts;
        this.json = json;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(IntegrationEvents.NOTIFICATION_DELIVERY_REQUESTED);
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID notificationId = event.uuid("notificationId");
        Notification notification = notification(notificationId).orElseThrow(() -> new PermanentDeliveryFailure(
                "Notification " + notificationId + " does not exist"));
        boolean last = attempts.isLast(event);
        int retrying = 0;
        for (Delivery delivery : dueDeliveries(notificationId)) {
            Optional<DeliveryOutcome> outcome = send(notification, delivery);
            if (outcome.isEmpty()) {
                continue;
            }
            DeliveryAttempts.record(jdbc, delivery.id(), outcome.get(), last);
            report(notification, delivery, outcome.get(), last);
            if (outcome.get() instanceof DeliveryOutcome.Transient && !last) {
                retrying++;
            }
        }
        if (retrying > 0) {
            throw new IllegalStateException(retrying + " delivery(ies) of notification " + notificationId
                    + " failed for now and will be retried");
        }
    }

    /** Empty: no adapter for this recipient and channel yet; the delivery stays {@code PENDING}. */
    private Optional<DeliveryOutcome> send(Notification notification, Delivery delivery) {
        try {
            return switch (notification.recipientType()) {
                case CUSTOMER -> switch (delivery.channel()) {
                    case PUSH -> Optional.of(customerPush(notification));
                    case EMAIL -> Optional.of(customerEmail(notification));
                    case IN_APP, SMS -> Optional.of(DeliveryOutcome.permanentFailure("CHANNEL_NOT_SENT_BY_WORKER"));
                };
                case VENUE -> switch (delivery.channel()) {
                    case PUSH -> Optional.of(venuePush(notification));
                    case EMAIL -> Optional.of(DeliveryOutcome.permanentFailure(NO_EMAIL_ADDRESS));
                    case IN_APP, SMS -> Optional.of(DeliveryOutcome.permanentFailure("CHANNEL_NOT_SENT_BY_WORKER"));
                };
                case ADMIN -> {
                    log.warn("Notification {} {} {} delivery waits: no {} adapter for ADMIN recipients yet",
                            notification.code(), notification.id(), delivery.channel(), delivery.channel());
                    yield Optional.empty();
                }
            };
        } catch (NotificationTemplates.MissingTemplate e) {
            log.error("Notification {} {} not sent: {}", notification.code(), notification.id(), e.getMessage());
            return Optional.of(DeliveryOutcome.permanentFailure(TEMPLATE_MISSING));
        }
    }

    private DeliveryOutcome customerPush(Notification notification) {
        List<Device> devices = activeDevices(notification.recipientId());
        if (devices.isEmpty()) {
            return DeliveryOutcome.permanentFailure(NO_ACTIVE_DEVICE);
        }
        NotificationTemplates.Rendered text = templates.push(notification.code(), Language.EN,
                NotificationTemplates.textParams(notification.params()));
        PushSender.PushMessage message = new PushSender.PushMessage(text.title(), text.body(), data(notification));
        String sent = null;
        String transientError = null;
        String invalidError = null;
        for (Device device : devices) {
            switch (push.send(device.token(), message)) {
                case PushSender.PushResult.Sent ok -> sent = sent == null ? ok.providerMessageId() : sent;
                case PushSender.PushResult.Transient failure -> transientError = failure.errorCode();
                case PushSender.PushResult.InvalidToken invalid -> {
                    revoke(device.id());
                    invalidError = invalid.errorCode();
                }
            }
        }
        if (sent != null) {
            return DeliveryOutcome.sent(sent);
        }
        return transientError != null ? DeliveryOutcome.transientFailure(transientError)
                : DeliveryOutcome.permanentFailure(invalidError);
    }

    /** ADR-014, 01 section 14 A6: Web Push to the venue account's Dashboard browsers, in its language (FR-VEN-40). */
    private DeliveryOutcome venuePush(Notification notification) {
        Optional<VenueAccount> account = venueAccount(notification.recipientId());
        List<VenueBrowser> browsers = account.map(found -> activeBrowsers(found.id())).orElse(List.of());
        if (browsers.isEmpty()) {
            return DeliveryOutcome.permanentFailure(NO_ACTIVE_DEVICE);
        }
        NotificationTemplates.Rendered text = templates.push(notification.code(), account.get().language(),
                NotificationTemplates.textParams(notification.params()));
        PushSender.PushMessage message = new PushSender.PushMessage(text.title(), text.body(), data(notification));
        String sent = null;
        String transientError = null;
        String permanentError = null;
        for (VenueBrowser browser : browsers) {
            switch (webPush.send(browser.subscription(), message)) {
                case WebPushSender.WebPushResult.Sent ok -> sent = sent == null ? ok.messageId() : sent;
                case WebPushSender.WebPushResult.Transient failure -> transientError = failure.errorCode();
                case WebPushSender.WebPushResult.Gone gone -> {
                    revokeBrowser(browser.id());
                    permanentError = gone.errorCode();
                }
                case WebPushSender.WebPushResult.Rejected rejected -> permanentError = rejected.errorCode();
            }
        }
        if (sent != null) {
            return DeliveryOutcome.sent(sent);
        }
        return transientError != null ? DeliveryOutcome.transientFailure(transientError)
                : DeliveryOutcome.permanentFailure(permanentError);
    }

    private DeliveryOutcome customerEmail(Notification notification) {
        Optional<String> address = jdbc.sql("""
                        SELECT email FROM iam.user_account
                         WHERE id = :id AND anonymised_at IS NULL AND btrim(email) <> ''""")
                .param("id", notification.recipientId()).query(String.class).optional();
        if (address.isEmpty()) {
            return DeliveryOutcome.permanentFailure(NO_EMAIL_ADDRESS);
        }
        // PO decision 10 Oct 2026: the registration address, verified or not (email verification is deferred).
        NotificationTemplates.Rendered text = templates.email(notification.code(), Language.EN,
                NotificationTemplates.textParams(notification.params()));
        return switch (email.send(address.get(), text.title(), text.body())) {
            case EmailSender.EmailResult.Sent ok -> DeliveryOutcome.sent(ok.messageId());
            case EmailSender.EmailResult.Rejected rejected -> DeliveryOutcome.permanentFailure(rejected.errorCode());
            case EmailSender.EmailResult.Transient failure -> DeliveryOutcome.transientFailure(failure.errorCode());
        };
    }

    /** FR-NTF-04: the push carries the exact Booking (or Tournament) the app opens. */
    private static Map<String, String> data(Notification notification) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("code", notification.code());
        data.put("notificationId", notification.id().toString());
        if (notification.bookingId() != null) {
            data.put("bookingId", notification.bookingId().toString());
        }
        if (notification.tournamentId() != null) {
            data.put("tournamentId", notification.tournamentId().toString());
        }
        return data;
    }

    private static void report(Notification notification, Delivery delivery, DeliveryOutcome outcome, boolean last) {
        switch (outcome) {
            case DeliveryOutcome.Sent ignored -> log.debug("Notification {} {} {} sent", notification.code(),
                    notification.id(), delivery.channel());
            case DeliveryOutcome.Transient failure when last -> log.error(
                    "Notification {} {} {} DEAD_LETTER after the last attempt: {} (Sev-2)", notification.code(),
                    notification.id(), delivery.channel(), failure.errorCode());
            case DeliveryOutcome.Transient failure -> log.warn("Notification {} {} {} failed for now: {}",
                    notification.code(), notification.id(), delivery.channel(), failure.errorCode());
            case DeliveryOutcome.Permanent failure when NO_ACTIVE_DEVICE.equals(failure.errorCode()) -> log.info(
                    "Notification {} {} PUSH not sent: the recipient has no active push device or browser", notification.code(),
                    notification.id());
            case DeliveryOutcome.Permanent failure -> log.warn("Notification {} {} {} DEAD_LETTER: {}",
                    notification.code(), notification.id(), delivery.channel(), failure.errorCode());
        }
    }

    record Notification(UUID id, String code, NotificationRecipientType recipientType, UUID recipientId,
                        UUID bookingId, UUID tournamentId, JsonNode params) {
    }

    private record Delivery(UUID id, NotificationChannel channel) {
    }

    private record Device(UUID id, String token) {
    }

    private record VenueAccount(UUID id, Language language) {
    }

    private record VenueBrowser(UUID id, WebPushSender.Subscription subscription) {
    }

    private Optional<Notification> notification(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, recipient_type, recipient_id, booking_id, tournament_id, params::text AS params
                          FROM notification.notification WHERE id = :id""")
                .param("id", id)
                .query((rs, row) -> new Notification(rs.getObject("id", UUID.class), rs.getString("code"),
                        NotificationRecipientType.valueOf(rs.getString("recipient_type")),
                        rs.getObject("recipient_id", UUID.class), rs.getObject("booking_id", UUID.class),
                        rs.getObject("tournament_id", UUID.class), tree(rs.getString("params"))))
                .optional();
    }

    private List<Delivery> dueDeliveries(UUID notificationId) {
        return new ArrayList<>(jdbc.sql("""
                        SELECT id, channel FROM notification.notification_delivery
                         WHERE notification_id = :id AND status IN ('PENDING', 'FAILED') AND channel <> 'IN_APP'
                         ORDER BY channel""")
                .param("id", notificationId)
                .query((rs, row) -> new Delivery(rs.getObject("id", UUID.class),
                        NotificationChannel.valueOf(rs.getString("channel"))))
                .list());
    }

    private List<Device> activeDevices(UUID userId) {
        return jdbc.sql("""
                        SELECT id, token FROM notification.device_token
                         WHERE user_id = :user AND revoked_at IS NULL ORDER BY created_at""")
                .param("user", userId)
                .query((rs, row) -> new Device(rs.getObject("id", UUID.class), rs.getString("token")))
                .list();
    }

    /** VENUE recipients are {@code venue.venue} rows; the Dashboard belongs to the venue's one account (FR-VEN-01). */
    private Optional<VenueAccount> venueAccount(UUID venueId) {
        return jdbc.sql("SELECT id, language_preference FROM iam.venue_account WHERE venue_id = :venue")
                .param("venue", venueId)
                .query((rs, row) -> new VenueAccount(rs.getObject("id", UUID.class),
                        "ar".equals(rs.getString("language_preference")) ? Language.AR : Language.EN))
                .optional();
    }

    private List<VenueBrowser> activeBrowsers(UUID venueAccountId) {
        return jdbc.sql("""
                        SELECT id, endpoint, p256dh_key, auth_key FROM notification.venue_push_subscription
                         WHERE venue_account_id = :account AND revoked_at IS NULL ORDER BY created_at""")
                .param("account", venueAccountId)
                .query((rs, row) -> new VenueBrowser(rs.getObject("id", UUID.class), new WebPushSender.Subscription(
                        rs.getString("endpoint"), rs.getString("p256dh_key"), rs.getString("auth_key"))))
                .list();
    }

    /** arch 08 section 4.3: a subscription the push service no longer knows is revoked, never deleted. */
    private void revokeBrowser(UUID subscriptionId) {
        jdbc.sql("""
                        UPDATE notification.venue_push_subscription
                           SET revoked_at = greatest(clock_timestamp(), last_seen_at)
                         WHERE id = :id AND revoked_at IS NULL""")
                .param("id", subscriptionId).update();
    }

    /** arch 08 section 4.3: an invalid push token is revoked, never deleted. */
    private void revoke(UUID deviceId) {
        jdbc.sql("""
                        UPDATE notification.device_token SET revoked_at = greatest(clock_timestamp(), last_seen_at)
                         WHERE id = :id AND revoked_at IS NULL""")
                .param("id", deviceId).update();
    }

    private JsonNode tree(String params) {
        try {
            return params == null ? json.createObjectNode() : json.readTree(params);
        } catch (Exception e) {
            throw new PermanentDeliveryFailure("Unreadable notification params");
        }
    }
}
