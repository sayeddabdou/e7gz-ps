package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.notification.internal.NotificationFlows;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-61 - the Phase 3 exit journeys J2 to J5 end to end (arch 14: "J2, J3, J4, J5 complete"; PRD v1.1 J2-J5). Every
 * action a person takes goes through its real route - book, accept, Late Confirm and its answer, check-in, Mark Present
 * - Late, no-show, walk-in - and every timer through the real deadline worker: T1 (E7-47), T4 (E7-60) and T5 (E7-61).
 * Notifications come from E7-55's one consumer over the real outbox.
 *
 * <p>Only the passage of time is planted, because a journey that spans a deadline cannot wait for it: a request's stored
 * response deadline is moved back to its creation, inside a command and through the guards ({@code Deadlines.move},
 * E7-45), with its T1 made due, so the real T1 expires it; a booking accepted for later has its interval moved to have
 * begun in the past with its T4 still waiting ({@link BookingStates#startOverdue}), so the real T4 starts it; and an
 * ACTIVE booking is moved back whole past its end ({@link BookingFlows#pastItsEnd}) after its evidence was recorded, so
 * the real T5 completes it. Nothing else is written past the commands.
 *
 * <p><b>J4 is PARTIAL.</b> Its third-consecutive-no-show step - the 7-day ban, the cancellation of the customer's pending
 * and future confirmed bookings, an active session left alone - is E7-71's (O14, the ban cascade). Until E7-71 the
 * database refuses that third no-show whole ({@code trust.assert_user_integrity}: a replayed qualification without its
 * exactly-once operational consequence), as E7-59's
 * {@code AttendanceIntegrationTest.theThirdConsecutiveNoShowNeedsE771sBanAndUntilThenRollsBackWhole} pins; E7-71 replaces
 * that test and completes J4 here.
 */
@AutoConfigureMockMvc
class Phase3JourneysHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    BookingFlows flows;
    NotificationFlows notifications;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        notifications = NotificationFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /**
     * J2 (PRD): the venue misses its ten minutes; the customer sees the booking under Cancelled; staff send a Late
     * Confirm from Missed / Expired Requests; the customer is told, accepts, and it is CONFIRMED under the same Booking
     * ID with its whole history.
     */
    @Test
    void j2TheVenueIsSlowAndTheCustomersEveningIsSaved() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID id = book(world, world.start(), 60);
        String t1 = VenueAnswers.venueResponseExpiryKey(id);
        assertThat(DueJobs.job(t1).status()).as("the creation scheduled T1").isEqualTo("PENDING");
        DueJobs expiry = DueJobs.of(context, new VenueResponseExpiryHandler(runner, store, answers));
        // the ten minutes pass (planted): the stored deadline goes back to the request's creation, as far as it can go,
        // and T1 is made due; then the real worker fires it (E7-47's t1FiredLateExpiresAsOfTheDeadlineNotOfWhenItRan)
        Instant deadline = runner.run(TestCommands.under(flows.scope(ref(world, id)), command -> {
            Instant at = createdAt(id);
            Deadlines.move(command, "booking.booking.venue_response_expires_at", id, at);
            expiry.makeDue(command, t1, at);
            return at;
        }));
        assertThat(expiry.fire(t1)).as("T1 settled by the real worker").isTrue();
        assertThat(DueJobs.job(t1).status()).isEqualTo("DONE");
        assertThat(VenueAnswerDb.history(id).getLast()).satisfies(row -> {
            assertThat(row.fromStatus() + "->" + row.toStatus()).isEqualTo("PENDING->VENUE_RESPONSE_EXPIRED");
            assertThat(row.actorType()).isEqualTo("SYSTEM");
            assertThat(row.occurredAt()).as("as of its stored deadline").isEqualTo(deadline);
        });

        JsonNode expired = read(get("/api/v1/customer/bookings/" + id), customer(world));
        assertThat(expired.at("/data/status").asText()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(expired.at("/data/displayBucket").asText()).as("shown under Cancelled").isEqualTo("cancelled");
        assertThat(read(get("/api/v1/venue/requests/missed"), venue(world)).at("/data").findValuesAsText("bookingId"))
                .contains(id.toString());

        assertThat(perform(post("/api/v1/venue/bookings/" + id + "/late-confirm"), venue(world))
                .andExpect(status().isOk()).andReturn()).isNotNull();
        notifications.drain();
        assertThat(notifications.notifications(id))
                .containsOnlyOnce("CUSTOMER LATE_CONFIRMATION_RECEIVED " + world.customer.id());

        JsonNode accepted = body(perform(post("/api/v1/customer/bookings/" + id + "/late-confirm/accept"),
                customer(world)).andExpect(status().isOk()));
        assertThat(accepted.at("/data/status").asText()).isEqualTo("CONFIRMED");
        JsonNode confirmed = read(get("/api/v1/customer/bookings/" + id), customer(world));
        assertThat(confirmed.at("/data/bookingId").asText()).as("the same Booking ID").isEqualTo(id.toString());
        assertThat(confirmed.at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.at("/data/history").findValuesAsText("toStatus")).as("its full history").containsExactly(
                "PENDING", "VENUE_RESPONSE_EXPIRED", "AWAITING_CUSTOMER_CONFIRMATION", "CONFIRMED");
        assertThat(OWNER.sql("SELECT count(*) FROM booking.booking WHERE customer_id = :id")
                .param("id", world.customer.id()).query(Integer.class).single()).as("no second booking").isOne();
        notifications.drain();
        assertThat(notifications.notifications(id))
                .containsOnlyOnce("CUSTOMER LATE_CONFIRMATION_RECEIVED " + world.customer.id())
                .containsOnlyOnce("VENUE VENUE_LATE_CONFIRM_ACCEPTED " + world.venue.id());
    }

    /**
     * J3 (PRD): the customer arrives; staff check them in within the first fifteen minutes; at the end the booking
     * completes ATTENDED - the launch reward of 200 points (the database's configuration), +1 toward Verified Player and
     * the consecutive no-show counter back to zero (an earlier no-show of theirs, before this booking, counted one).
     * The initial VERIFIED transition at ten (E7-72) and the points notification (E7-66) are not part of it.
     */
    @Test
    void j3PlayAndGetCredit() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID earlier = book(world, world.start(), 120);
        accept(world, earlier);
        started(world, earlier, Duration.ofMinutes(90));
        noShow(world, earlier).andExpect(status().isOk());
        assertThat(consecutiveNoShows(world)).isOne();

        UUID id = book(world, world.start().plus(Duration.ofHours(3)), 60);
        accept(world, id);
        Booking booking = started(world, id, Duration.ofMinutes(5));
        JsonNode checkedIn = body(attendance(world, id, "check-in").andExpect(status().isOk()));
        assertThat(checkedIn.at("/data/status").asText()).isEqualTo("ACTIVE");
        booking = flows.pastItsEnd(booking, Duration.ofMinutes(10));
        assertThat(booking.startAt()).as("after the earlier booking's start").isAfter(startAt(earlier));

        assertThat(flows.fireCompletion(booking)).isTrue();                                // T5

        JsonNode completed = read(get("/api/v1/customer/bookings/" + id), customer(world));
        assertThat(completed.at("/data/status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.at("/data/displayBucket").asText()).isEqualTo("completed");
        assertThat(result(id)).isEqualTo("ATTENDED");
        assertThat(points(id)).containsExactly("ATTENDED_COMPLETION 200");
        assertThat(balance(world)).isEqualTo(200);
        assertThat(completedAttended(world)).as("+1 toward Verified Player").isOne();
        assertThat(consecutiveNoShows(world)).as("the counter resets").isZero();
        notifications.drain();
        assertThat(notifications.notifications(id)).as("no completion notice here; POINTS_EARNED is E7-66's")
                .noneMatch(n -> n.contains("ATTENDANCE_UNCONFIRMED_COMPLETION") || n.contains("POINTS_EARNED"));
    }

    /**
     * J4 (PRD), PARTIAL - the steps E7-61 can complete: fifteen minutes past the start the room is empty; staff mark the
     * no-show; the remaining interval is released at once and sold to a walk-in; the customer's counter increments; the
     * booking's T5 never completes it. The third-no-show ban and cascade are E7-71's (see the class comment).
     */
    @Test
    void j4NobodyShowsUpPartialUntilE771() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID id = book(world, world.start(), 60);
        accept(world, id);
        Booking booking = started(world, id, Duration.ofMinutes(20));
        assertThat(body(walkIn(world).andExpect(status().isConflict())).at("/error/code").asText())
                .as("the room is taken before the no-show").isEqualTo("SLOT_NO_LONGER_AVAILABLE");

        JsonNode noShow = body(noShow(world, id).andExpect(status().isOk()));
        assertThat(noShow.at("/data/status").asText()).isEqualTo("NO_SHOW");

        Instant noShowAt = OWNER.sql("SELECT no_show_at FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", id).query(OffsetDateTime.class).single().toInstant();
        assertThat(OWNER.sql("""
                        SELECT actual_reservation_release_at FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND NOT active_flag""")
                .param("id", id).query(OffsetDateTime.class).single().toInstant())
                .as("released at the no-show instant").isEqualTo(noShowAt);
        JsonNode walkIn = body(walkIn(world).andExpect(status().isCreated()));
        assertThat(walkIn.at("/data/status").asText()).as("the remaining interval sold to a walk-in")
                .isEqualTo("ACTIVE");
        assertThat(consecutiveNoShows(world)).as("the counter increments").isOne();

        assertThat(flows.fireCompletion(booking)).as("its T5 was withdrawn by the no-show").isFalse();
        assertThat(DueJobs.job(BookingFlows.bookingCompleteKey(id)).status()).isEqualTo("SUPERSEDED");
        assertThat(read(get("/api/v1/customer/bookings/" + id), customer(world)).at("/data/status").asText())
                .isEqualTo("NO_SHOW");
        assertThat(VenueAnswerDb.outboxTypes(id)).doesNotContain(IntegrationEvents.BOOKING_COMPLETED);
        notifications.drain();
        assertThat(notifications.notifications(id)).containsOnlyOnce("CUSTOMER NO_SHOW_RECORDED " + world.customer.id());
    }

    /**
     * J5 (PRD): the customer walks in after the check-in window; check-in is refused; staff use Mark Present - Late,
     * which blocks the no-show; at the end the booking completes ATTENDANCE_UNCONFIRMED - no reward, no reset of the
     * counter (an earlier no-show still counts), no Verified Player progress - and the customer is told once.
     */
    @Test
    void j5TheCustomerIsLateButPresent() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID earlier = book(world, world.start(), 60);
        accept(world, earlier);
        started(world, earlier, Duration.ofMinutes(20));
        noShow(world, earlier).andExpect(status().isOk());

        UUID id = book(world, world.start().plus(Duration.ofHours(3)), 60);
        accept(world, id);
        Booking booking = started(world, id, Duration.ofMinutes(20));
        assertThat(body(attendance(world, id, "check-in").andExpect(status().isUnprocessableEntity()))
                .at("/error/code").asText()).isEqualTo("CHECK_IN_WINDOW_CLOSED");
        attendance(world, id, "late-present").andExpect(status().isOk());
        assertThat(body(noShow(world, id).andExpect(status().isUnprocessableEntity())).at("/error/code").asText())
                .as("Late Present blocks the no-show").isEqualTo("NO_SHOW_NOT_AVAILABLE");
        booking = flows.pastItsEnd(booking, Duration.ofMinutes(10));

        assertThat(flows.fireCompletion(booking)).isTrue();                                // T5

        assertThat(read(get("/api/v1/customer/bookings/" + id), customer(world)).at("/data/status").asText())
                .isEqualTo("COMPLETED");
        assertThat(result(id)).isEqualTo("ATTENDANCE_UNCONFIRMED");
        assertThat(points(id)).as("no reward").isEmpty();
        assertThat(balance(world)).isZero();
        assertThat(consecutiveNoShows(world)).as("not reset").isOne();
        assertThat(completedAttended(world)).as("no Verified Player progress").isZero();
        notifications.drain();
        notifications.drain();
        assertThat(notifications.notifications(id))
                .containsOnlyOnce("CUSTOMER ATTENDANCE_UNCONFIRMED_COMPLETION " + world.customer.id());
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private UUID book(OnlineBookingWorld world, Instant start, int minutes) throws Exception {
        JsonNode created = body(perform(post("/api/v1/customer/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + world.room.id() + "\", \"playMode\": \"SINGLE\", \"startAt\": \""
                                + start + "\", \"durationMinutes\": " + minutes + "}"), customer(world))
                .andExpect(status().isCreated()));
        assertThat(created.at("/data/status").asText()).isEqualTo("PENDING");
        return UUID.fromString(created.at("/data/bookingId").asText());
    }

    private void accept(OnlineBookingWorld world, UUID id) throws Exception {
        perform(post("/api/v1/venue/bookings/" + id + "/accept"), venue(world)).andExpect(status().isOk());
    }

    /**
     * The accepted booking's interval moved to have begun {@code ago} before the database clock with its T4 still
     * waiting (the passage of time, planted), then the real T4 starts it: CONFIRMED -> ACTIVE, with its T5 at endAt.
     */
    private Booking started(OnlineBookingWorld world, UUID id, Duration ago) {
        BookingStates.startOverdue(id, OnlineBookingWorld.dbNow().minus(ago));
        Booking booking = ref(world, id);
        assertThat(flows.fireStart(booking)).isTrue();
        assertThat(VenueAnswerDb.booking(id).status()).isEqualTo("ACTIVE");
        return booking;
    }

    /** A walk-in of an hour on the room, starting now (E7-48, as J8 records one). */
    private ResultActions walkIn(OnlineBookingWorld world) throws Exception {
        return perform(post("/api/v1/venue/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\": \"" + world.room.id() + "\", \"playMode\": \"SINGLE\", "
                        + "\"durationMinutes\": 60, \"customerName\": \"Omar\", "
                        + "\"customerPhone\": \"01001234567\"}"), venue(world));
    }

    private ResultActions noShow(OnlineBookingWorld world, UUID id) throws Exception {
        return attendance(world, id, "no-show");
    }

    private ResultActions attendance(OnlineBookingWorld world, UUID id, String action) throws Exception {
        return perform(post("/api/v1/venue/bookings/" + id + "/" + action), venue(world));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()));
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String token) throws Exception {
        return body(mvc.perform(request.header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Booking ref(OnlineBookingWorld world, UUID id) {
        return new Booking(id, world.customer.id(), world.venue.id(), world.venue.name(), world.account.id(),
                world.room.id(), startAt(id), null);
    }

    private String customer(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.CUSTOMER, world.customer.id());
    }

    private String venue(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }

    private static Instant createdAt(UUID id) {
        return OWNER.sql("SELECT created_at FROM booking.booking WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static Instant startAt(UUID id) {
        return OWNER.sql("SELECT start_at FROM booking.booking WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static String result(UUID id) {
        return OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id").param("id", id)
                .query(String.class).single();
    }

    private static List<String> points(UUID id) {
        return OWNER.sql("SELECT cause || ' ' || amount FROM loyalty.points_transaction WHERE booking_id = :id")
                .param("id", id).query(String.class).list();
    }

    private static int balance(OnlineBookingWorld world) {
        return OWNER.sql("SELECT balance FROM loyalty.points_account WHERE user_id = :id")
                .param("id", world.customer.id()).query(Integer.class).single();
    }

    private static int consecutiveNoShows(OnlineBookingWorld world) {
        return OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", world.customer.id()).query(Integer.class).single();
    }

    private static int completedAttended(OnlineBookingWorld world) {
        return OWNER.sql("SELECT completed_attended_count FROM trust.trust_projection WHERE user_id = :id")
                .param("id", world.customer.id()).query(Integer.class).single();
    }
}
