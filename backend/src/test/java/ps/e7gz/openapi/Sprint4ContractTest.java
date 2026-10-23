package ps.e7gz.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E7-65 — the Sprint 4 contract (arch 09 §3.1, §3.4, §5; PCR-004; PO decisions 17 Oct 2026,
 * packages/api-client/SPRINT-4-CONTRACT.md). Read from the committed contract, which {@link OpenApiContractTest} keeps
 * equal to the controllers; ADR-015's scope over every write is {@link IdempotencyScopeTest}'s.
 */
class Sprint4ContractTest {

    /** Every Sprint 4 operation, with the task that implements it in the comment. */
    static final Set<String> SPRINT_4_OPERATIONS = Set.of(
            "get /api/v1/customer/account-standing",                                    // E7-71, E7-72
            "get /api/v1/customer/points",                                              // E7-66
            "get /api/v1/customer/referrals",                                           // E7-67
            "get /api/v1/customer/wallet",                                              // E7-66
            "get /api/v1/admin/users",                                                  // E7-66 (PO D4)
            "get /api/v1/admin/users/{userId}",                                         // E7-66 (PO D4)
            "post /api/v1/admin/users/{userId}/trust-adjustment",                       // E7-71
            "post /api/v1/admin/users/{userId}/bans/{banId}/lift",                      // E7-71
            "post /api/v1/admin/users/{userId}/bans/{banId}/shorten",                   // E7-71
            "post /api/v1/admin/users/{userId}/bans/{banId}/correct",                   // E7-71
            "get /api/v1/admin/verified-players",                                       // E7-72
            "post /api/v1/admin/verified-players/{userId}/suspend",                     // E7-72
            "post /api/v1/admin/verified-players/{userId}/revoke",                      // E7-72
            "post /api/v1/admin/verified-players/{userId}/restore",                     // E7-72
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-check-in",      // E7-70
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-late-present",  // E7-70
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-no-show",       // E7-70
            "post /api/v1/admin/bookings/{bookingId}/attendance/correct",               // E7-70
            "get /api/v1/admin/wallet",                                                 // E7-66
            "get /api/v1/admin/users/{userId}/wallet",                                  // E7-66
            "get /api/v1/admin/points",                                                 // E7-66
            "get /api/v1/admin/users/{userId}/points",                                  // E7-66
            "post /api/v1/admin/points/adjust",                                         // E7-66
            "get /api/v1/admin/referrals",                                              // E7-67
            "post /api/v1/admin/referrals/{referralId}/invalidate",                     // E7-67
            "get /api/v1/admin/points/settings",                                        // E7-68
            "put /api/v1/admin/points/settings",                                        // E7-68
            "get /api/v1/admin/fees",                                                   // E7-68
            "put /api/v1/admin/fees",                                                   // E7-68
            "get /api/v1/admin/global-rules");                                          // E7-71 (PO 20 Oct)

    /** Writes refused with ADMIN_ACTION_NOT_ALLOWED when the target's state no longer allows them (PO D5). */
    static final Set<String> STATE_DEPENDENT_WRITES = Set.of(
            "post /api/v1/admin/users/{userId}/bans/{banId}/lift",
            "post /api/v1/admin/users/{userId}/bans/{banId}/shorten",
            "post /api/v1/admin/users/{userId}/bans/{banId}/correct",
            "post /api/v1/admin/verified-players/{userId}/suspend",
            "post /api/v1/admin/verified-players/{userId}/revoke",
            "post /api/v1/admin/verified-players/{userId}/restore",
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-check-in",
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-late-present",
            "post /api/v1/admin/bookings/{bookingId}/attendance/reverse-no-show",
            "post /api/v1/admin/bookings/{bookingId}/attendance/correct",
            "post /api/v1/admin/referrals/{referralId}/invalidate");

    private JsonNode contract;

    @BeforeEach
    void readContract() throws Exception {
        contract = OpenApiDocument.parse(Files.readString(OpenApiDocument.COMMITTED));
    }

    @Test
    void everySprint4OperationIsInTheContract() {
        SPRINT_4_OPERATIONS.forEach(key -> assertThat(operation(key).isMissingNode()).as(key).isFalse());
    }

