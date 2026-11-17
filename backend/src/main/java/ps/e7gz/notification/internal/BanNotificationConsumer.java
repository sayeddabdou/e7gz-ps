package ps.e7gz.notification.internal;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
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
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;

import ps.e7gz.notification.api.NotificationRequest;

/**
 * The customer notifications of a Ban (Product v5.0 section 52.2; FR-TRS-20..23; E7-71), from the Trust integration
 * events after their command committed (FR-NTF-12) - never a direct call from Trust: {@code BanActivated} is
 * "Temporary Ban activated" ({@code TEMPORARY_BAN_ACTIVATED}), {@code BanExtended} is {@code BAN_EXTENDED} (Amr Heiba's
 * delegated decision, 18 Oct 2026: every committed extension tells the customer, with its own code), {@code BanExpired}
 * is "Ban Expired" ({@code BAN_EXPIRED}). Each code is about the account (reference {@code NONE}), In-App + Push. The
 * bookings a Ban cancels are told by their own {@code BookingCancelled} through {@link BookingNotificationConsumer}.
 *
 * <p>A change a Platform Admin made, directly or through an attendance correction - {@code BanLifted},
 * {@code BanShortened}, {@code BanCorrected}, {@code TrustAdjusted} - is "Admin Trust correction"
 * ({@code ADMIN_TRUST_CORRECTION}, section 52.3, In-App + Email; FR-NTF-11), with {@code change} naming which one.
 *
 * <p>Params, never prose (ADR-021): the stage and the end the Ban now has; an extension also carries
 * {@code previousEndsAt} and {@code endsAtChanged}, false when the stage advanced but the end stayed (FR-TRS-22: MAX),
 * so no surface claims a longer restriction than there is.
 *
 * <p>Exactly once: the notification id is derived from the event and the code, so a retry of the event - every consumer
 * runs again - creates nothing ({@link NotificationService#notifyOnce}). One event per Ban change (Trust writes it
 * once per instance or consumed qualification), so a replayed Trust history adds none.
 */
@Component
@Profile("worker")
class BanNotificationConsumer implements OutboxConsumer {

    static final String ADMIN_TRUST_CORRECTION = "ADMIN_TRUST_CORRECTION";

    static final Map<String, String> CODES = Map.of(
            IntegrationEvents.BAN_ACTIVATED, "TEMPORARY_BAN_ACTIVATED",
            IntegrationEvents.BAN_EXTENDED, "BAN_EXTENDED",
            IntegrationEvents.BAN_EXPIRED, "BAN_EXPIRED",
            IntegrationEvents.BAN_LIFTED, ADMIN_TRUST_CORRECTION,
            IntegrationEvents.BAN_SHORTENED, ADMIN_TRUST_CORRECTION,
            IntegrationEvents.BAN_CORRECTED, ADMIN_TRUST_CORRECTION,
            IntegrationEvents.TRUST_ADJUSTED, ADMIN_TRUST_CORRECTION);

    /** The {@code change} param of an ADMIN_TRUST_CORRECTION, by event. */
    static final Map<String, String> CHANGES = Map.of(
            IntegrationEvents.BAN_LIFTED, "BAN_LIFTED",
            IntegrationEvents.BAN_SHORTENED, "BAN_SHORTENED",
            IntegrationEvents.BAN_CORRECTED, "BAN_CORRECTED",
            IntegrationEvents.TRUST_ADJUSTED, "TRUST_ADJUSTED");

    private final CommandRunner runner;
    private final NotificationService notifications;

    BanNotificationConsumer(CommandRunner runner, NotificationService notifications) {
        this.runner = runner;
        this.notifications = notifications;
    }

    @Override
    public Set<String> eventTypes() {
        return CODES.keySet();
    }

    @Override
    public void handle(OutboxEvent event) {
        runner.run(new Deliver(event));
    }

    /** The one customer notification of {@code event}, with the params its code's surfaces render. */
    static NotificationRequest request(OutboxEvent event) {
        String code = CODES.get(event.eventType());
        if (code == null) {
            throw new IllegalArgumentException("No Ban notification for " + event.eventType());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (code.equals(ADMIN_TRUST_CORRECTION)) {
            params.put("change", CHANGES.get(event.eventType()));
            if (event.eventType().equals(IntegrationEvents.TRUST_ADJUSTED)) {
                params.put("consecutiveNoShows", event.payload().path("counterAfter").asInt());
                params.put("penaltyStage", event.payload().path("penaltyStageAfter").asInt());
            } else {
                params.put("penaltyStage", event.payload().path("penaltyStage").asInt());
                params.put("endsAt", event.text("endsAt"));
            }
            return NotificationRequest.of(code, NotificationRecipientType.CUSTOMER, event.uuid("userId"), params);
        }
        if (!event.eventType().equals(IntegrationEvents.BAN_EXPIRED)) {
            params.put("penaltyStage", event.payload().path("penaltyStage").asInt());
            params.put("endsAt", event.text("endsAt"));
        }
        if (event.eventType().equals(IntegrationEvents.BAN_EXTENDED)) {
            params.put("previousEndsAt", event.text("previousEndsAt"));
            params.put("endsAtChanged", !event.text("endsAt").equals(event.text("previousEndsAt")));
        }
        return NotificationRequest.of(code, NotificationRecipientType.CUSTOMER, event.uuid("userId"), params);
    }

    static UUID id(OutboxEvent event, String code, UUID recipient) {
        return UUID.nameUUIDFromBytes(("E7-71 ban notification|outbox:" + event.id() + "|" + code + "|" + recipient)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** One transaction for the notification; it locks nothing and writes only its own row. */
    private final class Deliver implements Command<Void> {

        private final OutboxEvent event;

        private Deliver(OutboxEvent event) {
            this.event = event;
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
            NotificationRequest request = request(event);
            notifications.notifyOnce(context, id(event, request.code(), request.recipientId()), request);
            return null;
        }
    }
}
