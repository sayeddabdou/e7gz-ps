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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.BackgroundJobHandler;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobHandler;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-40 done-when (ADR-006, arch 08 sections 2 and 9): two workers never run the same job; a worker that dies mid-fire
 * leaves nothing behind and a crashed lease is taken again once it expires; late firing gives the boundary-correct
 * outcome. Also: back-off and FAILED, recurring system jobs, the correlation ID of every run, and the
 * {@code pg_notify('sched')} wake-up. Every test uses job types of its own, so no worker here fires another test's jobs.
 */
class DeadlineWorkerTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));
    /** The effect of a test command job: a row no worker fires, unique per fired job, written in the fire command. */
    private static final String EFFECT = "T_EFFECT";
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

    // ---------------------------------------------------------------------------------- two workers, one job

    @Test
    void twoWorkersPollingAtOnceNeverRunTheSameJob() throws Exception {
        String commandType = newType("CMD");
        String backgroundType = newType("BG");
        Map<UUID, AtomicInteger> fired = new ConcurrentHashMap<>();
        CommandJobHandler command = commandHandler(commandType, (context, job) -> {
            count(fired, job);
            effect(context, job, FAR_FUTURE);
            return JobOutcome.done();
        });
        BackgroundJobHandler background = backgroundHandler(backgroundType, job -> {
            count(fired, job);
            return JobOutcome.done();
        });
        List<UUID> commandJobs = new ArrayList<>();
        List<UUID> backgroundJobs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            commandJobs.add(schedule(commandType, now -> now));
        }
        for (int i = 0; i < 20; i++) {
            backgroundJobs.add(schedule(backgroundType, now -> now));
        }
        SchedulerProperties properties = properties(5, Duration.ofSeconds(10));
        DeadlineWorker a = worker("worker-a", properties, command, background);
        DeadlineWorker b = worker("worker-b", properties, command, background);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (DeadlineWorker worker : List.of(a, b)) {
                Callable<Integer> polling = () -> {
                    start.await();
                    return drain(worker);
                };
                runs.add(pool.submit(polling));
            }
            start.countDown();                                       // both poll the same 50 due jobs at once
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get(60, TimeUnit.SECONDS);
            }
            assertThat(total).as("jobs settled by both workers together").isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }

        List<UUID> all = new ArrayList<>(commandJobs);
        all.addAll(backgroundJobs);
        for (UUID id : all) {
            assertThat(fired.get(id)).as("fires of job %s", id).hasValue(1);
            assertThat(row(id).status()).isEqualTo("DONE");
            assertThat(row(id).attempt()).isEqualTo(1);
        }
        for (UUID id : commandJobs) {
            assertThat(effects(id)).as("effects of job %s", id).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------------------------- crashes and leases

    /** arch 08 section 9, crash mid-fire: the effect and the marking are one transaction, so both or neither. */
    @Test
    void aWorkerThatDiesMidFireLeavesNothingAndTheNextPollFiresTheJob() {
        String type = newType("CRASH");
        AtomicBoolean die = new AtomicBoolean(true);
        CommandJobHandler handler = commandHandler(type, (context, job) -> {
            effect(context, job, FAR_FUTURE);
            if (die.getAndSet(false)) {
                throw new WorkerDied();                              // after the effect, before the marking
            }
            return JobOutcome.done();
        });
        UUID id = schedule(type, now -> now);

        assertThatThrownBy(() -> worker("worker-a", properties(5, Duration.ofSeconds(10)), handler).fireDueJobs())
                .isInstanceOf(WorkerDied.class);

        Row afterCrash = row(id);
        assertThat(afterCrash.status()).isEqualTo("PENDING");
        assertThat(afterCrash.attempt()).as("the lease rolled back with the effect").isZero();
        assertThat(afterCrash.lastError()).isNull();
        assertThat(effects(id)).isZero();

        assertThat(drain(worker("worker-b", properties(5, Duration.ofSeconds(10)), handler))).isEqualTo(1);
        assertThat(row(id).status()).isEqualTo("DONE");
        assertThat(row(id).attempt()).isEqualTo(1);
        assertThat(effects(id)).isEqualTo(1);
    }

    @Test
    void aCrashedLeaseIsTakenAgainOnlyAfterItExpires() throws InterruptedException {
        String type = newType("LEASE");
        List<Integer> ranAtAttempt = new CopyOnWriteArrayList<>();
        BackgroundJobHandler handler = backgroundHandler(type, job -> {
            ranAtAttempt.add(job.attempt());
            return JobOutcome.done();
        });
        UUID shortLease = schedule(type, now -> now);
        UUID longLease = schedule(type, now -> now);
        // Worker A commits both leases, then dies before running either job.
        JdbcClient app = JdbcClient.create(appDataSource);
        new TransactionTemplate(new JdbcTransactionManager(appDataSource)).executeWithoutResult(status -> {
            assertThat(store.lease(app, shortLease, "worker-a", Duration.ofMillis(500))).isTrue();
            assertThat(store.lease(app, longLease, "worker-a", Duration.ofHours(1))).isTrue();
        });
        DeadlineWorker b = worker("worker-b", properties(5, Duration.ofSeconds(10)), handler);

        assertThat(b.fireDueJobs()).as("both leases are still held").isZero();
        waitUntilDatabaseTime(leasedUntil(shortLease));

        assertThat(drain(b)).isEqualTo(1);
        assertThat(ranAtAttempt).containsExactly(2);
        assertThat(row(shortLease).status()).isEqualTo("DONE");
        assertThat(row(shortLease).attempt()).isEqualTo(2);
        assertThat(row(longLease).status()).isEqualTo("LEASED");
        assertThat(row(longLease).attempt()).isEqualTo(1);
    }

    @Test
    void aFailingJobBacksOffAndIsFailedWhenItsAttemptsAreUsedUp() throws InterruptedException {
        String type = newType("FAIL");
        CommandJobHandler handler = commandHandler(type, (context, job) -> {
            effect(context, job, FAR_FUTURE);
            throw new IllegalStateException("handler broke");
        });
        UUID id = schedule(type, now -> now);
        Instant firstDue = row(id).dueAt();
        DeadlineWorker worker = worker("worker-a", properties(2, Duration.ofMillis(300)), handler);

        assertThat(worker.fireDueJobs()).isEqualTo(1);
        Row retrying = row(id);
        assertThat(retrying.status()).isEqualTo("PENDING");
        assertThat(retrying.attempt()).isEqualTo(1);
        assertThat(retrying.lastError()).contains("IllegalStateException: handler broke");
        assertThat(retrying.dueAt()).isAfterOrEqualTo(firstDue.plusMillis(300));
        assertThat(retrying.completedAt()).isNull();
        assertThat(worker.fireDueJobs()).as("backing off").isZero();

        waitUntilDatabaseTime(retrying.dueAt());
        assertThat(worker.fireDueJobs()).isEqualTo(1);
        Row failed = row(id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.attempt()).isEqualTo(2);
        assertThat(failed.lastError()).contains("handler broke");
        assertThat(failed.completedAt()).isNotNull();
        assertThat(effects(id)).as("every effect rolled back with its attempt").isZero();
        assertThat(worker.fireDueJobs()).as("a FAILED job never fires again").isZero();
    }

    // ----------------------------------------------------------------------------------------- boundary rule

    /** arch 08 sections 2.2 and 9 (NFR-14): the outcome comes from the stored deadline, never from when the worker ran. */
    @Test
    void lateFiringHasTheSameOutcomeAsOnTimeFiring() {
        String type = newType("BOUNDARY");
        Map<UUID, Deadline> state = new ConcurrentHashMap<>();      // the authoritative state the handler re-reads
        CommandJobHandler handler = commandHandler(type, (context, job) -> {
            Deadline deadline = state.get(job.subjectId());
            if (!deadline.open()) {
                return JobOutcome.superseded("no longer open");
            }
            if (context.commandNow().isBefore(deadline.at())) {
                return JobOutcome.rescheduleAt(deadline.at());
            }
            effect(context, job, deadline.at());                    // effective at the deadline
            return JobOutcome.done();
        });
        Instant now = databaseNow();
        UUID late = subjectWithDeadline(state, now.minus(Duration.ofMinutes(5)), true);
        UUID onTime = subjectWithDeadline(state, now, true);
        UUID movedLater = subjectWithDeadline(state, now.plus(Duration.ofHours(1)), true);
        UUID closed = subjectWithDeadline(state, now.minusSeconds(1), false);
        UUID lateJob = scheduleFor(type, late, state.get(late).at());
        UUID onTimeJob = scheduleFor(type, onTime, state.get(onTime).at());
        UUID movedJob = scheduleFor(type, movedLater, now.minusSeconds(1));   // written before the deadline moved
        UUID closedJob = scheduleFor(type, closed, now.minusSeconds(1));
        UUID notDueJob = scheduleFor(type, subjectWithDeadline(state, now.plus(Duration.ofHours(1)), true),
                now.plus(Duration.ofHours(1)));

        assertThat(drain(worker("worker-a", properties(5, Duration.ofSeconds(10)), handler))).isEqualTo(4);

        assertThat(outcome(lateJob, state.get(late).at())).isEqualTo(outcome(onTimeJob, state.get(onTime).at()))
                .isEqualTo(new Outcome("DONE", 1, 1, true));
        Row moved = row(movedJob);
        assertThat(moved.status()).isEqualTo("PENDING");
        assertThat(moved.dueAt()).isEqualTo(state.get(movedLater).at());
        assertThat(moved.attempt()).isEqualTo(1);
        assertThat(moved.lastError()).isNull();
        assertThat(effects(movedJob)).isZero();
        assertThat(row(closedJob).status()).isEqualTo("SUPERSEDED");
        assertThat(row(closedJob).completedAt()).isNotNull();
        assertThat(effects(closedJob)).isZero();
        assertThat(row(notDueJob).status()).isEqualTo("PENDING");
        assertThat(row(notDueJob).attempt()).as("never fired before it is due").isZero();
    }

    // ---------------------------------------------------------------------------------------- recurring jobs

    /** PO decision 9 Oct 2026: a frequent system job keeps one row that moves to its next run; none pile up. */
    @Test
    void aFrequentJobStartsWithOneRowOfItsOwn() {
        String type = newType("EVERY_START");
        RecurringJob recurring = new RecurringJob(type, Recurrence.every(Duration.ofHours(1)), () -> { });
        DeadlineWorker worker = worker("worker-a", properties(5, Duration.ofSeconds(10)), recurring);

        worker.scheduleRecurringJobs();
        worker.scheduleRecurringJobs();                              // a second worker start changes nothing

        List<Row> rows = rowsOf(type);
        assertThat(rows).hasSize(1);
        Row only = rows.getFirst();
        assertThat(only.status()).isEqualTo("PENDING");
        assertThat(only.dueAt()).isEqualTo(Recurrence.every(Duration.ofHours(1)).next(databaseNow()));
        assertThat(only.idempotencyKey()).isEqualTo(type + ":EVERY");
        assertThat(only.subjectType()).isEqualTo("SYSTEM");
        assertThat(only.subjectId()).isEqualTo(recurring.subjectId());
        assertThat(only.correlationId()).as("a system job is its own correlation").isEqualTo(only.id());
    }

    @Test
    void aFrequentJobRunsOnceUnderItsOwnIdAndItsOneRowMovesToTheNextRun() {
        String type = newType("EVERY_RUN");
        List<UUID> correlations = new CopyOnWriteArrayList<>();
        AtomicBoolean inTransaction = new AtomicBoolean();
        RecurringJob recurring = new RecurringJob(type, Recurrence.every(Duration.ofHours(1)), () -> {
            correlations.add(CorrelationId.require());
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
        });
        DeadlineWorker worker = worker("worker-a", properties(5, Duration.ofSeconds(10)), recurring);
        UUID row = insertOccurrence(recurring, databaseNow().minusSeconds(1));   // its run is due now
        worker.scheduleRecurringJobs();                              // it has a waiting row: nothing is added

        assertThat(drain(worker)).isEqualTo(1);

        assertThat(correlations).containsExactly(row);
        assertThat(inTransaction).as("a background job runs outside any transaction").isFalse();
        Row moved = row(row);
        assertThat(moved.status()).isEqualTo("PENDING");
        assertThat(moved.attempt()).as("attempts start again for the next run").isZero();
        assertThat(moved.dueAt()).isEqualTo(Recurrence.every(Duration.ofHours(1)).next(databaseNow()));
        assertThat(moved.lastError()).isNull();
        assertThat(moved.completedAt()).isNull();
        assertThat(rowsOf(type)).as("still one row").extracting(Row::id).containsExactly(row);
        assertThat(drain(worker)).as("the next run is not due yet").isZero();
    }

    @Test
    void aFailedFrequentRunIsLoggedAndTheNextRunIsStillOnTime() {
        String type = newType("EVERY_FAIL");
        RecurringJob recurring = new RecurringJob(type, Recurrence.every(Duration.ofHours(1)), () -> {
            throw new IllegalStateException("broken pass");
        });
        UUID row = insertOccurrence(recurring, databaseNow().minusSeconds(1));

        assertThat(drain(worker("worker-a", properties(1, Duration.ofSeconds(10)), recurring))).isEqualTo(1);

        Row moved = row(row);
        assertThat(moved.status()).as("never FAILED: the pass repeats every interval").isEqualTo("PENDING");
        assertThat(moved.attempt()).isZero();
        assertThat(moved.lastError()).contains("broken pass");
        assertThat(moved.dueAt()).isAfter(databaseNow());
        assertThat(rowsOf(type)).hasSize(1);
    }

    @Test
    void aFrequentRunWhoseWorkersKeptDyingIsSkippedToTheNextRun() throws InterruptedException {
        String type = newType("EVERY_DIED");
        AtomicInteger ran = new AtomicInteger();
        RecurringJob recurring = new RecurringJob(type, Recurrence.every(Duration.ofHours(1)), ran::incrementAndGet);
        UUID row = insertOccurrence(recurring, databaseNow().minusSeconds(1));
        JdbcClient app = JdbcClient.create(appDataSource);
        for (String dead : List.of("dead-1", "dead-2")) {            // two workers lease it and die
            new TransactionTemplate(new JdbcTransactionManager(appDataSource)).executeWithoutResult(status ->
                    assertThat(store.lease(app, row, dead, Duration.ofMillis(100))).isTrue());
            waitUntilDatabaseTime(leasedUntil(row));
        }

        assertThat(drain(worker("worker-a", properties(1, Duration.ofSeconds(10)), recurring))).isEqualTo(1);

        assertThat(ran).as("not run a third time").hasValue(0);
        Row moved = row(row);
        assertThat(moved.status()).isEqualTo("PENDING");
        assertThat(moved.attempt()).isZero();
        assertThat(moved.lastError()).contains("attempts exhausted");
        assertThat(moved.dueAt()).isAfter(databaseNow());
    }

    @Test
    void aNightlyRunThatFailsStillSchedulesTheNextNight() {
        String type = newType("NIGHT_FAIL");
        Recurrence.Nightly at4 = new Recurrence.Nightly(LocalTime.of(4, 0));
        RecurringJob recurring = new RecurringJob(type, at4, () -> {
            throw new IllegalStateException("broken night");
        });
        // A real past run at 04:00 Cairo: due a second ago, it would share the next night's key before 04:00 Cairo.
        Instant lastNight = NightlyRuns.lastRunAtOrBefore(at4, databaseNow());
        UUID run = insertOccurrence(recurring, lastNight);

        assertThat(drain(worker("worker-a", properties(1, Duration.ofSeconds(10)), recurring))).isEqualTo(1);

        assertThat(row(run).status()).isEqualTo("FAILED");
        assertThat(row(run).lastError()).contains("broken night");
        assertThat(rowsOf(type)).filteredOn(r -> r.status().equals("PENDING")).hasSize(1)
                .allSatisfy(r -> assertThat(r.dueAt()).isAfter(databaseNow()))
                .allSatisfy(r -> assertThat(r.idempotencyKey()).isNotEqualTo(recurring.idempotencyKey(lastNight))
                        .isEqualTo(recurring.idempotencyKey(r.dueAt())));
    }

    @Test
    void aNightlyJobIsKeyedByItsBusinessDateInCairo() {
        String type = newType("NIGHTLY");
        RecurringJob nightly = new RecurringJob(type, Recurrence.nightlyAt(LocalTime.of(4, 0)), () -> { });

        worker("worker-a", properties(5, Duration.ofSeconds(10)), nightly).scheduleRecurringJobs();

        Row next = rowsOf(type).getFirst();
        var local = next.dueAt().atZone(ZoneId.of("Africa/Cairo"));
        assertThat(local.toLocalTime()).isEqualTo(LocalTime.of(4, 0));
        assertThat(next.dueAt()).isAfter(databaseNow()).isBefore(databaseNow().plus(Duration.ofHours(25)));
        assertThat(next.idempotencyKey()).isEqualTo(type + ":" + local.toLocalDate());
    }

    // -------------------------------------------------------------------------------------- loop and wake-up

    @Test
    void aJobDueNowWakesAnIdleWorkerLongBeforeItsTickAndKeepsItsCommandsCorrelationId() throws Exception {
        String type = newType("WAKE");
        CompletableFuture<UUID> ranUnder = new CompletableFuture<>();
        BackgroundJobHandler handler = backgroundHandler(type, job -> {
            ranUnder.complete(CorrelationId.require());
            return JobOutcome.done();
        });
        SchedulerProperties hourlyTick = new SchedulerProperties(Duration.ofHours(1), 1, 10, Duration.ofMinutes(5), 5,
                Duration.ofSeconds(10), Duration.ofMinutes(10), LocalTime.of(4, 0));
        SchedulerLoop loop = new SchedulerLoop(List.of(worker("wake", hourlyTick, handler)), appDataSource,
                hourlyTick.tick());

        loop.start();
        try {
            waitUntilListening();
            Thread.sleep(300);                                       // the first polls are over; the worker now idles
            UUID correlation = UUID.randomUUID();
            try (CorrelationId.Scope ignored = CorrelationId.open(correlation)) {
                runner.run(new Schedule(type, UUID.randomUUID(), now -> now));   // due now: pg_notify at commit
            }
            assertThat(ranUnder.get(10, TimeUnit.SECONDS)).isEqualTo(correlation);
        } finally {
            loop.stop();
        }
        assertThat(loop.isRunning()).isFalse();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().startsWith("e7gz-scheduler-"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private record Deadline(Instant at, boolean open) {
    }

    private record Outcome(String status, int attempt, long effects, boolean effectAtDeadline) {
    }

    record Row(UUID id, String type, String subjectType, UUID subjectId, String status, int attempt, Instant dueAt,
               String lastError, Instant completedAt, String idempotencyKey, UUID correlationId) {
    }

    /** A worker died: an Error the worker does not catch, like the JVM going away mid-transaction. */
    private static final class WorkerDied extends Error {
    }

    /** A job type of this test only: {@code T_<name>_<8 hex>}. */
    private static String newType(String name) {
        return "T_" + name + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static SchedulerProperties properties(int maxAttempts, Duration firstBackoff) {
        return new SchedulerProperties(Duration.ofSeconds(1), 1, 10, Duration.ofMinutes(5), maxAttempts, firstBackoff,
                Duration.ofMinutes(10), LocalTime.of(4, 0));
    }

    private DeadlineWorker worker(String name, SchedulerProperties properties, ScheduledJobHandler... handlers) {
        return new DeadlineWorker(name, new JobHandlerRegistry(List.of(handlers)), store, runner, appDataSource,
                properties, clock);
    }

    /** Polls until a poll settles nothing. */
    private static int drain(DeadlineWorker worker) {
        int total = 0;
        for (int settled = worker.fireDueJobs(); settled > 0; settled = worker.fireDueJobs()) {
            total += settled;
        }
        return total;
    }

    private static void count(Map<UUID, AtomicInteger> fired, ScheduledJob job) {
        fired.computeIfAbsent(job.id(), id -> new AtomicInteger()).incrementAndGet();
    }

    private static CommandJobHandler commandHandler(String type,
                                                    BiFunction<CommandContext, ScheduledJob, JobOutcome> fire) {
        return new CommandJobHandler() {
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
                return fire.apply(context, job);
            }
        };
    }

    private static BackgroundJobHandler backgroundHandler(String type, Function<ScheduledJob, JobOutcome> run) {
        return new BackgroundJobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public JobOutcome run(ScheduledJob job) {
                return run.apply(job);
            }
        };
    }

    /** The effect of a command job, written through the producer API in the fire command's own transaction. */
    private void effect(CommandContext context, ScheduledJob job, Instant at) {
        assertThat(jobs.schedule(context, NewJob.of(EFFECT, "TEST", job.id(), at))).isTrue();
    }

    private long effects(UUID jobId) {
        return OWNER.sql("SELECT count(*) FROM technical.scheduled_job WHERE type = :type AND subject_id = :job")
                .param("type", EFFECT).param("job", jobId).query(Long.class).single();
    }

    private Outcome outcome(UUID jobId, Instant deadline) {
        Row row = row(jobId);
        List<Instant> effectsAt = OWNER.sql("""
                        SELECT due_at FROM technical.scheduled_job WHERE type = :type AND subject_id = :job""")
                .param("type", EFFECT).param("job", jobId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant()).list();
        return new Outcome(row.status(), row.attempt(), effectsAt.size(), effectsAt.equals(List.of(deadline)));
    }

    private UUID subjectWithDeadline(Map<UUID, Deadline> state, Instant at, boolean open) {
        UUID subject = UUID.randomUUID();
        state.put(subject, new Deadline(at, open));
        return subject;
    }

    private UUID schedule(String type, Function<Instant, Instant> due) {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            return runner.run(new Schedule(type, UUID.randomUUID(), due));
        }
    }

    private UUID scheduleFor(String type, UUID subject, Instant dueAt) {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            return runner.run(new Schedule(type, subject, now -> dueAt));
        }
    }

    private UUID insertOccurrence(RecurringJob job, Instant dueAt) {
        JdbcClient app = JdbcClient.create(appDataSource);
        new TransactionTemplate(new JdbcTransactionManager(appDataSource))
                .executeWithoutResult(status -> assertThat(store.insertOccurrence(app, job, dueAt)).isTrue());
        return OWNER.sql("SELECT id FROM technical.scheduled_job WHERE idempotency_key = :key")
                .param("key", job.idempotencyKey(dueAt)).query(UUID.class).single();
    }

    /** Schedules one test job through {@link ScheduledJobs}, as a command would; returns its id. */
    private final class Schedule implements Command<UUID> {

        private final String type;
        private final UUID subject;
        private final Function<Instant, Instant> due;

        Schedule(String type, UUID subject, Function<Instant, Instant> due) {
            this.type = type;
            this.subject = subject;
            this.due = due;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public UUID execute(CommandContext context) {
            NewJob job = NewJob.of(type, "TEST", subject, due.apply(context.commandNow()));
            assertThat(jobs.schedule(context, job)).isTrue();
            assertThat(jobs.schedule(context, job)).as("the same natural key twice").isFalse();
            return context.jdbc().sql("SELECT id FROM technical.scheduled_job WHERE idempotency_key = :key")
                    .param("key", job.idempotencyKey()).query(UUID.class).single();
        }
    }

    private static Row row(UUID id) {
        return OWNER.sql(ROW + " WHERE id = :id").param("id", id).query(DeadlineWorkerTest::mapRow).single();
    }

    private static List<Row> rowsOf(String type) {
        return OWNER.sql(ROW + " WHERE type = :type ORDER BY due_at").param("type", type)
                .query(DeadlineWorkerTest::mapRow).list();
    }

    private static final String ROW = """
            SELECT id, type, subject_type, subject_id, status, attempt, due_at, last_error, completed_at,
                   idempotency_key, correlation_id
              FROM technical.scheduled_job""";

    private static Row mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
        return new Row(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getString("status"), rs.getInt("attempt"),
                rs.getObject("due_at", OffsetDateTime.class).toInstant(), rs.getString("last_error"),
                completed == null ? null : completed.toInstant(), rs.getString("idempotency_key"),
                rs.getObject("correlation_id", UUID.class));
    }

    private static Instant leasedUntil(UUID id) {
        return OWNER.sql("SELECT leased_until FROM technical.scheduled_job WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static Instant databaseNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Workers compare against the database clock (arch 08 section 8), so the tests wait on it too. */
    private static void waitUntilDatabaseTime(Instant instant) throws InterruptedException {
        for (int i = 0; i < 100 && !databaseNow().isAfter(instant); i++) {
            Thread.sleep(50);
        }
        assertThat(databaseNow()).isAfter(instant);
    }

    private static void waitUntilListening() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            long listening = OWNER.sql("SELECT count(*) FROM pg_stat_activity WHERE query = 'LISTEN sched'")
                    .query(Long.class).single();
            if (listening > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the scheduler never listened on 'sched'");
    }
}
