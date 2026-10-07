package ps.e7gz.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-112 - {@code GET /api/v1/venue/availability} end to end (FR-VEN-37, arch 09 section 4.1; arch 04 section 4.3 for
 * the business date): the nine display states from their source rows, the business-day envelope, the closed day, the
 * emergency CLOSURE, the room states and tenant isolation.
 *
 * <p>Venues, accounts, rooms and Operating Sessions come from the shared builders. Bookings, ledger rows, Maintenance,
 * Closures and Emergencies are planted as SQL on one migrator connection with {@code session_replication_role =
 * replica} (the pattern of {@code ErrorEnvelopeHttpTest} and {@code GuardErrorIdentifiersTest}): the database's creation
 * guards decide "now" and refuse fixed-date rows, while every CHECK and exclusion constraint still applies. The
 * Tournament holds therefore need no Tournament row: no production Tournament code is involved. Each case uses its own
 * venue; the active rows never overlap one another on a room, so the shared database stays sweep-clean.
 *
 * <p>The Clock is fixed at 01:00 Cairo on 11 March 2031 for the default-date case; access tokens use the same Clock.
 */
@AutoConfigureMockMvc
@Import(VenueAvailabilityHttpTest.FixedClock.class)
class VenueAvailabilityHttpTest extends IntegrationTest {

    private static final String SCHEDULE = "/api/v1/venue/availability";
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 01:00 on Tuesday 11 March 2031 in Cairo (+02:00, no DST in March). */
    static final Instant NOW = Instant.parse("2031-03-10T23:00:00Z");
    private static final String DAY = "2031-03-10";

    /** One migrator connection with the creation triggers off, for planting source rows only. */
    private static final JdbcClient PLANT = planter();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessTokens accessTokens;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    record Venue(UUID id, String token) {
    }

    // ------------------------------------------------------------------------------------------ FREE and bookings

    @Test
    void unusedSessionTimeIsOneFreeSpanWithNoBooking() throws Exception {
        Venue venue = venue();
        UUID room = room(venue, "Room 1");
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");

        JsonNode data = schedule(venue, DAY);

        assertThat(data.path("businessDate").asText()).isEqualTo(DAY);
        assertThat(data.path("closed").isBoolean()).isTrue();
        assertThat(data.path("closed").asBoolean()).isFalse();
        assertThat(slots(data, room)).containsExactly("FREE 10:00-23:00");
        assertThat(bookingIds(data, room)).containsExactly("null");
    }

    @Test
    void bookingRowsShowTheOnlineStatusOrManualWithTheirBookingId() throws Exception {
        Venue venue = venue();
        UUID one = room(venue, "Room 1");
        UUID two = room(venue, "Room 2");
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        UUID pending = bookedRow(venue, one, "ONLINE", "PENDING", DAY + "T10:00", 60, DAY);
        UUID confirmed = bookedRow(venue, one, "ONLINE", "CONFIRMED", DAY + "T11:00", 60, DAY);
        UUID active = bookedRow(venue, one, "ONLINE", "ACTIVE", DAY + "T12:00", 60, DAY);
        UUID manualConfirmed = bookedRow(venue, one, "MANUAL", "CONFIRMED", DAY + "T14:00", 60, DAY);
        UUID manualActive = bookedRow(venue, two, "MANUAL", "ACTIVE", DAY + "T10:00", 60, DAY);

        JsonNode data = schedule(venue, DAY);

        assertThat(slots(data, one)).containsExactly("PENDING 10:00-11:00", "CONFIRMED 11:00-12:00",
                "ACTIVE 12:00-13:00", "FREE 13:00-14:00", "MANUAL 14:00-15:00", "FREE 15:00-23:00");
        assertThat(bookingIds(data, one)).containsExactly(pending.toString(), confirmed.toString(), active.toString(),
                "null", manualConfirmed.toString(), "null");
        assertThat(slots(data, two)).as("a manual ACTIVE booking is MANUAL: source overrides status for display")
                .containsExactly("MANUAL 10:00-11:00", "FREE 11:00-23:00");
        assertThat(bookingIds(data, two)).containsExactly(manualActive.toString(), "null");
    }

