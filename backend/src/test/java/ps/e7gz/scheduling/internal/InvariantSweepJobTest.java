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
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantSeverity;
import ps.e7gz.inventory.sweep.api.InvariantSweep;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-32 review carry-forward (PO decisions 4 Oct 2026): the nightly invariant sweep is a recurring
 * {@code technical.scheduled_job}. Its handler calls only {@link InvariantSweep#run()}, outside any transaction, with
 * the job's correlation ID open; the job is finished, and the next night's run inserted, in a transaction of its own.
 * The sweep refuses to run inside a caller's transaction ({@code PROPAGATION_NEVER}).
 */
@Import(InvariantSweepJobTest.RecordingCheckConfig.class)
class InvariantSweepJobTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    /** The correlation ID each sweep run saw (empty: none open). */
    static final List<Optional<UUID>> RUNS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordingCheckConfig {

        @Bean
        InvariantCheck recordingCheck() {
            return new InvariantCheck() {
                @Override
                public String invariant() {
                    return "TEST_E7_40_RECORDING_CHECK";
                }

                @Override
                public InvariantSeverity severity() {
                    return InvariantSeverity.SEV_1;
                }

                @Override
                public List<InvariantViolation> detect(JdbcClient snapshot) {
                    RUNS.add(CorrelationId.current());
                    return List.of();
                }
            };
        }
    }

    @Autowired
    InvariantSweep sweep;

    @Autowired
    JdbcScheduledJobStore store;

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    @Autowired
    Clock clock;

    @BeforeEach
    void noRunsYet() {
        RUNS.clear();
    }

    @Test
    void theSweepRunsNightlyThroughItsRegisteredHandlerWithTheJobsCorrelationId() {
        SchedulerProperties properties = new SchedulerProperties(Duration.ofSeconds(1), 1, 10, Duration.ofMinutes(5), 5,
                Duration.ofSeconds(10), Duration.ofMinutes(10), LocalTime.of(4, 0));
        RecurringJob job = new SchedulingWorkerConfig().invariantSweepJob(sweep, properties);
        assertThat(job.type()).isEqualTo("INVARIANT_SWEEP");
        assertThat(job.recurrence()).isEqualTo(Recurrence.nightlyAt(LocalTime.of(4, 0)));
        DeadlineWorker worker = new DeadlineWorker("sweep-worker", new JobHandlerRegistry(List.of(job)), store, runner,
                appDataSource, properties, clock);
        UUID run = UUID.randomUUID();
        JdbcClient app = JdbcClient.create(appDataSource);
        new TransactionTemplate(new JdbcTransactionManager(appDataSource)).executeWithoutResult(status ->
                assertThat(store.insert(app, run, new NewJob(job.type(), RecurringJob.SUBJECT_TYPE, job.subjectId(),
                        databaseNow().minusSeconds(1), "INVARIANT_SWEEP:test-" + run), run)).isTrue());

        int settled = 0;
        for (int polled = worker.fireDueJobs(); polled > 0; polled = worker.fireDueJobs()) {
            settled += polled;
        }

        assertThat(settled).isGreaterThanOrEqualTo(1);
        assertThat(RUNS).as("one sweep, under the run's own correlation ID").contains(Optional.of(run));
        assertThat(OWNER.sql("SELECT status FROM technical.scheduled_job WHERE id = :id").param("id", run)
                .query(String.class).single()).isEqualTo("DONE");
        List<Instant> nextRuns = OWNER.sql("""
                        SELECT due_at FROM technical.scheduled_job
                         WHERE type = 'INVARIANT_SWEEP' AND status = 'PENDING'""")
                .query(OffsetDateTime.class).list().stream().map(OffsetDateTime::toInstant).toList();
        assertThat(nextRuns).hasSize(1);
        assertThat(nextRuns.getFirst().atZone(ZoneId.of("Africa/Cairo")).toLocalTime()).isEqualTo(LocalTime.of(4, 0));
        assertThat(nextRuns.getFirst()).isAfter(databaseNow());
    }

    @Test
    void theSweepRefusesToRunInsideACallersTransaction() {
        TransactionTemplate outer = new TransactionTemplate(new JdbcTransactionManager(appDataSource));

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> sweep.run()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(RUNS).as("no check ran").isEmpty();
    }

    private static Instant databaseNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
}
