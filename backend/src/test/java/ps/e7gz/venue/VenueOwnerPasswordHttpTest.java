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
 * E7-24: the Owner Password on the Venue Dashboard's protected actions (ADR-011, arch 10 §3, FRS §2.4 as amended by
 * PCR-005). Done when: a wrong password is audited and rate limited; no session "unlock" exists; photo confirm,
 * reorder and remove with a wrong Owner Password return 423 OWNER_PASSWORD_INVALID and are audited; another venue's
 * photo is NOT_FOUND. Plus the venue's own Owner Password change (FR-VEN-46).
 */
@AutoConfigureMockMvc
class VenueOwnerPasswordHttpTest extends IntegrationTest {

    private static final String IMAGES = "/api/v1/venue/images";
    private static final String OWNER_PASSWORD = "Owner-pass-1";
    private static final String WRONG = "not-the-owner-password";

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
        venue = VenueAccountFixtures.venueWithAccount(passwords, OWNER_PASSWORD);
        token = accessTokens.issue(AuthSubjectType.VENUE, venue.accountId());
    }

    // ------------------------------------------------------------------------------------------ photos (PCR-005)

    @Test
    void confirmWithAWrongOwnerPasswordIs423AuditedAndThePhotoStaysHidden() throws Exception {
        UUID photo = uploaded();

        call(post(IMAGES + "/" + photo + "/confirm"), Map.of("ownerPassword", WRONG))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        assertThat(list()).isEmpty();
        assertThat(auditCount("VENUE_PHOTO_ADDED", photo)).isZero();
        assertRefusals("VENUE_PHOTO_ADD", 1);

        call(post(IMAGES + "/" + photo + "/confirm"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().isOk());
        assertThat(ids(list())).containsExactly(photo.toString());
        assertThat(outcome("VENUE_PHOTO_ADDED", photo)).isEqualTo("SUCCESS");
    }

    @Test
    void reorderWithAWrongOwnerPasswordIs423AuditedAndTheOrderIsUnchanged() throws Exception {
        UUID first = visiblePhoto(venue.venueId(), 1);
        UUID second = visiblePhoto(venue.venueId(), 2);

        call(put(IMAGES + "/order"), Map.of("imageIds", List.of(second, first), "ownerPassword", WRONG))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        assertThat(ids(list())).containsExactly(first.toString(), second.toString());
        assertRefusals("VENUE_PHOTO_REORDER", 1);

        call(put(IMAGES + "/order"), Map.of("imageIds", List.of(second, first), "ownerPassword", OWNER_PASSWORD))
                .andExpect(status().isOk());
        assertThat(ids(list())).containsExactly(second.toString(), first.toString());
        assertThat(outcome("VENUE_PHOTOS_REORDERED", venue.venueId())).isEqualTo("SUCCESS");
    }

    @Test
    void removeWithAWrongOwnerPasswordIs423AuditedAndThePhotoStaysVisible() throws Exception {
        UUID photo = visiblePhoto(venue.venueId(), 1);

        call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", WRONG))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        assertThat(ids(list())).containsExactly(photo.toString());
        assertRefusals("VENUE_PHOTO_REMOVE", 1);

        call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().isNoContent());
        assertThat(list()).isEmpty();
        assertThat(outcome("VENUE_PHOTO_REMOVED", photo)).isEqualTo("SUCCESS");
    }

    @Test
    void anotherVenuesPhotoIsNotFoundEvenWithTheRightOwnerPassword() throws Exception {
        VenueAccountFixtures.VenueWithAccount other = VenueAccountFixtures.venueWithAccount(passwords, "Other-pass-1");
        UUID theirs = visiblePhoto(other.venueId(), 1);

        call(post(IMAGES + "/" + theirs + "/remove"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        call(post(IMAGES + "/" + theirs + "/confirm"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().isNotFound());
        assertThat(OWNER.sql("SELECT removed_at IS NULL FROM venue.venue_image WHERE id = :id").param("id", theirs)
                .query(Boolean.class).single()).isTrue();
        assertThat(list()).isEmpty();
    }

    @Test
    void startingAnUploadNeedsNoOwnerPassword() throws Exception {
        call(post(IMAGES), Map.of("contentType", "image/jpeg", "sizeBytes", 1024)).andExpect(status().isCreated());
        assertRefusals("VENUE_PHOTO_ADD", 0);
    }

    // ------------------------------------------------------------------------------------------ no unlock (FR-VEN-02)

    @Test
    void twoProtectedActionsInARowEachNeedTheOwnerPassword() throws Exception {
        UUID first = visiblePhoto(venue.venueId(), 1);
        UUID second = visiblePhoto(venue.venueId(), 2);

        call(post(IMAGES + "/" + first + "/remove"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().isNoContent());
        // Right after a success, the next protected action is not unlocked: no password and a wrong one both fail.
        call(post(IMAGES + "/" + second + "/remove"), Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("ownerPassword"));
        call(post(IMAGES + "/" + second + "/remove"), Map.of("ownerPassword", WRONG))
                .andExpect(status().is(423));
        assertThat(ids(list())).containsExactly(second.toString());
    }

    @Test
    void theOwnerPasswordIsReadOnlyFromTheBodyNeverAHeaderOrCookie() throws Exception {
        UUID photo = visiblePhoto(venue.venueId(), 1);
        MockHttpServletRequestBuilder request = post(IMAGES + "/" + photo + "/remove")
                .header("X-Owner-Password", OWNER_PASSWORD).header("Owner-Password", OWNER_PASSWORD)
                .cookie(new jakarta.servlet.http.Cookie("ownerPassword", OWNER_PASSWORD));
        call(request, Map.of()).andExpect(status().isBadRequest());
        assertThat(ids(list())).containsExactly(photo.toString());
    }

    // ------------------------------------------------------------------------------------------ rate limit (NFR-16)

    @Test
    void fiveWrongPasswordsLockTheAccountForFifteenMinutesEvenForTheRightOne() throws Exception {
        UUID photo = visiblePhoto(venue.venueId(), 1);
        for (int i = 0; i < OwnerPasswordAttempts.MAX_FAILURES; i++) {
            call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", WRONG))
                    .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        }

        call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().is(429)).andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(ids(list())).containsExactly(photo.toString());
        assertRefusals("VENUE_PHOTO_REMOVE", OwnerPasswordAttempts.MAX_FAILURES + 1);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :account
                           AND payload ->> 'lockedOut' = 'true'""")
                .param("account", venue.accountId()).query(Long.class).single()).isEqualTo(1);

        // The lockout lasts 15 minutes from the fifth failure: once it has passed, the right password works again.
        OWNER.sql("""
                        UPDATE iam.security_rate_limit_counter
                           SET cooldown_until = now() - interval '1 second',
                               window_started_at = now() - interval '16 minutes'
                         WHERE limiter_type = 'OWNER_PASSWORD'
                           AND subject_key_hash = encode(sha256(convert_to('venue-account:' || :account, 'UTF8')), 'hex')""")
                .param("account", venue.accountId().toString()).update();
        call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().isNoContent());
    }

    @Test
    void theLockoutBelongsToOneVenueAccount() throws Exception {
        VenueAccountFixtures.VenueWithAccount other = VenueAccountFixtures.venueWithAccount(passwords, "Other-pass-1");
        UUID mine = visiblePhoto(venue.venueId(), 1);
        for (int i = 0; i < OwnerPasswordAttempts.MAX_FAILURES; i++) {
            call(post(IMAGES + "/" + mine + "/remove"), Map.of("ownerPassword", WRONG)).andExpect(status().is(423));
        }
        UUID theirs = visiblePhoto(other.venueId(), 1);
        mvc.perform(post(IMAGES + "/" + theirs + "/remove")
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, other.accountId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("ownerPassword", "Other-pass-1"))))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------------------------------ change (FR-VEN-46)

    @Test
    void changingTheOwnerPasswordNeedsTheCurrentOneAndTheOldOneStopsWorkingAtOnce() throws Exception {
        UUID photo = visiblePhoto(venue.venueId(), 1);
        long versionBefore = secretVersion();

        call(post("/api/v1/venue/owner-password"), Map.of("current", WRONG, "next", "New-owner-2"))
                .andExpect(status().is(423)).andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        assertThat(secretVersion()).isEqualTo(versionBefore);
        assertRefusals("OWNER_PASSWORD_CHANGE", 1);

        call(post("/api/v1/venue/owner-password"), Map.of("current", OWNER_PASSWORD, "next", "New-owner-2"))
                .andExpect(status().isNoContent());
        assertThat(secretVersion()).isEqualTo(versionBefore + 1);
        assertThat(outcome("OWNER_PASSWORD_CHANGED", venue.accountId())).isEqualTo("SUCCESS");

        call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", OWNER_PASSWORD))
                .andExpect(status().is(423));
        call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", "New-owner-2"))
                .andExpect(status().isNoContent());
    }

    @Test
    void noPasswordReachesAResponseOrAnAuditRow() throws Exception {
        UUID photo = visiblePhoto(venue.venueId(), 1);
        String refused = call(post(IMAGES + "/" + photo + "/remove"), Map.of("ownerPassword", WRONG))
                .andReturn().getResponse().getContentAsString();
        call(post("/api/v1/venue/owner-password"), Map.of("current", OWNER_PASSWORD, "next", "New-owner-2"))
                .andExpect(status().isNoContent());

        assertThat(refused).doesNotContain(WRONG);
        String audit = OWNER.sql("""
                        SELECT coalesce(string_agg(coalesce(payload::text, '') || coalesce(reason, ''), '|'), '')
                          FROM audit.audit_event WHERE actor_id = :account""")
                .param("account", venue.accountId()).query(String.class).single();
        assertThat(audit).doesNotContain(WRONG).doesNotContain(OWNER_PASSWORD).doesNotContain("New-owner-2")
                .doesNotContain("$argon2");
    }

    // ------------------------------------------------------------------------------------------ helpers

    private void assertRefusals(String protectedAction, long expected) {
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :account AND actor_type = 'VENUE'
                           AND actor_id = :account AND owner_password_used AND owner_password_outcome = 'FAILURE'
                           AND payload ->> 'protectedAction' = :action""")
                .param("account", venue.accountId()).param("action", protectedAction).query(Long.class).single())
                .as("refused " + protectedAction + " audit rows").isEqualTo(expected);
    }

    private String outcome(String action, UUID subject) {
        return OWNER.sql("""
                        SELECT owner_password_outcome FROM audit.audit_event
                         WHERE action = :action AND subject_id = :subject AND owner_password_used""")
                .param("action", action).param("subject", subject).query(String.class).single();
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
