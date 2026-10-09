package ps.e7gz.scheduling.api;

import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * A deadline whose effect is a business write (T1, T4, T5, T6, ...). The worker runs one command through the
 * CommandRunner: it takes the job's lease, calls {@link #fire}, and records the returned outcome, all in that command's
 * single {@code READ COMMITTED} transaction - the effect and the {@code DONE} marking commit together or not at all
 * (ADR-006, arch 08 section 2.2). A worker that dies mid-fire leaves nothing behind: the row is still {@code PENDING} and
 * the next poll picks it up.
 *
 * <p>The boundary rule (arch 08 section 2.2, NFR-14, FR-AVL-15): {@link #fire} re-reads the authoritative state under
 * the command's locks and decides from that state and {@link CommandContext#commandNow()} - never from when the worker
 * ran:
 * <ul>
 *   <li>no longer applicable - {@link JobOutcome#superseded};</li>
 *   <li>{@code commandNow} before the stored deadline (it moved later) - {@link JobOutcome#rescheduleAt} that deadline;</li>
 *   <li>otherwise apply the effect, as of the deadline, and return {@link JobOutcome#done}.</li>
 * </ul>
 * So a job fired five minutes late has the same outcome as one fired on time. Like every command body, {@link #fire}
 * may run more than once (SQLSTATE {@code 40001} retry); keep it free of side effects outside the database. A thrown
 * exception rolls everything back; the worker then backs the job off, or marks it {@code FAILED} when its attempts are
 * used up. Never change a job with a plain {@code UPDATE}.
 */
public non-sealed interface CommandJobHandler extends ScheduledJobHandler {

    EligibilityCategory eligibilityCategory();

    /**
     * The rows the fire command names up front ({@code begin_command}), from the job's subject. Read before the
     * transaction; the command re-reads the state under these locks.
     */
    LockSet lockSet(ScheduledJob job);

    JobOutcome fire(CommandContext context, ScheduledJob job);
}
