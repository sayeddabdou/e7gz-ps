package ps.e7gz.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anArea;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * Find Available over HTTP (FR-DIS-12, F-03, AC-US-CUS-007-01…04, arch 05 section 5.4) and the PO decisions of 8 Oct
 * 2026: the interval must lie inside one Operating Session; the duration follows FR-BOK-01; a start less than 15 minutes
 * ahead finds nothing (FR-BOK-02).
 *
 * <p>Every case works in its own new area. Sessions are on Monday 3 February 2031, 12:00-23:00 Cairo (UTC+2 then, so
 * {@code [10:00Z, 21:00Z)}); the real command instant is before 2031, so the lead rule never interferes there.
 */
@AutoConfigureMockMvc
class CustomerFindAvailableHttpTest extends IntegrationTest {

    private static final String FIND = "/api/v1/customer/find-available";
    private static final String DAY = "2031-02-03";
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    CommandRunner runner;

    @Autowired
    ReservationLedger ledger;

    private Fixtures.Area area;

    @BeforeEach
    void ownArea() {
        area = anArea().create();
    }

    // ------------------------------------------------------------------------------------------ the predicate

    @Test
    void onlyRoomsFreeForTheWholeIntervalAreReturned() throws Exception {
        Fixtures.Venue venue = openVenue("Arena");
        Fixtures.Resource free = room(venue, "Room 1");
        Fixtures.Resource overlapping = room(venue, "Room 2");
        Fixtures.Resource backToBack = room(venue, "Room 3");
        Fixtures.Resource earlier = room(venue, "Room 4");
        book(venue, overlapping, "2031-02-03T17:30:00Z", "2031-02-03T18:30:00Z");   // 19:30-20:30 Cairo
        book(venue, backToBack, "2031-02-03T18:00:00Z", "2031-02-03T19:00:00Z");    // starts as the search ends
        book(venue, earlier, "2031-02-03T15:00:00Z", "2031-02-03T16:00:00Z");       // ends as the search starts

        JsonNode venues = find(DAY, "18:00", 120);

        assertThat(venues).hasSize(1);
        assertThat(venues.get(0).path("venueId").asText()).isEqualTo(venue.id().toString());
        assertThat(venues.get(0).path("venueName").asText()).isEqualTo("Arena");
        assertThat(resourceIds(venues.get(0))).containsExactly(free.id().toString(), backToBack.id().toString(),
                earlier.id().toString());
        assertThat(venues.get(0).path("resources").get(0).path("name").asText()).isEqualTo("Room 1");
        assertThat(venues.get(0).path("resources").get(0).path("consoleType").asText()).isEqualTo("PS5");
    }

    @Test
    void blockedDisabledArchivedAndOwnerReviewRoomsAreNeverAvailable() throws Exception {
        Fixtures.Venue venue = openVenue("Arena");
        Fixtures.Resource free = room(venue, "Room 1");
        aResource().at(venue).playStation(ConsoleType.PS5).named("Room 2").disabled().create();
        aResource().at(venue).playStation(ConsoleType.PS5).named("Room 3").archived().create();
        aResource().at(venue).playStation(ConsoleType.PS5).named("Room 4").ownerReviewRequired().create();
        resourceSellingBlock(room(venue, "Room 5"));
        maintenance(room(venue, "Room 6"), "2031-02-03T16:30:00Z", "2031-02-03T17:00:00Z");

        JsonNode venues = find(DAY, "18:00", 120);

        assertThat(venues).hasSize(1);
        assertThat(resourceIds(venues.get(0))).containsExactly(free.id().toString());
    }

    @Test
    void aSellingBlockedVenueIsLeftOutAndAVenueWithNothingFreeToo() throws Exception {
        Fixtures.Venue blocked = openVenue("A blocked");
        room(blocked, "Room 1");
        venueSellingBlock(blocked);
        Fixtures.Venue full = openVenue("B full");
        book(full, room(full, "Room 1"), "2031-02-03T16:00:00Z", "2031-02-03T17:00:00Z");
        Fixtures.Venue open = openVenue("C open");
        room(open, "Room 1");

        assertThat(venueIds(find(DAY, "18:00", 60))).containsExactly(open.id().toString());
    }

    // ------------------------------------------------------------------------------------------ which venues

