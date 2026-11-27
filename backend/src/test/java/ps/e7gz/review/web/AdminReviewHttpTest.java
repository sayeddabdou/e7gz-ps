package ps.e7gz.review.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.review.web.ReviewHttpSupport.OWNER;
import static ps.e7gz.review.web.ReviewHttpSupport.notifications;
import static ps.e7gz.review.web.ReviewHttpSupport.rating;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-102: Platform Admin moderation (Spec v5 sections 49.4, 49.6, 49.7, 49.9; FR-REV-04, FR-REV-05, FR-REV-07,
 * FR-REV-08, FR-REV-10) and Review Report resolution (O20, FR-REV-11, arch 10 section 5.3a), through
 * {@code review.moderate} and {@code review.resolve_report}.
 */
@AutoConfigureMockMvc
class AdminReviewHttpTest extends IntegrationTest {

    private static final String REASON = "{\"reason\":\"Breaks the review rules.\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens tokens;

    ReviewHttpSupport http;
    String admin;
    UUID venue;
    Fixtures.User author;

    @BeforeEach
    void setUp() {
        http = new ReviewHttpSupport(mvc, tokens);
        admin = http.admin(anAdmin().create().id());
        venue = aVenue().named("Arena " + UUID.randomUUID().toString().substring(0, 6)).active().create().id();
        author = aUser().verified().fullName("Omar Hassan").create();
    }

