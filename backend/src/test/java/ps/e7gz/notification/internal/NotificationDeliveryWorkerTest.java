package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.DevicePlatform;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.OutboxAttempts;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.notification.internal.email.EmailSender;
import ps.e7gz.notification.internal.email.LoggingEmailSender;
import ps.e7gz.notification.internal.push.LoggingPushSender;
import ps.e7gz.notification.internal.push.PushSender;
import ps.e7gz.notification.internal.webpush.WebPushSender;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-43: the worker sends PUSH and EMAIL after commit and records every attempt (arch 08 sections 4.1-4.3). The worker
 * is built here with the dev adapters or recording fakes, as the {@code worker} profile builds it with the configured
 * ones; {@link OutboxDrain} runs the real dispatcher over the real database.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryWorkerTest extends IntegrationTest {

    @Autowired
    Notifications notifications;

    @Autowired
    CustomerNotifications customers;

    @Autowired
    DeliveryRecovery recovery;

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    Outbox outbox;

    JdbcClient db;
    CorrelationId.Scope correlation;
    final RecordingPush push = new RecordingPush();
    final RecordingEmail email = new RecordingEmail();
    final RecordingWebPush webPush = new RecordingWebPush();

    /** Test copy (the real copy comes with E7-55 / E7-80 / E7-89, PO decision 10 Oct 2026). */
    final NotificationTemplates templates = new NotificationTemplates(Map.of(Language.AR, copy(
            "VENUE_MAINTENANCE_STARTING_SOON.push.title", "صيانة قريبًا",
            "VENUE_MAINTENANCE_STARTING_SOON.push.body", "تبدأ الصيانة بعد {minutes} دقيقة"), Language.EN, copy(
            "VENUE_MAINTENANCE_STARTING_SOON.push.title", "Maintenance soon",
            "VENUE_MAINTENANCE_STARTING_SOON.push.body", "Maintenance starts in {minutes} minutes",
            "POINTS_EARNED.push.title", "Points earned",
            "POINTS_EARNED.push.body", "You earned {points} points",
            "BOOKING_CONFIRMED.push.title", "Booking confirmed",
            "BOOKING_CONFIRMED.push.body", "See you there",
            "PASSWORD_RESET.email.subject", "Your password was reset",
            "PASSWORD_RESET.email.body", "Your E7GZ PS password was changed.")));

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        correlation = CorrelationId.open(CorrelationId.generate());
        drain(event -> false, new LoggingPushSender(), new LoggingEmailSender());       // other tests' events
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /** The task's "done when": push delivered in dev through the fake (log) adapter. */
    @Test
    void aPushIsDeliveredInDevThroughTheLoggingAdapter(CapturedOutput output) {
        UUID user = aUser().create().id();
        customers.register(user, "fcm-token-dev-1", DevicePlatform.ANDROID);
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 40));

        drain(event -> false, new LoggingPushSender(), email);

        assertThat(status(id, "PUSH")).isEqualTo("SENT");
        assertThat(output).contains("DEV PUSH").contains("You earned 40 points").contains("POINTS_EARNED")
                .doesNotContain("fcm-token-dev-1");
    }

    @Test
    void theRealSenderGetsTheRenderedTextAndTheDeepLinkDataForEveryActiveDevice() {
        UUID user = aUser().create().id();
        customers.register(user, "token-android", DevicePlatform.ANDROID);
        customers.register(user, "token-iphone", DevicePlatform.IOS);
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 15));

        drain(event -> false, push, email);

        assertThat(push.sent).extracting(Sent::token).containsExactlyInAnyOrder("token-android", "token-iphone");
        assertThat(push.sent.getFirst().message().title()).isEqualTo("Points earned");
        assertThat(push.sent.getFirst().message().body()).isEqualTo("You earned 15 points");
        assertThat(push.sent.getFirst().message().data()).containsEntry("code", "POINTS_EARNED")
                .containsEntry("notificationId", id.toString());
        assertThat(status(id, "PUSH")).isEqualTo("SENT");
    }

    /** PO decision 10 Oct 2026: closed, logged at INFO, never Sev-2; the in-app row is still there. */
    @Test
    void aCustomerWithNoActivePushDeviceGetsTheInAppNotificationOnly(CapturedOutput output) {
        UUID user = aUser().create().id();
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 5));

        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("DEAD_LETTER");
        assertThat(error(id, "PUSH")).isEqualTo(NotificationDeliveryWorker.NO_ACTIVE_DEVICE);
        assertThat(status(id, "IN_APP")).isEqualTo("SENT");
        assertThat(push.sent).isEmpty();
        assertThat(output).contains("no active push device").doesNotContain("Sev-2");
    }

    /** arch 08 section 4.3: an invalid token is revoked, never deleted; the other device still gets it. */
    @Test
    void anInvalidTokenIsRevokedAndTheOtherDeviceStillReceivesThePush() {
        UUID user = aUser().create().id();
        CustomerNotifications.Device dead = customers.register(user, "token-uninstalled", DevicePlatform.ANDROID);
        customers.register(user, "token-live", DevicePlatform.IOS);
        push.invalid.add("token-uninstalled");
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 5));

        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("SENT");
        assertThat(db.sql("SELECT revoked_at IS NOT NULL FROM notification.device_token WHERE id = :id")
                .param("id", dead.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aTransientFailureIsRetriedAndASentDeliveryIsNeverSentAgain() {
        UUID user = aUser().create().id();
        customers.register(user, "token-flaky", DevicePlatform.ANDROID);
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 5));
        push.down.set(true);

        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("FAILED");
        assertThat(error(id, "PUSH")).isEqualTo("PROVIDER_DOWN");

        push.down.set(false);
        makeDue(id);
        drain(event -> false, push, email);
        assertThat(status(id, "PUSH")).isEqualTo("SENT");
        assertThat(push.sent).hasSize(1);

        // Even if the event were delivered once more (at least once), a SENT delivery is not sent again.
        worker(event -> false, push, email).handle(new OutboxEvent(0, "NotificationDeliveryRequested", "NOTIFICATION",
                id, json.valueToTree(Map.of("notificationId", id.toString())), CorrelationId.require(),
                Instant.EPOCH, 0));
        assertThat(push.sent).hasSize(1);
    }

    @Test
    void aTransientFailureOnTheLastAttemptIsDeadLetterLoggedAsSev2(CapturedOutput output) {
        UUID user = aUser().create().id();
        customers.register(user, "token-down", DevicePlatform.ANDROID);
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 5));
        push.down.set(true);

        drain(event -> true, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("DEAD_LETTER");
        assertThat(output).contains("DEAD_LETTER after the last attempt").contains("Sev-2");
    }

    /** PO decision 10 Oct 2026: the registration address, verified or not. */
    @Test
    void anEmailGoesToTheRegistrationAddressVerifiedOrNot() {
        UUID user = aUser().unverified().email("player@example.test").create().id();
        UUID id = notify("PASSWORD_RESET", user, Map.of());

        drain(event -> false, push, email);

        assertThat(status(id, "EMAIL")).isEqualTo("SENT");
        assertThat(email.sent).containsExactly("player@example.test|Your password was reset");
    }

    @Test
    void aCodeWithoutTemplateIsDeadLetterAndNothingIsSent() {
        UUID user = aUser().create().id();
        customers.register(user, "token-x", DevicePlatform.ANDROID);
        UUID id = notify("BAN_EXPIRED", user, Map.of());

        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("DEAD_LETTER");
        assertThat(error(id, "PUSH")).isEqualTo(NotificationDeliveryWorker.TEMPLATE_MISSING);
        assertThat(push.sent).isEmpty();
    }

    /** E7-41/E7-43: the admin "try again" reopens a DEAD_LETTER delivery, audited, and the worker sends it. */
    @Test
    void theAdminTryAgainSendsADeadLetteredDeliveryOnceMore() {
        UUID admin = anAdmin().create().id();
        UUID user = aUser().create().id();
        customers.register(user, "token-back", DevicePlatform.ANDROID);
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 5));
        push.down.set(true);
        drain(event -> true, push, email);
        UUID delivery = deliveryId(id, "PUSH");
        assertThat(status(id, "PUSH")).isEqualTo("DEAD_LETTER");

        push.down.set(false);
        recovery.recover(admin, delivery, "FCM was down; it is back");
        assertThat(status(id, "PUSH")).isEqualTo("PENDING");
        assertThat(db.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'NOTIFICATION_DELIVERY_RECOVERED' AND subject_id = :id AND actor_id = :admin""")
                .param("id", delivery).param("admin", admin).query(Integer.class).single()).isEqualTo(1);

        drain(event -> false, push, email);
        assertThat(status(id, "PUSH")).isEqualTo("SENT");
    }

    @Test
    void tryAgainOnADeliveryThatIsNotDeadLetterIsNothingToRetryAndAnUnknownIdIsNotFound() {
        UUID admin = anAdmin().create().id();
        UUID user = aUser().create().id();
        UUID id = notify("POINTS_EARNED", user, Map.of("points", 5));

        assertThatThrownBy(() -> recovery.recover(admin, deliveryId(id, "IN_APP"), "retry"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo(ApiErrorCode.NOTIFICATION_DELIVERY_NOT_DEAD_LETTER));
        assertThatThrownBy(() -> recovery.recover(admin, UUID.randomUUID(), "retry"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    // ------------------------------------------------------------------------------------------- venue Web Push (E7-44)

    @Test
    void aVenuePushGoesToEveryActiveDashboardBrowserOfItsAccountInTheAccountsLanguage() {
        Fixtures.VenueAccount account = aVenueAccount().language(VenueAccountLanguage.ar).create();
        UUID first = browser(account.id(), "https://fcm.googleapis.com/fcm/send/a-1");
        UUID second = browser(account.id(), "https://updates.push.services.mozilla.com/wpush/v2/a-2");
        revokedBrowser(account.id(), "https://fcm.googleapis.com/fcm/send/a-old");
        UUID id = notifyVenue(account.venueId(), Map.of("minutes", 15));

        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("SENT");
        assertThat(webPush.sent).extracting(WebPushSent::endpoint).containsExactlyInAnyOrder(
                endpoint(first), endpoint(second));
        assertThat(webPush.sent.getFirst().message().title()).isEqualTo("صيانة قريبًا");
        assertThat(webPush.sent.getFirst().message().body()).isEqualTo("تبدأ الصيانة بعد 15 دقيقة");
        assertThat(webPush.sent.getFirst().message().data()).containsEntry("code", "VENUE_MAINTENANCE_STARTING_SOON")
                .containsEntry("notificationId", id.toString());
        assertThat(push.sent).isEmpty();
    }

    /** PO decision 10 Oct 2026: what E7-43 left PENDING is sent once Web Push exists. */
    @Test
    void venuePushesLeftWaitingBeforeWebPushAreQueuedAgainOnceAndSent() {
        Fixtures.VenueAccount account = aVenueAccount().create();
        browser(account.id(), "https://fcm.googleapis.com/fcm/send/waiting-1");
        UUID id = notifyVenue(account.venueId(), Map.of("minutes", 5));
        // E7-43's worker: it skipped a venue delivery and the event was done.
        OutboxDrain.drain(appDataSource, json, new OutboxConsumer() {
            @Override
            public Set<String> eventTypes() {
                return Set.of(IntegrationEvents.NOTIFICATION_DELIVERY_REQUESTED);
            }

            @Override
            public void handle(OutboxEvent event) {
            }
        });
        assertThat(status(id, "PUSH")).isEqualTo("PENDING");

        WaitingVenuePushes waiting = new WaitingVenuePushes(runner, outbox);
        assertThat(waiting.requeue()).isGreaterThanOrEqualTo(1);
        waiting.requeue();                                                   // its event waits: not queued twice
        assertThat(events(id)).isEqualTo(2);
        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("SENT");
        assertThat(webPush.sent).extracting(WebPushSent::endpoint)
                .containsExactly("https://fcm.googleapis.com/fcm/send/waiting-1");
        waiting.requeue();                                                   // sent: never queued again
        assertThat(events(id)).isEqualTo(2);
    }

    /** arch 08 section 4.3: revoked, never deleted; the other browser still gets the push. */
    @Test
    void aBrowserThePushServiceNoLongerKnowsIsRevokedAndTheOtherStillGetsThePush() {
        Fixtures.VenueAccount account = aVenueAccount().create();
        UUID gone = browser(account.id(), "https://fcm.googleapis.com/fcm/send/gone-1");
        browser(account.id(), "https://fcm.googleapis.com/fcm/send/alive-1");
        webPush.gone.add("https://fcm.googleapis.com/fcm/send/gone-1");
        UUID id = notifyVenue(account.venueId(), Map.of("minutes", 1));

        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("SENT");
        assertThat(db.sql("SELECT revoked_at IS NOT NULL FROM notification.venue_push_subscription WHERE id = :id")
                .param("id", gone).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aVenueWithNoDashboardBrowserGetsTheInAppNotificationOnly() {
        Fixtures.VenueAccount account = aVenueAccount().create();
        UUID id = notifyVenue(account.venueId(), Map.of("minutes", 1));

        drain(event -> false, push, email);

        assertThat(status(id, "PUSH")).isEqualTo("DEAD_LETTER");
        assertThat(error(id, "PUSH")).isEqualTo(NotificationDeliveryWorker.NO_ACTIVE_DEVICE);
        assertThat(status(id, "IN_APP")).isEqualTo("SENT");
    }

    @Test
    void aWebPushFailingForNowIsRetriedAndOnTheLastAttemptDeadLettered() {
        Fixtures.VenueAccount account = aVenueAccount().create();
        browser(account.id(), "https://fcm.googleapis.com/fcm/send/down-1");
        webPush.down.set(true);
        UUID id = notifyVenue(account.venueId(), Map.of("minutes", 1));

        drain(event -> false, push, email);
        assertThat(status(id, "PUSH")).isEqualTo("FAILED");

        makeDue(id);
        drain(event -> true, push, email);
        assertThat(status(id, "PUSH")).isEqualTo("DEAD_LETTER");
        assertThat(error(id, "PUSH")).isEqualTo("WEB_PUSH_THROTTLED");
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private void drain(OutboxAttempts attempts, PushSender pushSender, EmailSender emailSender) {
        OutboxDrain.drain(appDataSource, json, worker(attempts, pushSender, emailSender));
    }

    private NotificationDeliveryWorker worker(OutboxAttempts attempts, PushSender pushSender, EmailSender emailSender) {
        return new NotificationDeliveryWorker(appDataSource, templates, pushSender, webPush, emailSender, attempts,
                json);
    }

    private UUID notifyVenue(UUID venueId, Map<String, Object> params) {
        return runner.run(new Command<UUID>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public UUID execute(CommandContext context) {
                return notifications.notify(context, NotificationRequest.of("VENUE_MAINTENANCE_STARTING_SOON",
                        NotificationRecipientType.VENUE, venueId, params));
            }
        });
    }

    /** An active Dashboard browser; the recording sender never reads the keys. */
    private UUID browser(UUID venueAccountId, String endpoint) {
        UUID id = UUID.randomUUID();
        db.sql("""
                        INSERT INTO notification.venue_push_subscription
                               (id, venue_account_id, endpoint, p256dh_key, auth_key, last_seen_at)
                        VALUES (:id, :account, :endpoint, 'p256dh', 'auth', clock_timestamp())""")
                .param("id", id).param("account", venueAccountId).param("endpoint", endpoint).update();
        return id;
    }

    private void revokedBrowser(UUID venueAccountId, String endpoint) {
        UUID id = browser(venueAccountId, endpoint);
        db.sql("""
                        UPDATE notification.venue_push_subscription SET revoked_at = clock_timestamp()
                         WHERE id = :id""").param("id", id).update();
    }

    private String endpoint(UUID subscriptionId) {
        return db.sql("SELECT endpoint FROM notification.venue_push_subscription WHERE id = :id")
                .param("id", subscriptionId).query(String.class).single();
    }

    private int events(UUID notificationId) {
        return db.sql("SELECT count(*) FROM technical.outbox_event WHERE aggregate_id = :id")
                .param("id", notificationId).query(Integer.class).single();
    }

    private UUID notify(String code, UUID user, Map<String, Object> params) {
        return runner.run(new Command<UUID>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public UUID execute(CommandContext context) {
                return notifications.notify(context,
                        NotificationRequest.of(code, NotificationRecipientType.CUSTOMER, user, params));
            }
        });
    }

    /** Waits (at most 10 s) until the retried event is due by the database clock, as the E7-41 tests do. */
    private void makeDue(UUID notificationId) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && Boolean.TRUE.equals(db.sql("""
                        SELECT bool_or(next_attempt_at > clock_timestamp()) FROM technical.outbox_event
                         WHERE aggregate_id = :id AND state = 'PENDING'""")
                .param("id", notificationId).query(Boolean.class).single())) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String status(UUID notificationId, String channel) {
        return db.sql("SELECT status FROM notification.notification_delivery WHERE notification_id = :id AND channel = :c")
                .param("id", notificationId).param("c", channel).query(String.class).single();
    }

    private String error(UUID notificationId, String channel) {
        return db.sql("""
                        SELECT error_code FROM notification.notification_delivery
                         WHERE notification_id = :id AND channel = :c""")
                .param("id", notificationId).param("c", channel).query(String.class).single();
    }

    private UUID deliveryId(UUID notificationId, String channel) {
        return db.sql("SELECT id FROM notification.notification_delivery WHERE notification_id = :id AND channel = :c")
                .param("id", notificationId).param("c", channel).query(UUID.class).single();
    }

    private static Properties copy(String... keysAndValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return properties;
    }

    record Sent(String token, PushSender.PushMessage message) {
    }

    static final class RecordingPush implements PushSender {
        final List<Sent> sent = new ArrayList<>();
        final List<String> invalid = new ArrayList<>();
        final AtomicBoolean down = new AtomicBoolean();

        @Override
        public PushResult send(String deviceToken, PushMessage message) {
            if (down.get()) {
                return new PushResult.Transient("PROVIDER_DOWN");
            }
            if (invalid.contains(deviceToken)) {
                return new PushResult.InvalidToken("FCM_UNREGISTERED");
            }
            sent.add(new Sent(deviceToken, message));
            return new PushResult.Sent("projects/test/messages/" + sent.size());
        }
    }

    record WebPushSent(String endpoint, PushSender.PushMessage message) {
    }

    static final class RecordingWebPush implements WebPushSender {
        final List<WebPushSent> sent = new ArrayList<>();
        final List<String> gone = new ArrayList<>();
        final AtomicBoolean down = new AtomicBoolean();

        @Override
        public WebPushResult send(Subscription subscription, PushSender.PushMessage message) {
            if (down.get()) {
                return new WebPushResult.Transient("WEB_PUSH_THROTTLED");
            }
            if (gone.contains(subscription.endpoint())) {
                return new WebPushResult.Gone("WEB_PUSH_GONE");
            }
            sent.add(new WebPushSent(subscription.endpoint(), message));
            return new WebPushResult.Sent("https://push.example.test/m/" + sent.size());
        }
    }

    static final class RecordingEmail implements EmailSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public EmailResult send(String to, String subject, String body) {
            sent.add(to + "|" + subject);
            return new EmailResult.Sent("<" + UUID.randomUUID() + "@test>");
        }
    }
}
