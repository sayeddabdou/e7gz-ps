package ps.e7gz.review.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.review.web.ReviewHttpSupport.OWNER;
import static ps.e7gz.review.web.ReviewHttpSupport.rating;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;

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
 * E7-102: a Customer blocks the author of a Review (PCR-014, FR-REV-12). The blocker sees none of the author's Reviews
 * on any Venue; nobody else is affected - not other readers, not the rating, not the Review - and the author is never
 * told; the blocker lists and unblocks.
 */
@AutoConfigureMockMvc
class CustomerReviewBlockHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens tokens;

    ReviewHttpSupport http;
    UUID first;
    UUID second;
    Fixtures.User a;
    Fixtures.User b;
    Fixtures.User c;
    UUID bOnFirst;
    UUID bOnSecond;

    @BeforeEach
    void setUp() throws Exception {
        http = new ReviewHttpSupport(mvc, tokens);
        first = aVenue().active().create().id();
        second = aVenue().active().create().id();
        a = aUser().verified().create();
        b = aUser().verified().create();
        c = aUser().verified().create();
        bOnFirst = http.reviewed(b.id(), first, 1, "Awful");
        bOnSecond = http.reviewed(b.id(), second, 2, "Bad");
        http.reviewed(c.id(), first, 5, "Lovely");
    }

    /**
     * Done when: A blocks B - A sees no Review by B on any Venue, C still sees them and the rating is the same; after
     * the unblock A sees them again.
     */
    @Test
    void aBlockHidesTheAuthorFromTheBlockerOnlyUntilTheUnblock() throws Exception {
        Object[] firstRating = rating(first);
        Object[] secondRating = rating(second);

        UUID block = blockId(http.blockAuthor(a.id(), bOnFirst)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blockId").isNotEmpty())
                .andExpect(jsonPath("$.data.username").value(b.username()))
                .andExpect(jsonPath("$.data.blockedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").doesNotExist()));   // arch 10 section 5.3: no user id

        http.list(a.id(), first, "")
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value(c.username()));
        http.list(a.id(), second, "").andExpect(jsonPath("$.data.items.length()").value(0));
        http.list(c.id(), first, "").andExpect(jsonPath("$.data.items.length()").value(2));
        http.list(c.id(), second, "")
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].reviewId").value(bOnSecond.toString()));
        assertThat(rating(first)).containsExactly(firstRating);
        assertThat(rating(second)).containsExactly(secondRating);
        assertThat(ReviewHttpSupport.status(bOnFirst)).isEqualTo("ACTIVE");

        http.unblock(a.id(), block).andExpect(status().isNoContent());
        http.list(a.id(), first, "").andExpect(jsonPath("$.data.items.length()").value(2));
        http.list(a.id(), second, "").andExpect(jsonPath("$.data.items.length()").value(1));
    }

    /** PCR-014 item 4: the author is never told - no notification, nothing in their own view. */
    @Test
    void theAuthorIsNeverTold() throws Exception {
        long before = OWNER.sql("SELECT count(*) FROM notification.notification WHERE recipient_id = :u")
                .param("u", b.id()).query(Long.class).single();
        http.blockAuthor(a.id(), bOnFirst).andExpect(status().isOk());
        assertThat(OWNER.sql("SELECT count(*) FROM notification.notification WHERE recipient_id = :u")
                .param("u", b.id()).query(Long.class).single()).isEqualTo(before);
        http.mine(b.id(), first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.blocked").doesNotExist());
        http.list(b.id(), first, "").andExpect(jsonPath("$.data.items.length()").value(2));
    }

    /** Blocking twice keeps one block; unblocking twice is fine; a Customer cannot block themself. */
    @Test
    void blockAndUnblockAreIdempotentAndNobodyBlocksThemself() throws Exception {
        UUID block = blockId(http.blockAuthor(a.id(), bOnFirst).andExpect(status().isOk()));
        assertThat(blockId(http.blockAuthor(a.id(), bOnSecond).andExpect(status().isOk()))).isEqualTo(block);
        assertThat(openBlocks(a.id(), b.id())).isEqualTo(1);

        http.unblock(c.id(), block).andExpect(status().isNoContent());      // someone else's block: nothing happens
        assertThat(openBlocks(a.id(), b.id())).isEqualTo(1);
        http.unblock(a.id(), block).andExpect(status().isNoContent());
        http.unblock(a.id(), block).andExpect(status().isNoContent());
        assertThat(openBlocks(a.id(), b.id())).isZero();
        assertThat(blockId(http.blockAuthor(a.id(), bOnFirst).andExpect(status().isOk()))).isNotEqualTo(block);
        assertThat(openBlocks(a.id(), b.id())).isEqualTo(1);

        http.blockAuthor(b.id(), bOnFirst)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        http.blockAuthor(a.id(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        http.unblock(a.id(), UUID.randomUUID()).andExpect(status().isNoContent());
    }

    /** PCR-014 item 5: Profile lists the blocked users, newest first; an unblocked one leaves the list. */
    @Test
    void theBlockerListsTheirBlockedUsers() throws Exception {
        UUID bBlock = blockId(http.blockAuthor(a.id(), bOnFirst).andExpect(status().isOk()));
        UUID cReview = OWNER.sql("SELECT id FROM review.review WHERE user_id = :u").param("u", c.id())
                .query(java.util.UUID.class).single();
        UUID cBlock = blockId(http.blockAuthor(a.id(), cReview).andExpect(status().isOk()));

        http.blocked(a.id(), "?limit=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].blockId").value(cBlock.toString()))
                .andExpect(jsonPath("$.data.items[0].username").value(c.username()))
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty());
        http.blocked(a.id(), "")
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[1].blockId").value(bBlock.toString()));
        http.unblock(a.id(), cBlock).andExpect(status().isNoContent());
        http.blocked(a.id(), "")
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].blockId").value(bBlock.toString()));
        http.blocked(b.id(), "").andExpect(jsonPath("$.data.items.length()").value(0));
    }

    private static UUID blockId(org.springframework.test.web.servlet.ResultActions answer) throws Exception {
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(answer.andReturn().getResponse().getContentAsString(),
                "$.data.blockId"));
    }

    private static long openBlocks(UUID customer, UUID blocked) {
        return OWNER.sql("""
                        SELECT count(*) FROM review.review_author_block
                         WHERE customer_user_id = :c AND blocked_user_id = :b AND unblocked_at IS NULL""")
                .param("c", customer).param("b", blocked).query(Long.class).single();
    }
}
