package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-82 (S5-E2) — venue Emergency Closures and resource Emergency Outages through {@code /api/v1/venue/emergencies} on
 * the real database, with genuine bookings made by their own commands ({@link BookingFlows}): the all-or-nothing cascade
 * over the declared window (arch 06 section 2.5, FR-OPS-06…FR-OPS-17, FR-OPS-44, FR-ATT-10), the late-T4 start then
 * interruption (Spec v5 section 31.7), the E7-69 carry-forward (BookingOutcomes.recordInterruption), the lifecycle
 * (expected end, extend, end) and the lists (the E7-77 contract D1, D3, D6).
 */
@AutoConfigureMockMvc
class EmergencyHttpTest extends IntegrationTest {

    static final String EMERGENCIES = "/api/v1/venue/emergencies";
    static final JdbcClient OWNER = JdbcClient.create(new SingleConnectionDataSource(
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true));

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
    VenueEmergencies emergencies;

    private BookingFlows flows;
    private AttendanceFlows attendance;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void flows() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------------------------- the venue cascade

    @Test
    void aVenueEmergencyCancelsThePendingBookingInItsWindowWithoutTheOwnerPasswordAndBlocksTheVenue()
            throws Exception {
        BookingFlows.Booking booking = flows.pending();
        Instant expectedEnd = booking.startAt().plus(Duration.ofMinutes(60));

        JsonNode emergency = data(declareVenue(booking, expectedEnd, UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scope").value("VENUE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.resourceId").isEmpty())
                .andExpect(jsonPath("$.data.reason").value("Power cut")));
        UUID id = UUID.fromString(emergency.path("id").asText());
        assertThat(Instant.parse(emergency.path("expectedEndAt").asText())).isEqualTo(expectedEnd);

        assertThat(statusOf(booking.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '"
                + booking.bookingId() + "' AND active_flag")).as("released").isZero();
        assertThat(count("SELECT count(*) FROM venue.venue_selling_block WHERE emergency_id = '" + id
                + "' AND active")).as("the venue's selling block (FR-OPS-15)").isOne();
        assertThat(events(IntegrationEvents.EMERGENCY_DECLARED, booking.venueId())).isOne();
        // E7-80 (Spec v5 section 52.6 "Emergency declarations", Q1): one Admin Alert, in the declaring command
        assertThat(count("SELECT count(*) FROM admin.admin_alert WHERE kind = 'EMERGENCY_DECLARED'"
                + " AND subject_type = 'EMERGENCY' AND subject_id = '" + id + "'")).isOne();
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE event_type = 'BookingCancelled'"
                + " AND payload->>'bookingId' = '" + booking.bookingId() + "'")).as("notified after commit").isOne();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '" + booking.bookingId()
                + "'")).as("the Booking Fee decision (Wallet or Points)").isOne();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'EMERGENCY_DECLARED'"
                + " AND subject_id = '" + id + "' AND owner_password_used IS NOT TRUE")).as("no Owner Password").isOne();
        assertThat(count("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id = '"
                + booking.bookingId() + "' AND cause = 'VENUE_EMERGENCY_CLOSURE'")).isOne();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE type = 'EMERGENCY_EXPECTED_END'"
                + " AND subject_id = '" + id + "' AND status = 'PENDING'")).as("the expected end").isOne();
        assertThat(count("SELECT count(*) FROM booking.booking_history WHERE booking_id = '" + booking.bookingId()
                + "' AND to_status = 'CANCELLED' AND actor_type = 'VENUE' AND actor_id = '"
                + booking.venueAccountId() + "'")).as("the declaring account").isOne();
    }

    /** FR-OPS-08, FR-OPS-17, FR-OPS-34: outside the window nothing is cancelled; a pending request waits, paused. */
    @Test
    void aPendingRequestOutsideTheWindowIsNotCancelledButItsResponseTimerPauses() throws Exception {
        List<BookingFlows.Booking> two = flows.twoPendingOfOneCustomer();
        BookingFlows.Booking inside = two.get(0);
        BookingFlows.Booking outside = two.get(1);

        declareVenue(inside, inside.startAt().plus(Duration.ofMinutes(60)), UUID.randomUUID())
                .andExpect(status().isCreated());

        assertThat(statusOf(inside.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(statusOf(outside.bookingId())).isEqualTo("PENDING/-");
        assertThat(count("SELECT count(*) FROM booking.booking WHERE id = '" + outside.bookingId()
                + "' AND response_pause_started_at IS NOT NULL")).isOne();
        assertThat(count("SELECT count(*) FROM operations.booking_timer_pause_history WHERE booking_id = '"
                + outside.bookingId() + "' AND pause_kind = 'VENUE_RESPONSE' AND pause_ended_at IS NULL"
                + " AND pause_cause = 'VENUE_EMERGENCY_CLOSURE'")).isOne();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE subject_id = '" + outside.bookingId()
                + "' AND type = 'VENUE_RESPONSE_EXPIRY' AND status = 'PAUSED'")).as("T1 never fires").isOne();
    }

    /** FR-OPS-44: AWAITING is cancelled under the emergency reason; never CUSTOMER_RECONFIRM_TIMEOUT. */
    @Test
    void anAwaitingBookingIsCancelledUnderTheEmergencyReasonAndItsLateConfirmTimerStops() throws Exception {
        BookingFlows.Booking awaiting = flows.lateConfirmed();

        declareVenue(awaiting, awaiting.startAt().plus(Duration.ofMinutes(60)), UUID.randomUUID())
                .andExpect(status().isCreated());

        assertThat(statusOf(awaiting.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(count("SELECT count(*) FROM booking.late_confirmation WHERE booking_id = '" + awaiting.bookingId()
                + "' AND closed_at IS NOT NULL")).as("its Late Confirmation closed").isOne();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE subject_id = '" + awaiting.bookingId()
                + "' AND type LIKE 'LATE_CONFIRM_%' AND status IN ('PENDING','PAUSED')")).as("timer stopped").isZero();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '"
                + awaiting.bookingId() + "' AND active_flag")).as("the hold released").isZero();
    }

    /** FR-OPS-44: even past its deadline with T2 not run yet, the Emergency reason wins over the reconfirm timeout. */
    @Test
    void anAwaitingBookingPastItsDeadlineIsStillCancelledUnderTheEmergencyReason() throws Exception {
        BookingFlows.Booking awaiting = flows.lateConfirmed();
        flows.lateConfirmDeadlinePassedBeforeT2(awaiting);

        declareVenue(awaiting, awaiting.startAt().plus(Duration.ofMinutes(60)), UUID.randomUUID())
                .andExpect(status().isCreated());

        assertThat(statusOf(awaiting.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
    }

    // ---------------------------------------------------------------------------------- ACTIVE interruptions

    /**
     * E7-69 carry-forward (FR-TRS-11, FR-ATT-10): an interrupted, checked-in ACTIVE booking resolves ATTENDED_INTERRUPTED
     * and records BOOKING_ATTENDED_INTERRUPTED through BookingOutcomes.recordInterruption: the consecutive no-show
     * counter resets, with no Verified Player progress and no points.
     */
    @Test
    void anInterruptedCheckedInBookingResetsTheNoShowCounterWithNoVerifiedProgressAndNoPoints() throws Exception {
        BookingFlows.Customer customer = flows.customer();
        BookingFlows.Booking missed = customer.nextActive(Duration.ofMinutes(20));
        attendance.noShow(missed.venueAccountId(), missed.bookingId());
        assertThat(projection(missed.customerId(), "consecutive_no_shows")).isOne();
        long attendedBefore = projection(missed.customerId(), "completed_attended_count");

        BookingFlows.Booking active = customer.nextActive(Duration.ofMinutes(5));
        attendance.checkIn(active.venueAccountId(), active.bookingId());
        declareVenue(active, dbNow().plus(Duration.ofMinutes(30)), UUID.randomUUID()).andExpect(status().isCreated());

        assertThat(statusOf(active.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(attendanceOf(active.bookingId())).isEqualTo("ATTENDED_INTERRUPTED");
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id = '" + active.bookingId()
                + "' AND event_type = 'BOOKING_ATTENDED_INTERRUPTED'")).isOne();
        assertThat(projection(active.customerId(), "consecutive_no_shows")).as("the counter resets").isZero();
        assertThat(projection(active.customerId(), "completed_attended_count")).as("no Verified Player progress")
                .isEqualTo(attendedBefore);
        assertThat(count("SELECT count(*) FROM loyalty.points_transaction WHERE booking_id = '"
                + active.bookingId() + "'")).as("no points").isZero();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '"
                + active.bookingId() + "' AND active_flag")).as("the room free from now (FR-AVL-12)").isZero();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE subject_id = '" + active.bookingId()
                + "' AND type = 'BOOKING_COMPLETE' AND status = 'PENDING'")).as("completion withdrawn").isZero();
    }

    /** FR-ATT-10: without evidence the interruption is NOT_APPLICABLE and writes no Trust outcome. */
    @Test
    void anInterruptedBookingWithoutEvidenceIsNotApplicableAndCountsNothing() throws Exception {
        BookingFlows.Booking active = flows.active(Duration.ofMinutes(5));

        declareVenue(active, dbNow().plus(Duration.ofMinutes(30)), UUID.randomUUID()).andExpect(status().isCreated());

        assertThat(statusOf(active.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(attendanceOf(active.bookingId())).isEqualTo("NOT_APPLICABLE");
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id = '" + active.bookingId() + "'"))
                .isZero();
    }

    /** A Manual Booking (a walk-in) is interrupted too: NOT_APPLICABLE, no customer, no fee decision (FR-MAN-06). */
    @Test
    void anActiveWalkInIsInterruptedWithoutAttendanceOrFee() throws Exception {
        BookingFlows.Booking walkIn = flows.walkIn();

        declareVenue(walkIn, dbNow().plus(Duration.ofMinutes(30)), UUID.randomUUID()).andExpect(status().isCreated());

        assertThat(statusOf(walkIn.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(attendanceOf(walkIn.bookingId())).isEqualTo("NOT_APPLICABLE");
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '" + walkIn.bookingId()
                + "'")).isZero();
        assertThat(count("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id = '"
                + walkIn.bookingId() + "' AND subject_type = 'MANUAL_BOOKING' AND NOT refund_applied")).isOne();
    }

    /** FR-ATT-10: with Mark Present - Late only it is ATTENDANCE_UNCONFIRMED, the counter unchanged. */
    @Test
    void anInterruptedBookingWithLatePresentOnlyIsUnconfirmed() throws Exception {
        BookingFlows.Booking active = flows.active(Duration.ofMinutes(20));
        attendance.latePresent(active.venueAccountId(), active.bookingId());

        declareVenue(active, dbNow().plus(Duration.ofMinutes(30)), UUID.randomUUID()).andExpect(status().isCreated());

        assertThat(attendanceOf(active.bookingId())).isEqualTo("ATTENDANCE_UNCONFIRMED");
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id = '" + active.bookingId() + "'"))
                .isZero();
    }

    /**
     * E7-59/E7-60 carry-forward (PO decisions 19-20 Oct 2026, Spec v5 section 31.7): a booking still CONFIRMED after its
     * startAt because T4 was late starts at startAt, then is interrupted at the Emergency's own time.
     */
    @Test
    void aLateStartBookingIsStartedAtItsStartThenInterruptedAtTheEmergencysTime() throws Exception {
        BookingFlows.Booking late = flows.confirmedWithOverdueStart(Duration.ofMinutes(10));

        JsonNode emergency = data(declareVenue(late, dbNow().plus(Duration.ofMinutes(30)), UUID.randomUUID())
                .andExpect(status().isCreated()));
        Instant declaredAt = Instant.parse(emergency.path("declaredAt").asText());

        assertThat(statusOf(late.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        List<String> transitions = OWNER.sql("""
                        SELECT from_status || '>' || to_status || '@' ||
                               CASE WHEN occurred_at = :start THEN 'startAt' ELSE 'later' END
                          FROM booking.booking_history WHERE booking_id = :id ORDER BY sequence""")
                .param("id", late.bookingId()).param("start", OffsetDateTime.ofInstant(late.startAt(),
                        java.time.ZoneOffset.UTC))
                .query(String.class).list();
        assertThat(transitions).containsSubsequence("CONFIRMED>ACTIVE@startAt", "ACTIVE>CANCELLED@later");
        Instant interruptedAt = OWNER.sql("SELECT terminal_at FROM booking.booking WHERE id = :id")
                .param("id", late.bookingId()).query(OffsetDateTime.class).single().toInstant();
        assertThat(interruptedAt).isAfter(late.startAt()).isBeforeOrEqualTo(declaredAt);
        assertThat(attendanceOf(late.bookingId())).isEqualTo("NOT_APPLICABLE");
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE subject_id = '" + late.bookingId()
                + "' AND type = 'BOOKING_START' AND status = 'PENDING'")).as("T4 withdrawn").isZero();
    }

    // ---------------------------------------------------------------------------------- resource scope

    /** FR-OPS-12, FR-OPS-15: a resource Emergency blocks only its room; the other room keeps operating. */
    @Test
    void aResourceEmergencyInterruptsItsRoomOnlyAndBlocksNoOtherResource() throws Exception {
        List<BookingFlows.Booking> two = flows.twoActiveAtOneVenue(Duration.ofMinutes(5));
        BookingFlows.Booking failed = two.get(0);
        BookingFlows.Booking other = two.get(1);

        JsonNode emergency = data(declareResource(failed, failed.resourceId(), dbNow().plus(Duration.ofMinutes(30)),
                UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scope").value("RESOURCE"))
                .andExpect(jsonPath("$.data.resourceId").value(failed.resourceId().toString())));

        assertThat(statusOf(failed.bookingId())).isEqualTo("CANCELLED/RESOURCE_EMERGENCY_OUTAGE");
        assertThat(statusOf(other.bookingId())).as("other resources keep operating").isEqualTo("ACTIVE/-");
        assertThat(count("SELECT count(*) FROM resource.resource_selling_block WHERE active AND emergency_id = '"
                + emergency.path("id").asText() + "' AND resource_id = '" + failed.resourceId() + "'")).isOne();
        assertThat(count("SELECT count(*) FROM resource.resource_selling_block WHERE active AND resource_id = '"
                + other.resourceId() + "'")).isZero();
        assertThat(count("SELECT count(*) FROM venue.venue_selling_block WHERE active AND venue_id = '"
                + failed.venueId() + "'")).as("no venue block").isZero();
    }

    @Test
    void aSecondEmergencyOnTheSameSubjectIsRefusedAndAnUnknownRoomIsNotFound() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        Instant end = booking.startAt().plus(Duration.ofMinutes(60));
        declareVenue(booking, end, UUID.randomUUID()).andExpect(status().isCreated());
        declareVenue(booking, end, UUID.randomUUID()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_SELLING_BLOCK_ACTIVE"));

        declareResource(booking, booking.resourceId(), end, UUID.randomUUID()).andExpect(status().isCreated());
        declareResource(booking, booking.resourceId(), end, UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_SELLING_BLOCK_ACTIVE"));
        declareResource(booking, UUID.randomUUID(), end, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** FR-OPS-07: the expected end is after now and inside the current Operating Session. */
    @Test
    void anExpectedEndOutsideTheSessionOrInThePastIsRefusedWithNothingWritten() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        for (Instant end : List.of(dbNow().plus(Duration.ofHours(11)), dbNow().minus(Duration.ofMinutes(1)))) {
            declareVenue(booking, end, UUID.randomUUID()).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("EMERGENCY_EXPECTED_END_OUT_OF_SESSION"));
        }
        assertThat(statusOf(booking.bookingId())).isEqualTo("PENDING/-");
        assertThat(count("SELECT count(*) FROM operations.emergency WHERE venue_id = '" + booking.venueId() + "'"))
                .isZero();
    }

    /** ADR-015: one declaration per Idempotency-Key, the stored answer on a replay. */
    @Test
    void aReplayedDeclarationAnswersAsStoredAndDeclaresNothingMore() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID key = UUID.randomUUID();
        Instant end = booking.startAt().plus(Duration.ofMinutes(60));
        String first = declareVenue(booking, end, key).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String again = declareVenue(booking, end, key).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        assertThat(json.readTree(again).path("data")).isEqualTo(json.readTree(first).path("data"));
        assertThat(count("SELECT count(*) FROM operations.emergency WHERE venue_id = '" + booking.venueId() + "'"))
                .isOne();
    }

    // ---------------------------------------------------------------------------------- lists (D1, D3)

    @Test
    void theVenueListsItsUnresolvedEmergenciesAndTheAdminMonitorPagesEveryVenue() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        Instant end = booking.startAt().plus(Duration.ofMinutes(60));
        UUID venueWide = id(declareVenue(booking, end, UUID.randomUUID()).andExpect(status().isCreated()));
        UUID roomWide = id(declareResource(booking, booking.resourceId(), end, UUID.randomUUID())
                .andExpect(status().isCreated()));

        mvc.perform(get(EMERGENCIES).header("Authorization", venue(booking)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(roomWide.toString()))
                .andExpect(jsonPath("$.data[1].id").value(venueWide.toString()));

        String admin = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
        JsonNode page = data(mvc.perform(get("/api/v1/admin/emergencies").header("Authorization", admin)
                        .param("venueId", booking.venueId().toString()).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(roomWide.toString()))
                .andExpect(jsonPath("$.data.items[0].venueName").value(booking.venueName()))
                .andExpect(jsonPath("$.data.items[0].resourceName").isNotEmpty()));
        mvc.perform(get("/api/v1/admin/emergencies").header("Authorization", admin)
                        .param("venueId", booking.venueId().toString()).param("limit", "1")
                        .param("cursor", page.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(venueWide.toString()))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
        mvc.perform(get("/api/v1/admin/emergencies").header("Authorization", admin)
                        .param("venueId", booking.venueId().toString()).param("status", "ENDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ---------------------------------------------------------------------------------- lifecycle

    /** FR-OPS-18: the expected end passing moves it to AWAITING_CONFIRMATION and never reopens anything. */
    @Test
    void theExpectedEndPassingAwaitsConfirmationAndKeepsTheBlock() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID id = id(declareVenue(booking, booking.startAt().plus(Duration.ofMinutes(60)), UUID.randomUUID())
                .andExpect(status().isCreated()));
        Instant expectedEnd = moveExpectedEndIntoThePast(id);

        assertThat(fireExpectedEnd(id, expectedEnd)).isEqualTo(EmergencyCommands.ExpectedEndOutcome.DONE);

        assertThat(OWNER.sql("SELECT status FROM operations.emergency WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(count("SELECT count(*) FROM venue.venue_selling_block WHERE emergency_id = '" + id
                + "' AND active")).as("the block stays (FR-OPS-17)").isOne();
        assertThat(fireExpectedEnd(id, expectedEnd)).as("a second fire changes nothing")
                .isEqualTo(EmergencyCommands.ExpectedEndOutcome.SUPERSEDED);
        // E7-80 (FR-OPS-25, R2): one awaiting event - the venue's one reminder - and no owner review for a venue scope
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE event_type = '"
                + IntegrationEvents.EMERGENCY_AWAITING_CONFIRMATION + "' AND payload->>'emergencyId' = '" + id
                + "' AND payload->>'ownerReviewEntered' = 'false' AND payload->>'venueId' = '" + booking.venueId()
                + "'")).isOne();
    }

    /** FR-OPS-19, FR-OPS-10, D6: END closes the block in the same commit; nothing cancelled comes back. */
    @Test
    void endingClosesTheBlockAtTheSameInstantRestoresNothingAndASecondActionIsStateChanged() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID id = id(declareVenue(booking, booking.startAt().plus(Duration.ofMinutes(60)), UUID.randomUUID())
                .andExpect(status().isCreated()));

        mvc.perform(write(post(EMERGENCIES + "/" + id + "/end"), booking, "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"))
                .andExpect(jsonPath("$.data.endedAt").isNotEmpty());

        assertThat(count("SELECT count(*) FROM venue.venue_selling_block b JOIN operations.emergency e"
                + " ON e.id = b.emergency_id WHERE e.id = '" + id + "' AND NOT b.active AND b.ended_at = e.ended_at"))
                .as("block closed at the resolution instant").isOne();
        assertThat(statusOf(booking.bookingId())).as("FR-OPS-10").isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(events(IntegrationEvents.EMERGENCY_ENDED, booking.venueId())).isOne();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE type = 'EMERGENCY_EXPECTED_END'"
                + " AND subject_id = '" + id + "' AND status = 'PENDING'")).as("expected end withdrawn").isZero();

        mvc.perform(write(post(EMERGENCIES + "/" + id + "/end"), booking, "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
        mvc.perform(json(post(EMERGENCIES + "/" + id + "/extend/preview"), booking,
                        "{\"expectedEndAt\":\"" + booking.startAt().plus(Duration.ofHours(3)) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
        mvc.perform(write(post(EMERGENCIES + "/" + UUID.randomUUID() + "/end"), booking, "{}"))
                .andExpect(status().isNotFound());
    }

    /** FR-OPS-20: a resource that cannot return to service is not ended. */
    @Test
    void aResourceEmergencyIsNotEndedWhileItsResourceIsNotOperational() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID id = id(declareResource(booking, booking.resourceId(), booking.startAt().plus(Duration.ofMinutes(60)),
                UUID.randomUUID()).andExpect(status().isCreated()));
        flows.resourceDisabledBehindTheGuards(booking);

        mvc.perform(write(post(EMERGENCIES + "/" + id + "/end"), booking, "{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EMERGENCY_CANNOT_END_RESOURCE_NOT_OPERATIONAL"));
        assertThat(OWNER.sql("SELECT status FROM operations.emergency WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    /** FR-OPS-22, D2: extend previews the newly affected set and cancels it atomically; a stale preview is refused. */
    @Test
    void extendingPreviewsTheNewlyAffectedBookingAndCancelsItAndAStalePreviewIsRefused() throws Exception {
        List<BookingFlows.Booking> two = flows.twoPendingOfOneCustomer();
        BookingFlows.Booking first = two.get(0);
        BookingFlows.Booking second = two.get(1);
        UUID id = id(declareVenue(first, first.startAt().plus(Duration.ofMinutes(60)), UUID.randomUUID())
                .andExpect(status().isCreated()));
        Instant later = second.startAt().plus(Duration.ofMinutes(60));

        JsonNode preview = data(mvc.perform(json(post(EMERGENCIES + "/" + id + "/extend/preview"), first,
                        "{\"expectedEndAt\":\"" + later + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected.length()").value(1))
                .andExpect(jsonPath("$.data.affected[0].bookingId").value(second.bookingId().toString()))
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(false)));

        mvc.perform(write(post(EMERGENCIES + "/" + id + "/extend"), first,
                        "{\"expectedEndAt\":\"" + later + "\",\"previewToken\":\"b64:stale\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AFFECTED_SET_CHANGED_REFRESH_PREVIEW"));
        assertThat(statusOf(second.bookingId())).isEqualTo("PENDING/-");

        mvc.perform(write(post(EMERGENCIES + "/" + id + "/extend"), first, "{\"expectedEndAt\":\"" + later
                        + "\",\"previewToken\":\"" + preview.path("previewToken").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expectedEndAt").value(later.toString()));
        assertThat(statusOf(second.bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        assertThat(count("SELECT count(*) FROM operations.booking_timer_pause_history WHERE booking_id = '"
                + second.bookingId() + "' AND pause_ended_at IS NOT NULL")).as("its pause closed").isOne();
        assertThat(events(IntegrationEvents.EMERGENCY_EXTENDED, first.venueId())).isOne();

        mvc.perform(json(post(EMERGENCIES + "/" + id + "/extend/preview"), first,
                        "{\"expectedEndAt\":\"" + dbNow().plus(Duration.ofHours(11)) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EMERGENCY_EXPECTED_END_OUT_OF_SESSION"));
    }

    // ---------------------------------------------------------------------------------- helpers

    ResultActions declareVenue(BookingFlows.Booking at, Instant expectedEnd, UUID key) throws Exception {
        return mvc.perform(write(post(EMERGENCIES + "/venue-closure"), at, "{\"reason\":\"Power cut\","
                + "\"expectedEndAt\":\"" + expectedEnd + "\"}", key));
    }

    ResultActions declareResource(BookingFlows.Booking at, UUID resourceId, Instant expectedEnd, UUID key)
            throws Exception {
        return mvc.perform(write(post(EMERGENCIES + "/resource-outage"), at, "{\"resourceId\":\"" + resourceId
                + "\",\"reason\":\"Console overheating\",\"expectedReturnAt\":\"" + expectedEnd + "\"}", key));
    }

    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, BookingFlows.Booking at, String body) {
        return write(request, at, body, UUID.randomUUID());
    }

    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, BookingFlows.Booking at, String body,
                                        UUID key) {
        return json(request, at, body).header("Idempotency-Key", key.toString());
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, BookingFlows.Booking at, String body) {
        return request.header("Authorization", venue(at)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String venue(BookingFlows.Booking at) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, at.venueAccountId());
    }

    JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    UUID id(ResultActions result) throws Exception {
        return UUID.fromString(data(result).path("id").asText());
    }

    /** The stored expected end moved one minute into the past, as if it had passed (the guard needs it for AWAITING). */
    Instant moveExpectedEndIntoThePast(UUID id) {
        Instant end = dbNow().minus(Duration.ofSeconds(1)).truncatedTo(ChronoUnit.MILLIS);
        SingleConnectionDataSource owner =
                new SingleConnectionDataSource(
                        PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql("""
                        UPDATE operations.emergency
                           SET expected_end_at = CAST(:end AS timestamptz),
                               declared_at = LEAST(declared_at, CAST(:end AS timestamptz) - interval '1 minute'),
                               declared_period = tstzrange(LEAST(declared_at, CAST(:end AS timestamptz)
                                                                 - interval '1 minute'),
                                                           CAST(:end AS timestamptz), '[)')
                         WHERE id = :id""")
                    .param("end", OffsetDateTime.ofInstant(end, java.time.ZoneOffset.UTC)).param("id", id).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
        return OWNER.sql("SELECT expected_end_at FROM operations.emergency WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single().toInstant();
    }

    EmergencyCommands.ExpectedEndOutcome fireExpectedEnd(UUID id, Instant dueAt) {
        UUID venueId = OWNER.sql("SELECT venue_id FROM operations.emergency WHERE id = :id").param("id", id)
                .query(UUID.class).single();
        return runner.run(TestCommands.under(CommandScope.shared(LockSet.builder().venues(venueId).build()),
                ctx -> EmergencyCommands.expectedEnd(ctx, emergencies.deps(), id, dueAt,
                        LockSet.builder().venues(venueId).build())));
    }

    static String statusOf(UUID bookingId) {
        return OWNER.sql("SELECT status || '/' || COALESCE(terminal_reason, '-') FROM booking.booking WHERE id = :id")
                .param("id", bookingId).query(String.class).single();
    }

    static String attendanceOf(UUID bookingId) {
        return OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", bookingId).query(String.class).single();
    }

    static long projection(UUID userId, String column) {
        return OWNER.sql("SELECT COALESCE(" + column + ", 0) FROM trust.trust_projection WHERE user_id = :id")
                .param("id", userId).query(Long.class).optional().orElse(0L);
    }

    static long count(String sql) {
        return OWNER.sql(sql).query(Long.class).single();
    }

    static long events(String type, UUID venueId) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND payload->>'venueId' = :venue""")
                .param("type", type).param("venue", venueId.toString()).query(Long.class).single();
    }

    static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
}