    @Test
    void holdsShowAwaitingOrTournamentHoldAndOnlyTheLateConfirmHoldCarriesTheBooking() throws Exception {
        Venue venue = venue();
        UUID room = room(venue, "Room 1");
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        // A late-confirmed Booking: its first row was released at expiry, the hold remains its reservation and is
        // shown as AWAITING by its row type even though the Booking is CONFIRMED (E7-112).
        UUID lateConfirmed = booking(venue, room, "ONLINE", "CONFIRMED", DAY + "T10:00", 60, DAY);
        reservation(room, "BOOKING", lateConfirmed, null, DAY + "T10:00", DAY + "T11:00", true);
        reservation(room, "LATE_CONFIRM_HOLD", lateConfirmed, null, DAY + "T10:00", DAY + "T11:00", false);
        UUID awaiting = booking(venue, room, "ONLINE", "AWAITING_CUSTOMER_CONFIRMATION", DAY + "T12:00", 60, DAY);
        reservation(room, "LATE_CONFIRM_HOLD", awaiting, null, DAY + "T12:00", DAY + "T13:00", false);
        reservation(room, "TOURNAMENT_APPROVAL_HOLD", null, UUID.randomUUID(), DAY + "T14:00", DAY + "T15:00", false);
        reservation(room, "TOURNAMENT_HOLD", null, UUID.randomUUID(), DAY + "T16:00", DAY + "T18:00", false);

        JsonNode data = schedule(venue, DAY);

        assertThat(slots(data, room)).containsExactly("AWAITING_CUSTOMER_CONFIRMATION 10:00-11:00",
                "FREE 11:00-12:00", "AWAITING_CUSTOMER_CONFIRMATION 12:00-13:00", "FREE 13:00-14:00",
                "TOURNAMENT_HOLD 14:00-15:00", "FREE 15:00-16:00", "TOURNAMENT_HOLD 16:00-18:00", "FREE 18:00-23:00");
        assertThat(bookingIds(data, room)).containsExactly(lateConfirmed.toString(), "null", awaiting.toString(),
                "null", "null", "null", "null", "null");
    }

    @Test
    void aReleasedRowIsNeverShown() throws Exception {
        Venue venue = venue();
        UUID room = room(venue, "Room 1");
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        UUID noShow = booking(venue, room, "ONLINE", "CONFIRMED", DAY + "T12:00", 60, DAY);
        reservation(room, "BOOKING", noShow, null, DAY + "T12:00", DAY + "T13:00", true);
        reservation(room, "TOURNAMENT_HOLD", null, UUID.randomUUID(), DAY + "T15:00", DAY + "T16:00", true);

        assertThat(slots(schedule(venue, DAY), room)).containsExactly("FREE 10:00-23:00");
    }

    // ------------------------------------------------------------------------------------------ Operations

    @Test
    void maintenanceAndAVenueClosureComeFromOperationsWithNoBooking() throws Exception {
        Venue venue = venue();
        UUID one = room(venue, "Room 1");
        UUID two = room(venue, "Room 2");
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        maintenance(one, DAY + "T11:00", DAY + "T12:00");
        closure(venue, DAY + "T15:00", DAY + "T16:00");

        JsonNode data = schedule(venue, DAY);

        assertThat(slots(data, one)).containsExactly("FREE 10:00-11:00", "MAINTENANCE 11:00-12:00",
                "FREE 12:00-15:00", "CLOSURE 15:00-16:00", "FREE 16:00-23:00");
        assertThat(slots(data, two)).containsExactly("FREE 10:00-15:00", "CLOSURE 15:00-16:00", "FREE 16:00-23:00");
        assertThat(bookingIds(data, one)).containsOnly("null");
    }