    @Test
    void venuesAreTheAreasActiveOnesOfferingTheActivityByName() throws Exception {
        Fixtures.Venue zed = openVenue("Zed");
        room(zed, "Room 1");
        Fixtures.Venue alpha = openVenue("Alpha");
        room(alpha, "Room 1");
        Fixtures.Venue inactive = aVenue().inArea(area).inactive().create();
        withSession(inactive);
        room(inactive, "Room 1");
        Fixtures.Venue billiards = aVenue().inArea(area).active().offering(Activity.BILLIARDS).create();
        withSession(billiards);
        aResource().at(billiards).billiards().named("Table 1").create();
        Fixtures.Venue elsewhere = aVenue().inArea(anArea().create()).active().create();
        withSession(elsewhere);
        room(elsewhere, "Room 1");

        assertThat(venueIds(find(DAY, "18:00", 60))).containsExactly(alpha.id().toString(), zed.id().toString());
        JsonNode tables = body(call("areaId", area.id().toString(), "activity", "BILLIARDS", "date", DAY,
                "startTime", "18:00", "durationMinutes", "60").andExpect(status().isOk())).path("data");
        assertThat(venueIds(tables)).containsExactly(billiards.id().toString());
        assertThat(tables.get(0).path("resources").get(0).path("consoleType").isNull()).isTrue();
    }

    @Test
    void anUnknownAreaFindsNothing() throws Exception {
        call("areaId", UUID.randomUUID().toString(), "activity", "PLAYSTATION", "date", DAY, "startTime", "18:00",
                "durationMinutes", "60").andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ------------------------------------------------------------------------------------------ opening hours

    @Test
    void theIntervalMustLieInsideOneOperatingSession() throws Exception {
        Fixtures.Venue venue = openVenue("Arena");
        room(venue, "Room 1");

        assertThat(find(DAY, "21:00", 120)).as("ends exactly at closing").hasSize(1);
        assertThat(find(DAY, "12:00", 60)).as("starts exactly at opening").hasSize(1);
        assertThat(find(DAY, "22:00", 120)).as("runs past closing").isEmpty();
        assertThat(find(DAY, "11:30", 60)).as("starts before opening").isEmpty();
        assertThat(find("2031-02-04", "18:00", 60)).as("a day without a session").isEmpty();
    }

    // ------------------------------------------------------------------------------------------ input rules

    @Test
    void theDurationFollowsTheOnlineBookingRule() throws Exception {
        Fixtures.Venue venue = openVenue("Arena");
        room(venue, "Room 1");

        for (int minutes : new int[] {60, 90, 180, 360}) {
            assertThat(find(DAY, "12:00", minutes)).as(minutes + " minutes").hasSize(1);
        }
        for (String minutes : new String[] {"0", "30", "45", "75", "390", "-60"}) {
            call("areaId", area.id().toString(), "activity", "PLAYSTATION", "date", DAY, "startTime", "12:00",
                    "durationMinutes", minutes).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field").value("durationMinutes"));
        }
    }

    @Test
    void aStartLessThanFifteenMinutesAheadOrInThePastFindsNothing() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().named("Now").create();
        LocalDateTime now = LocalDateTime.ofInstant(databaseNow(), CAIRO).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime opens = now.minusHours(1);
        anOperatingSession().forVenue(venue).on(opens.toLocalDate().toString(), opens.toString(),
                now.plusHours(4).toString()).create();
        room(venue, "Room 1");

        assertThat(find(now.plusMinutes(5), 60)).as("5 minutes ahead").isEmpty();
        assertThat(find(now.minusMinutes(30), 60)).as("in the past").isEmpty();
        assertThat(find(now.plusMinutes(30), 60)).as("30 minutes ahead").hasSize(1);
    }

