package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.venue.VenueAccountFixtures.OWNER;
import static ps.e7gz.venue.VenueAccountFixtures.auditCount;
import static ps.e7gz.venue.VenueAccountFixtures.visiblePhoto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.identity.api.OwnerPasswordAttempts;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-121 (PCR-012, amending PCR-006): 3 short lockouts of a venue account's Owner PIN within 24 hours lock every
 * Owner-PIN action until the Platform Admin resets the PIN - {@code 423 OWNER_PASSWORD_LOCKED} for a right and a wrong
 * PIN alike, after the 15 minutes too, with one audit row and one Platform Admin alert when it starts. The lockouts are
 * counted in fixed 24-hour windows and the reset starts the count again (PO decisions 17 Nov 2026). Command instants
 * come from the database clock, so the 15 minutes and the 24 hours pass by moving the counter rows back.
 */
@AutoConfigureMockMvc
class OwnerPinLongLockHttpTest extends IntegrationTest {

    private static final String CHANGE = "/api/v1/venue/owner-password";
    private static final String ACCOUNTS = "/api/v1/admin/venue-accounts/";
    private static final String OWNER_PIN = "1234";
    private static final String WRONG = "0000";
    private static final String NEW_PIN = "9753";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    PasswordEncoder passwords;

    private VenueAccountFixtures.VenueWithAccount venue;
    private String token;

    @BeforeEach
    void aVenueWithItsAccount() {
        venue = VenueAccountFixtures.venueWithAccount(passwords, OWNER_PIN);
        token = accessTokens.issue(AuthSubjectType.VENUE, venue.accountId());
    }

