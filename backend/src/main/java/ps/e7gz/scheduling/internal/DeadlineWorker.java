package ps.e7gz.scheduling.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.common.codes.ScheduledJobStatus;
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
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobHandler;

/**
 * The deadline engine of one worker thread (ADR-006, arch 08 sections 2.1-2.3). One call of {@link #fireDueJobs()} polls
 * the due jobs ({@code FOR UPDATE SKIP LOCKED}) and fires them one by one, each lease taken right before its own fire:
 * <ul>
 *   <li>{@link CommandJobHandler}: one command - {@code job_lease}, the handler (re-read state, boundary rule, effect)
 *       and {@code job_finish} commit together, or roll back together;</li>
 *   <li>{@link BackgroundJobHandler}: the lease commits, the handler runs outside any transaction, and the outcome is
 *       recorded in a transaction of its own; a {@link RecurringJob} inserts its next run in that same transaction.</li>
 * </ul>
 * A failed attempt is recorded on the job: back to {@code PENDING} after a growing wait, or {@code FAILED} when its
 * attempts are used up. Several workers - threads or processes - poll the same table safely with no other coordination.
 * The engine has no rule of any job type: every decision is its handler's.
 */
final class DeadlineWorker {

    private static final Logger log = LoggerFactory.getLogger(DeadlineWorker.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final String name;
    private final JobHandlerRegistry handlers;
    private final JdbcScheduledJobStore store;
    private final CommandRunner runner;
    private final SchedulerProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    /** Jobs whose failure could not even be recorded, set aside for a while so one cannot keep the worker spinning. */
    private final Map<UUID, Instant> setAside = new ConcurrentHashMap<>();

    /**
     * @param name the lease holder name ({@code leased_by}), unique per worker thread
     */
    DeadlineWorker(String name, JobHandlerRegistry handlers, JdbcScheduledJobStore store, CommandRunner runner,
                   DataSource appDataSource, SchedulerProperties properties, Clock clock) {
        this.name = name;
        this.handlers = handlers;
        this.store = store;
        this.runner = runner;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(new JdbcTransactionManager(appDataSource));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.jdbc = JdbcClient.create(appDataSource);
    }

    String name() {
        return name;
    }

    /**
     * Polls once and fires every due job it got; returns how many it settled (fired, or recorded as failed). Zero means
     * nothing was due for this worker, and the caller may wait for the next tick.
     */
    int fireDueJobs() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The deadline worker runs outside any transaction");
        }
        Instant now = clock.instant();
        setAside.values().removeIf(until -> !until.isAfter(now));
        List<ScheduledJob> due = store.due(jdbc, handlers.types(), setAside.keySet(), properties.batchSize());
        int settled = 0;
        for (ScheduledJob job : due) {
            if (fire(job)) {
                settled++;
            }
        }
        return settled;
    }

    /**
     * Gives every {@link RecurringJob} with no {@code PENDING} or {@code LEASED} row its next run (worker start-up). Two
     * workers starting together compute the same key, so the second insert changes nothing.
     */
    void scheduleRecurringJobs() {
        for (RecurringJob job : handlers.recurring()) {
            transaction.executeWithoutResult(status -> {
                if (!store.hasLiveJob(jdbc, job.type())) {
                    store.insertOccurrence(jdbc, job, job.recurrence().next(store.now(jdbc)));
                }
            });
        }
    }

    private boolean fire(ScheduledJob job) {
        ScheduledJobHandler handler = handlers.handler(job.type()).orElseThrow();
        try (CorrelationId.Scope ignored = CorrelationId.open(job.correlationId())) {
            return switch (handler) {
                case CommandJobHandler command -> fireInCommand(command, job);
                case BackgroundJobHandler background -> runInBackground(background, job);
            };
        }
    }

    // ------------------------------------------------------------------------------------------- command jobs

    private boolean fireInCommand(CommandJobHandler handler, ScheduledJob job) {
        LockSet locks = LockSet.empty();
        try {
            locks = handler.lockSet(job);
            return runner.run(new Fire(handler, job, locks));
        } catch (RuntimeException failure) {
            return recordFailure(handler.eligibilityCategory(), locks, job, failure);
        }
    }

    /** The fire command: lease, handler and outcome in one transaction (arch 08 section 2.2, property 2). */
    private final class Fire implements Command<Boolean> {

        private final CommandJobHandler handler;
        private final ScheduledJob job;
        private final LockSet locks;

        Fire(CommandJobHandler handler, ScheduledJob job, LockSet locks) {
            this.handler = handler;
            this.job = job;
            this.locks = locks;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return handler.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Boolean execute(CommandContext context) {
            // Never before its due instant, as the command sees it; then only the lease holder fires.
            if (context.commandNow().isBefore(job.dueAt()) || !lease(context.jdbc(), job)) {
                return false;
            }
            ScheduledJob leased = store.get(context.jdbc(), job.id());
            if (exhausted(leased)) {
                failExhausted(context.jdbc(), leased);
                return true;
            }
            record(context.jdbc(), leased, handler.fire(context, leased));
            return true;
        }
    }

    private boolean recordFailure(EligibilityCategory category, LockSet locks, ScheduledJob job,
                                  RuntimeException failure) {
        try {
            ScheduledJobStatus status = runner.run(new RecordFailure(category, locks, job, failure));
            logFailure(job, status, failure);
            return status != null;
        } catch (RuntimeException unrecorded) {
            setAside.put(job.id(), clock.instant().plus(properties.maxBackoff()));
            log.error("{} job {} failed and its failure could not be recorded; set aside for {}", job.type(), job.id(),
                    properties.maxBackoff(), unrecorded);
            return false;
        }
    }

    /** A failed fire, recorded under the same locks: its own lease counts the attempt that rolled back. */
    private final class RecordFailure implements Command<ScheduledJobStatus> {

        private final EligibilityCategory category;
        private final LockSet locks;
        private final ScheduledJob job;
        private final RuntimeException failure;

        RecordFailure(EligibilityCategory category, LockSet locks, ScheduledJob job, RuntimeException failure) {
            this.category = category;
            this.locks = locks;
            this.job = job;
            this.failure = failure;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return category;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public ScheduledJobStatus execute(CommandContext context) {
            if (!lease(context.jdbc(), job)) {
                return null;                                         // another worker has it now
            }
            return retryOrFail(context.jdbc(), store.get(context.jdbc(), job.id()), failure, context.commandNow());
        }
    }

    // ---------------------------------------------------------------------------------------- background jobs

    private boolean runInBackground(BackgroundJobHandler handler, ScheduledJob job) {
        ScheduledJob leased = transaction.execute(status -> lease(jdbc, job) ? store.get(jdbc, job.id()) : null);
        if (leased == null) {
            return false;
        }
        JobOutcome outcome = null;
        RuntimeException failure = null;
        if (!exhausted(leased)) {
            try {
                outcome = handler.run(leased);                       // outside any transaction
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        JobOutcome result = outcome;
        RuntimeException error = failure;
        try {
            ScheduledJobStatus status = transaction.execute(tx -> {
                ScheduledJobStatus settled;
                if (result != null) {
                    settled = record(jdbc, leased, result);
                } else if (error != null) {
                    settled = retryOrFail(jdbc, leased, error, store.now(jdbc));
                } else {
                    settled = failExhausted(jdbc, leased);
                }
                if (handler instanceof RecurringJob recurring && settled != ScheduledJobStatus.PENDING) {
                    store.insertOccurrence(jdbc, recurring, recurring.recurrence().next(store.now(jdbc)));
                }
                return settled;
            });
            if (error != null) {
                logFailure(leased, status, error);
            }
        } catch (RuntimeException unrecorded) {
            log.warn("{} job {} ran but its outcome could not be recorded; it runs again when its lease expires",
                    leased.type(), leased.id(), unrecorded);
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ shared

    private boolean lease(JdbcClient on, ScheduledJob job) {
        return store.lease(on, job.id(), name, properties.lease());
    }

    /** Leased more often than allowed: a worker died during every earlier attempt. */
    private boolean exhausted(ScheduledJob leased) {
        return leased.attempt() > properties.maxAttempts();
    }

    private ScheduledJobStatus failExhausted(JdbcClient on, ScheduledJob leased) {
        store.finish(on, leased.id(), name, ScheduledJobStatus.FAILED,
                "attempts exhausted: no attempt finished within its lease", null);
        log.error("{} job {} FAILED: {} attempts never finished (Sev-2)", leased.type(), leased.id(),
                leased.attempt() - 1);
        return ScheduledJobStatus.FAILED;
    }

    private ScheduledJobStatus record(JdbcClient on, ScheduledJob leased, JobOutcome outcome) {
        ScheduledJobStatus status = switch (outcome) {
            case JobOutcome.Done done -> ScheduledJobStatus.DONE;
            case JobOutcome.Superseded superseded -> ScheduledJobStatus.SUPERSEDED;
            case JobOutcome.Reschedule reschedule -> ScheduledJobStatus.PENDING;
        };
        Instant movedTo = outcome instanceof JobOutcome.Reschedule reschedule ? reschedule.dueAt() : null;
        store.finish(on, leased.id(), name, status, null, movedTo);
        if (outcome instanceof JobOutcome.Superseded superseded) {
            log.debug("{} job {} superseded: {}", leased.type(), leased.id(), superseded.reason());
        }
        return status;
    }

    /** Back to {@code PENDING} after the back-off of this attempt, or {@code FAILED} when it was the last one. */
    private ScheduledJobStatus retryOrFail(JdbcClient on, ScheduledJob leased, RuntimeException failure, Instant now) {
        String error = describe(failure);
        if (leased.attempt() >= properties.maxAttempts()) {
            store.finish(on, leased.id(), name, ScheduledJobStatus.FAILED, error, null);
            return ScheduledJobStatus.FAILED;
        }
        Instant from = now.isAfter(leased.dueAt()) ? now : leased.dueAt();
        store.finish(on, leased.id(), name, ScheduledJobStatus.PENDING, error,
                from.plus(properties.backoff(leased.attempt())));
        return ScheduledJobStatus.PENDING;
    }

    private static void logFailure(ScheduledJob job, ScheduledJobStatus status, RuntimeException failure) {
        if (status == ScheduledJobStatus.FAILED) {
            // Sev-2 (arch 08 section 2.1); the admin alert is raised by E7-56.
            log.error("{} job {} FAILED: its attempts are used up", job.type(), job.id(), failure);
        } else if (status == ScheduledJobStatus.PENDING) {
            log.warn("{} job {} attempt failed; it is retried after its back-off", job.type(), job.id(), failure);
        }
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String text = failure.getClass().getName() + (message == null ? "" : ": " + message);
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