    @Test
    void everyAdminWriteRequiresTheIdempotencyKeyAndAReason() {
        // ADR-015 and PO decision D6 (17 Oct 2026): remediation and configuration writes alike.
        // FR-AUD-06, FR-TRS-27, FR-ADM-05: every remediation needs a mandatory reason (E7-75's "done when").
        writes().forEach(key -> {
            JsonNode operation = operation(key);
            assertThat(header(operation, "Idempotency-Key").path("required").asBoolean(false)).as(key).isTrue();
            assertThat(codes(operation, "422")).as(key).contains("IDEMPOTENCY_KEY_REUSED");
            JsonNode body = requestBody(operation);
            assertThat(fieldNames(body.path("required"))).as(key).contains("reason");
            assertThat(body.path("properties").path("reason").path("minLength").asInt()).as(key).isEqualTo(1);
        });
    }

    @Test
    void stateDependentWritesDeclareTheGeneralAdminRefusal() {
        // PO decision D5 (17 Oct 2026): one general 409 code for these refusals.
        STATE_DEPENDENT_WRITES.forEach(key -> {
            assertThat(SPRINT_4_OPERATIONS).contains(key);
            assertThat(codes(operation(key), "409")).as(key).containsExactly("ADMIN_ACTION_NOT_ALLOWED");
        });
    }

    @Test
    void theWalletHasNoWriteAnywhere() {
        // PCR-004 / FR-PTS-11 / arch 09 §5: read-only customer wallet, no admin wallet adjust, no top-up.
        contract.path("paths").properties().forEach(path -> {
            if (path.getKey().contains("/wallet")) {
                assertThat(path.getValue().fieldNames()).toIterable().as(path.getKey())
                        .noneMatch(method -> Set.of("post", "put", "patch", "delete").contains(method));
            }
        });
        assertThat(contract.path("paths").has("/api/v1/admin/wallet/adjust")).isFalse();
    }

    @Test
    void theCustomerRoutesAreReadOnlyAndUseOnlyCustomerSchemas() {
        // arch 09 §1.1: separate DTOs per route group, always; standing, points, referrals and wallet are reads.
        // Shared: the envelope, Money and the closed code enums of ps.e7gz.common.codes.
        Set<String> shared = Set.of("ResponseMeta", "Money", "Currency", "VerifiedPlayerStatus",
                "VerifiedPlayerQualification", "PointsTransactionCause", "ReferralStatus", "WalletTransactionCause");
        SPRINT_4_OPERATIONS.stream().filter(key -> key.contains("/customer/")).forEach(key -> {
            assertThat(key).startsWith("get ");
            assertThat(refs(operation(key))).as(key).map(ref -> ref.replaceFirst("^DataResponse", ""))
                    .allMatch(ref -> ref.startsWith("Customer") || shared.contains(ref));
        });
        SPRINT_4_OPERATIONS.stream().filter(key -> key.contains("/admin/")).forEach(key ->
                assertThat(refs(operation(key))).as(key)
                        .map(ref -> ref.replaceFirst("^DataResponse", "").replaceFirst("^PageView", ""))
                        .noneMatch(ref -> ref.startsWith("Customer") || ref.startsWith("Venue")));
    }

    @Test
    void theStandingShowsExactlyOneBanAndVerifiedPlayerAsTwoFields() {
        // FR-TRS-15: stage, consecutive count, threshold, ban start and until — exactly one current ban.
        JsonNode standing = schema("CustomerAccountStanding");
        assertThat(properties(standing)).containsExactlyInAnyOrder("consecutiveNoShows", "penaltyStage",
                "currentThreshold", "currentBan", "verifiedPlayer");
        assertThat(standing.path("properties").path("currentBan").toString())
                .contains("#/components/schemas/CustomerCurrentBan").doesNotContain("\"array\"");
        assertThat(properties(schema("CustomerCurrentBan")))
                .containsExactlyInAnyOrder("banId", "penaltyStage", "startsAt", "endsAt");
        // FR-ACC-11: qualification progress and operational status are separate fields.
        assertThat(properties(schema("CustomerVerifiedPlayer"))).containsExactlyInAnyOrder("qualification",
                "completedBookings", "requiredBookings", "status");
        assertThat(schema("CustomerVerifiedPlayer").path("properties").path("qualification").toString())
                .contains("VerifiedPlayerQualification");
        assertThat(schema("CustomerVerifiedPlayer").path("properties").path("status").toString())
                .contains("VerifiedPlayerStatus");
    }

