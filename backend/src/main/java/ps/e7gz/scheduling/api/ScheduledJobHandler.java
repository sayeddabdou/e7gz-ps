package ps.e7gz.scheduling.api;

/**
 * Fires one job type. Each handler is a Spring bean of the worker profile; the deadline worker finds every bean of this
 * type, so a module adds its timers (T1 venue response expiry, T4 booking start, T5 completion, T6 ...) by adding a
 * handler, without touching the engine (E7-40). One handler per type; the worker never fires a type no handler claims.
 *
 * <p>Two kinds: a {@link CommandJobHandler} whose effect is a business write, committed together with the job's outcome
 * in one command transaction, and a {@link BackgroundJobHandler} for work done outside a transaction.
 */
public sealed interface ScheduledJobHandler permits CommandJobHandler, BackgroundJobHandler {

    /** The {@code technical.scheduled_job.type} it fires, e.g. {@code VENUE_RESPONSE_EXPIRY} ({@code ck_scheduled_job_type_format}). */
    String type();
}
