package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * E7-43: {@link Notifications#notify} creates the notification and the deliveries its catalogue code requires in the
 * caller's transaction, and the database - not Java - enforces the catalogue (arch 08 sections 4.2 and 4.4).
 */
class NotificationServiceTest extends IntegrationTest {

    @Autowired
    Notifications notifications;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueAccounts venueAccounts;

    @Autowired
    DataSource appDataSource;

    JdbcClient db;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void notifyCreatesTheRequiredDeliveriesInAppSentAndTheRestPendingWithOneOutboxEvent() {
        UUID user = aUser().create().id();

        UUID id = run(context -> notifications.notify(context,
                NotificationRequest.of("PASSWORD_RESET", NotificationRecipientType.CUSTOMER, user, Map.of())));

        assertThat(deliveries(id)).containsExactlyInAnyOrder("IN_APP:SENT", "EMAIL:PENDING");
        assertThat(db.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND aggregate_id = :id AND payload->>'notificationId' = :text""")
                .param("type", IntegrationEvents.NOTIFICATION_DELIVERY_REQUESTED).param("id", id)
                .param("text", id.toString()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anInAppOnlyCodeIsDeliveredByTheCommitAndPublishesNothing() {
        Fixtures.Venue venue = aVenue().create();

        UUID id = run(context -> notifications.notify(context, NotificationRequest.of("VENUE_OWNER_PASSWORD_RESET",
                NotificationRecipientType.VENUE, venue.id(), Map.of())));

        assertThat(deliveries(id)).containsExactly("IN_APP:SENT");
        assertThat(events(id)).isZero();
    }

    /** The task's "done when": the deferred constraint trigger refuses, at commit, a missing required channel. */
    @Test
    void aCataloguedCodeWithoutItsRequiredChannelsFailsAtCommit() {
        UUID user = aUser().create().id();
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> run(context -> {
            context.jdbc().sql("""
                            INSERT INTO notification.notification (id, recipient_type, recipient_id, code)
                            VALUES (:id, 'CUSTOMER', :user, 'POINTS_EARNED')""")
                    .param("id", id).param("user", user).update();
            context.jdbc().sql("""
                            INSERT INTO notification.notification_delivery (id, notification_id, channel, status, attempt)
                            VALUES (:delivery, :id, 'IN_APP', 'PENDING', 0)""")
                    .param("delivery", UUID.randomUUID()).param("id", id).update();
            return null;                                                            // PUSH is missing
        })).hasStackTraceContaining("lacks required delivery channel");

        assertThat(db.sql("SELECT count(*) FROM notification.notification WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aRolledBackCommandLeavesNoNotificationNoDeliveryAndNoEvent() {
        UUID user = aUser().create().id();
        UUID[] created = new UUID[1];

        assertThatThrownBy(() -> run(context -> {
            created[0] = notifications.notify(context,
                    NotificationRequest.of("POINTS_EARNED", NotificationRecipientType.CUSTOMER, user, Map.of()));
            throw new IllegalStateException("the business change failed");
        })).hasMessageContaining("business change failed");

        assertThat(db.sql("SELECT count(*) FROM notification.notification WHERE id = :id").param("id", created[0])
                .query(Integer.class).single()).isZero();
        assertThat(events(created[0])).isZero();
    }

    @Test
    void theDatabaseRefusesAnUnknownCodeAWrongRecipientAndAMissingBookingReference() {
        UUID user = aUser().create().id();

        assertThatThrownBy(() -> run(context -> notifications.notify(context,
                NotificationRequest.of("NOT_A_CODE", NotificationRecipientType.CUSTOMER, user, Map.of()))))
                .hasStackTraceContaining("is not registered in the catalogue");
        assertThatThrownBy(() -> run(context -> notifications.notify(context,
                NotificationRequest.of("POINTS_EARNED", NotificationRecipientType.VENUE, aVenue().create().id(),
                        Map.of()))))
                .hasStackTraceContaining("is not allowed for notification");
        assertThatThrownBy(() -> run(context -> notifications.notify(context,
                NotificationRequest.of("BOOKING_CONFIRMED", NotificationRecipientType.CUSTOMER, user, Map.of()))))
                .hasStackTraceContaining("requires exactly its Booking reference");
    }

    @Test
    void aSecretInTheParamsIsRefused() {
        UUID user = aUser().create().id();

        assertThatThrownBy(() -> run(context -> notifications.notify(context, NotificationRequest.of(
                "PASSWORD_RESET", NotificationRecipientType.CUSTOMER, user, Map.of("password", "1234")))))
                .hasStackTraceContaining("forbidden secret field");
    }

    /** FR-NTF-01: SMS is OTP only, and the OTP is sent from its own sealed outbox event (E7-41), never from here. */
    @Test
    void theOtpCodeIsNeverANotification() {
        UUID user = aUser().create().id();

        assertThatThrownBy(() -> run(context -> notifications.notify(context, NotificationRequest.of(
                "PHONE_VERIFICATION_OTP", NotificationRecipientType.CUSTOMER, user, Map.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** E7-24 carry-forward (FR-ADM-03, FR-VEN-05): the admin's Owner Password reset notifies the venue. */
    @Test
    void anAdminOwnerPasswordResetNotifiesTheVenueInTheSameTransactionWithoutThePassword() {
        UUID admin = anAdmin().create().id();
        Fixtures.Venue venue = aVenue().create();
        Fixtures.VenueAccount account = aVenueAccount().forVenue(venue).ownerPasswordHash("{noop}0000").create();

        venueAccounts.resetOwnerPassword(admin, account.id(), "4821");

        List<Map<String, Object>> rows = db.sql("""
                        SELECT n.id, n.params::text AS params FROM notification.notification n
                         WHERE n.recipient_type = 'VENUE' AND n.recipient_id = :venue
                           AND n.code = 'VENUE_OWNER_PASSWORD_RESET'""")
                .param("venue", venue.id()).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("params").toString()).doesNotContain("4821").isEqualTo("{}");
        assertThat(deliveries((UUID) rows.getFirst().get("id"))).containsExactly("IN_APP:SENT");
    }

    private <R> R run(Function<CommandContext, R> body) {
        return runner.run(new Command<R>() {
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
        });
    }

    private List<String> deliveries(UUID notificationId) {
        return db.sql("""
                        SELECT channel || ':' || status FROM notification.notification_delivery
                         WHERE notification_id = :id""")
                .param("id", notificationId).query(String.class).list();
    }

    private int events(UUID notificationId) {
        return db.sql("SELECT count(*) FROM technical.outbox_event WHERE aggregate_id = :id")
                .param("id", notificationId).query(Integer.class).single();
    }
}
