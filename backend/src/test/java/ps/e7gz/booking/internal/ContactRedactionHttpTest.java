package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.BookingStates.minutesFromNow;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-49 - the contract test of arch 10 section 5.1 / arch 09 section 7 (NFR-18, FR-PTS-20, FR-LCF-18) with PO decision
 * 11 Oct 2026, E7-49 (D1): every booking state x retention case, on every customer read route (My Bookings, one booking)
 * and every venue read route (bookings list, one booking, missed requests), and on the replays of E7-46 creation, E7-49
 * cancel and E7-47 reject after expiry. Hidden means the {@code phone} member is ABSENT from the JSON - not null, not
 * masked, not empty - and the number appears nowhere in the body; visible means it is there, unchanged.
 *
 * <p>The exact boundaries (visible one nanosecond before the point, hidden at it) are pinned by
 * {@link ContactVisibilityTest}; here each case sits five minutes on one side of its point of the real database clock,
 * so the HTTP round trip cannot cross it.
 */
@AutoConfigureMockMvc
class ContactRedactionHttpTest extends IntegrationTest {

    static final long MARGIN = 5;
    static final long TWO_HOURS = 120;

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

    /**
     * Each case: the plant, given the booking's original start (about an hour ahead), and whether the phones show now.
     * Instants are minutes from the database's "now".
     */
    static Stream<Arguments> retentionMatrix() {
        return Stream.of(
                // open states: visible, whatever the time
                visible("PENDING", () -> Plant.of("PENDING")),
                visible("CONFIRMED", () -> Plant.of("CONFIRMED")),
                visible("ACTIVE, started 30 min ago", () -> Plant.of("ACTIVE").startAt(minutesFromNow(-30))),
                // FR-LCF-18: a Late Confirm reopens it, even long after the expiry point; Accept restores it
                visible("AWAITING reopened after expiry + 2h", () -> Plant.of("AWAITING_CUSTOMER_CONFIRMATION")
                        .expiredAt(minutesFromNow(-300)).reopenedAt(minutesFromNow(-10))),
                visible("CONFIRMED by a Late Confirm accepted", () -> Plant.of("CONFIRMED")
                        .expiredAt(minutesFromNow(-300)).reopenedAt(minutesFromNow(-240))),
                // terminal before start: terminalAt + 2h
                visible("CANCELLED before start, before terminalAt + 2h", () -> Plant.of("CANCELLED")
                        .reason("CUSTOMER_CANCELLED").terminalAt(minutesFromNow(-TWO_HOURS + MARGIN))),
                hidden("CANCELLED before start, after terminalAt + 2h", () -> Plant.of("CANCELLED")
                        .reason("CUSTOMER_CANCELLED").terminalAt(minutesFromNow(-TWO_HOURS - MARGIN))),
                visible("REJECTED before start, before terminalAt + 2h", () -> Plant.of("REJECTED")
                        .reason("VENUE_REJECTED").terminalAt(minutesFromNow(-TWO_HOURS + MARGIN))),
                hidden("REJECTED before start, after terminalAt + 2h", () -> Plant.of("REJECTED")
                        .reason("VENUE_REJECTED").terminalAt(minutesFromNow(-TWO_HOURS - MARGIN))),
                // terminal at or after start, NO_SHOW included: endAt + 2h (a 60-minute booking)
                visible("COMPLETED, before endAt + 2h", () -> endedAt(Plant.of("COMPLETED"),
                        -TWO_HOURS + MARGIN, 60)),
                hidden("COMPLETED, after endAt + 2h", () -> endedAt(Plant.of("COMPLETED"),
                        -TWO_HOURS - MARGIN, 60)),
                visible("NO_SHOW, before endAt + 2h", () -> endedAt(Plant.of("NO_SHOW"),
                        -TWO_HOURS + MARGIN, 20)),
                hidden("NO_SHOW, after endAt + 2h", () -> endedAt(Plant.of("NO_SHOW"),
                        -TWO_HOURS - MARGIN, 20)),
                visible("CANCELLED after start (emergency), before endAt + 2h", () -> endedAt(
                        Plant.of("CANCELLED").reason("VENUE_EMERGENCY_CLOSURE"), -TWO_HOURS + MARGIN, 10)),
                hidden("CANCELLED after start (emergency), after endAt + 2h", () -> endedAt(
                        Plant.of("CANCELLED").reason("VENUE_EMERGENCY_CLOSURE"), -TWO_HOURS - MARGIN, 10)),
                // VENUE_RESPONSE_EXPIRED: venueResponseExpiredAt + 2h
                visible("VENUE_RESPONSE_EXPIRED, before expiry + 2h", () -> Plant.of("VENUE_RESPONSE_EXPIRED")
                        .expiredAt(minutesFromNow(-TWO_HOURS + MARGIN))),
                hidden("VENUE_RESPONSE_EXPIRED, after expiry + 2h", () -> Plant.of("VENUE_RESPONSE_EXPIRED")
                        .expiredAt(minutesFromNow(-TWO_HOURS - MARGIN))),
                // D1: after expiry + 2h it stays hidden, even when a later terminal point would show it
                hidden("D1: REJECTED_AFTER_EXPIRY after expiry + 2h (terminalAt + 2h still ahead)",
                        () -> Plant.of("REJECTED").reason("REJECTED_AFTER_EXPIRY")
                                .expiredAt(minutesFromNow(-TWO_HOURS - MARGIN)).terminalAt(minutesFromNow(-30))),
                hidden("D1: CANCELLED at start after expiry + 2h (endAt + 2h still ahead)",
                        () -> Plant.of("CANCELLED").reason("VENUE_DID_NOT_RESPOND_IN_TIME")
                                .startAt(minutesFromNow(-10)).expiredAt(minutesFromNow(-TWO_HOURS - MARGIN))
                                .terminalAt(minutesFromNow(-10))),
                // D1: terminal before expiry + 2h - visible until min(expiry + 2h, terminalAt + 2h)
                visible("D1: REJECTED_AFTER_EXPIRY before expiry + 2h", () -> Plant.of("REJECTED")
                        .reason("REJECTED_AFTER_EXPIRY").expiredAt(minutesFromNow(-60))
                        .terminalAt(minutesFromNow(-50))),
                hidden("D1: min(expiry + 2h, terminalAt + 2h) is the expiry point, now passed", () -> Plant
                        .of("REJECTED").reason("REJECTED_AFTER_EXPIRY")
                        .expiredAt(minutesFromNow(-TWO_HOURS - MARGIN))
                        .terminalAt(minutesFromNow(-TWO_HOURS - MARGIN + 1))),
                visible("D1: CANCELLED at start before expiry + 2h", () -> Plant.of("CANCELLED")
                        .reason("VENUE_DID_NOT_RESPOND_IN_TIME").startAt(minutesFromNow(-10))
                        .expiredAt(minutesFromNow(-60)).terminalAt(minutesFromNow(-10))),
                // a Late Confirm declined: the ordinary terminal-before-start row again
                visible("AWAITING declined, before terminalAt + 2h", () -> Plant.of("CANCELLED")
                        .reason("CUSTOMER_DECLINED_LATE_CONFIRM").expiredAt(minutesFromNow(-360))
                        .reopenedAt(minutesFromNow(-300)).terminalAt(minutesFromNow(-TWO_HOURS + MARGIN))),
                hidden("AWAITING declined, after terminalAt + 2h", () -> Plant.of("CANCELLED")
                        .reason("CUSTOMER_DECLINED_LATE_CONFIRM").expiredAt(minutesFromNow(-360))
                        .reopenedAt(minutesFromNow(-300)).terminalAt(minutesFromNow(-TWO_HOURS - MARGIN))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retentionMatrix")
    void everyReadRouteShowsThePhoneExactlyWhileTheRuleDoes(String name, Supplier<Plant> plant,
                                                            boolean visible) throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customerId = world.anotherCustomer();
        CreatedBooking created = creation.create(world.request(customerId, world.room, world.start(), 60));
        Plant planted = plant.get();
        BookingStates.plant(created.bookingId(), planted);
        String venuePhone = venuePhone(world);
        String customerPhone = customerPhone(customerId);
        String bucket = DisplayBucket.of(BookingStatus.valueOf(planted.status())).name().toLowerCase(Locale.ROOT);

        // the customer's routes: one booking, then My Bookings
        String detail = read(get("/api/v1/customer/bookings/" + created.bookingId()), customer(customerId));
        assertPhone(json.readTree(detail).at("/data/venue"), detail, venuePhone, visible);
        String list = read(get("/api/v1/customer/bookings").param("bucket", bucket), customer(customerId));
        assertPhone(item(json.readTree(list), created.bookingId()).get("venue"), list, venuePhone, visible);

        // the venue's routes: one booking, then the bookings list
        String venueDetail = read(get("/api/v1/venue/bookings/" + created.bookingId()), venue(world));
        assertPhone(json.readTree(venueDetail).at("/data/booking/customer"), venueDetail, customerPhone, visible);
        String venueList = read(get("/api/v1/venue/bookings").param("q", created.bookingId().toString()),
                venue(world));
        assertPhone(item(json.readTree(venueList), created.bookingId()).get("customer"), venueList, customerPhone,
                visible);
    }

    @Test
    void theMissedRequestsListLeavesThePhoneOutAfterExpiryPlusTwoHours() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID before = world.anotherCustomer();
        UUID after = world.anotherCustomer();
        Fixtures.Resource second = world.anotherRoom(room -> room.playStation(ConsoleType.PS5));
        CreatedBooking recent = creation.create(world.request(before, world.room, world.start(), 60));
        CreatedBooking old = creation.create(world.request(after, second, world.start(), 60));
        BookingStates.plant(recent.bookingId(), Plant.of("VENUE_RESPONSE_EXPIRED")
                .expiredAt(minutesFromNow(-TWO_HOURS + MARGIN)));
        BookingStates.plant(old.bookingId(), Plant.of("VENUE_RESPONSE_EXPIRED")
                .expiredAt(minutesFromNow(-TWO_HOURS - MARGIN)));

        String missed = read(get("/api/v1/venue/requests/missed"), venue(world));
        JsonNode body = json.readTree(missed);
        assertPhone(item(body, recent.bookingId()).get("customer"), null, customerPhone(before), true);
        assertPhone(item(body, old.bookingId()).get("customer"), missed, customerPhone(after), false);
    }

    @Test
    void aManualBookingKeepsTheWalkInContactTheVenueTypedWhateverItsAge() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String created = mvc.perform(post("/api/v1/venue/bookings")
                        .header("Authorization", "Bearer " + venue(world))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + world.room.id() + "\", \"playMode\": \"SINGLE\", "
                                + "\"durationMinutes\": 60, \"customerName\": \"Omar\", "
                                + "\"customerPhone\": \"01001234567\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(json.readTree(created).at("/data/bookingId").asText());
        // long completed: every retention point of an online booking is far behind
        BookingStates.plant(bookingId, Plant.of("COMPLETED").startAt(minutesFromNow(-600))
                .terminalAt(minutesFromNow(-540)));

        JsonNode detail = json.readTree(read(get("/api/v1/venue/bookings/" + bookingId), venue(world)));
        assertThat(detail.at("/data/booking/walkIn/phone").asText()).isEqualTo("+201001234567");
        assertThat(detail.at("/data/booking/walkIn/name").asText()).isEqualTo("Omar");
        assertThat(detail.at("/data/booking/customer").isNull()).isTrue();
        JsonNode list = json.readTree(read(get("/api/v1/venue/bookings").param("q", bookingId.toString()),
                venue(world)));
        assertThat(item(list, bookingId).at("/walkIn/phone").asText()).isEqualTo("+201001234567");
    }

    // ------------------------------------------------------------------------------------------------- replays

    @Test
    void aCancelReplayShowsTheVenuePhoneUntilTerminalAtPlusTwoHoursAndLeavesItOutAfter() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customerId = world.anotherCustomer();
        CreatedBooking created = creation.create(world.request(customerId, world.room, world.start(), 60));
        String key = UUID.randomUUID().toString();

        JsonNode first = json.readTree(read(cancel(created.bookingId(), key), customer(customerId)));
        assertThat(first.at("/data/venue/phone").asText()).isEqualTo(venuePhone(world));
        JsonNode replay = json.readTree(read(cancel(created.bookingId(), key), customer(customerId)));
        assertThat(replay.get("data")).isEqualTo(first.get("data"));

        // TEST-ONLY: the cancellation happened three hours ago, past terminalAt + 2h
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql(
                        "UPDATE booking.booking SET terminal_at = terminal_at - interval '3 hours' WHERE id = :id")
                .param("id", created.bookingId()).update());