    @Test
    void unresolvedEmergenciesShowAsClosureOnTheirScopeAndResolvedOnesDoNotShow() throws Exception {
        Venue venue = venue();
        UUID one = room(venue, "A Room");
        UUID two = room(venue, "B Room");
        UUID disabled = aResource().at(venue.id()).playStation(ConsoleType.PS5).named("C Room").disabled().create().id();
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        emergency(venue, null, "ACTIVE", DAY + "T12:00", DAY + "T13:00");
        emergency(venue, one, "AWAITING_CONFIRMATION", DAY + "T15:00", DAY + "T16:00");
        emergency(venue, null, "ENDED", DAY + "T17:00", DAY + "T18:00");
        emergency(venue, two, "CONVERTED", DAY + "T19:00", DAY + "T20:00");

        JsonNode data = schedule(venue, DAY);

        assertThat(slots(data, one)).containsExactly("FREE 10:00-12:00", "CLOSURE 12:00-13:00", "FREE 13:00-15:00",
                "CLOSURE 15:00-16:00", "FREE 16:00-23:00");
        assertThat(slots(data, two)).as("a RESOURCE emergency only on its room; ENDED / CONVERTED never")
                .containsExactly("FREE 10:00-12:00", "CLOSURE 12:00-13:00", "FREE 13:00-23:00");
        assertThat(slots(data, disabled)).as("a VENUE emergency on every listed room")
                .containsExactly("CLOSURE 12:00-13:00");
        assertThat(bookingIds(data, one)).containsOnly("null");
    }

    @Test
    void anEmergencyOverABookingKeepsBothSpansAndNoFreeUnderEither() throws Exception {
        Venue venue = venue();
        UUID room = room(venue, "Room 1");
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        UUID booked = bookedRow(venue, room, "ONLINE", "CONFIRMED", DAY + "T12:00", 60, DAY);
        emergency(venue, null, "ACTIVE", DAY + "T12:30", DAY + "T13:30");

        JsonNode data = schedule(venue, DAY);

        assertThat(slots(data, room)).containsExactly("FREE 10:00-12:00", "CONFIRMED 12:00-13:00",
                "CLOSURE 12:30-13:30", "FREE 13:30-23:00");
        assertThat(bookingIds(data, room)).containsExactly("null", booked.toString(), "null", "null");
    }

    // ------------------------------------------------------------------------------------------ business date

    @Test
    void aSessionCrossingMidnightShowsIts0100BookingOnItsOwnBusinessDateOnly() throws Exception {
        Venue venue = venue();
        UUID room = room(venue, "Room 1");
        session(venue, DAY, DAY + "T18:00", "2031-03-11T02:00");
        session(venue, "2031-03-11", "2031-03-11T18:00", "2031-03-11T23:00");
        UUID late = bookedRow(venue, room, "MANUAL", "CONFIRMED", "2031-03-11T01:00", 60, DAY);

        JsonNode data = schedule(venue, DAY);
        assertThat(slots(data, room)).containsExactly("FREE 18:00-01:00", "MANUAL 01:00-02:00");
        JsonNode slot = slotsOf(data, room).get(1);
        assertThat(Instant.parse(slot.path("startAt").asText())).isEqualTo(cairo("2031-03-11T01:00"));
        assertThat(slot.path("bookingId").asText()).isEqualTo(late.toString());

        assertThat(slots(schedule(venue, "2031-03-11"), room))
                .as("01:00 on 11 March belongs to the 10 March session, not to the 11 March calendar day")
                .containsExactly("FREE 18:00-23:00");
    }

    @Test
    void a0300ManualBookingWidensTheRangeAndTheGapBeforeTheSessionIsNotFree() throws Exception {
        Venue venue = venue();
        UUID room = room(venue, "Room 1");
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        bookedRow(venue, room, "MANUAL", "CONFIRMED", DAY + "T03:00", 60, DAY);

        JsonNode data = schedule(venue, DAY);

        assertThat(data.path("closed").asBoolean()).isFalse();
        assertThat(slots(data, room)).as("03:00-04:00 shown, 04:00-10:00 blank (no slot), FREE only in the session")
                .containsExactly("MANUAL 03:00-04:00", "FREE 10:00-23:00");
    }

