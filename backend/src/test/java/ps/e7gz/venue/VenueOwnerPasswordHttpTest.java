package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.venue.VenueAccountFixtures.OWNER;
import static ps.e7gz.venue.VenueAccountFixtures.auditCount;
import static ps.e7gz.venue.VenueAccountFixtures.visiblePhoto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.identity.api.OwnerPasswordAttempts;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-24: the Owner Password (a 4-digit PIN, PCR-006) on the Venue Dashboard (ADR-011, arch 10 §3). Today the venue's
 * one protected action is changing it (FR-VEN-46); the gate is the same for pricing and rooms later. Done when: a wrong
 * PIN is audited and rate limited, and no session "unlock" exists. Venue photos need no PIN (PCR-006), are audited, and
 * another venue's photo is NOT_FOUND.
 */
@AutoConfigureMockMvc
class VenueOwnerPasswordHttpTest extends IntegrationTest {

    private static final String IMAGES = "/api/v1/venue/images";
    private static final String CHANGE = "/api/v1/venue/owner-password";
    private static final String OWNER_PIN = "1234";
    private static final String WRONG = "0000";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    PasswordEncoder passwords;

    private final HttpClient http = HttpClient.newHttpClient();

    private VenueAccountFixtures.VenueWithAccount venue;
    private String token;

    @BeforeEach
    void aVenueWithItsAccount() {
        venue = VenueAccountFixtures.venueWithAccount(passwords, OWNER_PIN);
        token = accessTokens.issue(AuthSubjectType.VENUE, venue.accountId());
    }

    // ------------------------------------------------------------------------------------------ photos: no PIN

    @Test
    void addingAPhotoNeedsNoOwnerPinAndIsAudited() throws Exception {
        UUID photo = uploaded();

        call(post(IMAGES + "/" + photo + "/confirm"), null).andExpect(status().isOk());

        assertThat(ids(list())).containsExactly(photo.toString());
        assertThat(auditCount("VENUE_PHOTO_ADDED", photo)).isEqualTo(1);
        assertThat(ownerPinChecked("VENUE_PHOTO_ADDED", photo)).isFalse();
    }

    @Test
    void reorderingAndRemovingPhotosNeedNoOwnerPin() throws Exception {
        UUID first = visiblePhoto(venue.venueId(), 1);
        UUID second = visiblePhoto(venue.venueId(), 2);

        call(put(IMAGES + "/order"), Map.of("imageIds", List.of(second, first))).andExpect(status().isOk());
        assertThat(ids(list())).containsExactly(second.toString(), first.toString());

        call(post(IMAGES + "/" + first + "/remove"), null).andExpect(status().isNoContent());
        assertThat(ids(list())).containsExactly(second.toString());
        assertThat(auditCount("VENUE_PHOTOS_REORDERED", venue.venueId())).isEqualTo(1);
        assertThat(auditCount("VENUE_PHOTO_REMOVED", first)).isEqualTo(1);
        assertRefusals(0);
    }

    @Test
    void anotherVenuesPhotoIsNotFound() throws Exception {
        VenueAccountFixtures.VenueWithAccount other = VenueAccountFixtures.venueWithAccount(passwords, "5678");
        UUID theirs = visiblePhoto(other.venueId(), 1);

        call(post(IMAGES + "/" + theirs + "/remove"), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        call(post(IMAGES + "/" + theirs + "/confirm"), null).andExpect(status().isNotFound());
        assertThat(OWNER.sql("SELECT removed_at IS NULL FROM venue.venue_image WHERE id = :id").param("id", theirs)
                .query(Boolean.class).single()).isTrue();
        assertThat(list()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ the PIN (FR-VEN-46)

    @Test
    void aWrongCurrentPinIs423AuditedAndChangesNothing() throws Exception {
        long versionBefore = secretVersion();

        call(post(CHANGE), Map.of("current", WRONG, "next", "2468"))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));

        assertThat(secretVersion()).isEqualTo(versionBefore);
        assertRefusals(1);
    }

    @Test
    void theRightCurrentPinChangesItAndTheOldOneStopsWorkingAtOnce() throws Exception {
        long versionBefore = secretVersion();

        call(post(CHANGE), Map.of("current", OWNER_PIN, "next", "2468")).andExpect(status().isNoContent());
        assertThat(secretVersion()).isEqualTo(versionBefore + 1);
        assertThat(outcome("OWNER_PASSWORD_CHANGED", venue.accountId())).isEqualTo("SUCCESS");

        call(post(CHANGE), Map.of("current", OWNER_PIN, "next", "1357")).andExpect(status().is(423));
        call(post(CHANGE), Map.of("current", "2468", "next", "1357")).andExpect(status().isNoContent());
    }

    @Test
    void aNewPinMustBeExactlyFourDigits() throws Exception {
        for (String notAPin : List.of("weakpass", "123", "12345", "12a4", "١٢٣٤", "Sayed123")) {
            call(post(CHANGE), Map.of("current", OWNER_PIN, "next", notAPin))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("next"));
        }
        assertThat(secretVersion()).isZero();
    }

    // ------------------------------------------------------------------------------------------ no unlock (FR-VEN-02)

    @Test
    void twoProtectedActionsInARowEachNeedThePin() throws Exception {
        call(post(CHANGE), Map.of("current", OWNER_PIN, "next", "2468")).andExpect(status().isNoContent());
        // Right after a success the next protected action is not unlocked: no PIN and a wrong one both fail.
        call(post(CHANGE), Map.of("next", "1357"))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        call(post(CHANGE), Map.of("current", WRONG, "next", "1357")).andExpect(status().is(423));
        assertThat(secretVersion()).isEqualTo(1);
    }

