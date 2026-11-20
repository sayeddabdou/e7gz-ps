package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.operations.internal.EmergencyHttpTest.EMERGENCIES;
import static ps.e7gz.operations.internal.EmergencyHttpTest.OWNER;
import static ps.e7gz.operations.internal.EmergencyHttpTest.count;
import static ps.e7gz.operations.internal.EmergencyHttpTest.dbNow;
import static ps.e7gz.operations.internal.EmergencyHttpTest.events;
import static ps.e7gz.operations.internal.EmergencyHttpTest.statusOf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-82 (S5-E2) — an Emergency converted into its replacement in one commit (FR-OPS-23, FR-OPS-24; the E7-77 contract
 * D2, D5, D6): preview + commit through {@code /convert}, each target under its own rules and Owner Password, and the
 * opening-hours commit with {@code emergencyId}.
 */
@AutoConfigureMockMvc
class EmergencyConversionHttpTest extends IntegrationTest {

    private static final String PIN = BookingFlows.OWNER_PIN;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    private BookingFlows flows;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void flows() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------------------------- venue -> Closure

    /**
     * FR-OPS-23, FR-OPS-24: a venue Emergency becomes a normal Closure; the Closure cancels its own affected set (the
     * paused PENDING request outside the Emergency's window) and the block ends in the same commit.
     */
    @Test
    void aVenueEmergencyBecomesAClosureInOneCommitThatCancelsTheClosuresAffectedSet() throws Exception {
        BookingFlows.Booking later = flows.pending();
        UUID id = declareVenue(later, shortEnd());
        assertThat(statusOf(later.bookingId())).as("outside the window: paused, not cancelled").isEqualTo("PENDING/-");
        String conversion = closure(later.startAt().plus(Duration.ofHours(2)));

        JsonNode preview = data(previewConversion(later, id, conversion).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected.length()").value(1))
                .andExpect(jsonPath("$.data.affected[0].bookingId").value(later.bookingId().toString()))
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(false)));