    @Test
    void timesAreInstantsAndNoRouteReturnsACountdown() {
        // NFR-10, arch 09 §1.2: "remaining time" (FR-TRS-15) is endsAt against meta.serverTime, never seconds.
        Map.of("CustomerCurrentBan", List.of("startsAt", "endsAt"),
                "AdminBan", List.of("qualificationAt", "activatedAt", "endsAt"),
                "AdminBanShortenRequest", List.of("endsAt"),
                "AdminConfiguredMoney", List.of("effectiveFrom"),
                "AdminConfiguredInteger", List.of("effectiveFrom"),
                "AdminConfiguredBoolean", List.of("effectiveFrom")).forEach((name, fields) -> fields.forEach(field ->
                assertThat(schema(name).path("properties").path(field).path("format").asText()).as(name + "." + field)
                        .isEqualTo("date-time")));
        SPRINT_4_OPERATIONS.forEach(key -> refs(operation(key)).forEach(name ->
                assertThat(properties(schema(name))).as(name)
                        .noneMatch(p -> p.toLowerCase().matches(".*(second|remaining|countdown).*"))));
    }

    @Test
    void moneyIsAlwaysAStringWithACurrencyAndPointsAreWholeNumbers() {
        // NFR-25, arch 09 §1.2: wallet balances and fees are Money; points are integers (loyalty.points_account).
        Map.of("CustomerWallet", List.of("balance"),
                "CustomerWalletTransaction", List.of("amount", "balanceAfter"),
                "AdminWalletAccount", List.of("balance"),
                "AdminWalletTransaction", List.of("amount", "balanceAfter"),
                "AdminConfiguredMoney", List.of("value")).forEach((name, fields) -> fields.forEach(field ->
                assertThat(schema(name).path("properties").path(field).toString()).as(name + "." + field)
                        .contains("#/components/schemas/Money")));
        Map.of("CustomerPoints", List.of("balance"),
                "CustomerPointsTransaction", List.of("amount", "balanceAfter"),
                "AdminPointsTransaction", List.of("requestedAmount", "amount", "unrecoveredAmount", "balanceAfter"),
                "AdminPointsAdjustRequest", List.of("amount")).forEach((name, fields) -> fields.forEach(field ->
                assertThat(schema(name).path("properties").path(field).path("type").asText()).as(name + "." + field)
                        .isEqualTo("integer")));
    }

    @Test
    void pointsReversalsShowTheShortfallToTheAdminOnly() {
        // FR-PTS-03/04/09: requested, applied and unrecovered amounts; never a negative balance or a debt.
        assertThat(properties(schema("AdminPointsTransaction"))).contains("requestedAmount", "amount",
                "unrecoveredAmount", "balanceAfter", "reason", "adminId");
        assertThat(properties(schema("CustomerPointsTransaction")))
                .doesNotContain("requestedAmount", "unrecoveredAmount", "adminId", "reason");
        assertThat(properties(requestBody(operation("post /api/v1/admin/points/adjust"))))
                .containsExactlyInAnyOrder("userId", "amount", "reason");
    }

    @Test
    void attendanceCorrectionNamesTheChosenOutcome() {
        // FR-ATT-13, FR-TRS-07, arch 07 §7.4: no generic undo; the correction carries the chosen outcome.
        JsonNode correct = requestBody(operation("post /api/v1/admin/bookings/{bookingId}/attendance/correct"));
        assertThat(fieldNames(correct.path("required"))).containsExactlyInAnyOrder("correctedOutcome", "reason");
        assertThat(correct.path("properties").path("correctedOutcome").toString()).contains("AttendanceResult");
        for (String reversal : List.of("reverse-check-in", "reverse-late-present", "reverse-no-show")) {
            assertThat(properties(requestBody(operation(
                    "post /api/v1/admin/bookings/{bookingId}/attendance/" + reversal)))).as(reversal)
                    .containsExactly("reason");
        }
    }

