package ps.e7gz.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.common.codes.ScheduledJobStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * {@link ScheduledJobs#supersede} (E7-47, PO decision D1 10 Oct 2026): a command withdraws a job its own transition made
 * unnecessary, in its own transaction. A waiting job ({@code PENDING}, {@code PAUSED}, {@code FAILED}) never fires
 * again; a finished one is left alone; a {@code LEASED} one stays with its lease holder; a rolled-back command
 * supersedes nothing.
 */
class ScheduledJobSupersedeTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));
    private static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired
    CommandRunner runner;

    @Autowired
    JdbcScheduledJobStore store;

    @Autowired
    ScheduledJobs jobs;

    @Autowired
    DataSource appDataSource;

    @Autowired
    Clock clock;

    @Test
    void aPendingJobIsSupersededAndNeverFires() {
        String type = newType();
        NewJob job = schedule(type, now -> now);

        assertThat(supersede(job.idempotencyKey())).isTrue();

        Row row = row(job.idempotencyKey());
        assertThat(row.status()).isEqualTo("SUPERSEDED");
        assertThat(row.completedAt()).isNotNull();
        AtomicInteger fired = new AtomicInteger();
        assertThat(worker(type, fired).fireDueJobs()).isZero();
        assertThat(fired).hasValue(0);
    }

    @Test
    void supersedingTwiceOrAnUnknownKeyChangesNothing() {
        NewJob job = schedule(newType(), now -> FAR_FUTURE);
        assertThat(supersede(job.idempotencyKey())).isTrue();
        Instant completedAt = row(job.idempotencyKey()).completedAt();

        assertThat(supersede(job.idempotencyKey())).as("already SUPERSEDED").isFalse();
        assertThat(row(job.idempotencyKey()).completedAt()).isEqualTo(completedAt);
        assertThat(supersede("T_UNKNOWN:" + UUID.randomUUID())).isFalse();
    }

    @Test
    void aPausedJobIsSupersededToo() {
        NewJob job = schedule(newType(), now -> FAR_FUTURE);
        OWNER.sql("UPDATE technical.scheduled_job SET status = 'PAUSED' WHERE idempotency_key = :key")
                .param("key", job.idempotencyKey()).update();

        assertThat(supersede(job.idempotencyKey())).isTrue();
        assertThat(row(job.idempotencyKey()).status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void aFailedJobIsSupersededAndCanNoLongerBeRearmed() {
        NewJob job = schedule(newType(), now -> now);
        UUID id = idOf(job.idempotencyKey());
        inTransaction(jdbc -> {
            assertThat(store.lease(jdbc, id, "test-worker", Duration.ofMinutes(1))).isTrue();
            store.finish(jdbc, id, "test-worker", ScheduledJobStatus.FAILED, "boom", null);
            return null;
        });

        assertThat(supersede(job.idempotencyKey())).isTrue();
        assertThat(row(job.idempotencyKey()).status()).isEqualTo("SUPERSEDED");
        assertThatThrownBy(() -> run(context -> {
            jobs.rearm(context, job.idempotencyKey(), FAR_FUTURE);
            return null;
        })).hasMessageContaining("PAUSED or FAILED");
    }

    @Test
    void aDoneJobStaysDone() {
        NewJob job = schedule(newType(), now -> now);
        UUID id = idOf(job.idempotencyKey());
        inTransaction(jdbc -> {
            assertThat(store.lease(jdbc, id, "test-worker", Duration.ofMinutes(1))).isTrue();
            store.finish(jdbc, id, "test-worker", ScheduledJobStatus.DONE, null, null);
            return null;
        });

        assertThat(supersede(job.idempotencyKey())).isFalse();
        assertThat(row(job.idempotencyKey()).status()).isEqualTo("DONE");
    }

    @Test
    void aLeasedJobStaysWithItsLeaseHolder() {
        NewJob job = schedule(newType(), now -> now);
        UUID id = idOf(job.idempotencyKey());
        inTransaction(jdbc -> store.lease(jdbc, id, "test-worker", Duration.ofMinutes(1)));

        assertThat(supersede(job.idempotencyKey())).isFalse();
        assertThat(row(job.idempotencyKey()).status()).isEqualTo("LEASED");
        inTransaction(jdbc -> {
            store.finish(jdbc, id, "test-worker", ScheduledJobStatus.SUPERSEDED, null, null);
            return null;
        });
        assertThat(row(job.idempotencyKey()).status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void aRolledBackCommandSupersedesNothing() {
        NewJob job = schedule(newType(), now -> FAR_FUTURE);

        assertThatThrownBy(() -> run(context -> {
            assertThat(jobs.supersede(context, job.idempotencyKey())).isTrue();
            throw new IllegalStateException("roll back");
        })).hasMessage("roll back");

        assertThat(row(job.idempotencyKey()).status()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    record Row(String status, Instant completedAt) {
    }

    private static String newType() {
        return "T_SUP_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private NewJob schedule(String type, Function<Instant, Instant> due) {
        return run(context -> {
            NewJob job = NewJob.of(type, "TEST", UUID.randomUUID(), due.apply(context.commandNow()));
            assertThat(jobs.schedule(context, job)).isTrue();
            return job;
        });
    }

    private boolean supersede(String key) {
        return run(context -> jobs.supersede(context, key));
    }

    private <R> R run(Function<CommandContext, R> body) {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            return runner.run(new Command<R>() {
                @Override
                public EligibilityCategory eligibilityCategory() {
                    return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
                }

                @Override
                public CommandScope scope() {
                    return CommandScope.shared(LockSet.empty());
                }

                @Override
                public R execute(CommandContext context) {
                    return body.apply(context);
                }
            });
        }
    }

    private <R> R inTransaction(Function<JdbcClient, R> body) {
        JdbcClient app = JdbcClient.create(appDataSource);
        return new TransactionTemplate(new JdbcTransactionManager(appDataSource)).execute(status -> body.apply(app));
    }

    private DeadlineWorker worker(String type, AtomicInteger fired) {
        CommandJobHandler handler = new CommandJobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
            }

            @Override
            public LockSet lockSet(ScheduledJob job) {
                return LockSet.empty();
            }

            @Override
            public JobOutcome fire(CommandContext context, ScheduledJob job) {
                fired.incrementAndGet();
                return JobOutcome.done();
            }
        };
        SchedulerProperties properties = new SchedulerProperties(Duration.ofSeconds(1), 1, 10, Duration.ofMinutes(5), 5,
                Duration.ofSeconds(10), Duration.ofMinutes(10), LocalTime.of(4, 0));
        return new DeadlineWorker("supersede-test", new JobHandlerRegistry(List.of(handler)), store, runner,
                appDataSource, properties, clock);
    }

    private static UUID idOf(String key) {
        return OWNER.sql("SELECT id FROM technical.scheduled_job WHERE idempotency_key = :key").param("key", key)
                .query(UUID.class).single();
    }

    private static Row row(String key) {
        return OWNER.sql("SELECT status, completed_at FROM technical.scheduled_job WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, n) -> {
                    OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
                    return new Row(rs.getString("status"), completed == null ? null : completed.toInstant());
                })
                .single();
    }
}