    @Test
    void aDayWithNoSessionIsClosedWithEveryRoomAndNoSlotsEvenForAnOutOfHoursBooking() throws Exception {
        Venue venue = venue();
        UUID active = room(venue, "A Room");
        UUID disabled = aResource().at(venue.id()).playStation(ConsoleType.PS5).named("B Room").disabled().create().id();
        UUID archived = aResource().at(venue.id()).playStation(ConsoleType.PS5).named("C Room").archived().create().id();
        session(venue, "2031-03-09", "2031-03-09T10:00", "2031-03-09T23:00");
        bookedRow(venue, active, "MANUAL", "CONFIRMED", DAY + "T03:00", 60, DAY);
        maintenance(disabled, DAY + "T11:00", DAY + "T12:00");
        closure(venue, DAY + "T15:00", DAY + "T16:00");
        emergency(venue, null, "ACTIVE", DAY + "T17:00", DAY + "T18:00");

        JsonNode data = schedule(venue, DAY);

        assertThat(data.path("businessDate").asText()).isEqualTo(DAY);
        assertThat(data.path("closed").asBoolean()).isTrue();
        assertThat(resourceIds(data)).containsExactly(active.toString(), disabled.toString())
                .doesNotContain(archived.toString());
        assertThat(data.path("resources")).allSatisfy(r -> assertThat(r.path("slots")).isEmpty());
    }

