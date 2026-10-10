package ps.e7gz.support.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.context.ApplicationContext;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.scheduling.api.ScheduledJobHandler;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.scheduling.internal.TestDeadlineWorkers;

/**
 * "Run due jobs at T" (E7-45) through the real deadline worker (E7-40): lease, handler and outcome in one command,
 * exactly as in the {@code worker} profile, which is off in tests. The worker polls with the database clock, so "at T"
 * is reached by moving the job and its subject's deadline relative to the database's "now" ({@link #fireAt}).
 *
 * <pre>{@code
 * DueJobs due = DueJobs.of(applicationContext, new VenueResponseExpiryHandler(runner, store, answers));
 * Instant deadline = due.fireAt(scope, t1(bookingId), Boundary.AT,
 *         (context, at) -> Deadlines.move(context, "booking.booking.venue_response_expires_at", bookingId, at));
 * assertThat(DueJobs.job(t1(bookingId)).status()).isEqualTo("DONE");
 * }</pre>
 */
public final class DueJobs {

    private final TestDeadlineWorkers.Worker worker;
    private final CommandRunner runner;
    private final ScheduledJobs jobs;

    private DueJobs(TestDeadlineWorkers.Worker worker, CommandRunner runner, ScheduledJobs jobs) {
        this.worker = worker;
        this.runner = runner;
        this.jobs = jobs;
    }

    /** A worker for the job types of {@code handlers}; build handlers bound to the {@code worker} profile by hand. */
    public static DueJobs of(ApplicationContext context, ScheduledJobHandler... handlers) {
        return new DueJobs(TestDeadlineWorkers.worker(context, List.of(handlers)), context.getBean(CommandRunner.class),
                context.getBean(ScheduledJobs.class));
    }

    /**
     * Polls until a poll settles nothing: every due job of the handlers' types fires, other tests' included (the
     * database is shared). Returns how many were settled.
     */
    public int runDue() {
        int total = 0;
        for (int settled = worker.fireDueJobs(); settled > 0; settled = worker.fireDueJobs()) {
            total += settled;
        }
        return total;
    }

    /** Fires the job with this idempotency key once, if it is due and free; returns whether it was settled. */
    public boolean fire(String idempotencyKey) {
        return worker.fireIfDue(job(idempotencyKey).id());
    }

    /**
     * The job fired with "now" on {@code side} of its deadline. One command, under {@code scope} (the locks the
     * subject's guards need), stores the deadline {@code commandNow - side.offset()} through {@code moveDeadline} and
     * makes the job due; then the worker fires it once. The fire command samples its own instant a few milliseconds
     * later, still on the same side. Returns the deadline stored.
     */
    public Instant fireAt(CommandScope scope, String idempotencyKey, Boundary side,
                          BiConsumer<CommandContext, Instant> moveDeadline) {
        Instant deadline = runner.run(TestCommands.under(scope, context -> {
            Instant at = side.deadline(context.commandNow());
            moveDeadline.accept(context, at);
            makeDue(context, idempotencyKey, at.isBefore(context.commandNow()) ? at : context.commandNow());
            return at;
        }));
        fire(idempotencyKey);
        return deadline;
    }

    /**
     * Moves a {@code PENDING} job to {@code dueAt}, earlier or later, on the command's connection: {@code PAUSED} and
     * re-armed ({@code technical.job_rearm}), the one path the job guard lets {@code due_at} take.
     */
    public void makeDue(CommandContext context, String idempotencyKey, Instant dueAt) {
        int paused = context.jdbc()
                .sql("UPDATE technical.scheduled_job SET status = 'PAUSED' WHERE idempotency_key = :key AND status = 'PENDING'")
                .param("key", idempotencyKey).update();
        assertThat(paused).as("job %s is PENDING", idempotencyKey).isEqualTo(1);
        jobs.rearm(context, idempotencyKey, dueAt);
    }

    /** The job as stored now, read as the migration owner. */
    public static JobRow job(String idempotencyKey) {
        return PackDb.OWNER.sql("""
                        SELECT id, type, status, due_at, attempt
                          FROM technical.scheduled_job WHERE idempotency_key = :key""")
                .param("key", idempotencyKey)
                .query((rs, n) -> new JobRow(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("status"), rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempt")))
                .single();
    }

    public record JobRow(UUID id, String type, String status, Instant dueAt, int attempt) {
    }
}
