package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.internal.BookingNotificationRules.Plan;
import ps.e7gz.notification.internal.BookingNotificationRules.Recipient;
import ps.e7gz.notification.internal.BookingNotificationRules.Send;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.support.RepoFiles;

/**
 * E7-113 Phase 1 without a database: the hours change's {@code BookingCancelled} goes through the one booking
 * notification rule set (PO decision 12 Oct 2026) - the customer of an Online Booking only, a Manual Booking nobody -
 * with its English push copy, and no other main source creates the code. The database side (one row per stored event,
 * one per booking) is {@code BookingNotificationsIntegrationTest}.
 */
class HoursChangeNotificationRuleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CODE = "VENUE_OPENING_HOURS_CHANGED";

    @Test
    void anOnlineBookingCancelledByAnHoursChangeTellsItsCustomerOnlyAndArmsNoReminder() {
        Plan plan = cancelled(Map.of());

        assertThat(plan).isEqualTo(new Plan(List.of(new Send(Recipient.CUSTOMER, CODE)), Optional.empty()));
        assertThat(plan.sends()).noneMatch(send -> send.recipient() == Recipient.VENUE);
    }

    @Test
    void aManualBookingCancelledByAnHoursChangeNotifiesNobody() {
        assertThat(cancelled(Map.of("source", "MANUAL")).isEmpty()).isTrue();
    }

    /** The Manual exception is the hours change's alone: the other reasons keep their plans. */
    @Test
    void theOtherCancellationReasonsAreUnchanged() {
        assertThat(plan(Map.of("terminalReason", "CUSTOMER_CANCELLED")).sends())
                .containsExactly(new Send(Recipient.CUSTOMER, "BOOKING_CANCELLED"));
        assertThat(plan(Map.of("terminalReason", "VENUE_EMERGENCY_CLOSURE", "source", "MANUAL")).sends())
                .containsExactly(new Send(Recipient.CUSTOMER, "BOOKING_AUTO_CANCELLED"),
                        new Send(Recipient.VENUE, "VENUE_BOOKING_AUTO_CANCELLED"));
        assertThat(plan(Map.of("terminalReason", "VENUE_CANCELLED_MANUAL_BOOKING", "source", "MANUAL")).isEmpty())
                .isTrue();
        assertThat(plan(Map.of("terminalReason", "USER_BANNED")).isEmpty()).isTrue();
    }

    /** Each event is its own origin, so two cancelled bookings of one customer get two notification ids. */
    @Test
    void theNotificationIdIsPerEventNotPerCustomer() {
        UUID customer = UUID.randomUUID();

        assertThat(BookingNotifier.id("outbox:1", CODE, customer)).isEqualTo(BookingNotifier.id("outbox:1", CODE,
                customer));
        assertThat(BookingNotifier.id("outbox:1", CODE, customer)).isNotEqualTo(BookingNotifier.id("outbox:2", CODE,
                customer));
    }

    /** English only (a customer code); the body is the customer app's in-app text for the code. */
    @Test
    void itsPushCopyIsTheAppsInAppText() throws IOException {
        Properties english = new NotificationTemplates().all().get(Language.EN);
        String app = Files.readString(RepoFiles.get("apps/customer/src/i18n/en.ts"));

        assertThat(english.getProperty(CODE + ".push.title")).isEqualTo("Booking cancelled");
        assertThat(app).contains(CODE + ": \"" + english.getProperty(CODE + ".push.body") + "\"");
    }

    /** No producer and no other path creates the code: only the rule set names it (no Notifications.notify). */
    @Test
    void onlyTheRuleSetNamesTheCode() throws IOException {
        Path main = RepoFiles.get("backend/src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            List<String> naming = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> read(file).contains("\"" + CODE + "\""))
                    .map(file -> main.relativize(file).toString().replace('\\', '/'))
                    .toList();
            assertThat(naming).containsExactly("ps/e7gz/notification/internal/BookingNotificationRules.java");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Plan cancelled(Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("terminalReason", CODE);
        return plan(payload);
    }

    /** {@code BookingCancelled} with the fields {@code BookingEvents} writes, plus {@code extra}. */
    private static Plan plan(Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", UUID.randomUUID().toString());
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("venueId", UUID.randomUUID().toString());
        payload.put("resourceId", UUID.randomUUID().toString());
        payload.put("startAt", "2026-10-20T18:00:00Z");
        payload.put("endAt", "2026-10-20T19:00:00Z");
        payload.putAll(extra);
        return BookingNotificationRules.planFor(new OutboxEvent(1, IntegrationEvents.BOOKING_CANCELLED, "BOOKING",
                UUID.randomUUID(), JSON.valueToTree(payload), UUID.randomUUID(), Instant.EPOCH, 0));
    }
}
