package ps.e7gz.attendance.web;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-59 over HTTP: the three attendance routes of the Sprint 3 contract (E7-54, replacing their 501 stubs) through the
 * real security chain, Idempotency-Key handling (E7-42), error envelope and database, and {@code VenueBooking.attendance}
 * on the venue's booking detail.
 */
@AutoConfigureMockMvc
class VenueAttendanceHttpTest extends IntegrationTest {

    private static final String VENUE_BOOKINGS = "/api/v1/venue/bookings/";
    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationContext context;

    private BookingFlows flows;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void aCheckInAnswersTheBookingsAttendance() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(5));

        JsonNode body = body(perform(venue(booking), booking, "check-in", key()).andExpect(status().isOk()));

        assertThat(body.at("/data/bookingId").asText()).isEqualTo(booking.bookingId().toString());
        assertThat(body.at("/data/status").asText()).isEqualTo("ACTIVE");
        assertThat(body.at("/data/attendanceResult").isNull()).isTrue();
        assertThat(body.at("/data/checkedInAt").isTextual()).isTrue();
        assertThat(body.at("/data/latePresentAt").isNull()).isTrue();
        assertThat(body.at("/data/noShowAt").isNull()).isTrue();
        assertThat(body.at("/meta/serverTime").isTextual()).isTrue();
    }

    @Test
    void aNoShowAnswersNoShowAndAbsentNoShowAndIsThenTerminal() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));

        perform(venue(booking), booking, "no-show", key())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.data.attendanceResult").value("ABSENT_NO_SHOW"))
                .andExpect(jsonPath("$.data.noShowAt").isNotEmpty());

        for (String action : new String[] {"no-show", "late-present", "check-in"}) {
            perform(venue(booking), booking, action, key())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"));
        }
    }

    @Test
    void eachRefusalIsItsActionsOwn422() throws Exception {
        Booking inCheckIn = flows.active(Duration.ofMinutes(5));
        Booking late = flows.active(Duration.ofMinutes(20));

        refused(inCheckIn, "late-present", "LATE_PRESENT_NOT_AVAILABLE");
        refused(inCheckIn, "no-show", "NO_SHOW_NOT_AVAILABLE");
        refused(late, "check-in", "CHECK_IN_WINDOW_CLOSED");
        perform(venue(late), late, "late-present", key()).andExpect(status().isOk());
        refused(late, "no-show", "NO_SHOW_NOT_AVAILABLE");
    }

    /** FR-MAN-04 and the Sprint 3 contract: a Manual Booking answers each action's own code. */
    @Test
    void aManualBookingAnswersEachActionsOwnCodeAndHasNoAttendanceView() throws Exception {
        Booking walkIn = flows.walkIn();

        refused(walkIn, "check-in", "CHECK_IN_WINDOW_CLOSED");
        refused(walkIn, "late-present", "LATE_PRESENT_NOT_AVAILABLE");
        refused(walkIn, "no-show", "NO_SHOW_NOT_AVAILABLE");
        mvc.perform(get(VENUE_BOOKINGS + walkIn.bookingId()).header("Authorization", venue(walkIn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.booking.attendance").value((Object) null))
                .andExpect(jsonPath("$.data.booking.attendanceResult").value((Object) null));
    }

    /** ADR-015: an exact replay answers the stored response and runs nothing; the key for another booking is reused. */
    @Test
    void anExactReplayAnswersTheSameAndTheKeyReusedForAnotherBookingIsRefused() throws Exception {
        List<Booking> bookings = flows.twoActiveAtOneVenue(Duration.ofMinutes(5));
        Booking booking = bookings.get(0);
        Booking other = bookings.get(1);
        String key = key();

        String first = perform(venue(booking), booking, "check-in", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = perform(venue(booking), booking, "check-in", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(replay).get("data")).isEqualTo(json.readTree(first).get("data"));
        assertThat(events(booking, IntegrationEvents.CHECK_IN_RECORDED)).isOne();
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM booking.booking_history
                         WHERE booking_id = :id AND event_type = 'CHECK_IN_RECORDED'""")
                .param("id", booking.bookingId()).query(Long.class).single()).isOne();

        // the same venue account, route and key on another booking of the venue: refused, nothing recorded
        perform(venue(booking), other, "check-in", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(OWNER.sql("SELECT count(*) FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", other.bookingId()).query(Long.class).single()).isZero();
    }

    /**
     * R1 (PO decision 19 Oct 2026): a check-in on a booking whose start has not been processed starts it as T4 would
     * and records the check-in - 200, ACTIVE; the exact replay answers the same body and starts nothing twice.
     */
    @Test
    void aCheckInBehindADelayedStartStartsTheBookingAndItsReplayAnswersTheSame() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        String key = key();

        String first = perform(venue(booking), booking, "check-in", key).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String replay = perform(venue(booking), booking, "check-in", key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(replay).get("data")).isEqualTo(json.readTree(first).get("data"));
        assertThat(json.readTree(first).at("/data/checkedInAt").isNull()).isFalse();
        assertThat(detail(booking).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(events(booking, IntegrationEvents.BOOKING_STARTED)).isOne();
        assertThat(events(booking, IntegrationEvents.CHECK_IN_RECORDED)).isOne();
    }

    /**
     * A delayed start that would have cancelled (Amr Heiba, 17 Oct 2026): the no-show is answered 409
     * BOOKING_STATE_CHANGED - never a successful no-show - while the start-time cancellation commits; the exact replay
     * answers the same stored 409 and cancels nothing twice.
     */
    @Test
    void aNoShowOnACancellingDelayedStartIsAStored409AndItsReplayTheSame() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceDisabledBehindTheGuards(booking);
        String key = key();

        String first = perform(venue(booking), booking, "no-show", key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"))
                .andReturn().getResponse().getContentAsString();
        String replay = perform(venue(booking), booking, "no-show", key).andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(replay).get("error").get("code"))
                .isEqualTo(json.readTree(first).get("error").get("code"));
        assertThat(detail(booking).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(detail(booking).path("terminalReason").asText()).isEqualTo("SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        assertThat(detail(booking).path("attendanceResult").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(events(booking, IntegrationEvents.BOOKING_CANCELLED)).isOne();
        assertThat(events(booking, IntegrationEvents.NO_SHOW_RECORDED)).isZero();
    }

    /**
     * A pure refusal (decision of Amr Heiba, 18 Oct 2026: an Emergency that began after startAt) answers 409 and stores
     * nothing - not even an Idempotency-Key record - so the same key is evaluated afresh.
     */
    @Test
    void aPureDelayedStartRefusalStoresNoIdempotencyRecord() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceEmergencySince(booking, booking.startAt().plus(Duration.ofMinutes(2)));
        String key = key();

        perform(venue(booking), booking, "no-show", key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"));

        assertThat(OWNER.sql("SELECT count(*) FROM technical.idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single()).isZero();
        assertThat(detail(booking).path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(events(booking, IntegrationEvents.BOOKING_CANCELLED)).isZero();
        assertThat(events(booking, IntegrationEvents.NO_SHOW_RECORDED)).isZero();
    }

    @Test
    void everyRouteRequiresTheIdempotencyKeyAVenueTokenAndItsOwnBooking() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(5));
        String stranger = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, Fixtures.aVenueAccount().create().id());
        String customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, booking.customerId());

        for (String action : new String[] {"check-in", "late-present", "no-show"}) {
            perform(venue(booking), booking, action, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
            perform(customer, booking, action, key()).andExpect(status().isForbidden());
            perform(null, booking, action, key()).andExpect(status().isUnauthorized());
            perform(stranger, booking, action, key())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
        assertThat(OWNER.sql("SELECT count(*) FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).isZero();
    }

    /**
     * The Sprint 3 contract's {@code VenueBooking.attendance}: half-open windows from the stored start and end, as
     * instants, and the evidence recorded; the no-show's result on {@code attendanceResult}.
     */
    @Test
    void theVenuesBookingShowsTheWindowsAndTheEvidence() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));
        Instant start = booking.startAt();

        JsonNode before = detail(booking);
        assertThat(instant(before, "checkInWindow/opensAt")).isEqualTo(start);
        assertThat(instant(before, "checkInWindow/closesAt")).isEqualTo(start.plus(Duration.ofMinutes(15)));
        assertThat(instant(before, "lateWindow/opensAt")).isEqualTo(start.plus(Duration.ofMinutes(15)));
        assertThat(instant(before, "lateWindow/closesAt")).isEqualTo(start.plus(Duration.ofMinutes(60)));
        assertThat(before.at("/attendance/noShowAt").isNull()).isTrue();

        JsonNode recorded = body(perform(venue(booking), booking, "no-show", key()).andExpect(status().isOk()));

        JsonNode after = detail(booking);
        assertThat(after.at("/status").asText()).isEqualTo("NO_SHOW");
        assertThat(after.at("/attendanceResult").asText()).isEqualTo("ABSENT_NO_SHOW");
        assertThat(after.at("/attendance/noShowAt").asText()).isEqualTo(recorded.at("/data/noShowAt").asText());
        assertThat(after.at("/attendance/checkedInAt").isNull()).isTrue();
        assertThat(after.at("/attendance/latePresentAt").isNull()).isTrue();
    }

    /** Evidence a Platform Admin reversed is absent from the venue's view (Sprint 3 contract, FR-ATT-11). */
    @Test
    void evidenceReversedByAnAdminIsAbsent() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(5));
        perform(venue(booking), booking, "check-in", key()).andExpect(status().isOk());
        assertThat(detail(booking).at("/attendance/checkedInAt").isTextual()).isTrue();

        // no admin reversal command exists yet (E7-70): the marker alone, as it would record it
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql("UPDATE attendance.attendance_record SET check_in_reversed_at = check_in_at WHERE booking_id = :id")
                    .param("id", booking.bookingId()).update();
        } finally {
            owner.destroy();
        }

        assertThat(detail(booking).at("/attendance/checkedInAt").isNull()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void refused(Booking booking, String action, String code) throws Exception {
        perform(venue(booking), booking, action, key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private ResultActions perform(String token, Booking booking, String action, String key) throws Exception {
        MockHttpServletRequestBuilder request = post(VENUE_BOOKINGS + booking.bookingId() + "/" + action);
        if (token != null) {
            request.header("Authorization", token);
        }
        if (key != null) {
            request.header(OpenApiConfig.IDEMPOTENCY_KEY, key);
        }
        return mvc.perform(request);
    }

    private JsonNode detail(Booking booking) throws Exception {
        return body(mvc.perform(get(VENUE_BOOKINGS + booking.bookingId()).header("Authorization", venue(booking)))
                .andExpect(status().isOk())).at("/data/booking");
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static Instant instant(JsonNode booking, String path) {
        return Instant.parse(booking.at("/attendance/" + path).asText());
    }

    private String venue(Booking booking) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, booking.venueAccountId());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static long events(Booking booking, String type) {
        return OWNER.sql("SELECT count(*) FROM technical.outbox_event WHERE aggregate_id = :id AND event_type = :type")
                .param("id", booking.bookingId()).param("type", type).query(Long.class).single();
    }
}
