package ps.e7gz.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-26: the venue's resources on the Dashboard (FR-VEN-03, FR-VEN-13…FR-VEN-15, FR-VEN-41) and the admin Activities
 * view. Done when: ck_console_iff_playstation and the state-change history are tested. Every protected action checks
 * the Owner Password (a 4-digit PIN, PCR-006); Disable, Archive and Console Change are refused while a current or
 * future blocking reservation remains (FR-VEN-14), and a normal closure alone does not block.
 */
@AutoConfigureMockMvc
class VenueResourceHttpTest extends IntegrationTest {

    private static final String RESOURCES = "/api/v1/venue/resources";
    private static final String PIN = "1234";
    private static final String WRONG = "0000";

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessTokens accessTokens;
    @Autowired PasswordEncoder passwords;
    @Autowired CommandRunner runner;
    @Autowired ReservationLedger ledger;

    record TestCommand<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private UUID venueId;
    private UUID accountId;
    private String token;

    @BeforeEach
    void anActiveVenueWithItsAccount() {
        UUID[] venue = venueWithAccount();
        venueId = venue[0];
        accountId = venue[1];
        token = accessTokens.issue(AuthSubjectType.VENUE, accountId);
    }

    // ------------------------------------------------------------------------------------------ create and read

    @Test
    void theVenueAddsResourcesWithItsPinAndTheServerNamesThem() throws Exception {
        create("PLAYSTATION", "PS5", PIN).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Room 1"))
                .andExpect(jsonPath("$.data.consoleType").value("PS5"))
                .andExpect(jsonPath("$.data.baseState").value("ACTIVE"))
                .andExpect(jsonPath("$.data.operationalRestriction").value("NONE"));
        create("PLAYSTATION", "PS4", PIN).andExpect(jsonPath("$.data.name").value("Room 2"));
        create("BILLIARDS", null, PIN).andExpect(jsonPath("$.data.name").value("Pool Table 1"))
                .andExpect(jsonPath("$.data.consoleType").doesNotExist());
        create("PING_PONG", null, PIN).andExpect(jsonPath("$.data.name").value("Table 1"));

        JsonNode list = body(mvc.perform(get(RESOURCES).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        assertThat(list.path("data")).hasSize(4);

        List<Map<String, Object>> history = OWNER.sql("""
                        SELECT c.actor_type, c.actor_id, c.owner_password_used, c.from_base_state, c.to_base_state,
                               c.to_restriction, c.to_console
                          FROM resource.resource_state_change c JOIN resource.resource r ON r.id = c.resource_id
                         WHERE r.venue_id = :venue""").param("venue", venueId).query().listOfRows();
        assertThat(history).hasSize(4).allSatisfy(row -> {
            assertThat(row.get("actor_type")).isEqualTo("VENUE");
            assertThat(row.get("actor_id")).isEqualTo(accountId);
            assertThat(row.get("owner_password_used")).isEqualTo(true);
            assertThat(row.get("from_base_state")).isNull();
            assertThat(row.get("to_base_state")).isEqualTo("ACTIVE");
            assertThat(row.get("to_restriction")).isEqualTo("NONE");
        });
        assertThat(audit("RESOURCE_CREATED")).isEqualTo(4);
    }

    @Test
    void aWrongPinCreatesNothingAndIsAuditedAsRefused() throws Exception {
        create("PLAYSTATION", "PS5", WRONG)
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));

        assertThat(resourceCount()).isZero();
        assertThat(refusals("PROTECTED_RESOURCE_CONFIGURATION")).isEqualTo(1);
    }