    @Test
    void withoutADateTheCurrentBusinessDateIsUsed() throws Exception {
        // NOW is 01:00 Cairo on 11 March, inside the 10 March session that crosses midnight: rule 1 gives 10 March.
        Venue open = venue();
        UUID room = room(open, "Room 1");
        session(open, DAY, DAY + "T18:00", "2031-03-11T02:00");
        JsonNode data = schedule(open, null);
        assertThat(data.path("businessDate").asText()).isEqualTo(DAY);
        assertThat(data.path("closed").asBoolean()).isFalse();
        assertThat(slots(data, room)).containsExactly("FREE 18:00-02:00");

        // No session at NOW: rule 2, the Cairo calendar date (11 March), not the UTC date (10 March).
        Venue elsewhere = venue();
        room(elsewhere, "Room 1");
        JsonNode closedToday = schedule(elsewhere, null);
        assertThat(closedToday.path("businessDate").asText()).isEqualTo("2031-03-11");
        assertThat(closedToday.path("closed").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------------------------------ rooms and tenants

    @Test
    void archivedRoomsAreAbsentAndDisabledRoomsShowTheirSpansButNeverFree() throws Exception {
        Venue venue = venue();
        UUID active = room(venue, "A Room");
        UUID disabled = aResource().at(venue.id()).playStation(ConsoleType.PS5).named("B Room").disabled().create().id();
        UUID archived = aResource().at(venue.id()).playStation(ConsoleType.PS5).named("C Room").archived().create().id();
        session(venue, DAY, DAY + "T10:00", DAY + "T23:00");
        bookedRow(venue, disabled, "MANUAL", "CONFIRMED", DAY + "T11:00", 60, DAY);
        maintenance(disabled, DAY + "T14:00", DAY + "T15:00");
        bookedRow(venue, archived, "MANUAL", "CONFIRMED", DAY + "T11:00", 60, DAY);

        JsonNode data = schedule(venue, DAY);

        assertThat(resourceIds(data)).containsExactly(active.toString(), disabled.toString());
        assertThat(slots(data, active)).containsExactly("FREE 10:00-23:00");
        assertThat(slots(data, disabled)).containsExactly("MANUAL 11:00-12:00", "MAINTENANCE 14:00-15:00");
        JsonNode disabledRoom = resource(data, disabled);
        assertThat(disabledRoom.path("name").asText()).isEqualTo("B Room");
        assertThat(disabledRoom.path("activity").asText()).isEqualTo("PLAYSTATION");
        assertThat(disabledRoom.path("consoleType").asText()).isEqualTo("PS5");
    }

    @Test
    void anotherVenuesRoomsBookingsOperationsAndEmergenciesAreNeverReturned() throws Exception {
        Venue mine = venue();
        UUID myRoom = room(mine, "Room 1");
        session(mine, DAY, DAY + "T10:00", DAY + "T23:00");
        Venue theirs = venue();
        UUID theirRoom = room(theirs, "Room 1");
        session(theirs, DAY, DAY + "T10:00", DAY + "T23:00");
        UUID theirBooking = bookedRow(theirs, theirRoom, "ONLINE", "CONFIRMED", DAY + "T12:00", 60, DAY);
        maintenance(theirRoom, DAY + "T14:00", DAY + "T15:00");
        closure(theirs, DAY + "T16:00", DAY + "T17:00");
        emergency(theirs, null, "ACTIVE", DAY + "T18:00", DAY + "T19:00");
        emergency(theirs, theirRoom, "ACTIVE", DAY + "T20:00", DAY + "T21:00");

        JsonNode data = schedule(mine, DAY);
        assertThat(resourceIds(data)).containsExactly(myRoom.toString());
        assertThat(slots(data, myRoom)).containsExactly("FREE 10:00-23:00");
        assertThat(data.toString()).doesNotContain(theirRoom.toString()).doesNotContain(theirBooking.toString());

        JsonNode theirView = schedule(theirs, DAY);
        assertThat(resourceIds(theirView)).containsExactly(theirRoom.toString());
        assertThat(theirView.toString()).doesNotContain(myRoom.toString());
    }

    @Test
    void onlyAVenueTokenReadsTheSchedule() throws Exception {
        assertThat(mvc.perform(get(SCHEDULE)).andReturn().getResponse().getStatus()).isEqualTo(401);
        String customer = accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().verified().create().id());
        assertThat(mvc.perform(get(SCHEDULE).header("Authorization", "Bearer " + customer)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
    }

    // ================================================================================================ fixtures

    private Venue venue() {
        UUID venue = aVenue().active().create().id();
        UUID account = aVenueAccount().forVenue(venue).create().id();
        return new Venue(venue, accessTokens.issue(AuthSubjectType.VENUE, account));
    }

    private static UUID room(Venue venue, String name) {
        return aResource().at(venue.id()).playStation(ConsoleType.PS5).named(name).create().id();
    }

    private static void session(Venue venue, String day, String localStart, String localEnd) {
        anOperatingSession().forVenue(venue.id()).on(day, localStart, localEnd).create();
    }

    private static Instant cairo(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(CAIRO).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A Booking and its active BOOKING row. */
    private static UUID bookedRow(Venue venue, UUID room, String source, String status, String localStart, int minutes,
                                  String businessDate) {
        UUID booking = booking(venue, room, source, status, localStart, minutes, businessDate);
        Instant start = cairo(localStart);
        reservation(room, "BOOKING", booking, null, start, start.plusSeconds(minutes * 60L), false);
        return booking;
    }

    /** {@code booking.booking}; an Online one has its own Customer, so commitments never overlap. */
    private static UUID booking(Venue venue, UUID room, String source, String status, String localStart, int minutes,
                                String businessDate) {
        UUID id = UUID.randomUUID();
        Instant start = cairo(localStart);
        Instant end = start.plusSeconds(minutes * 60L);
        boolean online = "ONLINE".equals(source);
        BigDecimal price = new BigDecimal("100.00").multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        PLANT.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date,
                                                     commitment_period, created_at)
                        VALUES (:id, :venue, :room, :customer, :source, :status, 'PLAYSTATION', 'PS5', 'SINGLE', 'Room',
                                :start, :end, :minutes, 100.00, :price, 0.00, :price, :businessDate,
                                CASE WHEN :online THEN tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz),
                                                                 '[)') END,
                                now())""")
                .param("id", id).param("venue", venue.id()).param("room", room)
                .param("customer", online ? aUser().verified().create().id() : null)
                .param("source", source).param("status", status)
                .param("start", utc(start)).param("end", utc(end)).param("minutes", minutes).param("price", price)
                .param("businessDate", LocalDate.parse(businessDate)).param("online", online)
                .update();
        return id;
    }

    private static void reservation(UUID room, String type, UUID bookingId, UUID tournamentId, String localStart,
                                    String localEnd, boolean released) {
        reservation(room, type, bookingId, tournamentId, cairo(localStart), cairo(localEnd), released);
    }

    /** {@code inventory.resource_reservation}; a released row was held from the day before and released at its start. */
    private static void reservation(UUID room, String type, UUID bookingId, UUID tournamentId, Instant start,
                                    Instant end, boolean released) {
        PLANT.sql("""
                        INSERT INTO inventory.resource_reservation (id, resource_id, reservation_type, booking_id,
                                tournament_id, planned_start, planned_end, planned_range, actual_reservation_start,
                                actual_reservation_release_at, release_reason, active_flag)
                        VALUES (gen_random_uuid(), :room, :type, :booking, :tournament, :start, :end,
                                tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'), :held,
                                :releasedAt, :reason, :active)""")
                .param("room", room).param("type", type).param("booking", bookingId).param("tournament", tournamentId)
                .param("start", utc(start)).param("end", utc(end)).param("held", utc(start.minusSeconds(86_400)))
                .param("releasedAt", released ? utc(start) : null).param("reason", released ? "E7-112 test" : null)
                .param("active", !released)
                .update();
    }

    private static void maintenance(UUID room, String localStart, String localEnd) {
        PLANT.sql("""
                        INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                        VALUES (gen_random_uuid(), :room,
                                tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'E7-112 maintenance', false)""")
                .param("room", room).param("start", utc(cairo(localStart))).param("end", utc(cairo(localEnd)))
                .update();
    }

    private static void closure(Venue venue, String localStart, String localEnd) {
        PLANT.sql("""
                        INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                        VALUES (gen_random_uuid(), :venue,
                                tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'PARTIAL', 'E7-112 closure')""")
                .param("venue", venue.id()).param("start", utc(cairo(localStart))).param("end", utc(cairo(localEnd)))
                .update();
    }

    /** VENUE-scoped when {@code room} is null; ENDED / CONVERTED carry their resolution facts. */
    private static void emergency(Venue venue, UUID room, String status, String localStart, String localEnd) {
        Instant start = cairo(localStart);
        boolean resolved = "ENDED".equals(status) || "CONVERTED".equals(status);
        String convertedTo = !"CONVERTED".equals(status) ? null : room == null ? "CLOSURE" : "MAINTENANCE";
        PLANT.sql("""
                        INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                          expected_end_at, declared_period, reason, converted_to,
                                                          ended_at)
                        VALUES (gen_random_uuid(), :scope, :venue, :room, :status, :start, :end,
                                tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'E7-112 emergency', :convertedTo, :endedAt)""")
                .param("scope", room == null ? "VENUE" : "RESOURCE").param("venue", venue.id()).param("room", room)
                .param("status", status).param("start", utc(start)).param("end", utc(cairo(localEnd)))
                .param("convertedTo", convertedTo).param("endedAt", resolved ? utc(start.plusSeconds(600)) : null)
                .update();
    }

    private static JdbcClient planter() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        JdbcClient client = JdbcClient.create(source);
        client.sql("SET session_replication_role = replica").update();
        return client;
    }

    // ------------------------------------------------------------------------------------------ reading

    private JsonNode schedule(Venue venue, String date) throws Exception {
        var request = get(SCHEDULE).header("Authorization", "Bearer " + venue.token());
        if (date != null) {
            request = request.param("date", date);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static List<String> resourceIds(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.path("resources").forEach(r -> ids.add(r.path("resourceId").asText()));
        return ids;
    }

    private static JsonNode resource(JsonNode data, UUID room) {
        for (JsonNode r : data.path("resources")) {
            if (r.path("resourceId").asText().equals(room.toString())) {
                return r;
            }
        }
        throw new AssertionError("room " + room + " not in " + data);
    }

    private static JsonNode slotsOf(JsonNode data, UUID room) {
        return resource(data, room).path("slots");
    }

    /** {@code STATE HH:mm-HH:mm}, Cairo wall clock. */
    private static List<String> slots(JsonNode data, UUID room) {
        List<String> slots = new ArrayList<>();
        slotsOf(data, room).forEach(s -> slots.add(s.path("state").asText() + " " + local(s.path("startAt").asText())
                + "-" + local(s.path("endAt").asText())));
        return slots;
    }

    private static List<String> bookingIds(JsonNode data, UUID room) {
        List<String> ids = new ArrayList<>();
        slotsOf(data, room).forEach(s -> {
            assertThat(s.has("bookingId")).as("bookingId is always present, null or set").isTrue();
            ids.add(s.path("bookingId").isNull() ? "null" : s.path("bookingId").asText());
        });
        return ids;
    }

    private static String local(String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), CAIRO).format(HH_MM);
    }
}