        String after = read(cancel(created.bookingId(), key), customer(customerId));
        assertPhone(json.readTree(after).at("/data/venue"), after, venuePhone(world), false);
        assertThat(json.readTree(after).get("data")).as("only the phone is left out")
                .isEqualTo(without(first.get("data"), "venue"));
    }

    @Test
    void d1ARejectAfterExpiryReplayLeavesTheCustomerPhoneOutOnceExpiryPlusTwoHoursPassed() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customerId = world.anotherCustomer();
        CreatedBooking created = creation.create(world.request(customerId, world.room, world.start(), 60));
        BookingStates.plant(created.bookingId(), Plant.of("VENUE_RESPONSE_EXPIRED").expiredAt(minutesFromNow(-60)));
        String key = UUID.randomUUID().toString();
        MockHttpServletRequestBuilder reject = post("/api/v1/venue/bookings/" + created.bookingId()
                + "/reject-after-expiry").header(OpenApiConfig.IDEMPOTENCY_KEY, key);

        JsonNode first = json.readTree(read(reject, venue(world)));
        assertThat(first.at("/data/terminalReason").asText()).isEqualTo("REJECTED_AFTER_EXPIRY");
        assertThat(first.at("/data/customer/phone").asText()).as("min(expiry + 2h, terminalAt + 2h) ahead")
                .isEqualTo(customerPhone(customerId));

        // TEST-ONLY: the expiry was three hours ago; terminalAt + 2h is still ahead, but D1 caps it
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE booking.booking_history SET occurred_at = occurred_at - interval '3 hours'
                         WHERE booking_id = :id AND to_status = 'VENUE_RESPONSE_EXPIRED'""")
                .param("id", created.bookingId()).update());

        String after = read(reject, venue(world));
        assertPhone(json.readTree(after).at("/data/customer"), after, customerPhone(customerId), false);
        assertThat(json.readTree(after).get("data")).isEqualTo(without(first.get("data"), "customer"));
    }

    @Test
    void d1ACreationReplayLeavesTheVenuePhoneOutAfterExpiryPlusTwoHoursThoughALaterRejectionIsRecent()
            throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();
        String request = "{\"resourceId\": \"" + world.room.id() + "\", \"playMode\": \"SINGLE\", \"startAt\": \""
                + world.start() + "\", \"durationMinutes\": 60}";
        MockHttpServletRequestBuilder create = post("/api/v1/customer/bookings")
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key).contentType(MediaType.APPLICATION_JSON).content(request);
        JsonNode first = json.readTree(mvc.perform(create.header("Authorization", "Bearer "
                        + customer(world.customer.id())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID bookingId = UUID.fromString(first.at("/data/bookingId").asText());

        BookingStates.plant(bookingId, Plant.of("REJECTED").reason("REJECTED_AFTER_EXPIRY")
                .expiredAt(minutesFromNow(-TWO_HOURS - MARGIN)).terminalAt(minutesFromNow(-10)));

        String replay = mvc.perform(post("/api/v1/customer/bookings")
                        .header("Authorization", "Bearer " + customer(world.customer.id()))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, key)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertPhone(json.readTree(replay).at("/data/venue"), replay, venuePhone(world), false);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private static Arguments visible(String name, Supplier<Plant> plant) {
        return Arguments.of(name, plant, true);
    }

    private static Arguments hidden(String name, Supplier<Plant> plant) {
        return Arguments.of(name, plant, false);
    }

    /** A 60-minute booking that ended at {@code endedMinutesFromNow} and became terminal that long after its start. */
    private static Plant endedAt(Plant plant, long endedMinutesFromNow, long terminalAfterStartMinutes) {
        Instant end = minutesFromNow(endedMinutesFromNow);
        Instant start = end.minus(Duration.ofMinutes(60));
        return plant.startAt(start).terminalAt(start.plus(Duration.ofMinutes(terminalAfterStartMinutes)));
    }

    /** The {@code phone} member of {@code holder} is the number (visible) or absent - and nowhere in {@code raw}. */
    private static void assertPhone(JsonNode holder, String raw, String phone, boolean visible) {
        assertThat(phone).isNotBlank();
        if (visible) {
            assertThat(holder.get("phone")).as("visible").isNotNull();
            assertThat(holder.get("phone").asText()).isEqualTo(phone);
        } else {
            assertThat(holder.has("phone")).as("absent - not null, not masked, not empty (NFR-18)").isFalse();
            if (raw != null) {
                assertThat(raw).as("the number appears nowhere in the body").doesNotContain(phone)
                        .doesNotContain(phone.substring(3));
            }
        }
    }

    private static JsonNode item(JsonNode body, UUID bookingId) {
        JsonNode items = body.at("/data/items").isMissingNode() ? body.get("data") : body.at("/data/items");
        for (JsonNode item : items) {
            if (item.get("bookingId").asText().equals(bookingId.toString())) {
                return item;
            }
        }
        throw new AssertionError("booking " + bookingId + " not listed in " + body);
    }

    private static JsonNode without(JsonNode data, String holder) {
        JsonNode expected = data.deepCopy();
        ((ObjectNode) expected.get(holder)).remove("phone");
        return expected;
    }

    private String read(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder cancel(UUID bookingId, String key) {
        return post("/api/v1/customer/bookings/" + bookingId + "/cancel").header(OpenApiConfig.IDEMPOTENCY_KEY, key);
    }

    private String customer(UUID customerId) {
        return accessTokens.issue(AuthSubjectType.CUSTOMER, customerId);
    }

    private String venue(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }

    private static String venuePhone(OnlineBookingWorld world) {
        return OWNER.sql("SELECT phone FROM venue.venue WHERE id = :id").param("id", world.venue.id())
                .query(String.class).single();
    }

    static String customerPhone(UUID customerId) {
        return OWNER.sql("SELECT phone_e164 FROM iam.user_account WHERE id = :id").param("id", customerId)
                .query(String.class).single();
    }
}
