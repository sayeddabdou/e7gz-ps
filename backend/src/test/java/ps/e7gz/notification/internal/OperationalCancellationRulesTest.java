package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.internal.NotificationTemplates.Language;
import ps.e7gz.support.RepoFiles;

/** E7-80: the cancellation rule table, without a database (the catalogue checks stay in BookingNotificationRulesTest). */
class OperationalCancellationRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * E7-80 (PO decisions 25 Nov 2026, Q2, Q3, R3): the customer code of an operational cancellation by its reason and
     * {@code cause}; the venue always gets one VENUE_BOOKING_AUTO_CANCELLED. An Accept stopped by its cause, T6 and an
     * event without a cause keep the generic code (PO decision 13 Oct 2026).
     */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "VENUE_EMERGENCY_CLOSURE, CASCADE, BOOKING_CANCELLED_VENUE_EMERGENCY",
            "VENUE_EMERGENCY_CLOSURE, START_TIME, BOOKING_START_CANCELLED_EMERGENCY",
            "VENUE_EMERGENCY_CLOSURE, ACCEPT, BOOKING_AUTO_CANCELLED",
            "VENUE_EMERGENCY_CLOSURE, EXPIRED_REQUEST, BOOKING_AUTO_CANCELLED",
            "VENUE_EMERGENCY_CLOSURE, , BOOKING_AUTO_CANCELLED",
            "RESOURCE_EMERGENCY_OUTAGE, CASCADE, BOOKING_CANCELLED_RESOURCE_EMERGENCY",
            "RESOURCE_EMERGENCY_OUTAGE, START_TIME, BOOKING_START_CANCELLED_EMERGENCY",
            "RESOURCE_EMERGENCY_OUTAGE, ACCEPT, BOOKING_AUTO_CANCELLED",
            "RESOURCE_EMERGENCY_OUTAGE, EXPIRED_REQUEST, BOOKING_AUTO_CANCELLED",
            "RESOURCE_EMERGENCY_OUTAGE, , BOOKING_AUTO_CANCELLED",
            "RESOURCE_OWNER_REVIEW_REQUIRED, CASCADE, BOOKING_CANCELLED_OWNER_REVIEW",
            "RESOURCE_OWNER_REVIEW_REQUIRED, START_TIME, BOOKING_CANCELLED_OWNER_REVIEW",
            "RESOURCE_OWNER_REVIEW_REQUIRED, ACCEPT, BOOKING_AUTO_CANCELLED",
            "RESOURCE_OWNER_REVIEW_REQUIRED, EXPIRED_REQUEST, BOOKING_AUTO_CANCELLED",
            "RESOURCE_OWNER_REVIEW_REQUIRED, , BOOKING_AUTO_CANCELLED",
            "VENUE_CLOSURE, CASCADE, BOOKING_AUTO_CANCELLED",
            "VENUE_CLOSURE, START_TIME, BOOKING_AUTO_CANCELLED",
            "VENUE_CLOSURE, , BOOKING_AUTO_CANCELLED",
            "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, CASCADE, BOOKING_CANCELLED_SYSTEM_INTEGRITY_ERROR",
            "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, START_TIME, BOOKING_CANCELLED_SYSTEM_INTEGRITY_ERROR",
            "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, ACCEPT, BOOKING_CANCELLED_SYSTEM_INTEGRITY_ERROR",
            "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, , BOOKING_CANCELLED_SYSTEM_INTEGRITY_ERROR"})
    void operationalCancellationCodes(String reason, String cause, String customerCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("terminalReason", reason);
        if (cause != null) {
            payload.put(IntegrationEvents.CANCEL_CAUSE, cause);
        }
        assertThat(codes(IntegrationEvents.BOOKING_CANCELLED, payload))
                .containsExactly("CUSTOMER " + customerCode, "VENUE VENUE_BOOKING_AUTO_CANCELLED");
    }

    /** E7-80: a cause this module does not know is treated as no cause - never a failed delivery. */
    @Test
    void unknownCauseIsTreatedAsAbsent() {
        assertThat(codes(IntegrationEvents.BOOKING_CANCELLED, Map.of("terminalReason", "VENUE_EMERGENCY_CLOSURE",
                IntegrationEvents.CANCEL_CAUSE, "X")))
                .containsExactly("CUSTOMER BOOKING_AUTO_CANCELLED", "VENUE VENUE_BOOKING_AUTO_CANCELLED");
    }

    /** E7-80: the cause never changes a non-operational reason's codes (ban, customer cancel, hours change). */
    @Test
    void causeLeavesOtherReasonsAlone() {
        assertThat(codes(IntegrationEvents.BOOKING_CANCELLED, Map.of("terminalReason", "USER_BANNED",
                IntegrationEvents.CANCEL_CAUSE, IntegrationEvents.CAUSE_CASCADE)))
                .containsExactly("CUSTOMER BOOKING_CANCELLED_BAN", "VENUE VENUE_BOOKING_AUTO_CANCELLED");
        assertThat(codes(IntegrationEvents.BOOKING_CANCELLED, Map.of("terminalReason",
                "VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY", IntegrationEvents.CANCEL_CAUSE,
                IntegrationEvents.CAUSE_CASCADE))).containsExactly("CUSTOMER BOOKING_RESPONSE_INTERRUPTED_BY_EMERGENCY");
    }

    /** Each new customer push body is the customer app's in-app text for the code (templates_en.properties header). */
    @Test
    void operationalCustomerPushBodiesAreTheAppsInAppText() throws IOException {
        String app = Files.readString(RepoFiles.get("apps/customer/src/i18n/en.ts"));
        Properties english = new NotificationTemplates().all().get(Language.EN);
        for (String code : List.of("BOOKING_CANCELLED_VENUE_EMERGENCY", "BOOKING_CANCELLED_RESOURCE_EMERGENCY",
                "BOOKING_CANCELLED_OWNER_REVIEW", "BOOKING_CANCELLED_SYSTEM_INTEGRITY_ERROR",
                "BOOKING_START_CANCELLED_EMERGENCY")) {
            String body = english.getProperty(code + ".push.body");
            assertThat(body).as(code).isNotBlank();
            assertThat(app).as(code).contains(code + ": \"" + body + "\"");
        }
    }

    private static List<String> codes(String type, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", UUID.randomUUID().toString());
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("venueId", UUID.randomUUID().toString());
        payload.putAll(extra);
        return BookingNotificationRules.planFor(new OutboxEvent(1, type, "BOOKING", UUID.randomUUID(),
                        JSON.valueToTree(payload), UUID.randomUUID(), Instant.EPOCH, 0))
                .sends().stream().map(send -> send.recipient() + " " + send.code()).toList();
    }
}