    @Test
    void aLocalTimeTheClockSkipsIsValidationFailedOnStartTime() throws Exception {
        ZoneOffsetTransition gap = CAIRO.getRules().nextTransition(Instant.parse("2031-01-01T00:00:00Z"));
        assertThat(gap.isGap()).as("Egypt's spring-forward in 2031").isTrue();
        LocalDateTime skipped = gap.getDateTimeBefore().plusMinutes(30);

        call("areaId", area.id().toString(), "activity", "PLAYSTATION", "date", skipped.toLocalDate().toString(),
                "startTime", skipped.toLocalTime().toString(), "durationMinutes", "60")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("startTime"));
    }

    @Test
    void missingInputsAreValidationFailedOnThem() throws Exception {
        call("activity", "PLAYSTATION", "date", DAY, "startTime", "18:00", "durationMinutes", "60")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("areaId"));
        call("areaId", area.id().toString(), "activity", "PLAYSTATION", "startTime", "18:00", "durationMinutes", "60")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("date"));
        call("areaId", area.id().toString(), "activity", "PLAYSTATION", "date", DAY, "durationMinutes", "60")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("startTime"));
    }

    // ------------------------------------------------------------------------------------------ privacy

    @Test
    void theResultCarriesNoPhoneOrInternalState() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().named("Private").phone("+20229998877").create();
        withSession(venue);
        room(venue, "Room 1");

        String result = find(DAY, "18:00", 60).toString();

        assertThat(result).doesNotContain("29998877").doesNotContainIgnoringCase("\"phone")
                .doesNotContain("baseState").doesNotContain("operationalRestriction").doesNotContain("price");
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** An ACTIVE PlayStation venue of the case's area with an ENABLED account and the 3 February session. */
    private Fixtures.Venue openVenue(String name) {
        Fixtures.Venue venue = aVenue().inArea(area).active().named(name).create();
        withSession(venue);
        return venue;
    }

    private static void withSession(Fixtures.Venue venue) {
        aVenueAccount().forVenue(venue).create();
        anOperatingSession().forVenue(venue).on(DAY, DAY + "T12:00", DAY + "T23:00").create();
    }

    private static Fixtures.Resource room(Fixtures.Venue venue, String name) {
        return aResource().at(venue).playStation(ConsoleType.PS5).named(name).create();
    }

    record TestCommand(java.util.function.Consumer<CommandContext> body) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            body.accept(context);
            return null;
        }
    }

    /** A CONFIRMED manual Booking and its active BOOKING ledger row. */
    private void book(Fixtures.Venue venue, Fixtures.Resource room, String start, String end) {
        UUID booking = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :room, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', :console, 'SINGLE',
                                :name, '2030-01-01 08:00:00+00', '2030-01-01 09:00:00+00', 60, 100.00, 100.00, 0.00,
                                100.00, DATE '2030-01-01', 'Africa/Cairo', now())""")
                .param("id", booking).param("venue", venue.id()).param("room", room.id()).param("name", room.name())
                .param("console", room.consoleType().name())
                .update();
        runner.run(new TestCommand(ctx -> ledger.create(ctx, new NewReservation(room.id(),
                ReservationOwner.booking(booking), Instant.parse(start), Instant.parse(end)))));
    }

    /** Maintenance and, as arch 05 section 3.2 requires, its MAINTENANCE guard row. */
    private void maintenance(Fixtures.Resource room, String start, String end) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                        VALUES (:id, :room, tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'Find Available maintenance', false)""")
                .param("id", id).param("room", room.id())
                .param("start", OffsetDateTime.ofInstant(Instant.parse(start), ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(Instant.parse(end), ZoneOffset.UTC)).update();
        runner.run(new TestCommand(ctx -> ledger.create(ctx, new NewReservation(room.id(),
                ReservationOwner.maintenance(id), Instant.parse(start), Instant.parse(end)))));
    }

    private static void resourceSellingBlock(Fixtures.Resource room) {
        OWNER.sql("""
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
                        VALUES (:id, :room, 'Find Available block', true, '2020-01-01 00:00:00+00')""")
                .param("id", UUID.randomUUID()).param("room", room.id()).update();
    }

    private static void venueSellingBlock(Fixtures.Venue venue) {
        OWNER.sql("""
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
                        VALUES (:id, :venue, 'Find Available block', true, '2020-01-01 00:00:00+00')""")
                .param("id", UUID.randomUUID()).param("venue", venue.id()).update();
    }

    private static Instant databaseNow() {
        return OWNER.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    private JsonNode find(String date, String startTime, int minutes) throws Exception {
        return body(call("areaId", area.id().toString(), "activity", "PLAYSTATION", "date", date,
                "startTime", startTime, "durationMinutes", String.valueOf(minutes)).andExpect(status().isOk()))
                .path("data");
    }

    private JsonNode find(LocalDateTime start, int minutes) throws Exception {
        return find(start.toLocalDate().toString(), start.toLocalTime().toString(), minutes);
    }

    private ResultActions call(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(FIND);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> venueIds(JsonNode venues) {
        return StreamSupport.stream(venues.spliterator(), false).map(v -> v.path("venueId").asText()).toList();
    }

    private static List<String> resourceIds(JsonNode venue) {
        return StreamSupport.stream(venue.path("resources").spliterator(), false).map(r -> r.path("id").asText())
                .toList();
    }
}