    @Test
    void configurationCoversExactlyTheFiveA8Values() {
        // arch 01 §14 A8 and PO decision D3 (17 Oct 2026): two fees on /admin/fees, points and the switch here.
        assertThat(properties(schema("AdminFees"))).containsExactlyInAnyOrder("bookingFee", "tournamentCreationFee");
        assertThat(properties(schema("AdminPointsSettings")))
                .containsExactlyInAnyOrder("attendedPoints", "referralPoints", "walletRefundsEnabled");
        assertThat(properties(requestBody(operation("put /api/v1/admin/fees"))))
                .containsExactlyInAnyOrder("bookingFee", "tournamentCreationFee", "reason");
        assertThat(properties(requestBody(operation("put /api/v1/admin/points/settings"))))
                .containsExactlyInAnyOrder("attendedPoints", "referralPoints", "walletRefundsEnabled", "reason");
    }

    @Test
    void adminLookupsShowWhatFrAdm04And09Require() {
        // FR-ADM-04: name, username, phone, email, OTP verification, VP status, completed bookings, no-show
        // standing, account status (banned / deleted) and joined date.
        assertThat(properties(schema("AdminUserSummary"))).containsExactlyInAnyOrder("userId", "fullName",
                "username", "phone", "email", "phoneVerified", "verifiedPlayerStatus", "completedBookings",
                "consecutiveNoShows", "penaltyStage", "banned", "deleted", "joinedAt");
        // FR-ADM-09: referral records show referrer, code, referred user, signup date, reward applied and status.
        assertThat(properties(schema("AdminReferral"))).contains("referrerUserId", "code", "referredUserId",
                "signedUpAt", "rewardPointsApplied", "status");
    }

    @Test
    void theGlobalRulesAreReadOnly() {
        // arch 09 §5, NFR-37, A8: the no-show ladder is a fixed Product rule; PO decision 20 Oct 2026 (E7-65).
        JsonNode path = contract.path("paths").path("/api/v1/admin/global-rules");
        assertThat(path.fieldNames()).toIterable().containsExactly("get");
        assertThat(properties(schema("AdminGlobalRules"))).containsExactly("noShowLadder");
        assertThat(properties(schema("AdminNoShowLadderStep")))
                .containsExactlyInAnyOrder("penaltyStage", "consecutiveNoShows", "banDuration", "recurring");
    }

    private Set<String> writes() {
        return Set.copyOf(SPRINT_4_OPERATIONS.stream().filter(key -> !key.startsWith("get ")).toList());
    }

    private JsonNode operation(String key) {
        String[] parts = key.split(" ", 2);
        return contract.path("paths").path(parts[1]).path(parts[0]);
    }

    private JsonNode schema(String name) {
        return contract.path("components").path("schemas").path(name);
    }

    private JsonNode requestBody(JsonNode operation) {
        JsonNode body = operation.path("requestBody").path("content").path("application/json").path("schema");
        String ref = body.path("$ref").asText();
        return ref.isEmpty() ? body : schema(ref.substring(ref.lastIndexOf('/') + 1));
    }

    /** The error codes the operation declares for one status (its narrowed envelope). */
    private static List<String> codes(JsonNode operation, String status) {
        return fieldNames(operation.path("responses").path(status).path("content").path("application/json")
                .path("schema").path("allOf").path(1).path("properties").path("error").path("properties")
                .path("code").path("enum"));
    }

    private static JsonNode header(JsonNode operation, String name) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(p -> "header".equals(p.path("in").asText()) && name.equals(p.path("name").asText()))
                .findFirst().orElseThrow();
    }

    /** Every schema the operation's request and success responses reference, followed transitively. */
    private List<String> refs(JsonNode operation) {
        List<String> found = new ArrayList<>();
        collect(operation.path("requestBody"), found);
        operation.path("responses").properties().forEach(response -> {
            if (response.getKey().startsWith("2")) {
                collect(response.getValue(), found);
            }
        });
        return found;
    }

    private void collect(JsonNode node, List<String> found) {
        if (node.isObject()) {
            String ref = node.path("$ref").asText();
            if (!ref.isEmpty()) {
                String name = ref.substring(ref.lastIndexOf('/') + 1);
                if (!found.contains(name)) {
                    found.add(name);
                    collect(schema(name), found);
                }
            }
            node.forEach(child -> collect(child, found));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, found));
        }
    }

    private static List<String> properties(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> fieldNames(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
