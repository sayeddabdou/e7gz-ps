package ps.e7gz.scheduling.api;

import java.time.Instant;
import java.util.Objects;

/**
 * What firing a job decided (ADR-006, arch 08 section 2.1). The worker records it on the job through
 * {@code technical.job_finish}; for a {@link CommandJobHandler} in the same transaction as the effect. A failure is not
 * an outcome: the handler throws, and the worker backs the job off or marks it {@code FAILED}.
 */
public sealed interface JobOutcome {

    /** The effect was applied: the job is {@code DONE} and never fires again. */
    static JobOutcome done() {
        return new Done();
    }

    /**
     * The authoritative state no longer needs this job (for example the booking is no longer {@code PENDING}): the job
     * is {@code SUPERSEDED} and never fires again.
     *
     * @param reason for the log only; never shown to anyone
     */
    static JobOutcome superseded(String reason) {
        return new Superseded(reason);
    }

    /**
     * The job fired before its authoritative deadline (the deadline moved later since the job was written): it goes
     * back to {@code PENDING} at {@code dueAt}, which must be later than its current due instant.
     */
    static JobOutcome rescheduleAt(Instant dueAt) {
        return new Reschedule(dueAt);
    }

    record Done() implements JobOutcome {
    }

    record Superseded(String reason) implements JobOutcome {

        public Superseded {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Reschedule(Instant dueAt) implements JobOutcome {

        public Reschedule {
            Objects.requireNonNull(dueAt, "dueAt");
        }
    }
}