    @Test
    void aConsoleMismatchIsAFieldErrorAndTheDatabaseRefusesItToo() throws Exception {
        create("PLAYSTATION", null, PIN).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("consoleType"));
        create("BILLIARDS", "PS5", PIN).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("consoleType"));

        // ck_console_iff_playstation is the authority, whatever the caller.
        assertThatThrownBy(() -> insertDirect("PLAYSTATION", null))
                .hasMessageContaining("ck_console_iff_playstation");
        assertThatThrownBy(() -> insertDirect("BILLIARDS", "PS4"))
                .hasMessageContaining("ck_console_iff_playstation");
        assertThat(resourceCount()).isZero();
    }

    @Test
    void anotherVenuesResourceIsNotFound() throws Exception {
        UUID theirs = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));
        String otherToken = accessTokens.issue(AuthSubjectType.VENUE, venueWithAccount()[1]);

        mvc.perform(get(RESOURCES + "/" + theirs).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(post(RESOURCES + "/" + theirs + "/disable").header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("ownerPassword", PIN))))
                .andExpect(status().isNotFound());
        assertThat(baseState(theirs)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------------------------------ state changes

    @Test
    void everyStateChangeAppendsOneContiguousHistoryRowWithItsPin() throws Exception {
        UUID room = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));

        action(room, "disable").andExpect(status().isOk()).andExpect(jsonPath("$.data.baseState").value("DISABLED"));
        action(room, "re-enable").andExpect(status().isOk()).andExpect(jsonPath("$.data.baseState").value("ACTIVE"));
        action(room, "archive").andExpect(status().isOk()).andExpect(jsonPath("$.data.baseState").value("ARCHIVED"));
        action(room, "restore").andExpect(status().isOk()).andExpect(jsonPath("$.data.baseState").value("ACTIVE"));
        console(room, "PS4", PIN).andExpect(status().isOk()).andExpect(jsonPath("$.data.consoleType").value("PS4"));

        List<Map<String, Object>> chain = OWNER.sql("""
                        SELECT from_base_state, to_base_state, from_console, to_console, owner_password_used
                          FROM resource.resource_state_change WHERE resource_id = :id ORDER BY changed_at, id""")
                .param("id", room).query().listOfRows();
        assertThat(chain).extracting(r -> r.get("from_base_state") + ">" + r.get("to_base_state"))
                .containsExactly("null>ACTIVE", "ACTIVE>DISABLED", "DISABLED>ACTIVE", "ACTIVE>ARCHIVED",
                        "ARCHIVED>ACTIVE", "null>null");
        assertThat(chain.getLast()).containsEntry("from_console", "PS5").containsEntry("to_console", "PS4");
        assertThat(chain).allSatisfy(r -> assertThat(r.get("owner_password_used")).isEqualTo(true));
        assertThat(audit("RESOURCE_DISABLED") + audit("RESOURCE_RE_ENABLED") + audit("RESOURCE_ARCHIVED")
                + audit("RESOURCE_RESTORED") + audit("RESOURCE_CONSOLE_CHANGED")).isEqualTo(5);

        // Append-only: history cannot be edited or removed, even by the owner.
        assertThatThrownBy(() -> OWNER.sql("UPDATE resource.resource_state_change SET reason = 'x' "
                + "WHERE resource_id = :id").param("id", room).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> OWNER.sql("DELETE FROM resource.resource_state_change WHERE resource_id = :id")
                .param("id", room).update()).hasMessageContaining("append-only");
    }

    @Test
    void aWrongPinChangesNoStateAndEachActionAsksForItAgain() throws Exception {
        UUID room = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));

        act(room, "disable", WRONG).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        console(room, "PS4", WRONG).andExpect(status().is(423));

        assertThat(baseState(room)).isEqualTo("ACTIVE");
        assertThat(historyCount(room)).isEqualTo(1);
        assertThat(refusals("RESOURCE_DISABLE_REENABLE_ARCHIVE_RESTORE")).isEqualTo(1);
        assertThat(refusals("PS_CONSOLE_CHANGE")).isEqualTo(1);
    }

    @Test
    void anActionThatDoesNotFitTheCurrentStateIsRefused() throws Exception {
        UUID room = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));
        UUID table = UUID.fromString(field(create("BILLIARDS", null, PIN), "id"));

        action(room, "re-enable").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("baseState"));
        action(room, "restore").andExpect(status().isBadRequest());
        console(room, "PS5", PIN).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("consoleType"));
        console(table, "PS5", PIN).andExpect(status().isBadRequest());
        assertThat(historyCount(room)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ FR-VEN-14 / 15

    @Test
    void aFutureBookingBlocksDisableArchiveAndConsoleChangeAndIsNamed() throws Exception {
        UUID room = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));
        UUID booking = manualBooking(room);
        UUID reservation = reserve(room, ReservationOwner.booking(booking), "2030-05-01T08:00:00Z",
                "2030-05-01T09:00:00Z");

        for (String blocked : List.of("disable", "archive")) {
            action(room, blocked).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_HAS_BLOCKING_RESERVATIONS"))
                    .andExpect(jsonPath("$.error.details.reservations[0].reservationId").value(reservation.toString()))
                    .andExpect(jsonPath("$.error.details.reservations[0].reservationType").value("BOOKING"))
                    .andExpect(jsonPath("$.error.details.reservations[0].ownerId").value(booking.toString()));
        }
        console(room, "PS4", PIN).andExpect(status().isUnprocessableEntity());

        assertThat(baseState(room)).isEqualTo("ACTIVE");
        assertThat(historyCount(room)).isEqualTo(1);
        assertThat(OWNER.sql("SELECT active_flag FROM inventory.resource_reservation WHERE id = :id")
                .param("id", reservation).query(Boolean.class).single()).as("never cancelled").isTrue();

        run(room, ctx -> ledger.release(ctx, reservation, Instant.parse("2030-05-01T08:16:00Z"), "E7-26 test"));
        action(room, "disable").andExpect(status().isOk());
    }

    @Test
    void maintenanceBlocksButANormalClosureAloneDoesNot() throws Exception {
        UUID maintained = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));
        UUID closed = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));
        UUID maintenance = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                        VALUES (:id, :resource, tstzrange('2030-05-02 08:00+00', '2030-05-02 09:00+00', '[)'),
                                'E7-26 maintenance', false)""")
                .param("id", maintenance).param("resource", maintained).update();
        reserve(maintained, ReservationOwner.maintenance(maintenance), "2030-05-02T08:00:00Z", "2030-05-02T09:00:00Z");
        UUID closure = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                        VALUES (:id, :venue, tstzrange('2030-05-03 08:00+00', '2030-05-03 09:00+00', '[)'),
                                'PARTIAL', 'E7-26 closure')""")
                .param("id", closure).param("venue", venueId).update();
        reserve(closed, ReservationOwner.closure(closure), "2030-05-03T08:00:00Z", "2030-05-03T09:00:00Z");

        action(maintained, "archive").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reservations[0].reservationType").value("MAINTENANCE"));
        action(closed, "archive").andExpect(status().isOk());
    }

    @Test
    void aConsoleChangeClearsTheRoomsPriceOverrides() throws Exception {
        UUID room = UUID.fromString(field(create("PLAYSTATION", "PS5", PIN), "id"));
        OWNER.sql("""
                        INSERT INTO pricing.resource_price_override (id, resource_id, console_type, play_mode,
                                                                     hourly_price)
                        VALUES (:id, :resource, 'PS5', 'SINGLE', 120.00)""")
                .param("id", UUID.randomUUID()).param("resource", room).update();

        console(room, "PS4", PIN).andExpect(status().isOk());

        assertThat(OWNER.sql("SELECT count(*) FROM pricing.resource_price_override WHERE resource_id = :id")
                .param("id", room).query(Integer.class).single()).isZero();
    }

    // ------------------------------------------------------------------------------------------ admin Activities

    @Test
    void theAdminSeesTheThreeActivitiesAndHowManyVenuesOfferEach() throws Exception {
        String admin = accessTokens.issue(AuthSubjectType.ADMIN, AuditFixtures.admin());
        Map<String, Integer> before = activityCounts(admin);

        venueWithAccount();   // offers PLAYSTATION and BILLIARDS

        Map<String, Integer> after = activityCounts(admin);
        assertThat(after.keySet()).containsExactly("PLAYSTATION", "BILLIARDS", "PING_PONG");
        assertThat(after.get("PLAYSTATION")).isEqualTo(before.get("PLAYSTATION") + 1);
        assertThat(after.get("BILLIARDS")).isEqualTo(before.get("BILLIARDS") + 1);
        assertThat(after.get("PING_PONG")).isEqualTo(before.get("PING_PONG"));
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** An ACTIVE venue (a Manual Booking refuses ONBOARDING) offering three activities, with its account and PIN. */
    private UUID[] venueWithAccount() {
        UUID area = UUID.randomUUID();
        OWNER.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, true)")
                .param("id", area).param("name", "E7-26 area " + area).update();
        UUID venue = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, '12 Test St', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", venue).param("area", area).param("name", "E7-26 venue " + venue).update();
        boolean first = venueId == null;
        for (String activity : first ? List.of("PLAYSTATION", "BILLIARDS", "PING_PONG")
                : List.of("PLAYSTATION", "BILLIARDS")) {
            OWNER.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, :activity)")
                    .param("id", UUID.randomUUID()).param("venue", venue).param("activity", activity).update();
        }
        UUID account = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, 'not-used-here', 'ENABLED', 'en')""")
                .param("id", account).param("venue", venue).param("login", "e7-26-" + account).update();
        OWNER.sql("""
                        INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
                        VALUES (:account, :hash, now() - interval '1 day', 0)""")
                .param("account", account).param("hash", passwords.encode(PIN)).update();
        return new UUID[]{venue, account};
    }

    private UUID manualBooking(UUID room) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :room, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
                                'Room 1', '2030-05-01 08:00:00+00', '2030-05-01 09:00:00+00', 60, 100.00, 100.00,
                                0.00, 100.00, DATE '2030-05-01', 'Africa/Cairo', now())""")
                .param("id", id).param("venue", venueId).param("room", room).update();
        return id;
    }

    private UUID reserve(UUID room, ReservationOwner owner, String start, String end) {
        return run(room, ctx -> ledger.create(ctx,
                new NewReservation(room, owner, Instant.parse(start), Instant.parse(end))));
    }

    private <R> R run(UUID room, Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(CommandScope.shared(
                LockSet.builder().venues(venueId).resources(room).build()), body));
    }

    private void insertDirect(String activity, String console) {
        OWNER.sql("""
                        INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state,
                                                       operational_restriction)
                        VALUES (:id, :venue, :activity, 'Direct 1', :console, 'ACTIVE', 'NONE')""")
                .param("id", UUID.randomUUID()).param("venue", venueId).param("activity", activity)
                .param("console", console).update();
    }

    private ResultActions create(String activity, String console, String pin) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("activity", activity);
        body.put("ownerPassword", pin);
        if (console != null) {
            body.put("consoleType", console);
        }
        return mvc.perform(post(RESOURCES).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private ResultActions action(UUID resource, String action) throws Exception {
        return act(resource, action, PIN);
    }

    private ResultActions act(UUID resource, String action, String pin) throws Exception {
        return mvc.perform(post(RESOURCES + "/" + resource + "/" + action).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("ownerPassword", pin))));
    }

    private ResultActions console(UUID resource, String console, String pin) throws Exception {
        return mvc.perform(post(RESOURCES + "/" + resource + "/console").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("consoleType", console, "ownerPassword", pin))));
    }

    private Map<String, Integer> activityCounts(String adminToken) throws Exception {
        JsonNode data = body(mvc.perform(get("/api/v1/admin/activities")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk())).path("data");
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        data.forEach(a -> counts.put(a.path("activity").asText(), a.path("venueCount").asInt()));
        return counts;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private String field(ResultActions result, String name) throws Exception {
        return body(result.andExpect(status().isCreated())).path("data").path(name).asText();
    }

    private String baseState(UUID resource) {
        return OWNER.sql("SELECT base_state FROM resource.resource WHERE id = :id").param("id", resource)
                .query(String.class).single();
    }

    private int historyCount(UUID resource) {
        return OWNER.sql("SELECT count(*) FROM resource.resource_state_change WHERE resource_id = :id")
                .param("id", resource).query(Integer.class).single();
    }

    private int resourceCount() {
        return OWNER.sql("SELECT count(*) FROM resource.resource WHERE venue_id = :venue").param("venue", venueId)
                .query(Integer.class).single();
    }

    private int audit(String action) {
        return OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event e JOIN resource.resource r ON r.id = e.subject_id
                         WHERE e.action = :action AND r.venue_id = :venue""")
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
