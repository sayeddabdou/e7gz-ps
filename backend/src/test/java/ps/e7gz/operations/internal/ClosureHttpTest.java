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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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
import ps.e7gz.operations.api.OperationalStateAt;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-81 (S5-E1) — normal Venue Closures through {@code /api/v1/venue/closures} on the real database (FR-VEN-29…FR-VEN-33,
 * Product Spec §15; the E7-77 contract D1, D2, D6) with genuine bookings, each made by its own command in its own venue
 * ({@link BookingFlows}): preview, then the save that cancels the affected set CANCELLED / VENUE_CLOSURE through the
 * E7-113 cancellation and writes both records - the {@code venue_closure} row and one {@code CLOSURE} row per room.
 */
@AutoConfigureMockMvc
class ClosureHttpTest extends IntegrationTest {

    private static final String CLOSURES = "/api/v1/venue/closures";
    private static final String PIN = BookingFlows.OWNER_PIN;
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

    @Autowired
    OperationalStateAt stateAt;

    private BookingFlows flows;
    private CorrelationId.Scope correlation;
    private BookingFlows.Booking booking;
    private String token;

    @BeforeEach
    void flows() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------------------------- the cascade (FR-VEN-30)

    @Test
    void aClosureOverAPendingBookingCancelsItWithoutTheOwnerPasswordAndWritesBothRecords() throws Exception {
        use(flows.pending());
        Map<String, Object> proposal = around(booking.startAt());

        JsonNode preview = preview(null, proposal).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected.length()").value(1))
                .andExpect(jsonPath("$.data.affected[0].bookingId").value(booking.bookingId().toString()))
                .andExpect(jsonPath("$.data.affected[0].protectedCancellation").value(false))
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(false))
                .andReturn().getResponse().getContentAsString().transform(this::data);
        UUID closure = id(save(post(CLOSURES), proposal, preview.path("previewToken").asText(), null, UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("PARTIAL"))
                .andExpect(jsonPath("$.data.reason").value("Private event")));

