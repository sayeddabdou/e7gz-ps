package ps.e7gz.trust.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-71 - the trust routes of the Sprint 4 contract (E7-65; PO decisions D2, D5, D6 and D7) through the real security
 * chain, on Bans the real third no-show activated: the customer's Account Standing, the Platform Admin's trust
 * adjustment, a Ban's lift, shortening and correction, and the read-only Global Rules. Replaces this task's lines in
 * {@code Sprint4StubHttpTest}.
 */
@AutoConfigureMockMvc
class TrustRoutesHttpTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private static final String REASON = "{\"reason\":\"Venue confirmed the no-shows were a mistake.\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    BookingFlows flows;
    AttendanceFlows attendance;
    String admin;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());    // for the fixtures; each request has its own
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        admin = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------ account standing

    /** FR-TRS-15: a customer with no history stands at the start of the ladder, no ban; Verified Player progress. */
    @Test
    void aCustomerWithoutHistoryStandsAtTheStartOfTheLadder() throws Exception {
        UUID customer = flows.customer().nextActive(Duration.ofMinutes(5)).customerId();

        mvc.perform(get("/api/v1/customer/account-standing").header("Authorization", customerToken(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consecutiveNoShows").value(0))
                .andExpect(jsonPath("$.data.penaltyStage").value(0))
                .andExpect(jsonPath("$.data.currentThreshold").value(3))
                .andExpect(jsonPath("$.data.currentBan").value((Object) null))
                .andExpect(jsonPath("$.data.verifiedPlayer.qualification").value("NOT_QUALIFIED"))
                .andExpect(jsonPath("$.data.verifiedPlayer.completedBookings").value(0))
                .andExpect(jsonPath("$.data.verifiedPlayer.requiredBookings").value(10))
                .andExpect(jsonPath("$.data.verifiedPlayer.status").value("NOT_VERIFIED"))
                .andExpect(jsonPath("$.meta.serverTime").exists());
    }

    /** FRS §2.11: open to every customer state - an unverified account reads its standing too. */
    @Test
    void anUnverifiedCustomerMayReadTheStanding() throws Exception {
        UUID unverified = Fixtures.aUser().create().id();

        mvc.perform(get("/api/v1/customer/account-standing").header("Authorization", customerToken(unverified)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.penaltyStage").value(0))
                .andExpect(jsonPath("$.data.currentBan").value((Object) null));
        mvc.perform(get("/api/v1/customer/account-standing").header("Authorization", admin))
                .andExpect(status().isForbidden());                                 // arch 09 §1.1
    }

    /**
     * FR-TRS-15, FR-ACC-17: a banned customer may still read it - the one current ban with its start and end, the
     * stage advanced and the counter reset (FR-TRS-14).
     */
    @Test
    void aBannedCustomerSeesTheCurrentBan() throws Exception {
        Booking third = banned();
        UUID ban = activeBan(third.customerId());

        mvc.perform(get("/api/v1/customer/account-standing").header("Authorization",
                        customerToken(third.customerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consecutiveNoShows").value(0))
                .andExpect(jsonPath("$.data.penaltyStage").value(1))
                .andExpect(jsonPath("$.data.currentThreshold").value(2))
                .andExpect(jsonPath("$.data.currentBan.banId").value(ban.toString()))
                .andExpect(jsonPath("$.data.currentBan.penaltyStage").value(1))
                .andExpect(jsonPath("$.data.currentBan.startsAt").exists())
                .andExpect(jsonPath("$.data.currentBan.endsAt").exists());
    }

    // ------------------------------------------------------------------------------------------- trust adjustment

    /**
     * FR-TRS-27: the absolute values, effective now, answered as the standing after replay; the same key replays
     * without a second adjustment (D6); the ban is not touched by the adjustment.
     */
    @Test
    void theTrustAdjustmentSetsTheStandingOnce() throws Exception {
        Booking first = missed(flows.customer(), Duration.ofMinutes(20));
        UUID user = first.customerId();
        UUID key = UUID.randomUUID();
        String body = "{\"counterAfter\":0,\"penaltyStageAfter\":1,\"reason\":\"Closed venue.\"}";

        adjust(user, body, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.toString()))
                .andExpect(jsonPath("$.data.consecutiveNoShows").value(0))
                .andExpect(jsonPath("$.data.penaltyStage").value(1))
                .andExpect(jsonPath("$.data.currentThreshold").value(2))
                .andExpect(jsonPath("$.data.activeBan").value((Object) null));
        adjust(user, body, key).andExpect(status().isOk()).andExpect(jsonPath("$.data.penaltyStage").value(1));

        assertThat(OWNER.sql("""
                        SELECT count(*) FROM trust.trust_event
                         WHERE subject_user_id = :id AND event_type = 'ADMIN_TRUST_ADJUSTMENT'""")
                .param("id", user).query(Long.class).single()).isOne();
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE subject_id = :id AND action = 'ADMIN_TRUST_ADJUSTMENT' AND actor_type = 'ADMIN'
                           AND reason = 'Closed venue.'""")
                .param("id", user).query(Long.class).single()).isOne();
        assertThat(events(user, "TrustAdjusted")).isOne();
    }

    /** FR-TRS-13: counterAfter at or above the stage's threshold is 400 on counterAfter; nothing is written. */
    @Test
    void anAdjustmentAtTheThresholdIsRefusedOnCounterAfter() throws Exception {
        UUID user = missed(flows.customer(), Duration.ofMinutes(20)).customerId();

        adjust(user, "{\"counterAfter\":2,\"penaltyStageAfter\":1,\"reason\":\"Too many.\"}", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("counterAfter"));
        adjust(user, "{\"counterAfter\":0,\"penaltyStageAfter\":4,\"reason\":\"No such stage.\"}", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("penaltyStageAfter"));
        adjust(user, "{\"counterAfter\":0,\"penaltyStageAfter\":0}", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("reason"));
        adjust(UUID.randomUUID(), "{\"counterAfter\":0,\"penaltyStageAfter\":0,\"reason\":\"Who?\"}",
                UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(events(user, "TrustAdjusted")).isZero();
    }

    // ---------------------------------------------------------------------------------------------------- bans

    /**
     * FR-TRS-23, FR-TRS-27: LIFTED now, the restriction stops at once - the customer can book again - and a second lift
     * (a new key) is 409; the bookings the ban cancelled do not return (FR-TRS-25).
     */
    @Test
    void liftingEndsTheBanNowAndOnlyOnce() throws Exception {
        BookingFlows.Customer customer = flows.customer();
        Booking third = banned(customer);
        UUID user = third.customerId();
        UUID ban = activeBan(user);

        ban(user, ban, "lift", REASON, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.banId").value(ban.toString()))
                .andExpect(jsonPath("$.data.userId").value(user.toString()))
                .andExpect(jsonPath("$.data.status").value("LIFTED"))
                .andExpect(jsonPath("$.data.penaltyStage").value(1))
                .andExpect(jsonPath("$.data.qualificationAt").exists());
        notAllowed(ban(user, ban, "lift", REASON, UUID.randomUUID()));
        notAllowed(ban(user, ban, "shorten", shorten(Instant.now().plus(Duration.ofHours(1))), UUID.randomUUID()));

        assertThat(projectionBan(user)).isNull();
        assertThat(events(user, "BanLifted")).isOne();
        assertThat(OWNER.sql("""
                        SELECT status FROM technical.scheduled_job WHERE idempotency_key = :key""")
                .param("key", "BAN_EXPIRY:" + ban).query(String.class).single()).isEqualTo("SUPERSEDED");
        customer.nextPending(Duration.ofHours(6));                                  // FR-ACC-17: may book again
    }

    /**
     * FR-TRS-27: the end brought forward, still ACTIVE until then; endsAt in the past or not before the current end is
     * 400 on endsAt; T10 moves to the new end.
     */
    @Test
    void shorteningBringsTheEndForward() throws Exception {
        UUID user = banned().customerId();
        UUID ban = activeBan(user);
        Instant end = endsAt(ban);
        Instant newEnd = end.minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);

        ban(user, ban, "shorten", shorten(Instant.now().minus(Duration.ofMinutes(1))), UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("endsAt"));
        ban(user, ban, "shorten", shorten(end.plus(Duration.ofDays(1))), UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("endsAt"));

        ban(user, ban, "shorten", shorten(newEnd), UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.endsAt").value(newEnd.toString()));

        assertThat(endsAt(ban)).isEqualTo(newEnd);
        assertThat(projectionBan(user)).isEqualTo(ban);
        assertThat(OWNER.sql("""
                        SELECT due_at FROM technical.scheduled_job
                         WHERE subject_id = :ban AND type = 'BAN_EXPIRY' AND status = 'PENDING'""")
                .param("ban", ban).query(OffsetDateTime.class).single().toInstant()).isEqualTo(newEnd);
        assertThat(events(user, "BanShortened")).isOne();
    }

    /**
     * D2, D5 (decided for E7-71): corrected trust history still supports the ban - the three no-shows stand - so
     * {@code /correct} is 409 and the ban stays ACTIVE; correcting the attendance is what ends it.
     */
    @Test
    void correctingABanTheHistoryStillSupportsIsRefused() throws Exception {
        UUID user = banned().customerId();
        UUID ban = activeBan(user);

        notAllowed(ban(user, ban, "correct", REASON, UUID.randomUUID()));

        assertThat(activeBan(user)).isEqualTo(ban);
    }

    /** A ban of another customer, or an unknown ban, is 404; only a Platform Admin may call; the key is required. */
    @Test
    void anotherCustomersBanIsNotFoundAndOnlyAnAdminMayAct() throws Exception {
        UUID user = banned().customerId();
        UUID ban = activeBan(user);
        UUID other = flows.customer().nextActive(Duration.ofMinutes(5)).customerId();

        ban(other, ban, "lift", REASON, UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        ban(user, UUID.randomUUID(), "lift", REASON, UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(post(banPath(user, ban, "lift")).header("Authorization", customerToken(user))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        mvc.perform(post(banPath(user, ban, "lift")).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));             // no Idempotency-Key

        assertThat(activeBan(user)).isEqualTo(ban);
    }

    // ---------------------------------------------------------------------------------------------- global rules

    /** D7, FR-TRS-13: the ladder from the database functions every ban uses, read-only. */
    @Test
    void theGlobalRulesAreTheLadderTheBansUse() throws Exception {
        mvc.perform(get("/api/v1/admin/global-rules").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noShowLadder.length()").value(4))
                .andExpect(jsonPath("$.data.noShowLadder[0].penaltyStage").value(0))
                .andExpect(jsonPath("$.data.noShowLadder[0].consecutiveNoShows").value(3))
                .andExpect(jsonPath("$.data.noShowLadder[0].banDuration").value("P7D"))
                .andExpect(jsonPath("$.data.noShowLadder[0].recurring").value(false))
                .andExpect(jsonPath("$.data.noShowLadder[1].consecutiveNoShows").value(2))
                .andExpect(jsonPath("$.data.noShowLadder[1].banDuration").value("P1M"))
                .andExpect(jsonPath("$.data.noShowLadder[2].consecutiveNoShows").value(2))
                .andExpect(jsonPath("$.data.noShowLadder[2].banDuration").value("P2M"))
                .andExpect(jsonPath("$.data.noShowLadder[3].penaltyStage").value(3))
                .andExpect(jsonPath("$.data.noShowLadder[3].consecutiveNoShows").value(3))
                .andExpect(jsonPath("$.data.noShowLadder[3].banDuration").value("P2M"))
                .andExpect(jsonPath("$.data.noShowLadder[3].recurring").value(true));
        mvc.perform(put("/api/v1/admin/global-rules").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());                                       // NFR-37: no write
    }

    // ------------------------------------------------------------------------------------------------- support

    private Booking banned() {
        return banned(flows.customer());
    }

    private Booking banned(BookingFlows.Customer customer) {
        missed(customer, Duration.ofMinutes(20));
        missed(customer, Duration.ofMinutes(20));
        return missed(customer, Duration.ofMinutes(20));
    }

    private Booking missed(BookingFlows.Customer customer, Duration startedAgo) {
        Booking booking = customer.nextActive(startedAgo);
        attendance.noShow(booking.venueAccountId(), booking.bookingId());
        return booking;
    }

    private String customerToken(UUID customer) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customer);
    }

    private ResultActions adjust(UUID user, String body, UUID key) throws Exception {
        return mvc.perform(post("/api/v1/admin/users/" + user + "/trust-adjustment").header("Authorization", admin)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ban(UUID user, UUID ban, String action, String body, UUID key) throws Exception {
        return mvc.perform(post(banPath(user, ban, action)).header("Authorization", admin)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String banPath(UUID user, UUID ban, String action) {
        return "/api/v1/admin/users/" + user + "/bans/" + ban + "/" + action;
    }

    private static String shorten(Instant endsAt) {
        return "{\"endsAt\":\"" + endsAt + "\",\"reason\":\"First offence; agreed with the venue.\"}";
    }

    private static void notAllowed(ResultActions result) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
    }

    private static UUID activeBan(UUID user) {
        return OWNER.sql("SELECT id FROM trust.ban_instance WHERE user_id = :id AND status = 'ACTIVE'")
                .param("id", user).query(UUID.class).single();
    }

    private static Instant endsAt(UUID ban) {
        return OWNER.sql("SELECT ends_at FROM trust.ban_instance WHERE id = :id").param("id", ban)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static UUID projectionBan(UUID user) {
        String id = OWNER.sql("""
                        SELECT COALESCE(CAST(active_ban_instance_id AS text), '-') FROM trust.trust_projection
                         WHERE user_id = :id""")
                .param("id", user).query(String.class).single();
        return id.equals("-") ? null : UUID.fromString(id);
    }

    private static long events(UUID user, String type) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND payload ->> 'userId' = :user""")
                .param("type", type).param("user", user.toString()).query(Long.class).single();
    }
}
