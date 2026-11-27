package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * v1.1.47 (E7-102, PCR-014): the database side of a Review author block, written as the owner with every trigger on.
 * A block is written only inside a command, at its instant; at most one block per pair is in force; the unblock is
 * the one change and happens once; nothing is deleted; a Customer cannot block themself.
 */
class ReviewAuthorBlockDbTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private UUID customer;
    private UUID author;

    @BeforeEach
    void twoCustomers() {
        customer = aUser().verified().create().id();
        author = aUser().verified().create().id();
    }

    @Test
    void aBlockIsWrittenOnlyInsideACommandAtItsInstant() {
        assertThatThrownBy(() -> OWNER.sql("""
                        INSERT INTO review.review_author_block (id, customer_user_id, blocked_user_id, blocked_at)
                        VALUES (:id, :c, :a, now())""")
                .param("id", UUID.randomUUID()).param("c", customer).param("a", author).update())
                .hasMessageContaining("Missing trusted Tournament command context");
        assertThatThrownBy(() -> inCommand("""
                INSERT INTO review.review_author_block (id, customer_user_id, blocked_user_id, blocked_at)
                VALUES ('%s', '%s', '%s', now() - interval '1 day');""".formatted(UUID.randomUUID(), customer, author)))
                .hasMessageContaining("dated its command instant");
        UUID id = block();
        assertThat(open(customer, author)).isEqualTo(1);
        assertThat(OWNER.sql("SELECT unblocked_at IS NULL FROM review.review_author_block WHERE id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    void onlyOneBlockPerPairIsInForce() {
        block();
        assertThatThrownBy(this::block).hasMessageContaining("ux_review_author_block_open");
    }

    @Test
    void theUnblockHappensOnceAndALaterBlockIsANewRow() {
        UUID first = block();
        unblock(first);
        assertThat(open(customer, author)).isZero();
        assertThatThrownBy(() -> unblock(first)).hasMessageContaining("lifted Review author block is immutable");
        UUID second = block();
        assertThat(second).isNotEqualTo(first);
        assertThat(open(customer, author)).isEqualTo(1);
        assertThatThrownBy(() -> inCommand("UPDATE review.review_author_block SET blocked_user_id = '%s' WHERE id = '%s';"
                .formatted(customer, second))).hasMessageContaining("immutable");
        assertThatThrownBy(() -> OWNER.sql("UPDATE review.review_author_block SET unblocked_at = now() WHERE id = :id")
                .param("id", second).update()).hasMessageContaining("Missing trusted Tournament command context");
    }

    @Test
    void aBlockIsNeverDeletedAndNobodyBlocksThemself() {
        UUID id = block();
        assertThatThrownBy(() -> OWNER.sql("DELETE FROM review.review_author_block WHERE id = :id")
                .param("id", id).update()).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> OWNER.sql("TRUNCATE review.review_author_block").update())
                .hasMessageContaining("TRUNCATE");
        assertThatThrownBy(() -> inCommand("""
                INSERT INTO review.review_author_block (id, customer_user_id, blocked_user_id, blocked_at)
                VALUES ('%s', '%s', '%s', tournament.command_now());""".formatted(UUID.randomUUID(), customer, customer)))
                .hasMessageContaining("ck_review_author_block_self");
    }

    private UUID block() {
        UUID id = UUID.randomUUID();
        inCommand("""
                INSERT INTO review.review_author_block (id, customer_user_id, blocked_user_id, blocked_at)
                VALUES ('%s', '%s', '%s', tournament.command_now());""".formatted(id, customer, author));
        return id;
    }

    private void unblock(UUID id) {
        inCommand("UPDATE review.review_author_block SET unblocked_at = tournament.command_now() WHERE id = '%s';"
                .formatted(id));
    }

    private static long open(UUID customer, UUID author) {
        return OWNER.sql("""
                        SELECT count(*) FROM review.review_author_block
                         WHERE customer_user_id = :c AND blocked_user_id = :a AND unblocked_at IS NULL""")
                .param("c", customer).param("a", author).query(Long.class).single();
    }

    /** One transaction: the command context first, then the statement (test ids only, no user input). */
    private static void inCommand(String statement) {
        OWNER.sql("""
                DO $t$
                BEGIN
                 PERFORM tournament.begin_command(ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[]);
                 %s
                END;
                $t$""".formatted(statement)).update();
    }
}
