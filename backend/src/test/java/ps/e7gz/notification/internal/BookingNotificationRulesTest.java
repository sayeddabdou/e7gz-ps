package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;
import ps.e7gz.notification.internal.BookingNotificationRules.Plan;
import ps.e7gz.notification.internal.BookingNotificationRules.Recipient;
import ps.e7gz.notification.internal.BookingNotificationRules.Reminder;
import ps.e7gz.notification.internal.BookingNotificationRules.Send;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-55: the event-to-notification rules and the copy rules of arch 08 section 4.5, checked against the database's
 * notification catalogue and the shipped push copy.
 */
class BookingNotificationRulesTest extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    DataSource appDataSource;

    // ---------------------------------------------------------------------------------------------- the mapping

    @Test
    void eachEventMapsToItsCodes() {
        assertThat(plan(IntegrationEvents.BOOKING_CREATED, Map.of())).isEqualTo(new Plan(List.of(
                new Send(Recipient.CUSTOMER, "BOOKING_CREATED"), new Send(Recipient.VENUE, "VENUE_NEW_BOOKING_REQUEST")),
                Optional.of(Reminder.VENUE_PENDING_REQUEST_EXPIRING)));
        assertThat(plan(IntegrationEvents.BOOKING_CONFIRMED, Map.of())).isEqualTo(new Plan(List.of(
                new Send(Recipient.CUSTOMER, "BOOKING_CONFIRMED")), Optional.of(Reminder.BOOKING_STARTS_SOON)));
        assertThat(codes(IntegrationEvents.BOOKING_REJECTED, Map.of("terminalReason", "VENUE_REJECTED")))
                .containsExactly("CUSTOMER BOOKING_REJECTED");
        assertThat(codes(IntegrationEvents.BOOKING_REJECTED, Map.of("terminalReason", "REJECTED_AFTER_EXPIRY")))
                .containsExactly("CUSTOMER REJECTED_AFTER_EXPIRY");
        assertThat(codes(IntegrationEvents.VENUE_RESPONSE_EXPIRED, Map.of()))
                .containsExactly("CUSTOMER VENUE_RESPONSE_EXPIRED");
        assertThat(codes(IntegrationEvents.BOOKING_CANCELLED, Map.of("terminalReason", "CUSTOMER_CANCELLED")))
                .containsExactly("CUSTOMER BOOKING_CANCELLED");
        for (String operational : List.of("VENUE_EMERGENCY_CLOSURE", "RESOURCE_EMERGENCY_OUTAGE",
                "RESOURCE_OWNER_REVIEW_REQUIRED")) {
            assertThat(codes(IntegrationEvents.BOOKING_CANCELLED, Map.of("terminalReason", operational)))
                    .as(operational).containsExactly("CUSTOMER BOOKING_AUTO_CANCELLED",
                            "VENUE VENUE_BOOKING_AUTO_CANCELLED");
        }
    }

    /** A Manual Booking has no customer (E7-48); BookingStarted sends nothing (PO decision 13 Oct 2026). */
    @Test
    void manualBookingsAndTheStartSendNothing() {
        assertThat(plan(IntegrationEvents.BOOKING_CONFIRMED, Map.of("source", "MANUAL")).isEmpty()).isTrue();
        assertThat(plan(IntegrationEvents.BOOKING_STARTED, Map.of("source", "MANUAL")).isEmpty()).isTrue();
        assertThat(plan(IntegrationEvents.BOOKING_STARTED, Map.of()).isEmpty()).isTrue();
        assertThat(plan(IntegrationEvents.BOOKING_CANCELLED,
                Map.of("terminalReason", "VENUE_CANCELLED_MANUAL_BOOKING", "source", "MANUAL")).isEmpty()).isTrue();
    }

    /** A cancellation reason no task has wired yet sends nothing until its producer task adds it. */
    @Test
    void aReasonNotWiredYetSendsNothingAndAMalformedOneIsAProducerDefect() {
        assertThat(plan(IntegrationEvents.BOOKING_CANCELLED, Map.of("terminalReason", "USER_BANNED")).isEmpty())
                .isTrue();
        assertThatThrownBy(() -> plan(IntegrationEvents.BOOKING_CANCELLED, Map.of("terminalReason", "NOPE")))
                .isInstanceOf(PermanentDeliveryFailure.class);
        assertThatThrownBy(() -> plan(IntegrationEvents.BOOKING_REJECTED, Map.of("terminalReason", "CUSTOMER_CANCELLED")))
                .isInstanceOf(PermanentDeliveryFailure.class);
    }

    // ------------------------------------------------------------------------------- copy rules, arch 08 section 4.5

    /** FR-NTF-04: every code is a BOOKING code for its recipient, so the database requires the Booking ID. */
    @Test
    void everyCodeIsABookingCodeTheRecipientMayReceive() {
        Map<String, String> reference = new HashMap<>();
        Map<String, Set<String>> recipients = new HashMap<>();
        JdbcClient.create(appDataSource).sql("SELECT code, reference_type, recipient_types FROM notification.notification_catalogue")
                .query(rs -> {
                    reference.put(rs.getString("code"), rs.getString("reference_type"));
                    recipients.put(rs.getString("code"), Set.of((String[]) rs.getArray("recipient_types").getArray()));
                });

        for (Send send : allSends()) {
            assertThat(reference.get(send.code())).as(send.code()).isEqualTo("BOOKING");
            assertThat(recipients.get(send.code())).as(send.code()).contains(send.recipient().name());
        }
    }

    /** FR-NTF-07: only BookingCancelled - whose producer wrote CANCELLED - may say "cancelled". */
    @Test
    void aCodeSayingCancelledComesOnlyFromBookingCancelled() {
        for (String type : BookingNotificationRules.eventTypes()) {
            if (type.equals(IntegrationEvents.BOOKING_CANCELLED)) {
                continue;
            }
            for (Map<String, Object> payload : payloadsOf(type)) {
                assertThat(plan(type, payload).sends()).as(type).noneMatch(send -> send.code().contains("CANCELLED"));
            }
        }
    }

    /** FR-NTF-06: ordinary expiry, platform account disable and an operational cause are three distinct codes. */
    @Test
    void ordinaryExpiryHasItsOwnCodeApartFromAccountDisableAndOperationalCauses() {
        List<String> expiry = codes(IntegrationEvents.VENUE_RESPONSE_EXPIRED, Map.of());
        List<String> operational = codes(IntegrationEvents.BOOKING_CANCELLED,
                Map.of("terminalReason", "VENUE_EMERGENCY_CLOSURE"));

        assertThat(expiry).containsExactly("CUSTOMER VENUE_RESPONSE_EXPIRED").doesNotContainAnyElementsOf(operational);
        assertThat(expiry).doesNotContain("CUSTOMER BOOKING_CANCELLED_VENUE_ACCOUNT_DISABLED");
        assertThat(catalogue()).contains("VENUE_RESPONSE_EXPIRED", "BOOKING_CANCELLED_VENUE_ACCOUNT_DISABLED",
                "BOOKING_AUTO_CANCELLED");
    }

    /** FR-NTF-05: attendance-unconfirmed completion is its own code, not a parameter of a generic one (E7-61 emits it). */
    @Test
    void attendanceUnconfirmedCompletionIsADistinctCode() {
        assertThat(catalogue()).contains("ATTENDANCE_UNCONFIRMED_COMPLETION");
    }

    /** FR-NTF-08: a re-enabled venue account notifies no customer. */
    @Test
    void aReEnabledVenueAccountHasNoNotificationRule() {
        assertThat(BookingNotificationRules.eventTypes()).doesNotContain("VenueAccountEnabled");
    }

    /** Carry-forwards from E7-43 and E7-44: English push copy for every code, Arabic too for a venue code. */
    @Test
    void everyCodeHasItsPushCopy() {
        Map<Language, Properties> copy = new NotificationTemplates().all();
        List<Send> sends = new ArrayList<>(allSends());
        sends.add(VenuePendingRequestExpiringHandler.SEND);
        sends.add(BookingStartsSoonHandler.SEND);
        for (Send send : sends) {
            List<Language> languages = send.recipient() == Recipient.VENUE ? List.of(Language.EN, Language.AR)
                    : List.of(Language.EN);
            for (Language language : languages) {
                for (String part : List.of("title", "body")) {
                    assertThat(copy.get(language).getProperty(send.code() + ".push." + part))
                            .as(language + " " + send.code() + ".push." + part).isNotBlank();
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Every send of every rule over the payloads that matter to it. */
    private static List<Send> allSends() {
        return BookingNotificationRules.eventTypes().stream()
                .flatMap(type -> payloadsOf(type).stream().flatMap(payload -> plan(type, payload).sends().stream()))
                .distinct().toList();
    }

    private static List<Map<String, Object>> payloadsOf(String type) {
        if (type.equals(IntegrationEvents.BOOKING_CANCELLED)) {
            return BookingNotificationRules.CANCELLED.keySet().stream()
                    .map(reason -> Map.<String, Object>of("terminalReason", reason.name())).toList();
        }
        if (type.equals(IntegrationEvents.BOOKING_REJECTED)) {
            return Stream.of(BookingTerminalReason.VENUE_REJECTED, BookingTerminalReason.REJECTED_AFTER_EXPIRY)
                    .map(reason -> Map.<String, Object>of("terminalReason", reason.name())).toList();
        }
        return List.of(Map.of(), Map.of("source", "MANUAL"));
    }

    private Set<String> catalogue() {
        return Set.copyOf(JdbcClient.create(appDataSource).sql("SELECT code FROM notification.notification_catalogue")
                .query(String.class).list());
    }

    private static List<String> codes(String type, Map<String, Object> payload) {
        return plan(type, payload).sends().stream().map(send -> send.recipient() + " " + send.code()).toList();
    }

    private static Plan plan(String type, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", UUID.randomUUID().toString());
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("venueId", UUID.randomUUID().toString());
        payload.putAll(extra);
        return BookingNotificationRules.planFor(new OutboxEvent(1, type, "BOOKING", UUID.randomUUID(),
                JSON.valueToTree(payload), UUID.randomUUID(), Instant.EPOCH, 0));
    }
}
