package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-81 (S5-E1) — Maintenance through {@code /api/v1/venue/maintenance} on the real database (FR-OPS-01…FR-OPS-05,
 * Product Spec §39, FR-VEN-33; the E7-77 contract D1, D6). Each test has its own venue: a genuine PENDING booking's
 * world ({@link BookingFlows}), whose room carries every window. Windows are placed days ahead, away from the booking,
 * unless a test wants the overlap.
 */
@AutoConfigureMockMvc
class MaintenanceHttpTest extends IntegrationTest {

    private static final String MAINTENANCE = "/api/v1/venue/maintenance";
    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    OperationalOverlaps overlaps;

    private BookingFlows flows;
    private BookingFlows.Booking booking;
    private String token;
    private UUID room;
    /** A whole hour three days ahead: far from the booking, which starts about an hour from now. */
    private Instant t;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void aVenueWithARoomAndAnOwnerPin() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        booking = flows.pending();
        flows.ownerPin(booking.venueAccountId());
        token = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, booking.venueAccountId());
        room = booking.resourceId();
        t = booking.startAt().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // -------------------------------------------------------------------------- the rolling threshold (FR-OPS-03)

    /** The task's "done when": the one-minute-gap bypass is closed (Product Spec §39.2). */
    @Test
    void thirtyHoursSplitIntoTwoWindowsWithAOneMinuteGapStillDemandsTheOwnerPassword() throws Exception {
        create(t, t.plus(Duration.ofHours(15)), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ownerPasswordUsed").value(false));
        Instant second = t.plus(Duration.ofHours(15)).plus(Duration.ofMinutes(1));

        create(second, second.plus(Duration.ofHours(15)), null).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        assertThat(windows()).as("nothing written").isEqualTo(1);

        create(second, second.plus(Duration.ofHours(15)), BookingFlows.OWNER_PIN).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ownerPasswordUsed").value(true));
        assertThat(windows()).isEqualTo(2);
        assertThat(audits("MAINTENANCE_CREATED", true)).as("the PIN check is on the audit row").isEqualTo(1);
    }

    /** Overlapping time is counted once (A1, O3): 20 h and 20 h overlapping by 16 h are 24 h, not 40 h. */
    @Test
    void overlappingMaintenanceIsCountedOnceNotTwice() throws Exception {
        create(t, t.plus(Duration.ofHours(20)), null).andExpect(status().isCreated());
        create(t.plus(Duration.ofHours(4)), t.plus(Duration.ofHours(24)), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ownerPasswordUsed").value(false));
        // Back-to-back time is one period too: one more minute right after it goes past 24 hours
        create(t.plus(Duration.ofHours(24)), t.plus(Duration.ofHours(24)).plus(Duration.ofMinutes(1)), null)
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
    }

    /** The threshold is per room (Product Spec §39.2): 24 h on one room does not count against another. */
    @Test
    void theThresholdIsPerRoom() throws Exception {
        create(t, t.plus(Duration.ofHours(24)), null).andExpect(status().isCreated());
        UUID other = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state,
                                                       operational_restriction)
                        VALUES (:id, :venue, 'PLAYSTATION', 'Room 2', 'PS5', 'ACTIVE', 'NONE')""")
                .param("id", other).param("venue", booking.venueId()).update();
        mvc.perform(json(post(MAINTENANCE), body(other, t, t.plus(Duration.ofHours(24)), "Paint", null)))
                .andExpect(status().isCreated());
    }

    /** Within the rolling window, not only the new window: 20 h now and 5 h 30 h later still exceed 24 h in 48 h. */
    @Test
    void theRollingWindowAroundTheNewWindowIsChecked() throws Exception {
        create(t, t.plus(Duration.ofHours(20)), null).andExpect(status().isCreated());
        create(t.plus(Duration.ofHours(30)), t.plus(Duration.ofHours(35)), null).andExpect(status().is(423));
        // 48 hours after the first window ends, the rolling window no longer holds it
        create(t.plus(Duration.ofHours(68)), t.plus(Duration.ofHours(73)), null).andExpect(status().isCreated());
    }

    @Test
    void aWrongOwnerPasswordIsRefusedAndAudited() throws Exception {
        create(t, t.plus(Duration.ofHours(25)), "0000").andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        assertThat(windows()).isZero();
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'OWNER_PASSWORD_REFUSED'
                           AND actor_id = :account AND payload->>'protectedAction' = 'MAINTENANCE_OVER_THRESHOLD'""")
                .param("account", booking.venueAccountId()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void anEditThatExtendsPastTheThresholdNeedsTheOwnerPassword() throws Exception {
        UUID id = id(create(t, t.plus(Duration.ofHours(20)), null).andExpect(status().isCreated()));
        mvc.perform(json(patch(MAINTENANCE + "/" + id), "{\"endAt\":\"" + t.plus(Duration.ofHours(26)) + "\"}"))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        mvc.perform(json(patch(MAINTENANCE + "/" + id), "{\"endAt\":\"" + t.plus(Duration.ofHours(26))
                        + "\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endAt").value(t.plus(Duration.ofHours(26)).toString()))
                .andExpect(jsonPath("$.data.ownerPasswordUsed").value(true));
        // A re-wording keeps the interval, so it needs no PIN, and the ledger row is unchanged
        mvc.perform(json(patch(MAINTENANCE + "/" + id), "{\"reason\":\"New controller\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("New controller"));
        assertThat(activeRows(id)).containsExactly(List.of(t, t.plus(Duration.ofHours(26))));
        assertThat(audits("MAINTENANCE_EDITED", true)).as("the extension, with its PIN check").isEqualTo(1);
        assertThat(audits("MAINTENANCE_EDITED", false)).as("the re-wording").isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- overlaps (FR-OPS-02, FR-VEN-33)

    @Test
    void maintenanceOverABookingIsRefusedAndNothingIsCancelled() throws Exception {
        create(booking.startAt().minus(Duration.ofMinutes(30)), booking.startAt().plus(Duration.ofMinutes(30)), null)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_OVERLAPS_RESERVATION"));
        assertThat(windows()).isZero();
        assertThat(OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", booking.bookingId())
                .query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void maintenanceOverAClosureIsRefused() throws Exception {
        OWNER.sql("""
                        INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                        VALUES (:id, :venue, tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'PARTIAL', 'Private event')""")
                .param("id", UUID.randomUUID()).param("venue", booking.venueId())
                .param("start", t.atOffset(java.time.ZoneOffset.UTC))
                .param("end", t.plus(Duration.ofHours(4)).atOffset(java.time.ZoneOffset.UTC)).update();
        create(t.plus(Duration.ofHours(3)), t.plus(Duration.ofHours(5)), null).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_OVERLAPS_RESERVATION"));
        create(t.plus(Duration.ofHours(4)), t.plus(Duration.ofHours(5)), null).andExpect(status().isCreated());
    }

    @Test
    void overlappingAndBackToBackMaintenanceIsAllowed() throws Exception {
        create(t, t.plus(Duration.ofHours(2)), null).andExpect(status().isCreated());
        create(t.plus(Duration.ofHours(1)), t.plus(Duration.ofHours(3)), null).andExpect(status().isCreated());
        create(t.plus(Duration.ofHours(3)), t.plus(Duration.ofHours(4)), null).andExpect(status().isCreated());
        assertThat(windows()).isEqualTo(3);
    }

    // ------------------------------------------------------------------- the ledger row, the list, delete, events

    @Test
    void aWindowHasItsMaintenanceRowAndBlocksAvailabilityUntilDeleted() throws Exception {
        UUID id = id(create(t, t.plus(Duration.ofHours(2)), null).andExpect(status().isCreated()));
        assertThat(activeRows(id)).containsExactly(List.of(t, t.plus(Duration.ofHours(2))));
        assertThat(maintenanceBlocks()).as("isFree check 6").isTrue();

        mvc.perform(delete(MAINTENANCE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());

        assertThat(maintenanceBlocks()).as("a deleted window no longer makes isFree refuse").isFalse();
        assertThat(activeRows(id)).isEmpty();
        assertThat(OWNER.sql("SELECT deleted_at IS NOT NULL FROM operations.maintenance_window WHERE id = :id")
                .param("id", id).query(Boolean.class).single()).as("kept as history").isTrue();
        assertThat(audits("MAINTENANCE_DELETED", false)).isEqualTo(1);
        mvc.perform(delete(MAINTENANCE + "/" + id).header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get(MAINTENANCE).header("Authorization", token)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void aDeletedWindowNoLongerCountsTowardTheThreshold() throws Exception {
        UUID id = id(create(t, t.plus(Duration.ofHours(20)), null).andExpect(status().isCreated()));
        create(t.plus(Duration.ofHours(20)), t.plus(Duration.ofHours(30)), null).andExpect(status().is(423));
        mvc.perform(delete(MAINTENANCE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        create(t.plus(Duration.ofHours(20)), t.plus(Duration.ofHours(30)), null).andExpect(status().isCreated());
    }

    @Test
    void theListHasTheWindowsNotYetEndedWithTheirIds() throws Exception {
        UUID later = id(create(t.plus(Duration.ofHours(5)), t.plus(Duration.ofHours(6)), null));
        UUID first = id(create(t, t.plus(Duration.ofHours(1)), null));
        UUID ended = endedWindow();
        mvc.perform(get(MAINTENANCE).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(first.toString()))
                .andExpect(jsonPath("$.data[0].resourceId").value(room.toString()))
                .andExpect(jsonPath("$.data[0].createdFromEmergencyId").isEmpty())
                .andExpect(jsonPath("$.data[1].id").value(later.toString()));
        mvc.perform(get(MAINTENANCE).param("resourceId", UUID.randomUUID().toString())
                .header("Authorization", token)).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(ended).isNotIn(first, later);
    }

    /** PO decision D6 (19 Nov 2026): editing or deleting a window that has ended is OPERATIONAL_STATE_CHANGED. */
    @Test
    void anEndedWindowCannotBeEditedOrDeleted() throws Exception {
        UUID ended = endedWindow();
        mvc.perform(json(patch(MAINTENANCE + "/" + ended), "{\"reason\":\"Late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
        mvc.perform(delete(MAINTENANCE + "/" + ended).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
    }

    @Test
    void anotherVenuesWindowOrRoomIsNotFound() throws Exception {
        BookingFlows.Booking other = flows.pending();
        mvc.perform(json(post(MAINTENANCE), body(other.resourceId(), t, t.plus(Duration.ofHours(1)), "x", null)))
                .andExpect(status().isNotFound());
        UUID id = id(create(t, t.plus(Duration.ofHours(1)), null));
        String otherToken = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, other.venueAccountId());
        mvc.perform(delete(MAINTENANCE + "/" + id).header("Authorization", otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidIntervalsAreValidationFailed() throws Exception {
        create(t, t, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("endAt"));
        Instant past = t.minus(Duration.ofDays(10));
        create(past, past.plus(Duration.ofHours(1)), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("endAt"));
    }

    @Test
    void eachWriteWritesItsEventInTheSameCommand() throws Exception {
        UUID id = id(create(t, t.plus(Duration.ofHours(1)), null));
        mvc.perform(json(patch(MAINTENANCE + "/" + id), "{\"endAt\":\"" + t.plus(Duration.ofHours(2)) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete(MAINTENANCE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        for (String type : List.of(IntegrationEvents.MAINTENANCE_CREATED, IntegrationEvents.MAINTENANCE_EDITED,
                IntegrationEvents.MAINTENANCE_DELETED)) {
            assertThat(OWNER.sql("""
                            SELECT count(*) FROM technical.outbox_event WHERE event_type = :type
                               AND payload->>'maintenanceId' = :id AND payload->>'venueId' = :venue""")
                    .param("type", type).param("id", id.toString()).param("venue", booking.venueId().toString())
                    .query(Long.class).single()).as(type).isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private ResultActions create(Instant start, Instant end, String pin) throws Exception {
        return mvc.perform(json(post(MAINTENANCE), body(room, start, end, "Controller replacement", pin)));
    }

    private String body(UUID resource, Instant start, Instant end, String reason, String pin) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceId", resource);
        body.put("startAt", start.toString());
        body.put("endAt", end.toString());
        body.put("reason", reason);
        if (pin != null) {
            body.put("ownerPassword", pin);
        }
        return json.writeValueAsString(body);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID id(ResultActions created) throws Exception {
        JsonNode body = json.readTree(created.andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.path("data").path("id").asText());
    }

    private long windows() {
        return OWNER.sql("SELECT count(*) FROM operations.maintenance_window WHERE resource_id = :room")
                .param("room", room).query(Long.class).single();
    }

    private long audits(String action, boolean withOwnerPassword) {
        return OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = :action AND actor_id = :account AND COALESCE(owner_password_used, false) = :used""")
                .param("action", action).param("account", booking.venueAccountId()).param("used", withOwnerPassword)
                .query(Long.class).single();
    }

    private List<List<Instant>> activeRows(UUID maintenanceId) {
        return OWNER.sql("""
                        SELECT planned_start, planned_end FROM inventory.resource_reservation
                         WHERE maintenance_id = :id AND active_flag ORDER BY planned_start""")
                .param("id", maintenanceId)
                .query((rs, n) -> List.of(rs.getObject(1, java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(2, java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Check 6 of isFree, as the predicate reads it, over the first two hours from {@link #t}. */
    private boolean maintenanceBlocks() {
        return runner.run(new Read<>(LockSet.builder().venues(booking.venueId()).build(),
                ctx -> overlaps.maintenanceOverlaps(ctx, room, t, t.plus(Duration.ofHours(2)))));
    }

    /** A window that ended yesterday, written as the owner (no command makes one in the past). */
    private UUID endedWindow() {
        UUID id = UUID.randomUUID();
        Instant end = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
        OWNER.sql("""
                        INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                        VALUES (:id, :room, tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'Old', false)""")
                .param("id", id).param("room", room)
                .param("start", end.minus(Duration.ofHours(1)).atOffset(java.time.ZoneOffset.UTC))
                .param("end", end.atOffset(java.time.ZoneOffset.UTC)).update();
        return id;
    }

    private record Read<R>(LockSet locks, java.util.function.Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
