package ps.e7gz.review.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.UUID;
import org.postgresql.Driver;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.PostgresTestContainer;

/** HTTP helpers shared by the E7-102 review route tests: tokens, the calls, and owner reads of the review tables. */
final class ReviewHttpSupport {

    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private final MockMvc mvc;
    private final AccessTokens tokens;

    ReviewHttpSupport(MockMvc mvc, AccessTokens tokens) {
        this.mvc = mvc;
        this.tokens = tokens;
    }

    String customer(UUID userId) {
        return "Bearer " + tokens.issue(AuthSubjectType.CUSTOMER, userId);
    }

    String admin(UUID adminId) {
        return "Bearer " + tokens.issue(AuthSubjectType.ADMIN, adminId);
    }

    String venue(UUID venueAccountId) {
        return "Bearer " + tokens.issue(AuthSubjectType.VENUE, venueAccountId);
    }

    ResultActions write(UUID user, UUID venue, int rating, String comment) throws Exception {
        return mvc.perform(post("/api/v1/customer/venues/{v}/review", venue).header("Authorization", customer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body(rating, comment)));
    }

    ResultActions edit(UUID user, UUID venue, int rating, String comment) throws Exception {
        return mvc.perform(patch("/api/v1/customer/venues/{v}/review", venue).header("Authorization", customer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body(rating, comment)));
    }

    ResultActions remove(UUID user, UUID venue) throws Exception {
        return mvc.perform(delete("/api/v1/customer/venues/{v}/review", venue).header("Authorization", customer(user)));
    }

    ResultActions mine(UUID user, UUID venue) throws Exception {
        return mvc.perform(get("/api/v1/customer/venues/{v}/review", venue).header("Authorization", customer(user)));
    }

    ResultActions list(UUID reader, UUID venue, String query) throws Exception {
        return mvc.perform(get("/api/v1/customer/venues/{v}/reviews" + query, venue)
                .header("Authorization", customer(reader)));
    }

    ResultActions report(UUID reporter, UUID review, String json) throws Exception {
        return mvc.perform(post("/api/v1/customer/reviews/{r}/report", review)
                .header("Authorization", customer(reporter)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions blockAuthor(UUID customer, UUID review) throws Exception {
        return mvc.perform(post("/api/v1/customer/reviews/{r}/block-author", review)
                .header("Authorization", customer(customer)));
    }

    ResultActions blocked(UUID customer, String query) throws Exception {
        return mvc.perform(get("/api/v1/customer/blocked-users" + query).header("Authorization", customer(customer)));
    }

    ResultActions unblock(UUID customer, UUID blockedUser) throws Exception {
        return mvc.perform(post("/api/v1/customer/blocked-users/{u}/unblock", blockedUser)
                .header("Authorization", customer(customer)));
    }

    /** Creates an ACTIVE (or PENDING) review and returns its id. */
    UUID reviewed(UUID user, UUID venue, int rating, String comment) throws Exception {
        String json = write(user, venue, rating, comment).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.data.reviewId"));
    }

    static String body(int rating, String comment) {
        return "{\"rating\":" + rating + ",\"comment\":\"" + comment + "\"}";
    }

    static String status(UUID review) {
        return OWNER.sql("SELECT status FROM review.review WHERE id = :id").param("id", review).query(String.class)
                .single();
    }

    /** {@code [review_count, average_rating]} of the projection, {@code [0, null]} without a row. */
    static Object[] rating(UUID venue) {
        return OWNER.sql("SELECT review_count, average_rating FROM review.venue_rating_stats WHERE venue_id = :v")
                .param("v", venue)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getBigDecimal(2)})
                .optional().orElse(new Object[] {0, (BigDecimal) null});
    }

    static long notifications(UUID user, String code) {
        return OWNER.sql("""
                        SELECT count(*) FROM notification.notification
                         WHERE recipient_type = 'CUSTOMER' AND recipient_id = :u AND code = :c""")
                .param("u", user).param("c", code).query(Long.class).single();
    }
}
