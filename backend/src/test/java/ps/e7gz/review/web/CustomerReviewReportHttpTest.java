package ps.e7gz.review.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.review.web.ReviewHttpSupport.OWNER;
import static ps.e7gz.review.web.ReviewHttpSupport.rating;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-102: a Customer reports a public Review (PCR-003, FR-REV-11, ADR-025, arch 10 section 5.3a). A report is an
 * abuse signal only: it never changes the Review's status or the rating, and one reporter has at most one open report
 * per Review ({@code ux_review_report_open_reporter}).
 */
@AutoConfigureMockMvc
class CustomerReviewReportHttpTest extends IntegrationTest {

    private static final String REPORT = "{\"reason\":\"Abusive language\",\"details\":\"Insults the staff.\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens tokens;

    ReviewHttpSupport http;
    UUID venue;
    UUID author;
    UUID reporter;
    UUID review;

    @BeforeEach
    void setUp() throws Exception {
        http = new ReviewHttpSupport(mvc, tokens);
        venue = aVenue().active().create().id();
        author = aUser().verified().create().id();
        reporter = aUser().verified().create().id();
        review = http.reviewed(author, venue, 2, "Rude staff");
    }

    /** Done when: a duplicate open report is REVIEW_REPORT_ALREADY_OPEN. */
    @Test
    void aSecondOpenReportBySameCustomerIsRefused() throws Exception {
        http.report(reporter, review, REPORT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reportId").isNotEmpty())
                .andExpect(jsonPath("$.data.reviewId").value(review.toString()));
        http.report(reporter, review, "{\"reason\":\"Again\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REVIEW_REPORT_ALREADY_OPEN"));

        UUID other = aUser().verified().create().id();                 // another reporter may still report it
        http.report(other, review, REPORT).andExpect(status().isCreated());
        assertThat(OWNER.sql("SELECT count(*) FROM review.review_report WHERE review_id = :r")
                .param("r", review).query(Long.class).single()).isEqualTo(2);
    }

    /** ADR-025: reporting never changes the Review's status or the Venue's rating, however many reports. */
    @Test
    void reportsChangeNeitherTheStatusNorTheRating() throws Exception {
        Object[] before = rating(venue);
        for (int i = 0; i < 5; i++) {
            http.report(aUser().verified().create().id(), review, REPORT).andExpect(status().isCreated());
        }
        assertThat(ReviewHttpSupport.status(review)).isEqualTo("ACTIVE");
        assertThat(rating(venue)).containsExactly(before);
        assertThat(rating(venue)).containsExactly(1, new BigDecimal("2.00"));
    }

    /** Arch 10 section 5.3a: only a Review visible to the reporter can be reported; the reason is required. */
    @Test
    void onlyAVisibleReviewCanBeReportedWithAReason() throws Exception {
        http.report(reporter, review, "{\"reason\":\"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        http.report(reporter, UUID.randomUUID(), REPORT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        http.remove(author, venue).andExpect(status().isNoContent());     // no longer public
        http.report(reporter, review, REPORT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** PCR-014 with arch 10 section 5.3a: a Review by an author the reporter blocked is not visible to them. */
    @Test
    void aReviewByABlockedAuthorCannotBeReportedByTheBlocker() throws Exception {
        http.blockAuthor(reporter, review).andExpect(status().isOk());
        http.report(reporter, review, REPORT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** The report's reason and details are stored trimmed, the details optional. */
    @Test
    void theReportIsStoredForTheAdmin() throws Exception {
        http.report(reporter, review, "{\"reason\":\"  Spam  \"}").andExpect(status().isCreated());
        assertThat(OWNER.sql("""
                        SELECT reason || '|' || coalesce(details, '<none>') || '|' || (resolved_at IS NULL)
                          FROM review.review_report WHERE review_id = :r AND reporter_user_id = :u""")
                .param("r", review).param("u", reporter).query(String.class).single())
                .isEqualTo("Spam|<none>|true");
    }
}
