package ps.e7gz.review.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.review.web.ReviewHttpSupport.rating;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-102 (S7-E1): a Customer writes, edits and deletes their one current Review of a Venue (Spec v5 sections 49.1-49.5,
 * FR-REV-01...FR-REV-06, arch 09 section 3.4) and reads a Venue's public Reviews (FR-REV-02), through the real security
 * chain and the review.* producers.
 */
@AutoConfigureMockMvc
class CustomerReviewHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens tokens;

    ReviewHttpSupport http;
    UUID venue;
    Fixtures.User author;

    @BeforeEach
    void setUp() {
        http = new ReviewHttpSupport(mvc, tokens);
        venue = aVenue().active().create().id();
        author = aUser().verified().create();
    }

    /** FR-REV-01, FR-REV-05: a verified Customer, no booking needed; created ACTIVE and counted in the rating. */
    @Test
    void aVerifiedCustomerWritesAnActiveReviewThatCountsInTheRating() throws Exception {
        http.write(author.id(), venue, 4, "Great rooms")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reviewId").isNotEmpty())
                .andExpect(jsonPath("$.data.venueId").value(venue.toString()))
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.comment").value("Great rooms"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.meta.serverTime").exists());

        assertThat(rating(venue)).containsExactly(1, new BigDecimal("4.00"));
    }

    /** FR-REV-03: one current Review per User per Venue; a second one is REVIEW_ALREADY_EXISTS. */
    @Test
    void aSecondReviewOfTheSameVenueIsRefused() throws Exception {
        http.write(author.id(), venue, 4, "First").andExpect(status().isCreated());
        http.write(author.id(), venue, 2, "Second")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_EXISTS"));
    }

    /** FR-REV-01: a Customer without a verified phone cannot review. */
    @Test
    void anUnverifiedCustomerIsRefused() throws Exception {
        UUID unverified = aUser().unverified().create().id();
        http.write(unverified, venue, 5, "Nice")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PHONE_VERIFICATION_REQUIRED"));
    }

    @Test
    void anUnknownVenueOrABadBodyIsRefused() throws Exception {
        http.write(author.id(), UUID.randomUUID(), 5, "Nice")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        http.write(author.id(), venue, 6, "Too many stars")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        http.write(author.id(), venue, 3, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** FR-REV-09: a SUSPENDED or INACTIVE Venue still receives Reviews. */
    @Test
    void aSuspendedVenueStillReceivesReviews() throws Exception {
        UUID suspended = aVenue().suspended().create().id();
        http.write(author.id(), suspended, 3, "Closed for now").andExpect(status().isCreated());
    }

    /** FR-REV-03, FR-REV-06: edit changes the content and the rating; delete removes it and frees the Venue. */
    @Test
    void theAuthorEditsAndDeletesTheirReview() throws Exception {
        UUID review = http.reviewed(author.id(), venue, 2, "Meh");

        http.edit(author.id(), venue, 5, "Better now")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewId").value(review.toString()))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.comment").value("Better now"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(rating(venue)).containsExactly(1, new BigDecimal("5.00"));

        http.remove(author.id(), venue).andExpect(status().isNoContent());
        assertThat(ReviewHttpSupport.status(review)).isEqualTo("REMOVED");
        assertThat(rating(venue)).containsExactly(0, null);
        http.mine(author.id(), venue).andExpect(status().isNotFound());
        http.edit(author.id(), venue, 3, "Again").andExpect(status().isNotFound());
        http.remove(author.id(), venue).andExpect(status().isNotFound());

        http.write(author.id(), venue, 4, "Back again")            // author deletion starts no cooldown (49.5, 49.6)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void myReviewIsReadBack() throws Exception {
        http.mine(author.id(), venue).andExpect(status().isNotFound());
        UUID review = http.reviewed(author.id(), venue, 3, "Fine");
        http.mine(author.id(), venue)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewId").value(review.toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    /** FR-REV-02: current usernames only, newest first, cursor paging; the reader's own Review is marked. */
    @Test
    void theVenueReviewsAreListedNewestFirstWithUsernamesOnly() throws Exception {
        Fixtures.User second = aUser().verified().create();
        Fixtures.User third = aUser().verified().create();
        UUID first = http.reviewed(author.id(), venue, 5, "One");
        UUID middle = http.reviewed(second.id(), venue, 4, "Two");
        UUID last = http.reviewed(third.id(), venue, 3, "Three");

        String page = http.list(author.id(), venue, "?limit=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].reviewId").value(last.toString()))
                .andExpect(jsonPath("$.data.items[0].username").value(third.username()))
                .andExpect(jsonPath("$.data.items[0].rating").value(3))
                .andExpect(jsonPath("$.data.items[0].comment").value("Three"))
                .andExpect(jsonPath("$.data.items[0].mine").value(false))
                .andExpect(jsonPath("$.data.items[0].fullName").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].reviewId").value(middle.toString()))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page, "$.data.nextCursor");

        http.list(author.id(), venue, "?limit=2&cursor=" + cursor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].reviewId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[0].mine").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value((Object) null));
        http.list(author.id(), venue, "?cursor=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /** FR-REV-02: a username change is shown at once. */
    @Test
    void theListShowsTheCurrentUsername() throws Exception {
        http.reviewed(author.id(), venue, 5, "One");
        ReviewHttpSupport.OWNER.sql("UPDATE iam.user_account SET username = 'renamed_' || substr(md5(random()::text), 1, 8)"
                + " WHERE id = :id RETURNING username").param("id", author.id()).query(String.class).single();
        String now = ReviewHttpSupport.OWNER.sql("SELECT username FROM iam.user_account WHERE id = :id")
                .param("id", author.id()).query(String.class).single();
        http.list(author.id(), venue, "").andExpect(jsonPath("$.data.items[0].username").value(now));
    }

    /** The list needs a signed-in Customer: it leaves out the Reviews of Users the reader blocked (PCR-014). */
    @Test
    void theListNeedsASignedInCustomer() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/customer/venues/{v}/reviews", venue))
                .andExpect(status().isUnauthorized());
    }
}
