package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E7-42 — ADR-015's scope over the whole committed contract: every write is classified, the ones in scope require the
 * {@code Idempotency-Key} and declare {@code 422 IDEMPOTENCY_KEY_REUSED}, every other one keeps it optional (CLAUDE.md
 * §5; PO decision 3 Oct 2026). A new write fails this test until it is put on one side: an ADR-015 operation (Late
 * Confirm, attendance, tournaments, admin remediation, points/wallet/referral) goes into {@link #MANDATORY}.
 */
class IdempotencyScopeTest {

    /** ADR-015 / arch 08 §6 scope as it exists in the contract today, with the task that implements each. */
    static final Set<String> MANDATORY = Set.of(
            "post /api/v1/customer/bookings",                                   // booking create, E7-46
            "post /api/v1/customer/bookings/{bookingId}/cancel",                // customer cancel, E7-49
            "post /api/v1/venue/bookings/{bookingId}/accept",                   // venue accept, E7-47
            "post /api/v1/venue/bookings/{bookingId}/reject",                   // venue reject, E7-47
            "post /api/v1/venue/bookings/{bookingId}/reject-after-expiry",      // reject after expiry, E7-47
            "post /api/v1/venue/bookings",                                      // manual booking create, E7-48
            "post /api/v1/venue/bookings/{bookingId}/cancel-manual",            // manual booking cancel, E7-48
            "post /api/v1/customer/bookings/{bookingId}/late-confirm/accept",   // Late Confirm accept, E7-58
            "post /api/v1/customer/bookings/{bookingId}/late-confirm/decline",  // Late Confirm decline, E7-58
            "post /api/v1/venue/bookings/{bookingId}/late-confirm",             // Late Confirm send, E7-58
            "post /api/v1/venue/bookings/{bookingId}/cancel-confirmed",         // venue cancels CONFIRMED, E7-114
            "post /api/v1/venue/bookings/{bookingId}/check-in",                 // check-in, E7-59
            "post /api/v1/venue/bookings/{bookingId}/late-present",             // Mark Present - Late, E7-59
            "post /api/v1/venue/bookings/{bookingId}/no-show",                  // no-show, E7-59
            "post /api/v1/venue/opening-hours/commit",                          // hours change cancels, E7-113
            // Sprint 4 admin remediation and configuration (PO decision D6, 17 Oct 2026, E7-65).
            "post /api/v1/admin/users/{userId}/trust-adjustment",               // trust adjustment, E7-71
            "post /api/v1/admin/users/{userId}/bans/{banId}/lift",              // ban lift, E7-71
            "post /api/v1/admin/users/{userId}/bans/{banId}/shorten",           // ban shorten, E7-71
            "post /api/v1/admin/users/{userId}/bans/{banId}/correct",           // ban correct, E7-71
            "post /api/v1/admin/verified-players/{userId}/suspend",             // VP suspend, E7-72
            "post /api/v1/admin/verified-players/{userId}/revoke",              // VP revoke, E7-72
            "post /api/v1/admin/verified-players/{userId}/restore",             // VP restore, E7-72
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-check-in",      // E7-70
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-late-present",  // E7-70
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-no-show",       // E7-70
            "post /api/v1/admin/bookings/{bookingId}/attendance/correct",               // E7-70
            "post /api/v1/admin/points/adjust",                                 // points adjustment, E7-66
            "post /api/v1/admin/referrals/{referralId}/invalidate",             // referral invalidation, E7-67
            "put /api/v1/admin/points/settings",                                // points configuration, E7-68
            "put /api/v1/admin/fees",                                           // fee configuration, E7-68
            // Sprint 5 (E7-77): writes that cancel commitments and move Booking Fee refunds.
            "post /api/v1/venue/closures",                                      // closure cancels, E7-81
            "patch /api/v1/venue/closures/{closureId}",                         // closure edit cancels, E7-81
            "post /api/v1/venue/emergencies/venue-closure",                     // emergency cascade, E7-82
            "post /api/v1/venue/emergencies/resource-outage",                   // emergency cascade, E7-82
            "post /api/v1/venue/emergencies/{emergencyId}/extend",              // extension cancels, E7-82
            "post /api/v1/venue/emergencies/{emergencyId}/end",                 // owner-review entry, E7-82/E7-84
            "post /api/v1/venue/emergencies/{emergencyId}/convert",             // conversion, E7-82
            // PO decision 19 Nov 2026 (E7-77): O10 cancels AWAITING bookings and refunds them (E7-78).
            "post /api/v1/admin/venue-accounts/{venueAccountId}/disable",
            "post /api/v1/admin/venue-accounts/{venueAccountId}/enable",
            // Sprint 6: tournament creation holds rooms (ADR-015 "tournaments"), E7-91.
            "post /api/v1/customer/tournaments",
            "post /api/v1/venue/tournaments",
            // Sprint 6: the venue's answer turns Approval Holds into Tournament Holds or releases them, E7-92.
            "post /api/v1/venue/tournaments/{tournamentId}/approve",
            "post /api/v1/venue/tournaments/{tournamentId}/reject",
            // Sprint 6: participations and cancellations move commitments to held rooms (ADR-015), E7-93.
            "post /api/v1/customer/tournaments/{tournamentId}/join",
            "post /api/v1/customer/tournaments/join-private",
            "post /api/v1/customer/tournaments/{tournamentId}/leave",
            "post /api/v1/customer/tournaments/{tournamentId}/participants/{userId}/remove",
            "post /api/v1/customer/tournaments/{tournamentId}/cancel",
            "post /api/v1/venue/tournaments/{tournamentId}/cancel",
            "post /api/v1/venue/tournaments/{tournamentId}/operational-termination",
            "post /api/v1/admin/tournaments/{tournamentId}/cancel");

    /** Writes outside ADR-015's scope, so the header is accepted but optional. */
    static final Map<String, List<String>> OPTIONAL = Map.of(
            "authentication and phone verification (no commitment; arch 09 §1.2)", List.of(
                    "post /api/v1/customer/auth/register", "post /api/v1/customer/auth/otp/send",
                    "post /api/v1/customer/auth/verify-phone", "post /api/v1/customer/auth/login",
                    "post /api/v1/customer/auth/refresh", "post /api/v1/customer/auth/logout",
                    "post /api/v1/venue/auth/login", "post /api/v1/venue/auth/refresh",
                    "post /api/v1/venue/auth/logout", "post /api/v1/admin/auth/login",
                    "post /api/v1/admin/auth/refresh", "post /api/v1/admin/auth/logout"),
            "admin area, venue and venue-account setup (PO decision 3 Oct 2026: not remediation)", List.of(
                    "post /api/v1/admin/areas", "patch /api/v1/admin/areas/{areaId}",
                    "post /api/v1/admin/areas/{areaId}/activate", "post /api/v1/admin/areas/{areaId}/deactivate",
                    "post /api/v1/admin/venues", "patch /api/v1/admin/venues/{venueId}",
                    "post /api/v1/admin/venues/{venueId}/onboarding/complete",
                    "post /api/v1/admin/venues/{venueId}/activate", "post /api/v1/admin/venues/{venueId}/deactivate",
                    "post /api/v1/admin/venues/{venueId}/suspend", "post /api/v1/admin/venues/{venueId}/venue-account",
                    "post /api/v1/admin/venues/{venueId}/resources", "post /api/v1/admin/venues/{venueId}/images",
                    "put /api/v1/admin/venues/{venueId}/images/order",
                    "post /api/v1/admin/venues/{venueId}/images/{imageId}/confirm",
                    "post /api/v1/admin/venues/{venueId}/images/{imageId}/remove",
                    "post /api/v1/admin/venue-accounts/{venueAccountId}/reset-login-password",
                    "post /api/v1/admin/venue-accounts/{venueAccountId}/reset-owner-password"),
            "venue setup: resources, prices, photos, hours, settings, Owner Password (Sprint 1 contract)", List.of(
                    "post /api/v1/venue/resources", "post /api/v1/venue/resources/{resourceId}/disable",
                    "post /api/v1/venue/resources/{resourceId}/re-enable",
                    "post /api/v1/venue/resources/{resourceId}/archive",
                    "post /api/v1/venue/resources/{resourceId}/restore",
                    "post /api/v1/venue/resources/{resourceId}/console", "put /api/v1/venue/pricing",
                    "put /api/v1/venue/resources/{resourceId}/price-override",
                    "delete /api/v1/venue/resources/{resourceId}/price-override", "post /api/v1/venue/images",
                    "put /api/v1/venue/images/order", "post /api/v1/venue/images/{imageId}/confirm",
                    "post /api/v1/venue/images/{imageId}/remove", "post /api/v1/venue/opening-hours/preview",
                    "post /api/v1/venue/owner-password",
                    "patch /api/v1/venue/settings"),
            "admin notification \"try again\" (E7-43: re-sends a message, no commitment, money or points; a second "
                    + "press answers NOTIFICATION_DELIVERY_NOT_DEAD_LETTER)", List.of(
                    "post /api/v1/admin/notification-deliveries/{deliveryId}/recover"),
            "admin alert acknowledge (E7-56: no commitment, money or points; acknowledging again by the same admin "
                    + "returns the same alert)", List.of(
                    "post /api/v1/admin/alerts/{alertId}/acknowledge"),
            "customer password reset and profile (no commitment, money or points; SPRINT-3-CONTRACT.md)", List.of(
                    "post /api/v1/customer/auth/password-reset/request",
                    "post /api/v1/customer/auth/password-reset/complete",
                    "patch /api/v1/customer/profile", "post /api/v1/customer/profile/username",
                    "post /api/v1/customer/profile/phone", "post /api/v1/customer/profile/phone/verify",
                    "post /api/v1/customer/profile/email", "post /api/v1/customer/profile/email/verify"),
            "venue maintenance, closure delete, previews and owner-review resolution (SPRINT-5-CONTRACT.md: they "
                    + "cancel nothing, FR-OPS-02, FR-OPS-43)", List.of(
                    "post /api/v1/venue/maintenance", "patch /api/v1/venue/maintenance/{maintenanceId}",
                    "delete /api/v1/venue/maintenance/{maintenanceId}", "post /api/v1/venue/closures/preview",
                    "delete /api/v1/venue/closures/{closureId}",
                    "post /api/v1/venue/emergencies/{emergencyId}/extend/preview",
                    "post /api/v1/venue/emergencies/{emergencyId}/convert/preview",
                    "post /api/v1/venue/owner-reviews/{resourceId}/resolve"),
            "notifications and push (SPRINT-2-CONTRACT.md: optional)", List.of(
                    "post /api/v1/customer/notifications/{notificationId}/read",
                    "post /api/v1/customer/notifications/devices",
                    "post /api/v1/customer/notifications/devices/{deviceId}/revoke",
                    "post /api/v1/venue/push-subscriptions",
                    "post /api/v1/venue/push-subscriptions/{subscriptionId}/revoke",
                    "post /api/v1/venue/notifications/{notificationId}/read"),              // E7-55
            "reviews, reports, blocks and moderation (E7-102: no booking, commitment, money or points; a repeat is "
                    + "REVIEW_ALREADY_EXISTS, REVIEW_REPORT_ALREADY_OPEN or the same block, a repeated moderation is "
                    + "refused, a repeated resolution answers the same Report)", List.of(
                    "post /api/v1/customer/venues/{venueId}/review", "patch /api/v1/customer/venues/{venueId}/review",
                    "delete /api/v1/customer/venues/{venueId}/review",
                    "post /api/v1/customer/reviews/{reviewId}/report",
                    "post /api/v1/customer/reviews/{reviewId}/block-author",
                    "post /api/v1/customer/blocked-users/{blockId}/unblock",
                    "post /api/v1/admin/reviews/{reviewId}/approve", "post /api/v1/admin/reviews/{reviewId}/hide",
                    "post /api/v1/admin/reviews/{reviewId}/restore", "post /api/v1/admin/reviews/{reviewId}/remove",
                    "post /api/v1/admin/review-reports/{reportId}/resolve"));

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
    }

    @Test
    void everyWriteInTheContractIsClassified() {
        List<String> optional = OPTIONAL.values().stream().flatMap(List::stream).toList();
        assertThat(optional).doesNotHaveDuplicates().doesNotContainAnyElementsOf(MANDATORY);
        assertThat(writes().keySet()).as("each write must be put in MANDATORY or OPTIONAL (ADR-015)")
                .containsExactlyInAnyOrderElementsOf(Stream.concat(MANDATORY.stream(), optional.stream()).toList());
    }

    @Test
    void writesInScopeRequireTheKeyAndDeclareItsReuseError() {
        MANDATORY.forEach(key -> {
            JsonNode operation = writes().get(key);
            JsonNode header = header(operation);
            assertThat(header.path("required").asBoolean(false)).as(key).isTrue();
            assertThat(header.path("schema").path("format").asText()).as(key).isEqualTo("uuid");
            assertThat(operation.path("responses").path("422").path("description").asText()).as(key)
                    .contains("IDEMPOTENCY_KEY_REUSED");
        });
    }

    @Test
    void writesOutsideTheScopeKeepTheKeyOptional() {
        OPTIONAL.values().stream().flatMap(List::stream).forEach(key ->
                assertThat(header(writes().get(key)).path("required").asBoolean(false)).as(key).isFalse());
    }

    /** "method path" → operation, for every POST / PUT / PATCH / DELETE. */
    private Map<String, JsonNode> writes() {
        Map<String, JsonNode> writes = new TreeMap<>();
        contract.path("paths").properties().forEach(path -> {
            for (String method : List.of("post", "put", "patch", "delete")) {
                if (path.getValue().has(method)) {
                    writes.put(method + " " + path.getKey(), path.getValue().get(method));
                }
            }
        });
        return writes;
    }

    private static JsonNode header(JsonNode operation) {
        List<JsonNode> found = new ArrayList<>();
        operation.path("parameters").forEach(p -> {
            if ("header".equals(p.path("in").asText()) && "idempotency-key".equalsIgnoreCase(p.path("name").asText())) {
                found.add(p);
            }
        });
        assertThat(found).as("exactly one Idempotency-Key header").hasSize(1);
        return found.get(0);
    }
}