    @Test
    void thePinIsReadOnlyFromTheBodyNeverAHeaderOrCookie() throws Exception {
        MockHttpServletRequestBuilder request = post(CHANGE)
                .header("X-Owner-Password", OWNER_PIN).header("Owner-Password", OWNER_PIN)
                .cookie(new Cookie("ownerPassword", OWNER_PIN));
        call(request, Map.of("next", "2468"))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        assertThat(secretVersion()).isZero();
    }

    // ------------------------------------------------------------------------------------------ rate limit (NFR-16)

    @Test
    void fiveWrongPinsLockTheAccountForFifteenMinutesEvenForTheRightOne() throws Exception {
        for (int i = 0; i < OwnerPasswordAttempts.MAX_FAILURES; i++) {
            call(post(CHANGE), Map.of("current", WRONG, "next", "2468"))
                    .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        }

        call(post(CHANGE), Map.of("current", OWNER_PIN, "next", "2468"))
                .andExpect(status().is(429)).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(secretVersion()).isZero();
        assertRefusals(OwnerPasswordAttempts.MAX_FAILURES + 1);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :account
                           AND payload ->> 'lockedOut' = 'true'""")
                .param("account", venue.accountId()).query(Long.class).single()).isEqualTo(1);

        // The lockout lasts 15 minutes from the fifth failure: once it has passed, the right PIN works again.
        OWNER.sql("""
                        UPDATE iam.security_rate_limit_counter
                           SET cooldown_until = now() - interval '1 second',
                               window_started_at = now() - interval '16 minutes'
                         WHERE limiter_type = 'OWNER_PASSWORD'
                           AND subject_key_hash = encode(sha256(convert_to('venue-account:' || :account, 'UTF8')), 'hex')""")
                .param("account", venue.accountId().toString()).update();
        call(post(CHANGE), Map.of("current", OWNER_PIN, "next", "2468")).andExpect(status().isNoContent());
    }

    @Test
    void theLockoutBelongsToOneVenueAccount() throws Exception {
        VenueAccountFixtures.VenueWithAccount other = VenueAccountFixtures.venueWithAccount(passwords, "5678");
        for (int i = 0; i < OwnerPasswordAttempts.MAX_FAILURES; i++) {
            call(post(CHANGE), Map.of("current", WRONG, "next", "2468")).andExpect(status().is(423));
        }
        mvc.perform(post(CHANGE)
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, other.accountId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("current", "5678", "next", "2468"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void noPinReachesAResponseOrAnAuditRow() throws Exception {
        String refused = call(post(CHANGE), Map.of("current", WRONG, "next", "2468"))
                .andReturn().getResponse().getContentAsString();
        call(post(CHANGE), Map.of("current", OWNER_PIN, "next", "2468")).andExpect(status().isNoContent());

        assertThat(refused).doesNotContain("current").doesNotContain("$argon2");
        String audit = OWNER.sql("""
                        SELECT coalesce(string_agg(coalesce(payload::text, '') || coalesce(reason, ''), '|'), '')
                          FROM audit.audit_event WHERE actor_id = :account""")
                .param("account", venue.accountId()).query(String.class).single();
        assertThat(audit).doesNotContain("\"current\"").doesNotContain("\"next\"").doesNotContain("ownerPassword")
                .doesNotContain("$argon2");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void assertRefusals(long expected) {
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :account AND actor_type = 'VENUE'
                           AND actor_id = :account AND owner_password_used AND owner_password_outcome = 'FAILURE'
                           AND payload ->> 'protectedAction' = 'OWNER_PASSWORD_CHANGE'""")
                .param("account", venue.accountId()).query(Long.class).single())
                .as("refused Owner PIN audit rows").isEqualTo(expected);
    }

    private String outcome(String action, UUID subject) {
        return OWNER.sql("""
                        SELECT owner_password_outcome FROM audit.audit_event
                         WHERE action = :action AND subject_id = :subject AND owner_password_used""")
                .param("action", action).param("subject", subject).query(String.class).single();
    }

    private boolean ownerPinChecked(String action, UUID subject) {
        return OWNER.sql("""
                        SELECT owner_password_used IS TRUE FROM audit.audit_event
                         WHERE action = :action AND subject_id = :subject""")
                .param("action", action).param("subject", subject).query(Boolean.class).single();
    }

    private long secretVersion() {
        return OWNER.sql("SELECT version FROM iam.venue_owner_secret WHERE venue_account_id = :account")
                .param("account", venue.accountId()).query(Long.class).single();
    }

    /** Starts an upload and stores a real JPEG at its signed URL; the photo is not confirmed. */
    private UUID uploaded() throws Exception {
        byte[] file = jpeg();
        JsonNode upload = data(call(post(IMAGES), Map.of("contentType", "image/jpeg", "sizeBytes", file.length))
                .andExpect(status().isCreated()));
        int stored = http.send(HttpRequest.newBuilder(URI.create(upload.path("uploadUrl").asText()))
                        .header("Content-Type", "image/jpeg").PUT(HttpRequest.BodyPublishers.ofByteArray(file)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        assertThat(stored).isEqualTo(200);
        return UUID.fromString(upload.path("image").path("id").asText());
    }

    private List<JsonNode> list() throws Exception {
        List<JsonNode> items = new ArrayList<>();
        data(call(get(IMAGES), null).andExpect(status().isOk())).forEach(items::add);
        return items;
    }

    private static List<String> ids(List<JsonNode> items) {
        return items.stream().map(n -> n.path("id").asText()).toList();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 200, 300);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
