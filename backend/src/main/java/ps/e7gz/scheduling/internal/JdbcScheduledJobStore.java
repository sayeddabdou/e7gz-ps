package ps.e7gz.scheduling.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.ScheduledJobStatus;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * SQL over {@code technical.scheduled_job}. A job's status and due instant change only through the database functions
 * {@code technical.job_lease} / {@code job_finish} / {@code job_rearm}, never a plain {@code UPDATE} (arch 08 section
 * 2.4; the lifecycle guard refuses one) - except {@link #supersede}, a status-only move to {@code SUPERSEDED} that the
 * guard allows and no function covers. Every method runs on the {@link JdbcClient} it is given: the caller's command or
 * transaction.
 */
@Component
class JdbcScheduledJobStore {

    private static final String COLUMNS =
            "id, type, subject_type, subject_id, due_at, attempt, idempotency_key, correlation_id";

    /**
     * Due jobs of the given types, oldest first: {@code PENDING} and due, or {@code LEASED} by a worker whose lease ran
     * out. Rows another worker is firing right now are locked and skipped; the locks of this statement go with its
     * transaction. Times are the database's ({@code now()}, arch 08 sections 2.1 and 8): unlike
     * {@code clock_timestamp()} it lets the poll use the {@code (status, due_at)} index, so it reads only due rows
     * however many jobs wait; {@code job_lease} checks the due instant again with the exact clock.
     */
    List<ScheduledJob> due(JdbcClient jdbc, Collection<String> types, Collection<UUID> skip, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM technical.scheduled_job
                        WHERE type = ANY(:types)
                          AND ((status = 'PENDING' AND due_at <= now())
                               OR (status = 'LEASED' AND leased_until <= now()))
                          AND NOT (id = ANY(:skip))
                        ORDER BY due_at, id
                        LIMIT :limit
                          FOR UPDATE SKIP LOCKED""")
                .param("types", types.toArray(String[]::new))
                .param("skip", skip.toArray(UUID[]::new))
                .param("limit", limit)
                .query(JdbcScheduledJobStore::job).list();
    }

    /** {@code technical.job_lease}: false when the job is not due, already taken, or locked by another worker. */
    boolean lease(JdbcClient jdbc, UUID id, String worker, Duration lease) {
        return jdbc.sql("SELECT technical.job_lease(:id, :worker, :millis * interval '1 millisecond')")
                .param("id", id).param("worker", worker).param("millis", lease.toMillis())
                .query(Boolean.class).single();
    }

    ScheduledJob get(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM technical.scheduled_job WHERE id = :id")
                .param("id", id).query(JdbcScheduledJobStore::job).single();
    }

    /**
     * {@code technical.job_finish} by the lease holder. {@code dueAt} goes with {@code PENDING} only and must be later
     * than the job's due instant; {@code error} is kept as {@code last_error} for {@code PENDING} and {@code FAILED}.
     */
    void finish(JdbcClient jdbc, UUID id, String worker, ScheduledJobStatus status, String error, Instant dueAt) {
        finish(jdbc, id, worker, status, error, dueAt, false);
    }

    /** The job's one row moves to its next run at {@code dueAt}, attempts starting again ({@code p_next_run}). */
    void moveToNextRun(JdbcClient jdbc, UUID id, String worker, String error, Instant dueAt) {
        finish(jdbc, id, worker, ScheduledJobStatus.PENDING, error, dueAt, true);
    }

    private void finish(JdbcClient jdbc, UUID id, String worker, ScheduledJobStatus status, String error, Instant dueAt,
                        boolean nextRun) {
        jdbc.sql("""
                        SELECT technical.job_finish(:id, :worker, :status, CAST(:error AS text),
                                                    CAST(:dueAt AS timestamptz), :nextRun)""")
                .param("id", id).param("worker", worker).param("status", status.name())
                .param("error", error).param("dueAt", dueAt == null ? null : utc(dueAt)).param("nextRun", nextRun)
                .query().singleRow();
    }

    /** {@code technical.job_rearm} of the job with this key ({@code PAUSED} or {@code FAILED} only). */
    void rearm(JdbcClient jdbc, String idempotencyKey, Instant dueAt) {
        List<UUID> rearmed = jdbc.sql("""
                        SELECT id, technical.job_rearm(id, :dueAt)
                          FROM technical.scheduled_job
                         WHERE idempotency_key = :key""")
                .param("key", idempotencyKey).param("dueAt", utc(dueAt))
                .query((rs, row) -> rs.getObject("id", UUID.class)).list();
        if (rearmed.isEmpty()) {
            throw new IllegalStateException("No scheduled job has the key " + idempotencyKey);
        }
    }

    /**
     * {@code PENDING} / {@code PAUSED} / {@code FAILED} {@code -> SUPERSEDED} of the job with this key, the transitions
     * {@code guard_scheduled_job} allows a command (table comment, v1.1.25); false when nothing moved. A
     * {@code LEASED} job is left to its lease holder: only {@code job_finish} may end a lease. There is no
     * {@code job_supersede} producer function, so this is the one guarded {@code UPDATE} of status outside them; the
     * guard and the lifecycle CHECK refuse anything else (E7-47, PO decision D1 10 Oct 2026).
     */
    boolean supersede(JdbcClient jdbc, String idempotencyKey) {
        return jdbc.sql("""
                        UPDATE technical.scheduled_job
                           SET status = :superseded, completed_at = clock_timestamp()
                         WHERE idempotency_key = :key
                           AND status IN (:pending, :paused, :failed)""")
                .param("superseded", ScheduledJobStatus.SUPERSEDED.name()).param("key", idempotencyKey)
                .param("pending", ScheduledJobStatus.PENDING.name()).param("paused", ScheduledJobStatus.PAUSED.name())
                .param("failed", ScheduledJobStatus.FAILED.name())
                .update() == 1;
    }

    /** A new {@code PENDING} job; false when its idempotency key is taken (nothing changes then). */
    boolean insert(JdbcClient jdbc, UUID id, NewJob job, UUID correlationId) {
        return jdbc.sql("""
                        INSERT INTO technical.scheduled_job (id, type, subject_type, subject_id, due_at, status,
                                                             idempotency_key, correlation_id)
                        VALUES (:id, :type, :subjectType, :subjectId, :dueAt, :status, :key, :correlation)
                        ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("id", id).param("type", job.type()).param("subjectType", job.subjectType())
                .param("subjectId", job.subjectId()).param("dueAt", utc(job.dueAt()))
                .param("status", ScheduledJobStatus.PENDING.name()).param("key", job.idempotencyKey())
                .param("correlation", correlationId)
                .update() == 1;
    }

    /** The run (or, for a job of one moving row, the row) due at {@code dueAt}; its correlation ID is its own id. */
    boolean insertOccurrence(JdbcClient jdbc, RecurringJob job, Instant dueAt) {
        UUID id = UUID.randomUUID();
        return insert(jdbc, id, new NewJob(job.type(), RecurringJob.SUBJECT_TYPE, job.subjectId(), dueAt,
                job.idempotencyKey(dueAt)), id);
    }

    /** Whether the type has a job waiting or running ({@code PENDING} or {@code LEASED}). */
    boolean hasLiveJob(JdbcClient jdbc, String type) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM technical.scheduled_job
                                        WHERE type = :type AND status IN (:pending, :leased))""")
                .param("type", type).param("pending", ScheduledJobStatus.PENDING.name())
                .param("leased", ScheduledJobStatus.LEASED.name())
                .query(Boolean.class).single();
    }

    /** The database clock (arch 08 section 8: workers compare against the database, not the JVM). */
    Instant now(JdbcClient jdbc) {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Wakes every listening worker when the transaction commits; nothing is sent if it rolls back. */
    void wakeWorkers(JdbcClient jdbc) {
        jdbc.sql("SELECT pg_notify(:channel, '')").param("channel", SchedulerLoop.CHANNEL).query().singleRow();
    }

    private static ScheduledJob job(ResultSet rs, int row) throws SQLException {
        return new ScheduledJob(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getObject("due_at", OffsetDateTime.class).toInstant(),
                rs.getInt("attempt"), rs.getString("idempotency_key"), rs.getObject("correlation_id", UUID.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
