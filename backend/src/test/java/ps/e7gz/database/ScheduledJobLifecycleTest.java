package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.support.IntegrationTest;

/**
 * v1.1.33 (E7-40, PO decisions 4 Oct 2026): {@code technical.job_finish} and the scheduled-job guard, as the application
 * role with every trigger on. The lease holder can supersede a job or send it back to {@code PENDING} later; nothing
 * else moves a leased job's due time, and the correlation ID never changes.
 */
class ScheduledJobLifecycleTest extends IntegrationTest {

    @Autowired
    DataSource appDataSource;

    private JdbcClient app;
    private TransactionTemplate transaction;
    private UUID job;

    @BeforeEach
    void aLeasedJob() {
        app = JdbcClient.create(appDataSource);
        transaction = new TransactionTemplate(new JdbcTransactionManager(appDataSource));
        job = UUID.randomUUID();
        inTransaction(jdbc -> {
            jdbc.sql("""
                            INSERT INTO technical.scheduled_job (id, type, subject_type, subject_id, due_at, status,
                                                                 idempotency_key, correlation_id)
                            VALUES (:id, 'T_LIFECYCLE', 'TEST', :id, clock_timestamp() - interval '1 minute', 'PENDING',
                                    'T_LIFECYCLE:' || :id, gen_random_uuid())""")
                    .param("id", job).update();
            assertThat(jdbc.sql("SELECT technical.job_lease(:id, 'worker-a', interval '1 hour')").param("id", job)
                    .query(Boolean.class).single()).isTrue();
        });
    }

    @Test
    void theLeaseHolderSupersedesAJob() {
        inTransaction(jdbc -> finish(jdbc, "'worker-a', 'SUPERSEDED'"));

        assertThat(column("status")).isEqualTo("SUPERSEDED");
        assertThat(column("completed_at")).isNotNull();
        assertThat(column("leased_by")).isNull();
        assertThat(app.sql("SELECT technical.job_lease(:id, 'worker-b', interval '1 hour')").param("id", job)
                .query(Boolean.class).single()).as("a SUPERSEDED job is never leased again").isFalse();
    }

    @Test
    void theLeaseHolderSendsAJobBackLater() {
        OffsetDateTime due = dueAt();

        inTransaction(jdbc -> finish(jdbc, "'worker-a', 'PENDING', 'backing off', due_at + interval '10 minutes'"));

        assertThat(column("status")).isEqualTo("PENDING");
        assertThat(dueAt()).isEqualTo(due.plusMinutes(10));
        assertThat(column("attempt")).isEqualTo("1");
        assertThat(column("last_error")).isEqualTo("backing off");
        assertThat(column("completed_at")).isNull();
        assertThat(column("leased_by")).isNull();
    }

    @Test
    void theFourArgumentCallStillFinishesWithoutMovingTheJob() {
        OffsetDateTime due = dueAt();

        inTransaction(jdbc -> finish(jdbc, "'worker-a', 'DONE'"));

        assertThat(column("status")).isEqualTo("DONE");
        assertThat(dueAt()).isEqualTo(due);
    }

    @Test
    void aJobIsOnlyEverMovedLaterAndOnlyBackToPending() {
        assertRefused("A job is only ever moved later",
                jdbc -> finish(jdbc, "'worker-a', 'PENDING', NULL, due_at - interval '1 minute'"));
        assertRefused("A job is only ever moved later", jdbc -> finish(jdbc, "'worker-a', 'PENDING', NULL, due_at"));
        assertRefused("Only a job sent back to PENDING takes a new due time",
                jdbc -> finish(jdbc, "'worker-a', 'DONE', NULL, due_at + interval '1 minute'"));
        assertRefused("Invalid job outcome", jdbc -> finish(jdbc, "'worker-a', 'PAUSED'"));
        assertThat(column("status")).isEqualTo("LEASED");
    }

    @Test
    void onlyTheLeaseHolderFinishesAJob() {
        assertRefused("Only the current lease holder can finish this job", jdbc -> finish(jdbc, "'worker-b', 'DONE'"));
    }

    @Test
    void aPlainUpdateNeverMovesALeasedJobNorChangesItsCorrelation() {
        assertRefused("due_at changes only through the explicit pause/re-arm path or job_finish", jdbc -> jdbc.sql("""
                        UPDATE technical.scheduled_job
                           SET status = 'PENDING', leased_at = NULL, leased_until = NULL, leased_by = NULL,
                               due_at = due_at + interval '1 hour'
                         WHERE id = :id""").param("id", job).update());
        assertRefused("Job type/subject/idempotency/correlation identity is immutable", jdbc -> jdbc.sql(
                "UPDATE technical.scheduled_job SET correlation_id = gen_random_uuid() WHERE id = :id")
                .param("id", job).update());
        assertThat(column("status")).isEqualTo("LEASED");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void finish(JdbcClient jdbc, String arguments) {
        jdbc.sql("SELECT technical.job_finish(id, " + arguments + ") FROM technical.scheduled_job WHERE id = :id")
                .param("id", job).query().listOfRows();
    }

    private void inTransaction(Consumer<JdbcClient> body) {
        transaction.executeWithoutResult(status -> body.accept(app));
    }

    private void assertRefused(String message, Consumer<JdbcClient> body) {
        assertThatThrownBy(() -> inTransaction(body)).isInstanceOf(DataAccessException.class).hasMessageContaining(message);
    }

    private String column(String name) {
        return app.sql("SELECT " + name + "::text FROM technical.scheduled_job WHERE id = :id").param("id", job)
                .query((rs, row) -> rs.getString(1)).list().getFirst();
    }

    private OffsetDateTime dueAt() {
        return app.sql("SELECT due_at FROM technical.scheduled_job WHERE id = :id").param("id", job)
                .query(OffsetDateTime.class).single();
    }
}