        assertCancelledByTheClosure(booking.bookingId());
        assertThat(closureRows(closure)).as("one CLOSURE row per room").isEqualTo(rooms());
        assertThat(count("SELECT count(*) FROM operations.venue_closure WHERE id = '" + closure + "'")).isEqualTo(1);
        assertThat(events(IntegrationEvents.CLOSURE_CREATED)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE event_type = 'BookingCancelled'"
                + " AND payload->>'bookingId' = '" + booking.bookingId() + "'")).as("the customer's notification")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '" + booking.bookingId()
                + "'")).as("the Booking Fee refund decision (Wallet or Points, never a card)").isEqualTo(1);
        assertThat(audit("CLOSURE_CREATED", false)).isEqualTo(1);
        assertThat(closureBlocks(booking.startAt())).as("isFree check 7").isTrue();
    }

    /** FR-VEN-30: a CONFIRMED Online booking is protected - the save needs the Owner Password, checked in the save. */
    @Test
    void cancellingAConfirmedOnlineBookingNeedsTheOwnerPassword() throws Exception {
        BookingFlows.Booking confirmed = flows.pending();
        flows.accept(confirmed);
        use(confirmed);
        Map<String, Object> proposal = around(booking.startAt());
        String token = preview(null, proposal).andExpect(jsonPath("$.data.ownerPasswordRequired").value(true))
                .andExpect(jsonPath("$.data.affected[0].protectedCancellation").value(true))
                .andReturn().getResponse().getContentAsString().transform(this::data).path("previewToken").asText();

        save(post(CLOSURES), proposal, token, null, UUID.randomUUID()).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        save(post(CLOSURES), proposal, token, "0000", UUID.randomUUID()).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        assertThat(bookingStatus(booking.bookingId())).as("nothing changed").isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM operations.venue_closure WHERE venue_id = '" + booking.venueId() + "'"))
                .isZero();

        save(post(CLOSURES), proposal, token, PIN, UUID.randomUUID()).andExpect(status().isCreated());
        assertCancelledByTheClosure(booking.bookingId());
        assertThat(audit("CLOSURE_CREATED", true)).as("the PIN check is on the Closure's audit row").isEqualTo(1);
        // PO decision 19 Oct 2026: no Closure is left overlapping a CONFIRMED booking
        assertThat(count("""
                SELECT count(*) FROM booking.booking b JOIN operations.venue_closure c ON c.venue_id = b.venue_id
                 WHERE b.venue_id = '%s' AND b.status = 'CONFIRMED' AND c.deleted_at IS NULL
                   AND tstzrange(b.start_at, b.end_at, '[)') && c.period""".formatted(booking.venueId()))).isZero();
    }

    /** FR-VEN-29: a Closure blocks the whole venue whatever the hours, so it cancels a Manual Booking too. */
    @Test
    void aManualBookingIsCancelledWithoutTheOwnerPassword() throws Exception {
        use(flows.manualConfirmed());
        Map<String, Object> proposal = around(booking.startAt());
        String token = token(preview(null, proposal).andExpect(jsonPath("$.data.ownerPasswordRequired").value(false)));
        save(post(CLOSURES), proposal, token, null, UUID.randomUUID()).andExpect(status().isCreated());
        assertCancelledByTheClosure(booking.bookingId());
    }

    /** FR-VEN-30, FR-VEN-28: an AWAITING booking is protected; its Late Confirmation is closed in the same save. */
    @Test
    void anAwaitingBookingIsProtectedAndItsLateConfirmationIsClosed() throws Exception {
        use(flows.lateConfirmed());
        Map<String, Object> proposal = around(booking.startAt());
        String token = token(preview(null, proposal).andExpect(jsonPath("$.data.ownerPasswordRequired").value(true)));
        save(post(CLOSURES), proposal, token, PIN, UUID.randomUUID()).andExpect(status().isCreated());
        assertCancelledByTheClosure(booking.bookingId());
        assertThat(count("SELECT count(*) FROM booking.late_confirmation WHERE booking_id = '" + booking.bookingId()
                + "' AND closed_at IS NULL")).isZero();
    }

    // ------------------------------------------------------------------------------------- refusals (FR-VEN-26/31)

    /** The reviewer's checklist: a stale preview whose affected set grew is 409, with nothing changed. */
    @Test
    void aSaveWithAStalePreviewIsAffectedSetChangedAndChangesNothing() throws Exception {
        use(flows.pending());
        Map<String, Object> proposal = around(booking.startAt());
        String token = token(preview(null, proposal).andExpect(jsonPath("$.data.affected.length()").value(1)));
        UUID grown = flows.manualOnRoomOf(booking, booking.startAt().plus(Duration.ofMinutes(60)), 60);

        save(post(CLOSURES), proposal, token, null, UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AFFECTED_SET_CHANGED_REFRESH_PREVIEW"));
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(bookingStatus(grown)).isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM operations.venue_closure WHERE venue_id = '" + booking.venueId() + "'"))
                .isZero();

        String fresh = token(preview(null, proposal).andExpect(jsonPath("$.data.affected.length()").value(2)));
        save(post(CLOSURES), proposal, fresh, null, UUID.randomUUID()).andExpect(status().isCreated());
        assertCancelledByTheClosure(grown);
    }

    @Test
    void aClosureOverAnActiveSessionIsRefused() throws Exception {
        use(flows.active(Duration.ofMinutes(10)));
        Map<String, Object> proposal = around(Instant.now());
        preview(null, proposal).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("CLOSURE_OVERLAPS_ACTIVE_SESSION"));
        save(post(CLOSURES), proposal, "v1.x", PIN, UUID.randomUUID()).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("CLOSURE_OVERLAPS_ACTIVE_SESSION"));
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("ACTIVE");
    }

    /** FR-VEN-33: a Closure may overlap existing Maintenance. */
    @Test
    void aClosureMayOverlapMaintenance() throws Exception {
        use(flows.pending());
        Instant later = booking.startAt().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
        mvc.perform(post("/api/v1/venue/maintenance").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + booking.resourceId() + "\",\"startAt\":\"" + later
                                + "\",\"endAt\":\"" + later.plus(Duration.ofHours(2)) + "\",\"reason\":\"Paint\"}"))
                .andExpect(status().isCreated());
        Map<String, Object> proposal = proposal(later.minus(Duration.ofHours(1)), later.plus(Duration.ofHours(1)));
        save(post(CLOSURES), proposal, token(preview(null, proposal)), null, UUID.randomUUID())
                .andExpect(status().isCreated());
    }

    @Test
    void aSecondClosureOverlappingTheFirstIsRefused() throws Exception {
        use(flows.pending());
        Instant later = booking.startAt().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
        Map<String, Object> first = proposal(later, later.plus(Duration.ofHours(4)));
        save(post(CLOSURES), first, token(preview(null, first)), null, UUID.randomUUID())
                .andExpect(status().isCreated());
        preview(null, proposal(later.plus(Duration.ofHours(3)), later.plus(Duration.ofHours(5))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // -------------------------------------------------------------------------------- idempotency, edit, delete

    @Test
    void aReplayWithTheSameKeyRunsOnce() throws Exception {
        use(flows.pending());
        Map<String, Object> proposal = around(booking.startAt());
        String token = token(preview(null, proposal));
        UUID key = UUID.randomUUID();
        UUID first = id(save(post(CLOSURES), proposal, token, null, key).andExpect(status().isCreated()));
        UUID again = id(save(post(CLOSURES), proposal, token, null, key).andExpect(status().isCreated()));
        assertThat(again).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM operations.venue_closure WHERE venue_id = '" + booking.venueId() + "'"))
                .isEqualTo(1);
    }

    @Test
    void anEditReplacesTheClosureRowsAndShrinkingRestoresNothing() throws Exception {
        use(flows.pending());
        Map<String, Object> wide = around(booking.startAt());
        UUID closure = id(save(post(CLOSURES), wide, token(preview(null, wide)), null, UUID.randomUUID())
                .andExpect(status().isCreated()));
        Instant later = booking.startAt().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
        Map<String, Object> moved = proposal(later, later.plus(Duration.ofHours(3)));

        String token = token(preview(closure, moved).andExpect(jsonPath("$.data.affected.length()").value(0)));
        save(patch(CLOSURES + "/" + closure), moved, token, null, UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(closure.toString()))
                .andExpect(jsonPath("$.data.startAt").value(later.toString()));

        assertThat(closureRows(closure)).isEqualTo(rooms());
        assertThat(count("""
                SELECT count(*) FROM inventory.resource_reservation
                 WHERE closure_id = '%s' AND active_flag AND planned_start = '%s'""".formatted(closure, later)))
                .as("only the new interval's rows are active").isEqualTo(rooms());
        assertThat(bookingStatus(booking.bookingId())).as("never restored").isEqualTo("CANCELLED");
        assertThat(closureBlocks(booking.startAt())).isFalse();
        assertThat(audit("CLOSURE_EDITED", false)).isEqualTo(1);
    }

    @Test
    void aDeletedClosureNoLongerBlocksAndItsBookingsStayCancelled() throws Exception {
        use(flows.pending());
        Map<String, Object> proposal = around(booking.startAt());
        UUID closure = id(save(post(CLOSURES), proposal, token(preview(null, proposal)), null, UUID.randomUUID()));
        mvc.perform(get(CLOSURES).header("Authorization", token)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(closure.toString()));

        mvc.perform(delete(CLOSURES + "/" + closure).header("Authorization", token)).andExpect(status().isNoContent());

        assertThat(closureBlocks(booking.startAt())).as("a deleted Closure no longer makes isFree refuse").isFalse();
        assertThat(startCheckSeesAClosure(booking.startAt()))
                .as("E7-60's start check ignores a deleted Closure too").isFalse();
        assertThat(closureRows(closure)).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("CANCELLED");
        assertThat(audit("CLOSURE_DELETED", false)).isEqualTo(1);
        mvc.perform(get(CLOSURES).header("Authorization", token)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(delete(CLOSURES + "/" + closure).header("Authorization", token)).andExpect(status().isNotFound());
    }

    /** PO decision D6 (19 Nov 2026): an ended Closure cannot be edited or deleted. */
    @Test
    void anEndedClosureIsOperationalStateChanged() throws Exception {
        use(flows.pending());
        UUID ended = UUID.randomUUID();
        Instant end = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MINUTES);
        OWNER.sql("""
                        INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                        VALUES (:id, :venue, tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'PARTIAL', 'Old')""")
                .param("id", ended).param("venue", booking.venueId())
                .param("start", end.minus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC))
                .param("end", end.atOffset(ZoneOffset.UTC)).update();
        mvc.perform(delete(CLOSURES + "/" + ended).header("Authorization", token)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
        Instant later = booking.startAt().plus(Duration.ofDays(1));
        preview(ended, proposal(later, later.plus(Duration.ofHours(1)))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
        mvc.perform(get(CLOSURES).header("Authorization", token)).andExpect(jsonPath("$.data.length()").value(0));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private void use(BookingFlows.Booking booking) {
        this.booking = booking;
        flows.ownerPin(booking.venueAccountId());
        token = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, booking.venueAccountId());
    }

    /** A partial Closure from 30 minutes before {@code at} to two hours after it. */
    private static Map<String, Object> around(Instant at) {
        Instant start = at.truncatedTo(ChronoUnit.MINUTES);
        return proposal(start.minus(Duration.ofMinutes(30)), start.plus(Duration.ofHours(2)));
    }

    private static Map<String, Object> proposal(Instant start, Instant end) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("kind", "PARTIAL");
        proposal.put("startAt", start.toString());
        proposal.put("endAt", end.toString());
        proposal.put("reason", "Private event");
        return proposal;
    }

    private ResultActions preview(UUID closureId, Map<String, Object> proposal) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (closureId != null) {
            body.put("closureId", closureId);
        }
        body.put("proposal", proposal);
        return mvc.perform(post(CLOSURES + "/preview").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions save(MockHttpServletRequestBuilder request, Map<String, Object> proposal, String token,
                               String pin, UUID key) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposal", proposal);
        body.put("previewToken", token);
        if (pin != null) {
            body.put("ownerPassword", pin);
        }
        return mvc.perform(request.header("Authorization", this.token).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private String token(ResultActions preview) throws Exception {
        return data(preview.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("previewToken").asText();
    }

    private UUID id(ResultActions saved) throws Exception {
        return UUID.fromString(data(saved.andReturn().getResponse().getContentAsString()).path("id").asText());
    }

    private JsonNode data(String body) {
        try {
            return json.readTree(body).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertCancelledByTheClosure(UUID bookingId) {
        assertThat(OWNER.sql("SELECT status || '/' || terminal_reason FROM booking.booking WHERE id = :id")
                .param("id", bookingId).query(String.class).single()).isEqualTo("CANCELLED/VENUE_CLOSURE");
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '" + bookingId
                + "' AND active_flag")).as("its reservation released").isZero();
        assertThat(count("SELECT count(*) FROM booking.booking_history WHERE booking_id = '" + bookingId
                + "' AND terminal_reason = 'VENUE_CLOSURE' AND actor_type = 'VENUE'")).isEqualTo(1);
    }

    private String bookingStatus(UUID bookingId) {
        return OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", bookingId)
                .query(String.class).single();
    }

    private long closureRows(UUID closureId) {
        return count("SELECT count(*) FROM inventory.resource_reservation WHERE closure_id = '" + closureId
                + "' AND active_flag AND reservation_type = 'CLOSURE'");
    }

    private long rooms() {
        return count("SELECT count(*) FROM resource.resource WHERE venue_id = '" + booking.venueId() + "'");
    }

    private long events(String type) {
        return count("SELECT count(*) FROM technical.outbox_event WHERE event_type = '" + type
                + "' AND payload->>'venueId' = '" + booking.venueId() + "'");
    }

    private long audit(String action, boolean withOwnerPassword) {
        return count("SELECT count(*) FROM audit.audit_event WHERE action = '" + action + "' AND actor_id = '"
                + booking.venueAccountId() + "' AND COALESCE(owner_password_used, false) = " + withOwnerPassword);
    }

    private static long count(String sql) {
        return OWNER.sql(sql).query(Long.class).single();
    }

    /** Check 7 of isFree, as the predicate reads it, over the hour from {@code at}. */
    private boolean closureBlocks(Instant at) {
        return runner.run(new Read<>(booking.venueId(),
                ctx -> overlaps.closureOverlaps(ctx, booking.venueId(), at, at.plus(Duration.ofHours(1)))));
    }

    /** E7-60's start check ({@code closurePreventsService}) over the hour from {@code at}. */
    private boolean startCheckSeesAClosure(Instant at) {
        return runner.run(new Read<>(booking.venueId(),
                ctx -> stateAt.closurePreventsService(ctx, booking.venueId(), at, at.plus(Duration.ofHours(1)))));
    }

    private record Read<R>(UUID venueId, java.util.function.Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
