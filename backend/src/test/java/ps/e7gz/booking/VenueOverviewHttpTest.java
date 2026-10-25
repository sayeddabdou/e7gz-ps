package ps.e7gz.booking;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestClock;
import ps.e7gz.support.boundary.TestClockConfig;

/**
 * E7-115 - {@code GET /api/v1/venue/overview} end to end (FR-VEN-35, FR-VEN-36, arch 09 section 4.1; arch 04 section
 * 4.3 for the business date): a seeded month of online and manual bookings in every status, at two venues, gives
 * exactly the hand-computed figures. Revenue is the {@code venue_price_total} of COMPLETED bookings only (PO decision 6
 * Oct 2026); today's bookings count every status (PO decision 11 Oct 2026, E7-54); "today" is the business date of
 * the injected Clock (PO decision 3 Oct 2026, restated 12 Oct 2026).
 *
 * <p>Venues, accounts, rooms and Operating Sessions come from the shared builders; bookings are planted as SQL with
 * {@code session_replication_role = replica} (the pattern of {@code VenueAvailabilityHttpTest}), so the creation guards
 * do not refuse fixed-date rows while every CHECK still applies. The stored {@code business_date} is planted as the
 * creating command would have stored it: the after-midnight booking of a night carries the night's date.
 */
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class VenueOverviewHttpTest extends IntegrationTest {

    private static final String OVERVIEW = "/api/v1/venue/overview";
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final Set<String> HELD = Set.of("PENDING", "AWAITING_CUSTOMER_CONFIRMATION", "CONFIRMED", "ACTIVE");

    /** One migrator connection with the creation triggers off, for planting bookings only. */
    private static final JdbcClient PLANT = planter();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccessTokens accessTokens;
    @Autowired TestClock clock;

    record Venue(UUID id, UUID account, UUID room) {
    }

    /**
     * A venue open 18:00-02:00 Cairo on 30 May, 31 May and 1 June 2031, its bookings around the May/June boundary, and a
     * second venue's bookings on the same night.
     */
    private Venue seededMonthEnd() {
        Venue venue = venue();
        for (String day : List.of("2031-05-30", "2031-05-31", "2031-06-01")) {
            LocalDate next = LocalDate.parse(day).plusDays(1);
            anOperatingSession().forVenue(venue.id()).on(day, day + "T18:00", next + "T02:00").create();
        }
        // Business date 31 May: three COMPLETED, 100.00 + 128.25 + 70.25 = 298.50; ten bookings in all.
        booking(venue, "ONLINE", "COMPLETED", "2031-05-31T18:00", 60, "100.00", "2031-05-31");
        booking(venue, "MANUAL", "COMPLETED", "2031-05-31T19:00", 90, "85.50", "2031-05-31");    // 128.25
        booking(venue, "ONLINE", "COMPLETED", "2031-06-01T00:30", 60, "70.25", "2031-05-31");    // after midnight
        booking(venue, "ONLINE", "CONFIRMED", "2031-05-31T21:00", 60, "300.00", "2031-05-31");
        booking(venue, "ONLINE", "ACTIVE", "2031-05-31T22:00", 120, "300.00", "2031-05-31");
        booking(venue, "MANUAL", "ACTIVE", "2031-05-31T23:00", 60, "300.00", "2031-05-31");
        booking(venue, "MANUAL", "CANCELLED", "2031-05-31T20:00", 60, "300.00", "2031-05-31");
        booking(venue, "ONLINE", "REJECTED", "2031-05-31T20:30", 60, "300.00", "2031-05-31");
        booking(venue, "ONLINE", "NO_SHOW", "2031-05-31T21:30", 60, "300.00", "2031-05-31");
        booking(venue, "ONLINE", "PENDING", "2031-06-01T01:00", 60, "300.00", "2031-05-31");
        // Earlier in May: 80.00 COMPLETED and a cancelled one; April's COMPLETED is another month.
        booking(venue, "MANUAL", "COMPLETED", "2031-05-30T18:00", 120, "40.00", "2031-05-30");    // 80.00
        booking(venue, "ONLINE", "CANCELLED", "2031-05-30T21:00", 60, "300.00", "2031-05-30");
        booking(venue, "MANUAL", "COMPLETED", "2031-04-30T19:00", 60, "33.33", "2031-04-30");
        // Business date 1 June: 55.55 COMPLETED, three bookings, one PENDING.
        booking(venue, "ONLINE", "COMPLETED", "2031-06-01T18:00", 60, "55.55", "2031-06-01");
        booking(venue, "ONLINE", "PENDING", "2031-06-01T20:00", 60, "300.00", "2031-06-01");
        booking(venue, "MANUAL", "CONFIRMED", "2031-06-01T21:00", 60, "300.00", "2031-06-01");
        // Another venue, same night: never counted here.
        Venue other = venue();
        booking(other, "ONLINE", "COMPLETED", "2031-05-31T18:00", 60, "1000.00", "2031-05-31");
        booking(other, "MANUAL", "COMPLETED", "2031-05-31T19:00", 60, "1000.00", "2031-05-31");
        booking(other, "ONLINE", "PENDING", "2031-05-31T20:00", 60, "1000.00", "2031-05-31");
        return venue;
    }

    @Test
    void atOneInTheMorningTheRunningNightIsTodayAndItsMonthIsMay() throws Exception {
        Venue venue = seededMonthEnd();

        JsonNode data = overviewAt(venue, "2031-06-01T01:00");

        assertThat(money(data, "todayRevenue")).as("COMPLETED only, the after-midnight one included")
                .isEqualTo("298.50");
        assertThat(money(data, "monthRevenue")).as("1-31 May: 298.50 + 80.00; April and June excluded")
                .isEqualTo("378.50");
        assertThat(data.path("todayBookings").asInt()).as("every status of 31 May, online and manual").isEqualTo(10);
        assertThat(data.path("pendingRequests").asInt()).as("every PENDING request of this venue").isEqualTo(2);
    }

    @Test
    void atThreeInTheMorningAfterClosingTheCalendarDayIsTodayAndANewMonthBegins() throws Exception {
        Venue venue = seededMonthEnd();

        JsonNode data = overviewAt(venue, "2031-06-01T03:00");

        assertThat(money(data, "todayRevenue")).isEqualTo("55.55");
        assertThat(money(data, "monthRevenue")).as("June has only its first business date so far").isEqualTo("55.55");
        assertThat(data.path("todayBookings").asInt()).isEqualTo(3);
        assertThat(data.path("pendingRequests").asInt()).isEqualTo(2);
    }

    @Test
    void theNextNightAfterMidnightStillBelongsToTheFirstOfJune() throws Exception {
        Venue venue = seededMonthEnd();

        JsonNode data = overviewAt(venue, "2031-06-02T01:00");

        assertThat(money(data, "todayRevenue")).isEqualTo("55.55");
        assertThat(data.path("todayBookings").asInt()).isEqualTo(3);
    }

    @Test
    void theShapeIsTheSprint3ContractWithMoneyAtScaleTwo() throws Exception {
        Venue venue = venue();

        JsonNode data = overviewAt(venue, "2031-06-01T12:00");

        List<String> fields = new ArrayList<>();
        data.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("todayRevenue", "monthRevenue", "todayBookings",
                "pendingRequests");
        for (String revenue : List.of("todayRevenue", "monthRevenue")) {
            assertThat(data.path(revenue).path("amount").isTextual()).isTrue();
            assertThat(data.path(revenue).path("amount").asText()).as("zero at scale 2").isEqualTo("0.00");
            assertThat(data.path(revenue).path("currency").asText()).isEqualTo("EGP");
        }
        assertThat(data.path("todayBookings").isInt()).isTrue();
        assertThat(data.path("todayBookings").asInt()).isZero();
        assertThat(data.path("pendingRequests").asInt()).isZero();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Venue venue() {
        UUID venue = aVenue().active().create().id();
        UUID account = aVenueAccount().forVenue(venue).create().id();
        UUID room = aResource().at(venue).playStation(ConsoleType.PS5).named("Room 1").create().id();
        return new Venue(venue, account, room);
    }

    private static Instant cairo(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(CAIRO).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * {@code booking.booking} with the money arithmetic of its CHECKs: venue price = hourly x minutes / 60 at scale 2;
     * an online booking carries a 5.00 Booking Fee (never revenue), a manual one none. Each online booking has its own
     * Customer, so commitments never overlap.
     */
    private static void booking(Venue venue, String source, String status, String localStart, int minutes,
                                String hourly, String businessDate) {
        Instant start = cairo(localStart);
        Instant end = start.plusSeconds(minutes * 60L);
        boolean online = "ONLINE".equals(source);
        BigDecimal rate = new BigDecimal(hourly);
        BigDecimal price = rate.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(60), 2,
                RoundingMode.HALF_UP);
        BigDecimal fee = online ? new BigDecimal("5.00") : new BigDecimal("0.00");
        boolean terminal = Set.of("COMPLETED", "REJECTED", "CANCELLED", "NO_SHOW").contains(status);
        String reason = switch (status) {
            case "REJECTED" -> "VENUE_REJECTED";
            case "CANCELLED" -> online ? "CUSTOMER_CANCELLED" : "VENUE_CANCELLED_MANUAL_BOOKING";
            default -> null;
        };
        String feeTreatment = reason == null ? null : online ? "FULL_REFUND" : "NOT_APPLICABLE";
        PLANT.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date,
                                                     commitment_period, terminal_reason, terminal_at, fee_treatment,
                                                     venue_reject_reason_text, created_at)
                        VALUES (gen_random_uuid(), :venue, :room, :customer, :source, :status, 'PLAYSTATION', 'PS5',
                                'SINGLE', 'Room 1', :start, :end, :minutes, :rate, :price, :fee, :total, :businessDate,
                                CASE WHEN :held THEN tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz),
                                                               '[)') END,
                                :reason, :terminalAt, :feeTreatment, :rejectText, :createdAt)""")
                .param("venue", venue.id()).param("room", venue.room())
                .param("customer", online ? aUser().verified().create().id() : null)
                .param("source", source).param("status", status)
                .param("start", utc(start)).param("end", utc(end)).param("minutes", minutes).param("rate", rate)
                .param("price", price).param("fee", fee).param("total", price.add(fee))
                .param("businessDate", LocalDate.parse(businessDate))
                .param("held", online && HELD.contains(status))
                .param("reason", reason).param("terminalAt", terminal ? utc(end) : null)
                .param("feeTreatment", feeTreatment)
                .param("rejectText", "VENUE_REJECTED".equals(reason) ? "Room busy" : null)
                .param("createdAt", utc(start.minusSeconds(86_400)))
                .update();
    }

    private static JdbcClient planter() {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        JdbcClient client = JdbcClient.create(source);
        client.sql("SET session_replication_role = replica").update();
        return client;
    }

    /** The Overview with the Clock at {@code localNow} Cairo; the token is issued at the same instant. */
    private JsonNode overviewAt(Venue venue, String localNow) throws Exception {
        clock.set(cairo(localNow));
        String token = accessTokens.issue(AuthSubjectType.VENUE, venue.account());
        MvcResult result = mvc.perform(get(OVERVIEW).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static String money(JsonNode data, String field) {
        assertThat(data.path(field).path("currency").asText()).isEqualTo("EGP");
        return data.path(field).path("amount").asText();
    }
}
