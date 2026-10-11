package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-49 over HTTP: the venue's booking history list (FR-VEN-38) and one booking with its history (FR-VEN-34) - the
 * filters, inclusive business dates and the defaults of PO decision 11 Oct 2026, E7-49 (D4), the search of D3 with its
 * privacy-negative case (never the customer's phone, NFR-18), and the NFR-19 history redaction (arch 10 section 5.2).
 */
@AutoConfigureMockMvc
class VenueBookingListHttpTest extends IntegrationTest {

    static final String BOOKINGS = "/api/v1/venue/bookings";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    OnlineBookingCreation creation;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------- D4: defaults and paging

    @Test
    void withoutDatesItListsEveryBookingOfThisVenueOnlyNewestFirstAndPagesByCursor() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        List<UUID> newestFirst = new ArrayList<>();
        for (int hours = 0; hours < 5; hours++) {
            newestFirst.add(0, online(world, hours).bookingId());
        }
        newestFirst.add(walkIn(world, second(world), "Omar", "01001234567"));            // starts now: the oldest
        businessDate(newestFirst.get(2), LocalDate.of(2031, 3, 15));                       // any date at all
        OnlineBookingWorld other = OnlineBookingWorld.create();
        online(other, 0);                                                                 // another venue's

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MockHttpServletRequestBuilder page = get(BOOKINGS).param("limit", "4");
            if (cursor != null) {
                page.param("cursor", cursor);
            }
            JsonNode body = read(page, world);
            body.at("/data/items").forEach(item -> seen.add(UUID.fromString(item.get("bookingId").asText())));
            cursor = body.at("/data/nextCursor").isNull() ? null : body.at("/data/nextCursor").asText();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(2);
        assertThat(seen).as("all dates, newest first, online and manual, this venue only")
                .containsExactlyElementsOf(newestFirst);
    }

    @Test
    void aLimitAbove100FromAfterToAndABadCursorAreValidationFailed() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        expectInvalid(world, get(BOOKINGS).param("limit", "101"));
        expectInvalid(world, get(BOOKINGS).param("limit", "0"));
        expectInvalid(world, get(BOOKINGS).param("cursor", "nope"));
        mvc.perform(get(BOOKINGS).param("from", "2026-10-12").param("to", "2026-10-11")
                        .header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("from"));
        mvc.perform(get(BOOKINGS).param("limit", "100").header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------- filters

    @Test
    void fromAndToAreInclusiveBusinessDates() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID before = online(world, 0).bookingId();
        UUID on = online(world, 2).bookingId();
        UUID after = online(world, 4).bookingId();
        LocalDate day = LocalDate.of(2031, 3, 15);
        businessDate(before, day.minusDays(1));
        businessDate(on, day);
        businessDate(after, day.plusDays(1));

        assertThat(ids(list(world, "from", day, "to", day))).containsExactly(on);
        assertThat(ids(list(world, "from", day.minusDays(1), "to", day))).containsExactly(on, before);
        assertThat(ids(list(world, "from", day))).containsExactly(after, on);
        assertThat(ids(list(world, "to", day))).containsExactly(on, before);
        assertThat(ids(list(world, "from", day.plusDays(2)))).isEmpty();
    }

    @Test
    void activitySourceResourceAndStatusEachNarrowTheList() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = second(world);
        UUID online = online(world, 0).bookingId();
        UUID billiards = online(world, 2).bookingId();
        UUID manual = walkIn(world, second, "Omar", "01001234567");
        // TEST-ONLY: one booking's snapshot made a billiards one (no console, no play mode: ck_booking_snapshot_dimensions)
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE booking.booking SET activity = 'BILLIARDS', console_type = NULL, play_mode = NULL
                         WHERE id = :id""").param("id", billiards).update());

        assertThat(ids(list(world, "activity", "BILLIARDS"))).containsExactly(billiards);
        assertThat(ids(list(world, "activity", "PLAYSTATION"))).containsExactly(online, manual);
        assertThat(ids(list(world, "source", "MANUAL"))).containsExactly(manual);
        assertThat(ids(list(world, "source", "ONLINE"))).containsExactly(billiards, online);
        assertThat(ids(list(world, "resourceId", second.id()))).containsExactly(manual);
        assertThat(ids(list(world, "status", "ACTIVE"))).containsExactly(manual);
        assertThat(ids(list(world, "status", "PENDING", "source", "ONLINE", "activity", "PLAYSTATION")))
                .containsExactly(online);
    }

    // ---------------------------------------------------------------------------------------------- D3: search

    @Test
    void qMatchesTheExactBookingIdTheUsernameTheWalkInNameAndPhoneAndTheResourceName() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = second(world);
        CreatedBooking online = online(world, 0);
        UUID manual = walkIn(world, second, "Mostafa Kamal", "01112223334");
        String username = OWNER.sql("SELECT username FROM iam.user_account WHERE id = :id")
                .param("id", online.customerId()).query(String.class).single();
        String secondRoomName = OWNER.sql("SELECT name FROM resource.resource WHERE id = :id")
                .param("id", second.id()).query(String.class).single();

        assertThat(ids(list(world, "q", online.bookingId()))).as("exact booking ID").containsExactly(online.bookingId());
        assertThat(ids(list(world, "q", online.bookingId().toString().substring(0, 8))))
                .as("a part of an ID is no ID").isEmpty();
        assertThat(ids(list(world, "q", username))).as("exact current username").containsExactly(online.bookingId());
        assertThat(ids(list(world, "q", "  " + username.toUpperCase(Locale.ROOT) + "  ")))
                .as("username login's trim and case rule").containsExactly(online.bookingId());
        assertThat(ids(list(world, "q", "kamal"))).as("walk-in name").containsExactly(manual);
        assertThat(ids(list(world, "q", "1112223334"))).as("walk-in phone").containsExactly(manual);
        assertThat(ids(list(world, "q", "+201112223334"))).containsExactly(manual);
        assertThat(ids(list(world, "q", secondRoomName))).as("resource name").containsExactly(manual);
        assertThat(ids(list(world, "q", "%"))).as("a wildcard is a literal character").isEmpty();
    }

    /** PO decision 11 Oct 2026, E7-49, D3 amendment: the username must be whole - no prefix, suffix or middle. */
    @Test
    void aPartOfAUsernameFindsNothing() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking online = online(world, 0);
        String username = OWNER.sql("SELECT username FROM iam.user_account WHERE id = :id")
                .param("id", online.customerId()).query(String.class).single();

        for (String part : new String[] {username.substring(0, username.length() - 1), username.substring(1),
                username.substring(1, username.length() - 1), username + "x"}) {
            assertThat(ids(list(world, "q", part))).as("q=%s", part).isEmpty();
        }
        assertThat(ids(list(world, "q", username))).containsExactly(online.bookingId());
    }

    /** The exact username of another venue's customer finds nothing here: the match is scoped to this venue. */
    @Test
    void anotherVenuesCustomerFoundByUsernameAddsNothing() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();
        online(world, 0);
        CreatedBooking elsewhere = online(other, 0);
        String username = OWNER.sql("SELECT username FROM iam.user_account WHERE id = :id")
                .param("id", elsewhere.customerId()).query(String.class).single();

        assertThat(ids(list(world, "q", username))).isEmpty();
        assertThat(ids(list(other, "q", username))).containsExactly(elsewhere.bookingId());
    }

    @Test
    void qNeverSearchesTheCustomersPhone() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking online = online(world, 0);
        String phone = ContactRedactionHttpTest.customerPhone(online.customerId());
        JsonNode visible = read(get(BOOKINGS + "/" + online.bookingId()), world);
        assertThat(visible.at("/data/booking/customer/phone").asText()).as("the phone is visible right now")
                .isEqualTo(phone);

        for (String q : new String[] {phone, phone.substring(1), phone.substring(3), "0" + phone.substring(3),
                phone.substring(phone.length() - 6)}) {
            assertThat(ids(list(world, "q", q))).as("q=%s finds nothing (PO decision 11 Oct 2026, E7-49, D3)", q)
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------------------- one booking, NFR-19 history

    @Test
    void theVenueHistoryNeverShowsWhyACustomerIsIneligibleForALateConfirm() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = online(world, 0);
        BookingStates.plant(created.bookingId(), BookingStates.Plant.of("VENUE_RESPONSE_EXPIRED")
                .expiredAt(BookingStates.minutesFromNow(-20)));
        event(created.bookingId(), "LATE_CONFIRM_FAILED_BOOKING_LIMIT");
        event(created.bookingId(), "LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT");
        event(created.bookingId(), "LATE_CONFIRM_FAILED_RESOURCE_CONFLICT");

        String raw = mvc.perform(get(BOOKINGS + "/" + created.bookingId())
                        .header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode history = json.readTree(raw).at("/data/history");

        assertThat(raw).doesNotContain("LATE_CONFIRM_FAILED_BOOKING_LIMIT")
                .doesNotContain("LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT");
        List<String> shown = new ArrayList<>();
        history.forEach(entry -> shown.add(entry.get("kind").asText() + ":"
                + (entry.get("eventType").isNull() ? entry.get("toStatus").asText() : entry.get("eventType").asText())));
        assertThat(shown).containsExactly("STATUS_TRANSITION:PENDING", "STATUS_TRANSITION:VENUE_RESPONSE_EXPIRED",
                "BOOKING_EVENT:LATE_CONFIRM_FAILED_RESOURCE_CONFLICT");
        assertThat(json.readTree(raw).at("/data/booking/bookingId").asText()).isEqualTo(created.bookingId().toString());
        assertThat(VenueAnswerDb.count("booking.booking_history WHERE booking_id = :id", created.bookingId()))
                .as("the events are still recorded internally").isEqualTo(5);
    }

    @Test
    void anotherVenuesBookingIsNotFound() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();
        CreatedBooking created = online(world, 0);

        mvc.perform(get(BOOKINGS + "/" + created.bookingId()).header("Authorization", "Bearer " + venue(other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get(BOOKINGS + "/" + UUID.randomUUID()).header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isNotFound());
        assertThat(ids(list(other, "q", created.bookingId()))).isEmpty();
        mvc.perform(get(BOOKINGS).header("Authorization", "Bearer "
                        + accessTokens.issue(AuthSubjectType.CUSTOMER, created.customerId())))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private CreatedBooking online(OnlineBookingWorld world, int hoursAfter) {
        return creation.create(world.request(world.anotherCustomer(), world.room,
                world.start().plus(Duration.ofHours(hoursAfter)), 60));
    }

    private static Fixtures.Resource second(OnlineBookingWorld world) {
        return world.anotherRoom(room -> room.playStation(ConsoleType.PS5));
    }

    /** A walk-in starting now, through the real route (E7-48). */
    private UUID walkIn(OnlineBookingWorld world, Fixtures.Resource room, String name, String phone) throws Exception {
        String body = mvc.perform(post(BOOKINGS).header("Authorization", "Bearer " + venue(world))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + room.id() + "\", \"playMode\": \"SINGLE\", "
                                + "\"durationMinutes\": 60, \"customerName\": \"" + name + "\", "
                                + "\"customerPhone\": \"" + phone + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).at("/data/bookingId").asText());
    }

    private static void businessDate(UUID bookingId, LocalDate day) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("UPDATE booking.booking SET business_date = :day WHERE id = :id")
                .param("day", day).param("id", bookingId).update());
    }

    /** A Booking Event row, as the Late Confirm task will record it (FR-LCF-05, FR-LCF-15). */
    private static void event(UUID bookingId, String type) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind,
                                                             event_type, actor_type, correlation_id)
                        SELECT gen_random_uuid(), :id, max(h.sequence) + 1, now(), 'BOOKING_EVENT', :type, 'SYSTEM',
                               gen_random_uuid()
                          FROM booking.booking_history h WHERE h.booking_id = :id""")
                .param("id", bookingId).param("type", type).update());
    }

    private JsonNode list(OnlineBookingWorld world, Object... params) throws Exception {
        MockHttpServletRequestBuilder request = get(BOOKINGS);
        for (int i = 0; i < params.length; i += 2) {
            request.param((String) params[i], params[i + 1].toString());
        }
        return read(request, world);
    }

    private JsonNode read(MockHttpServletRequestBuilder request, OnlineBookingWorld world) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void expectInvalid(OnlineBookingWorld world, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.at("/data/items").forEach(item -> ids.add(UUID.fromString(item.get("bookingId").asText())));
        return ids;
    }

    private String venue(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }
}