        convert(later, id, conversion, preview.path("previewToken").asText(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONVERTED"))
                .andExpect(jsonPath("$.data.convertedTo").value("CLOSURE"));

        assertThat(statusOf(later.bookingId())).isEqualTo("CANCELLED/VENUE_CLOSURE");
        assertThat(count("SELECT count(*) FROM venue.venue_selling_block b JOIN operations.emergency e"
                + " ON e.id = b.emergency_id WHERE e.id = '" + id + "' AND NOT b.active AND b.ended_at = e.ended_at"))
                .as("no sellable gap").isOne();
        assertThat(count("SELECT count(*) FROM operations.venue_closure c JOIN operations.emergency e"
                + " ON e.venue_id = c.venue_id WHERE e.id = '" + id + "' AND c.period @> e.ended_at")).isOne();
        assertThat(events(IntegrationEvents.EMERGENCY_CONVERTED, later.venueId())).isOne();
        assertThat(events(IntegrationEvents.CLOSURE_CREATED, later.venueId())).isOne();

        previewConversion(later, id, conversion).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
    }

    /** FR-VEN-30 via FR-OPS-23: a Closure that cancels a CONFIRMED online booking needs the Owner Password. */
    @Test
    void aClosureConversionCancellingAProtectedBookingNeedsTheOwnerPassword() throws Exception {
        BookingFlows.Booking confirmed = flows.pending();
        flows.accept(confirmed);
        flows.ownerPin(confirmed.venueAccountId());
        UUID id = declareVenue(confirmed, shortEnd());
        String conversion = closure(confirmed.startAt().plus(Duration.ofHours(2)));
        String token = data(previewConversion(confirmed, id, conversion)
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(true))).path("previewToken").asText();

        convert(confirmed, id, conversion, token, null).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        assertThat(statusOf(confirmed.bookingId())).isEqualTo("CONFIRMED/-");

        convert(confirmed, id, conversion, token, PIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.convertedTo").value("CLOSURE"));
        assertThat(statusOf(confirmed.bookingId())).isEqualTo("CANCELLED/VENUE_CLOSURE");
    }

    // ---------------------------------------------------------------------------------- resource -> Maintenance

    /** FR-OPS-05, FR-OPS-23: a resource Emergency becomes Maintenance created from it, counted toward the threshold. */
    @Test
    void aResourceEmergencyBecomesMaintenanceCreatedFromIt() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID id = declareResource(booking, shortEnd());
        Instant end = shortEnd().plus(Duration.ofMinutes(20));
        String conversion = maintenance(end);

        String token = data(previewConversion(booking, id, conversion).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected.length()").value(0))
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(false))).path("previewToken").asText();
        convert(booking, id, conversion, token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.convertedTo").value("MAINTENANCE"));

        assertThat(count("SELECT count(*) FROM operations.maintenance_window WHERE created_from_emergency_id = '" + id
                + "' AND resource_id = '" + booking.resourceId() + "'")).isOne();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation rr JOIN operations.maintenance_window m"
                + " ON m.id = rr.maintenance_id WHERE m.created_from_emergency_id = '" + id + "' AND rr.active_flag"))
                .isOne();
        assertThat(count("SELECT count(*) FROM resource.resource_selling_block WHERE emergency_id = '" + id
                + "' AND active")).isZero();
        assertThat(events(IntegrationEvents.MAINTENANCE_CREATED, booking.venueId())).isOne();
        assertThat(events(IntegrationEvents.EMERGENCY_CONVERTED, booking.venueId())).isOne();
    }

    /** FR-OPS-02: Maintenance never overlaps a reservation; it is refused, nothing cancelled. */
    @Test
    void maintenanceOverAReservationIsRefused() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID id = declareResource(booking, shortEnd());
        previewConversion(booking, id, maintenance(booking.startAt().plus(Duration.ofMinutes(30))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_OVERLAPS_RESERVATION"));
        assertThat(statusOf(booking.bookingId())).isEqualTo("PENDING/-");
    }

    // ---------------------------------------------------------------------------------- resource -> Disabled

    /** FR-OPS-23: Disable follows the resource's own rule - the Owner Password, and no blocking reservation. */
    @Test
    void disablingNeedsTheOwnerPasswordAndNoBlockingReservation() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        flows.ownerPin(booking.venueAccountId());
        UUID id = declareResource(booking, shortEnd());
        String disable = "{\"target\":\"DISABLED\"}";

        previewConversion(booking, id, disable).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_HAS_BLOCKING_RESERVATIONS"));
        flows.customerCancels(booking);

        String token = data(previewConversion(booking, id, disable).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(true))).path("previewToken").asText();
        convert(booking, id, disable, token, null).andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        convert(booking, id, disable, token, PIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.convertedTo").value("DISABLED"));
        assertThat(OWNER.sql("SELECT base_state FROM resource.resource WHERE id = :id")
                .param("id", booking.resourceId()).query(String.class).single()).isEqualTo("DISABLED");
    }

    // ---------------------------------------------------------------------------------- refusals

    @Test
    void aTargetOfTheOtherScopeOrAnHoursChangeIsNotAllowedAndAMissingPartIsInvalid() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID venueWide = declareVenue(booking, shortEnd());
        previewConversion(booking, venueWide, maintenance(shortEnd().plus(Duration.ofMinutes(5))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CONVERSION_TARGET_NOT_ALLOWED"));
        previewConversion(booking, venueWide, "{\"target\":\"OPENING_HOURS_CHANGE\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CONVERSION_TARGET_NOT_ALLOWED"));
        previewConversion(booking, venueWide, "{\"target\":\"CLOSURE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("closure"));
        convert(booking, venueWide, closure(booking.startAt().plus(Duration.ofHours(2))), "b64:stale", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AFFECTED_SET_CHANGED_REFRESH_PREVIEW"));
    }

    // ---------------------------------------------------------------------------------- the hours commit (D5)

    /** D5: a venue Emergency becomes an opening-hours change through the hours commit with its emergencyId. */
    @Test
    void theHoursCommitWithAnEmergencyIdConvertsItInTheSameCommit() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID venueWide = declareVenue(booking, booking.startAt().plus(Duration.ofMinutes(60)));
        UUID roomWide = declareResource(booking, shortEnd());
        String proposal = "{\"defaultOpen\":\"00:00:00\",\"defaultClose\":\"00:00:00\",\"defaultFullDay\":true,"
                + "\"days\":[]}";
        String token = data(mvc.perform(json(post("/api/v1/venue/opening-hours/preview"), booking,
                        "{\"proposal\":" + proposal + "}")).andExpect(status().isOk())).path("previewToken").asText();

        hoursCommit(booking, proposal, token, roomWide).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CONVERSION_TARGET_NOT_ALLOWED"));
        hoursCommit(booking, proposal, token, UUID.randomUUID()).andExpect(status().isNotFound());

        hoursCommit(booking, proposal, token, venueWide).andExpect(status().isOk());
        assertThat(OWNER.sql("SELECT status || '/' || converted_to FROM operations.emergency WHERE id = :id")
                .param("id", venueWide).query(String.class).single()).isEqualTo("CONVERTED/OPENING_HOURS_CHANGE");
        assertThat(count("SELECT count(*) FROM venue.venue_selling_block WHERE emergency_id = '" + venueWide
                + "' AND active")).isZero();

        String again = data(mvc.perform(json(post("/api/v1/venue/opening-hours/preview"), booking,
                        "{\"proposal\":" + proposal + "}")).andExpect(status().isOk())).path("previewToken").asText();
        hoursCommit(booking, proposal, again, venueWide).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
    }

    // ---------------------------------------------------------------------------------- helpers

    private static Instant shortEnd() {
        return dbNow().plus(Duration.ofMinutes(10));
    }

    private static String closure(Instant end) {
        return "{\"target\":\"CLOSURE\",\"closure\":{\"kind\":\"PARTIAL\",\"endAt\":\"" + end
                + "\",\"reason\":\"Water damage\"}}";
    }

    private static String maintenance(Instant end) {
        return "{\"target\":\"MAINTENANCE\",\"maintenance\":{\"endAt\":\"" + end
                + "\",\"reason\":\"Part on order\"}}";
    }

    private UUID declareVenue(BookingFlows.Booking at, Instant end) throws Exception {
        return id(mvc.perform(write(post(EMERGENCIES + "/venue-closure"), at,
                "{\"reason\":\"Power cut\",\"expectedEndAt\":\"" + end + "\"}")).andExpect(status().isCreated()));
    }

    private UUID declareResource(BookingFlows.Booking at, Instant end) throws Exception {
        return id(mvc.perform(write(post(EMERGENCIES + "/resource-outage"), at, "{\"resourceId\":\""
                + at.resourceId() + "\",\"reason\":\"Overheating\",\"expectedReturnAt\":\"" + end + "\"}"))
                .andExpect(status().isCreated()));
    }

    private ResultActions previewConversion(BookingFlows.Booking at, UUID id, String conversion) throws Exception {
        return mvc.perform(json(post(EMERGENCIES + "/" + id + "/convert/preview"), at, conversion));
    }

    private ResultActions convert(BookingFlows.Booking at, UUID id, String conversion, String token, String pin)
            throws Exception {
        return mvc.perform(write(post(EMERGENCIES + "/" + id + "/convert"), at, "{\"conversion\":" + conversion
                + ",\"previewToken\":\"" + token + "\"" + (pin == null ? "" : ",\"ownerPassword\":\"" + pin + "\"")
                + "}"));
    }

    private ResultActions hoursCommit(BookingFlows.Booking at, String proposal, String token, UUID emergencyId)
            throws Exception {
        return mvc.perform(write(post("/api/v1/venue/opening-hours/commit"), at, "{\"proposal\":" + proposal
                + ",\"previewToken\":\"" + token + "\",\"emergencyId\":\"" + emergencyId + "\"}"));
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, BookingFlows.Booking at,
                                                String body) {
        return json(request, at, body).header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, BookingFlows.Booking at,
                                               String body) {
        return request.header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE,
                at.venueAccountId())).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private UUID id(ResultActions result) throws Exception {
        return UUID.fromString(data(result).path("id").asText());
    }
}
