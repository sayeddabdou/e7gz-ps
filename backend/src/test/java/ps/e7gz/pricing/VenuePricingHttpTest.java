package ps.e7gz.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-27: venue default prices (FR-VEN-43, FR-VEN-44), PlayStation room overrides and their reset, the Owner Password on
 * every write (FRS §2.4), and the hourly price the booking flows will read (FR-VEN-45).
 */
@AutoConfigureMockMvc
class VenuePricingHttpTest extends IntegrationTest {

    private static final String PRICING = "/api/v1/venue/pricing";
    private static final String PIN = "1234";
    private static final String WRONG = "0000";

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessTokens accessTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired CommandRunner runner;
    @Autowired HourlyPrices hourlyPrices;

    record TestCommand<R>(java.util.function.Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private UUID venueId;
    private UUID accountId;
    private String token;
    private UUID room;
    private UUID table;

    @BeforeEach
    void aVenueWithAPlayStationRoomAndABilliardsTable() {
        UUID[] venue = venueWithAccount("PLAYSTATION", "BILLIARDS");
        venueId = venue[0];
        accountId = venue[1];
        token = accessTokens.issue(AuthSubjectType.VENUE, accountId);
        room = resource(venueId, "PLAYSTATION", "Room 1", "PS5");
        table = resource(venueId, "BILLIARDS", "Pool Table 1", null);
    }

    // ------------------------------------------------------------------------------------------ defaults

    @Test
    void aNewVenueHasNoPrices() throws Exception {
        mvc.perform(get(PRICING).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaults").isEmpty()).andExpect(jsonPath("$.data.overrides").isEmpty());
    }

    @Test
    void theVenueSetsItsDefaultsWithThePinAndTheyAreAudited() throws Exception {
        putDefaults(fullSet(), PIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaults.length()").value(5))
                // PlayStation first, PS4 before PS5, SINGLE before MULTIPLAYER (FR-VEN-43), then the table rate.
                .andExpect(jsonPath("$.data.defaults[0].consoleType").value("PS4"))
                .andExpect(jsonPath("$.data.defaults[0].playMode").value("SINGLE"))
                .andExpect(jsonPath("$.data.defaults[0].hourlyPrice.amount").value("80.00"))
                .andExpect(jsonPath("$.data.defaults[0].hourlyPrice.currency").value("EGP"))
                .andExpect(jsonPath("$.data.defaults[4].activity").value("BILLIARDS"))
                .andExpect(jsonPath("$.data.defaults[4].consoleType").isEmpty())
                .andExpect(jsonPath("$.data.defaults[4].playMode").isEmpty());

        assertThat(defaultRows()).isEqualTo(5);
        Map<String, Object> audit = lastAudit("VENUE_PRICING_CHANGED");
        assertThat(audit).containsEntry("subject_id", venueId).containsEntry("context_venue_id", venueId)
                .containsEntry("owner_password_outcome", "SUCCESS");
        assertThat(audit.get("payload").toString()).contains("\"defaults\"").doesNotContain(PIN);
    }

    @Test
    void aSecondSaveChangesOnlyThePricesSentAndKeepsThePreviousInTheAudit() throws Exception {
        putDefaults(fullSet(), PIN).andExpect(status().isOk());
        putDefaults(List.of(price("BILLIARDS", null, null, "60.00")), PIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaults.length()").value(5))
                .andExpect(jsonPath("$.data.defaults[0].hourlyPrice.amount").value("80.00"))
                .andExpect(jsonPath("$.data.defaults[4].hourlyPrice.amount").value("60.00"));

        // Default prices are history: updated in place, never deleted (R__security_baseline).
        assertThat(defaultRows()).isEqualTo(5);
        JsonNode payload = json.readTree(lastAudit("VENUE_PRICING_CHANGED").get("payload").toString());
        assertThat(payload.path("previous")).hasSize(5);
        assertThat(payload.path("defaults")).hasSize(1);
    }

    @Test
    void aBadSetIsAFieldErrorAndChangesNothing() throws Exception {
        putDefaults(fullSet(), PIN).andExpect(status().isOk());

        expectField(putDefaults(List.of(price("PLAYSTATION", null, "SINGLE", "1.00")), PIN), "defaults[0].consoleType");
        expectField(putDefaults(List.of(price("PLAYSTATION", "PS5", null, "1.00")), PIN), "defaults[0].playMode");
        expectField(putDefaults(List.of(price("BILLIARDS", null, "SINGLE", "1.00")), PIN), "defaults[0].playMode");
        expectField(putDefaults(List.of(price("BILLIARDS", "PS5", null, "1.00")), PIN), "defaults[0].consoleType");
        expectField(putDefaults(List.of(price("BILLIARDS", null, null, "1.00"), price("BILLIARDS", null, null, "2.00")),
                PIN), "defaults[1]");
        // The venue offers PlayStation and Billiards only; the Platform Admin manages the list (FR-ADM-02).
        expectField(putDefaults(List.of(price("PING_PONG", null, null, "1.00")), PIN), "defaults[0].activity");
        expectField(putDefaults(List.of(price("BILLIARDS", null, null, "10000000000.00")), PIN),
                "defaults[0].hourlyPrice");
        // Money is a decimal string with two places (arch 09 §1.2): no floats, no negatives.
        putDefaults(List.of(price("BILLIARDS", null, null, "12.5")), PIN).andExpect(status().isBadRequest());
        putDefaults(List.of(price("BILLIARDS", null, null, "-1.00")), PIN).andExpect(status().isBadRequest());
        putDefaults(List.of(), PIN).andExpect(status().isBadRequest());

        assertThat(defaultRows()).isEqualTo(5);
        assertThat(auditCount("VENUE_PRICING_CHANGED")).isEqualTo(1);
    }

    @Test
    void everyWriteNeedsThePinAndThereIsNoUnlock() throws Exception {
        putDefaults(fullSet(), null).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        putDefaults(fullSet(), WRONG).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        assertThat(refusals("VENUE_PRICING_CHANGE")).isEqualTo(1);

        putDefaults(fullSet(), PIN).andExpect(status().isOk());
        // Right after a success the next write still asks for it (FR-VEN-02).
        putOverride(room, List.of(mode("SINGLE", "1.00")), null).andExpect(status().is(423));
        putOverride(room, List.of(mode("SINGLE", "1.00")), WRONG).andExpect(status().is(423));
        resetOverride(room, null).andExpect(status().is(423));
        assertThat(refusals("ROOM_PRICE_OVERRIDE_SET_RESET")).isEqualTo(1);
        assertThat(overrideRows(room)).isZero();
    }

    // ------------------------------------------------------------------------------------------ room overrides

    @Test
    void aPlayStationRoomGetsOverridesForItsConsoleAndCanBeReset() throws Exception {
        putOverride(room, List.of(mode("SINGLE", "200.00")), PIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overrides.length()").value(1))
                .andExpect(jsonPath("$.data.overrides[0].resourceId").value(room.toString()))
                .andExpect(jsonPath("$.data.overrides[0].consoleType").value("PS5"))
                .andExpect(jsonPath("$.data.overrides[0].hourlyPrice.amount").value("200.00"));

        // The set sent replaces the room's overrides.
        putOverride(room, List.of(mode("MULTIPLAYER", "260.00"), mode("SINGLE", "210.00")), PIN)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.overrides.length()").value(2))
                .andExpect(jsonPath("$.data.overrides[0].playMode").value("SINGLE"))
                .andExpect(jsonPath("$.data.overrides[0].hourlyPrice.amount").value("210.00"));
        assertThat(lastAudit("ROOM_PRICE_OVERRIDE_SET")).containsEntry("subject_id", room)
                .containsEntry("owner_password_outcome", "SUCCESS");

        resetOverride(room, PIN).andExpect(status().isOk()).andExpect(jsonPath("$.data.overrides").isEmpty());
        assertThat(overrideRows(room)).isZero();
        assertThat(json.readTree(lastAudit("ROOM_PRICE_OVERRIDE_RESET").get("payload").toString()).path("previous"))
                .hasSize(2);
    }

    @Test
    void overridesAreForTheVenuesOwnPlayStationRoomsOnly() throws Exception {
        expectField(putOverride(table, List.of(mode("SINGLE", "1.00")), PIN), "resourceId");   // FR-VEN-44
        expectField(putOverride(room, List.of(mode("SINGLE", "1.00"), mode("SINGLE", "2.00")), PIN),
                "prices[1].playMode");
        putOverride(room, List.of(), PIN).andExpect(status().isBadRequest());

        UUID[] other = venueWithAccount("PLAYSTATION");
        UUID otherRoom = resource(other[0], "PLAYSTATION", "Room 1", "PS4");
        putOverride(otherRoom, List.of(mode("SINGLE", "1.00")), PIN).andExpect(status().isNotFound());
        resetOverride(otherRoom, PIN).andExpect(status().isNotFound());
        putOverride(UUID.randomUUID(), List.of(mode("SINGLE", "1.00")), PIN).andExpect(status().isNotFound());

        // One venue never sees another's prices.
        putOverride(room, List.of(mode("SINGLE", "5.00")), PIN).andExpect(status().isOk());
        mvc.perform(get(PRICING).header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE,
                other[1]))).andExpect(status().isOk()).andExpect(jsonPath("$.data.overrides").isEmpty());
    }

    @Test
    void routeGroupsAreEnforced() throws Exception {
        mvc.perform(get(PRICING)).andExpect(status().isUnauthorized());
        mvc.perform(get(PRICING).header("Authorization", "Bearer "
                + accessTokens.issue(AuthSubjectType.ADMIN, ps.e7gz.audit.AuditFixtures.admin())))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------ the hourly price

    @Test
    void theRoomUsesItsOverrideElseTheVenueDefaultAndAConsoleChangeClearsIt() throws Exception {
        assertThat(resolve(room, PlayMode.SINGLE)).isEmpty();           // FR-VEN-45: nothing configured yet
        putDefaults(fullSet(), PIN).andExpect(status().isOk());
        assertThat(resolve(room, PlayMode.SINGLE)).contains(Money.of("100.00"));
        assertThat(resolve(room, PlayMode.MULTIPLAYER)).contains(Money.of("150.00"));

        putOverride(room, List.of(mode("SINGLE", "120.00")), PIN).andExpect(status().isOk());
        assertThat(resolve(room, PlayMode.SINGLE)).contains(Money.of("120.00"));
        assertThat(resolve(room, PlayMode.MULTIPLAYER)).contains(Money.of("150.00"));   // inherits

        // FR-VEN-15: a console change clears the room's overrides; it returns to the default for the new console.
        mvc.perform(post("/api/v1/venue/resources/" + room + "/console").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("consoleType", "PS4", "ownerPassword", PIN))))
                .andExpect(status().isOk());
        assertThat(overrideRows(room)).isZero();
        assertThat(resolve(room, PlayMode.SINGLE)).contains(Money.of("80.00"));
    }

    @Test
    void playModeIsForPlayStationOnly() throws Exception {
        putDefaults(fullSet(), PIN).andExpect(status().isOk());
        assertThat(resolve(table, null)).contains(Money.of("70.00"));
        assertThat(resolve(table, PlayMode.SINGLE)).isEmpty();         // FR-VEN-42
        assertThat(resolve(room, null)).isEmpty();
        assertThat(resolve(UUID.randomUUID(), PlayMode.SINGLE)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private static List<Map<String, Object>> fullSet() {
        return List.of(
                price("PLAYSTATION", "PS5", "SINGLE", "100.00"), price("PLAYSTATION", "PS5", "MULTIPLAYER", "150.00"),
                price("PLAYSTATION", "PS4", "SINGLE", "80.00"), price("PLAYSTATION", "PS4", "MULTIPLAYER", "120.00"),
                price("BILLIARDS", null, null, "70.00"));
    }

    private static Map<String, Object> price(String activity, String console, String mode, String amount) {
        Map<String, Object> row = new HashMap<>();
        row.put("activity", activity);
        row.put("consoleType", console);
        row.put("playMode", mode);
        row.put("hourlyPrice", Map.of("amount", amount, "currency", "EGP"));
        return row;
    }

    private static Map<String, Object> mode(String mode, String amount) {
        return Map.of("playMode", mode, "hourlyPrice", Map.of("amount", amount, "currency", "EGP"));
    }

    private ResultActions putDefaults(List<Map<String, Object>> defaults, String pin) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("defaults", new ArrayList<>(defaults));
        body.put("ownerPassword", pin);
        return send(put(PRICING), body);
    }

    private ResultActions putOverride(UUID resource, List<Map<String, Object>> prices, String pin) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("prices", prices);
        body.put("ownerPassword", pin);
        return send(put("/api/v1/venue/resources/" + resource + "/price-override"), body);
    }

    private ResultActions resetOverride(UUID resource, String pin) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("ownerPassword", pin);
        return send(delete("/api/v1/venue/resources/" + resource + "/price-override"), body);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, Object> body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }

    private static void expectField(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    private Optional<Money> resolve(UUID resource, PlayMode playMode) {
        return runner.run(new TestCommand<>(ctx -> hourlyPrices.resolve(ctx, resource, playMode)));
    }

    private UUID[] venueWithAccount(String... activities) {
        UUID area = UUID.randomUUID();
        OWNER.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, true)")
                .param("id", area).param("name", "E7-27 area " + area).update();
        UUID venue = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, '12 Test St', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", venue).param("area", area).param("name", "E7-27 venue " + venue).update();
        for (String activity : activities) {
            OWNER.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, :activity)")
                    .param("id", UUID.randomUUID()).param("venue", venue).param("activity", activity).update();
        }
        UUID account = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, 'not-used-here', 'ENABLED', 'en')""")
                .param("id", account).param("venue", venue).param("login", "e7-27-" + account).update();
        OWNER.sql("""
                        INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
                        VALUES (:account, :hash, now() - interval '1 day', 0)""")
                .param("account", account).param("hash", passwords.encode(PIN)).update();
        return new UUID[]{venue, account};
    }

    private static UUID resource(UUID venue, String activity, String name, String console) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state,
                                                       operational_restriction)
                        VALUES (:id, :venue, :activity, :name, :console, 'ACTIVE', 'NONE')""")
                .param("id", id).param("venue", venue).param("activity", activity).param("name", name)
                .param("console", console).update();
        return id;
    }

    private int defaultRows() {
        return OWNER.sql("SELECT count(*) FROM pricing.venue_default_price WHERE venue_id = :venue")
                .param("venue", venueId).query(Integer.class).single();
    }

    private static int overrideRows(UUID resource) {
        return OWNER.sql("SELECT count(*) FROM pricing.resource_price_override WHERE resource_id = :id")
                .param("id", resource).query(Integer.class).single();
    }

    private Map<String, Object> lastAudit(String action) {
        return OWNER.sql("""
                        SELECT subject_id, context_venue_id, owner_password_outcome, payload::text AS payload
                          FROM audit.audit_event
                         WHERE action = :action AND context_venue_id = :venue
                         ORDER BY occurred_at DESC, id LIMIT 1""")
                .param("action", action).param("venue", venueId).query().singleRow();
    }

    private int auditCount(String action) {
        return OWNER.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND context_venue_id = :venue")
                .param("action", action).param("venue", venueId).query(Integer.class).single();
    }

    private int refusals(String protectedAction) {
        return OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :account
                           AND payload ->> 'protectedAction' = :protected""")
                .param("account", accountId).param("protected", protectedAction).query(Integer.class).single();
    }
}