    /** Done when: refused even with the right PIN and after the 15 minutes; one alert; the admin reset ends it. */
    @Test
    void threeLockoutsWithin24HoursLockThePinUntilTheAdminResetsIt() throws Exception {
        lockOut();
        shortLockoutPassed();
        lockOut();
        shortLockoutPassed();
        lockOut();

        // the long lock wins over the 15-minute lockout still running, and outlasts it
        changePin(OWNER_PIN, "2468").andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_LOCKED"));
        shortLockoutPassed();
        changePin(OWNER_PIN, "2468").andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_LOCKED"));
        changePin(WRONG, "2468").andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_LOCKED"));
        lockoutWindowStartedAgo("2 days");                          // time never ends it
        changePin(OWNER_PIN, "2468").andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_LOCKED"));

        assertThat(secretVersion()).isZero();
        assertThat(alerts()).isEqualTo(1);
        assertThat(auditCount("OWNER_PASSWORD_LONG_LOCKED", venue.accountId())).isEqualTo(1);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :account
                           AND payload ->> 'longLocked' = 'true'""")
                .param("account", venue.accountId()).query(Long.class).single()).as("refused while long locked")
                .isEqualTo(4);
        assertThat(failuresCounted()).as("refusals while locked are not counted")
                .isEqualTo(OwnerPasswordAttempts.MAX_FAILURES);

        adminResetsThePin().andExpect(status().isNoContent());

        changePin(NEW_PIN, "2468").andExpect(status().isNoContent());
        assertThat(alerts()).isEqualTo(1);
    }

    /** Done when: two lockouts within 24 hours are no long lock. */
    @Test
    void twoLockoutsWithin24HoursAreNoLongLock() throws Exception {
        lockOut();
        shortLockoutPassed();
        lockOut();
        changePin(OWNER_PIN, "2468").andExpect(status().is(429))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        shortLockoutPassed();

        changePin(OWNER_PIN, "2468").andExpect(status().isNoContent());
        assertThat(alerts()).isZero();
    }

    /** Done when: three lockouts spread over more than 24 hours are no long lock (the window starts again). */
    @Test
    void threeLockoutsSpreadOverMoreThan24HoursAreNoLongLock() throws Exception {
        lockOut();
        shortLockoutPassed();
        lockOut();
        shortLockoutPassed();
        lockoutWindowStartedAgo("24 hours 1 second");               // the first two were more than 24 hours ago

        lockOut();
        shortLockoutPassed();

        changePin(OWNER_PIN, "2468").andExpect(status().isNoContent());
        assertThat(alerts()).isZero();
    }

    /** PO decision 17 Nov 2026: the admin reset starts the count again, so lockouts before it do not count. */
    @Test
    void theAdminResetStartsTheCountAgain() throws Exception {
        lockOut();
        shortLockoutPassed();
        lockOut();
        adminResetsThePin().andExpect(status().isNoContent());

        changePin(NEW_PIN, "8642").andExpect(status().isNoContent());    // it also ended the running 15-minute lockout
        lockOut("8642");
        shortLockoutPassed();

        changePin("8642", "2468").andExpect(status().isNoContent());
        assertThat(alerts()).isZero();
    }

    /** Done when: actions that need no PIN (venue photos, PCR-006) keep working during the long lock. */
    @Test
    void photosNeedNoPinAndKeepWorkingDuringTheLongLock() throws Exception {
        lockOut();
        shortLockoutPassed();
        lockOut();
        shortLockoutPassed();
        lockOut();
        UUID first = visiblePhoto(venue.venueId(), 1);
        UUID second = visiblePhoto(venue.venueId(), 2);

        call(put("/api/v1/venue/images/order"), Map.of("imageIds", List.of(second, first)))
                .andExpect(status().isOk());
        call(post("/api/v1/venue/images/" + first + "/remove"), null).andExpect(status().isNoContent());
        changePin(OWNER_PIN, "2468").andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_LOCKED"));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** Five wrong PINs: the 5th starts a 15-minute lockout and is still answered as a wrong PIN. */
    private void lockOut() throws Exception {
        lockOut(OWNER_PIN);
    }

    private void lockOut(String rightPin) throws Exception {
        String wrong = WRONG.equals(rightPin) ? "1111" : WRONG;
        for (int i = 0; i < OwnerPasswordAttempts.MAX_FAILURES; i++) {
            changePin(wrong, "2468").andExpect(status().is(423))
                    .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        }
    }

    /** TEST-ONLY: the 15-minute lockout and its window ran out (arch 10 §7), as the NFR-16 test does. */
    private void shortLockoutPassed() {
        assertThat(OWNER.sql("""
                        UPDATE iam.security_rate_limit_counter
                           SET cooldown_until = now() - interval '1 second',
                               window_started_at = now() - interval '16 minutes'
                         WHERE limiter_type = 'OWNER_PASSWORD' AND action_class = 'VENUE' AND subject_key_hash = """
                        + KEY)
                .param("account", venue.accountId().toString()).update()).isEqualTo(1);
    }

    /** TEST-ONLY: the long lock's 24-hour window started {@code ago}. */
    private void lockoutWindowStartedAgo(String ago) {
        assertThat(OWNER.sql("""
                        UPDATE iam.security_rate_limit_counter
                           SET window_started_at = now() - CAST(:ago AS interval)
                         WHERE limiter_type = 'OWNER_PASSWORD' AND action_class = 'VENUE_LOCKOUTS'
                           AND subject_key_hash = """ + KEY)
                .param("ago", ago).param("account", venue.accountId().toString()).update()).isEqualTo(1);
    }

    private static final String KEY = "encode(sha256(convert_to('venue-account:' || :account, 'UTF8')), 'hex')";

    private long failuresCounted() {
        return OWNER.sql("""
                        SELECT counter FROM iam.security_rate_limit_counter
                         WHERE limiter_type = 'OWNER_PASSWORD' AND action_class = 'VENUE' AND subject_key_hash = """
                        + KEY)
                .param("account", venue.accountId().toString()).query(Long.class).single();
    }

    private long alerts() {
        return OWNER.sql("""
                        SELECT count(*) FROM admin.admin_alert
                         WHERE kind = 'OWNER_PASSWORD_LONG_LOCK' AND subject_type = 'VENUE_ACCOUNT'
                           AND subject_id = :account""")
                .param("account", venue.accountId()).query(Long.class).single();
    }

    private long secretVersion() {
        return OWNER.sql("SELECT version FROM iam.venue_owner_secret WHERE venue_account_id = :account")
                .param("account", venue.accountId()).query(Long.class).single();
    }

    private ResultActions changePin(String current, String next) throws Exception {
        return call(post(CHANGE), Map.of("current", current, "next", next));
    }

    private ResultActions adminResetsThePin() throws Exception {
        return mvc.perform(post(ACCOUNTS + venue.accountId() + "/reset-owner-password")
                .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, AuditFixtures.admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newPassword", NEW_PIN))));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
