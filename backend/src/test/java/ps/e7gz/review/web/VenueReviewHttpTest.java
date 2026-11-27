package ps.e7gz.review.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-102: the Venue Reviews page is view only (Spec v5 section 49.7, FR-VEN-39, FR-REV-08): average rating, total,
 * distribution, and per Review the username, rating, comment and date. Blocks change nothing here (PCR-014 item 3).
 */
@AutoConfigureMockMvc
class VenueReviewHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens tokens;

    ReviewHttpSupport http;
    UUID venue;
    String staff;

    @BeforeEach
    void setUp() {
        http = new ReviewHttpSupport(mvc, tokens);
        venue = aVenue().active().create().id();
        staff = http.venue(aVenueAccount().forVenue(venue).create().id());
    }

    @Test
    void theVenueSeesItsSummaryAndPublicReviewsOnly() throws Exception {
        mvc.perform(get("/api/v1/venue/reviews").header("Authorization", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(0))
                .andExpect(jsonPath("$.data.summary.averageRating").value((Object) null))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        Fixtures.User a = aUser().verified().create();
        Fixtures.User b = aUser().verified().create();
        http.reviewed(a.id(), venue, 5, "Great");
        UUID second = http.reviewed(b.id(), venue, 2, "Slow");
        http.blockAuthor(a.id(), second).andExpect(status().isOk());       // a Customer's block: no effect here
        http.reviewed(aUser().verified().create().id(), aVenue().active().create().id(), 1, "Elsewhere");

        mvc.perform(get("/api/v1/venue/reviews").header("Authorization", staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(2))
                .andExpect(jsonPath("$.data.summary.averageRating").value(3.5))
                .andExpect(jsonPath("$.data.summary.distribution.five").value(1))
                .andExpect(jsonPath("$.data.summary.distribution.two").value(1))
                .andExpect(jsonPath("$.data.summary.distribution.one").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].reviewId").value(second.toString()))
                .andExpect(jsonPath("$.data.items[0].username").value(b.username()))
                .andExpect(jsonPath("$.data.items[0].rating").value(2))
                .andExpect(jsonPath("$.data.items[0].comment").value("Slow"))
                .andExpect(jsonPath("$.data.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].fullName").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").value((Object) null));
    }

    /** Arch 10 section 5.3a: no reporting or moderation route for a Venue. */
    @Test
    void theVenueCannotReportOrModerate() throws Exception {
        UUID review = http.reviewed(aUser().verified().create().id(), venue, 3, "Ok");
        mvc.perform(post("/api/v1/customer/reviews/{id}/report", review).header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/venue/reviews/{id}/hide", review).header("Authorization", staff)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
