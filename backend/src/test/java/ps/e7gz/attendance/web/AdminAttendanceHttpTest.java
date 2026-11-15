package ps.e7gz.attendance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
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
 * E7-70 - the four Platform Admin attendance routes of the Sprint 4 contract (E7-65, PO decisions D1, D5 and D6)
 * through the real security chain: each answers {@code AdminAttendance}; a state that does not allow the action is
 * {@code 409 ADMIN_ACTION_NOT_ALLOWED}; the reason and the Idempotency-Key are required, and a retry with the same key
 * replays the first answer without acting twice. Replaces this task's lines in {@code Sprint4StubHttpTest}.
 */
@AutoConfigureMockMvc
class AdminAttendanceHttpTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private static final String REASON = "{\"reason\":\"Venue confirmed the mistake.\"}";

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

    @Test
    void reverseCheckInAnswersTheAttendanceWithoutTheReversedEvidence() throws Exception {
        Booking booking = checkedIn();

        call(booking, "reverse-check-in", REASON, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingId").value(booking.bookingId().toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.terminalReason").value((Object) null))
                .andExpect(jsonPath("$.data.attendanceResult").value((Object) null))
                .andExpect(jsonPath("$.data.checkedInAt").value((Object) null))
                .andExpect(jsonPath("$.data.latePresentAt").value((Object) null))
                .andExpect(jsonPath("$.data.noShowAt").value((Object) null));
        assertThat(events(booking, "CHECK_IN_REVERSED")).isOne();
    }

    @Test
    void reverseLatePresentAnswersTheAttendance() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));
        attendance.latePresent(booking.venueAccountId(), booking.bookingId());

        call(booking, "reverse-late-present", REASON, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.latePresentAt").value((Object) null));
        assertThat(events(booking, "LATE_PRESENT_REVERSED")).isOne();
    }

    /**
     * FR-ATT-12 and FR-ATT-13: the reversal before the original endAt leaves the booking CANCELLED under
     * ADMIN_NO_SHOW_REVERSAL with the penalty removed; that the customer attended is then recorded with
     * {@code correct}, which changes the result and never the status (FR-ATT-09).
     */
    @Test
    void reverseNoShowThenCorrectRecordsTheChosenOutcome() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));
        attendance.noShow(booking.venueAccountId(), booking.bookingId());

        call(booking, "reverse-no-show", REASON, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.terminalReason").value("ADMIN_NO_SHOW_REVERSAL"))
                .andExpect(jsonPath("$.data.attendanceResult").value("ATTENDANCE_UNCONFIRMED"))
                .andExpect(jsonPath("$.data.noShowAt").value((Object) null));

        call(booking, "correct", "{\"correctedOutcome\":\"ATTENDED\",\"reason\":\"CCTV shows the session.\"}",
                UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.attendanceResult").value("ATTENDED"));
        assertThat(OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single()).isZero();
    }

    /** ADR-015, PO decision D6: the same key replays the first answer and acts once; another body is refused. */
    @Test
    void aRetryWithTheSameKeyReplaysAndActsOnce() throws Exception {
        Booking booking = checkedIn();
        UUID key = UUID.randomUUID();

        call(booking, "reverse-check-in", REASON, key).andExpect(status().isOk());
        call(booking, "reverse-check-in", REASON, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.checkedInAt").value((Object) null));
        call(booking, "reverse-check-in", "{\"reason\":\"Another reason.\"}", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(events(booking, "CHECK_IN_REVERSED")).isOne();
    }

    /** PO decision D5: no such evidence, a Manual Booking or the wrong state is 409 ADMIN_ACTION_NOT_ALLOWED. */
    @Test
    void aStateThatDoesNotAllowTheActionIsRefused() throws Exception {
        Booking untouched = flows.active(Duration.ofMinutes(20));
        for (String action : new String[] {"reverse-check-in", "reverse-late-present", "reverse-no-show"}) {
            notAllowed(call(untouched, action, REASON, UUID.randomUUID()));
        }
        notAllowed(call(untouched, "correct", "{\"correctedOutcome\":\"ATTENDED\",\"reason\":\"Not resolved yet.\"}",
                UUID.randomUUID()));

        Booking reversed = checkedIn();
        call(reversed, "reverse-check-in", REASON, UUID.randomUUID()).andExpect(status().isOk());
        notAllowed(call(reversed, "reverse-check-in", REASON, UUID.randomUUID()));          // a new key: a new try

        notAllowed(call(flows.walkIn(), "reverse-check-in", REASON, UUID.randomUUID()));    // FR-MAN-04
    }

    @Test
    void theRequestIsValidatedBeforeAnythingIsWritten() throws Exception {
        Booking booking = checkedIn();

        call(booking, "correct", "{\"correctedOutcome\":\"NOT_APPLICABLE\",\"reason\":\"Never chosen.\"}",
                UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("correctedOutcome"));
        call(booking, "correct", "{\"reason\":\"No outcome.\"}", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("correctedOutcome"));
        call(booking, "reverse-check-in", "{}", UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("reason"));
        mvc.perform(post(path(booking.bookingId(), "reverse-check-in")).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));               // no Idempotency-Key

        assertThat(events(booking, "CHECK_IN_REVERSED")).isZero();
    }

    @Test
    void anUnknownBookingIsNotFoundAndOnlyAnAdminMayCall() throws Exception {
        Booking booking = checkedIn();

        mvc.perform(post(path(UUID.randomUUID(), "reverse-check-in")).header("Authorization", admin)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        String customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, booking.customerId());
        mvc.perform(post(path(booking.bookingId(), "reverse-check-in")).header("Authorization", customer)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(events(booking, "CHECK_IN_REVERSED")).isZero();
    }

    // ------------------------------------------------------------------------------------------------- support

    /** An ACTIVE Online Booking with a check-in recorded by the real venue command. */
    private Booking checkedIn() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        return booking;
    }

    private ResultActions call(Booking booking, String action, String body, UUID key) throws Exception {
        return mvc.perform(post(path(booking.bookingId(), action)).header("Authorization", admin)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void notAllowed(ResultActions result) throws Exception {
        result.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
    }

    private static String path(UUID bookingId, String action) {
        return "/api/v1/admin/bookings/" + bookingId + "/attendance/" + action;
    }

    /** The Admin Booking Events of that type on the booking: how often the action was really done. */
    private static long events(Booking booking, String type) {
        return OWNER.sql("""
                        SELECT count(*) FROM booking.booking_history
                         WHERE booking_id = :id AND entry_kind = 'BOOKING_EVENT' AND event_type = :type
                           AND actor_type = 'ADMIN'""")
                .param("id", booking.bookingId()).param("type", type).query(Long.class).single();
    }
}