    /** Section 49.4: HIDE, an edit while HIDDEN stays HIDDEN, RESTORE, REMOVE; each with its event; rating follows. */
    @Test
    void hideRestoreAndRemoveMoveTheReviewAndTheRating() throws Exception {
        UUID review = http.reviewed(author.id(), venue, 4, "Good");

        moderate(review, "hide", REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewId").value(review.toString()))
                .andExpect(jsonPath("$.data.moderationEventId").isNotEmpty())
                .andExpect(jsonPath("$.data.action").value("HIDE"))
                .andExpect(jsonPath("$.data.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.newStatus").value("HIDDEN"))
                .andExpect(jsonPath("$.data.reason").value("Breaks the review rules."));
        assertThat(rating(venue)).containsExactly(0, null);
        assertThat(notifications(author.id(), "REVIEW_HIDDEN")).isEqualTo(1);

        http.edit(author.id(), venue, 5, "Edited while hidden")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HIDDEN"));
        assertThat(rating(venue)).containsExactly(0, null);

        moderate(review, "restore", REASON).andExpect(jsonPath("$.data.newStatus").value("ACTIVE"));
        assertThat(rating(venue)).containsExactly(1, new BigDecimal("5.00"));

        moderate(review, "remove", REASON).andExpect(jsonPath("$.data.newStatus").value("REMOVED"));
        assertThat(rating(venue)).containsExactly(0, null);
        assertThat(notifications(author.id(), "REVIEW_REMOVED")).isEqualTo(1);

        mvc.perform(get("/api/v1/admin/reviews/{id}", review).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review.status").value("REMOVED"))
                .andExpect(jsonPath("$.data.review.author.fullName").value("Omar Hassan"))
                .andExpect(jsonPath("$.data.review.author.username").value(author.username()))
                .andExpect(jsonPath("$.data.moderation.length()").value(3))
                .andExpect(jsonPath("$.data.moderation[0].action").value("HIDE"))
                .andExpect(jsonPath("$.data.moderation[2].action").value("REMOVE"));
    }

    /** Section 49.4: REMOVED is terminal; any other action from the wrong state is ADMIN_ACTION_NOT_ALLOWED. */
    @Test
    void aRemovedReviewIsNeverRestoredAndWrongTransitionsAreRefused() throws Exception {
        UUID review = http.reviewed(author.id(), venue, 4, "Good");
        moderate(review, "restore", REASON)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
        moderate(review, "approve", REASON)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
        moderate(review, "remove", REASON).andExpect(status().isOk());
        for (String action : new String[] {"restore", "hide", "approve", "remove"}) {
            moderate(review, action, REASON)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("REVIEW_REMOVED_NOT_RESTORABLE"));
        }
        moderate(review, "hide", "{\"reason\":\" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        moderate(UUID.randomUUID(), "hide", REASON)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** Sections 49.6 and 49.9: 30-day cooldown, then a PENDING_MODERATION re-entry the Admin approves. */
    @Test
    void afterARemovalTheCooldownRunsAndTheNextReviewWaitsForApproval() throws Exception {
        UUID removed = http.reviewed(author.id(), venue, 1, "Spam");
        moderate(removed, "remove", REASON).andExpect(status().isOk());

        http.write(author.id(), venue, 4, "Trying again")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REVIEW_COOLDOWN_ACTIVE"));

        cooldownPassed(author.id(), venue);
        UUID again = UUID.fromString(JsonPath.read(http.write(author.id(), venue, 4, "Trying again")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_MODERATION"))
                .andReturn().getResponse().getContentAsString(), "$.data.reviewId"));
        assertThat(notifications(author.id(), "REVIEW_SUBMITTED_FOR_MODERATION")).isEqualTo(1);
        assertThat(rating(venue)).containsExactly(0, null);
        Fixtures.User reader = aUser().verified().create();
        http.list(reader.id(), venue, "").andExpect(jsonPath("$.data.items.length()").value(0));   // not public

        moderate(again, "approve", REASON).andExpect(jsonPath("$.data.newStatus").value("ACTIVE"));
        assertThat(notifications(author.id(), "REVIEW_APPROVED")).isEqualTo(1);
        assertThat(rating(venue)).containsExactly(1, new BigDecimal("4.00"));
    }

    /** The Admin list filters by status, venue, author and open reports; full name and username. */
    @Test
    void theAdminListsReviewsWithFilters() throws Exception {
        UUID shown = http.reviewed(author.id(), venue, 4, "Good");
        Fixtures.User other = aUser().verified().create();
        UUID hidden = http.reviewed(other.id(), venue, 2, "Bad");
        moderate(hidden, "hide", REASON).andExpect(status().isOk());
        http.report(author.id(), hidden, "{\"reason\":\"x\"}").andExpect(status().isNotFound());  // HIDDEN: not public
        http.report(other.id(), shown, "{\"reason\":\"Fake\"}").andExpect(status().isCreated());

        list("?venueId=" + venue)
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].reviewId").value(hidden.toString()))
                .andExpect(jsonPath("$.data.items[0].venueName").isNotEmpty());
        list("?venueId=" + venue + "&status=HIDDEN")
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("HIDDEN"));
        list("?userId=" + author.id())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].author.fullName").value("Omar Hassan"))
                .andExpect(jsonPath("$.data.items[0].openReports").value(1));
        list("?venueId=" + venue + "&reported=true")
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].reviewId").value(shown.toString()));
    }

    /** O20: a note is required; a linked event must be of the same Review; resolved once; status untouched. */
    @Test
    void anAdminResolvesAReportWithANoteAndAnOptionalLinkedEvent() throws Exception {
        UUID review = http.reviewed(author.id(), venue, 1, "Abusive");
        UUID other = http.reviewed(aUser().verified().create().id(), venue, 5, "Fine");
        UUID reporter = aUser().verified().create().id();
        UUID report = reportId(http.report(reporter, review, "{\"reason\":\"Abuse\",\"details\":\"Swearing\"}"));
        UUID foreignEvent = eventId(moderate(other, "hide", REASON));

        reports("?resolved=false&reviewId=" + review)
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].reportId").value(report.toString()))
                .andExpect(jsonPath("$.data.items[0].reason").value("Abuse"))
                .andExpect(jsonPath("$.data.items[0].details").value("Swearing"))
                .andExpect(jsonPath("$.data.items[0].reporter.userId").value(reporter.toString()))
                .andExpect(jsonPath("$.data.items[0].resolvedAt").value((Object) null));

        resolve(report, "{\"resolutionNote\":\"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        resolve(report, "{\"resolutionNote\":\"Removed\",\"moderationEventId\":\"" + foreignEvent + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        resolve(UUID.randomUUID(), "{\"resolutionNote\":\"x\"}").andExpect(status().isNotFound());

        UUID removal = eventId(moderate(review, "remove", REASON));
        resolve(report, "{\"resolutionNote\":\"Removed for abuse\",\"moderationEventId\":\"" + removal + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.resolutionNote").value("Removed for abuse"))
                .andExpect(jsonPath("$.data.moderationEventId").value(removal.toString()));
        resolve(report, "{\"resolutionNote\":\"Removed for abuse\",\"moderationEventId\":\"" + removal + "\"}")
                .andExpect(status().isOk());                               // the same resolution again: the same answer
        resolve(report, "{\"resolutionNote\":\"Other\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
        reports("?resolved=false&reviewId=" + review).andExpect(jsonPath("$.data.items.length()").value(0));
        reports("?resolved=true&reviewId=" + review).andExpect(jsonPath("$.data.items.length()").value(1));
    }

    /** A note-only resolution leaves the Review exactly as it was (ADR-025). */
    @Test
    void resolvingWithoutAnActionChangesNothingElse() throws Exception {
        UUID review = http.reviewed(author.id(), venue, 3, "Ok");
        UUID report = reportId(http.report(aUser().verified().create().id(), review, "{\"reason\":\"Fake\"}"));
        resolve(report, "{\"resolutionNote\":\"Looks genuine\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.moderationEventId").value((Object) null));
        assertThat(ReviewHttpSupport.status(review)).isEqualTo("ACTIVE");
        assertThat(rating(venue)).containsExactly(1, new BigDecimal("3.00"));
    }

    /** Arch 10 section 5.3a: the Venue and the Customer have no moderation path. */
    @Test
    void onlyTheAdminModerates() throws Exception {
        UUID review = http.reviewed(author.id(), venue, 3, "Ok");
        String venueToken = http.venue(aVenueAccount().forVenue(venue).create().id());
        mvc.perform(post("/api/v1/admin/reviews/{id}/hide", review).header("Authorization", venueToken)
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reviews/{id}/hide", review).header("Authorization", http.customer(author.id()))
                        .contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andExpect(status().isForbidden());
        assertThat(ReviewHttpSupport.status(review)).isEqualTo("ACTIVE");
    }

    private ResultActions moderate(UUID review, String action, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/reviews/{id}/" + action, review).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get("/api/v1/admin/reviews" + query).header("Authorization", admin))
                .andExpect(status().isOk());
    }

    private ResultActions reports(String query) throws Exception {
        return mvc.perform(get("/api/v1/admin/review-reports" + query).header("Authorization", admin))
                .andExpect(status().isOk());
    }

    private ResultActions resolve(UUID report, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/review-reports/{id}/resolve", report).header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static UUID reportId(ResultActions created) throws Exception {
        return UUID.fromString(JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.data.reportId"));
    }

    private static UUID eventId(ResultActions moderated) throws Exception {
        return UUID.fromString(JsonPath.read(moderated.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.data.moderationEventId"));
    }

    /** Test setup only: moves the User's cooldowns for the Venue 31 days into the past, triggers off. */
    private static void cooldownPassed(UUID user, UUID venue) {
        OWNER.sql("""
                DO $t$
                BEGIN
                 SET LOCAL session_replication_role = replica;
                 UPDATE review.review_cooldown
                    SET removed_at = removed_at - interval '31 days', cooldown_until = cooldown_until - interval '31 days'
                  WHERE user_id = '%s' AND venue_id = '%s';
                END;
                $t$""".formatted(user, venue)).update();
    }
}
