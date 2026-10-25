package ps.e7gz.common.command;

/**
 * One business write. A command declares, before it runs, its eligibility category (ADR-022) and the rows it locks
 * (arch 04 section 2.1); {@link CommandRunner} opens the transaction and the database command context, then calls
 * {@link #execute}.
 *
 * <p>{@link #execute} may run more than once: on SQLSTATE {@code 40001} the whole transaction is rolled back and the
 * whole body rerun in a new one. Keep it free of side effects outside the database (send notifications through the
 * outbox), and read time only from {@link CommandContext#commandNow()}.
 */
public interface Command<R> {

    EligibilityCategory eligibilityCategory();

    CommandScope scope();

    /**
     * Called by {@link CommandRunner} once at the start of every attempt - the first and each {@code 40001} rerun -
     * before its transaction opens, so before {@code begin_command}. For a wrapper that keeps state per attempt (the
     * Owner Password gate's, E7-114) to start each attempt clean, even one that fails before {@link #execute}. No
     * database access and no other side effect. Does nothing by default; a wrapper that keeps no such state need not
     * forward it.
     *
     * @param attempt the attempt about to start, from 1 (as {@link CommandContext#attempt()} will then report)
     */
    default void beforeAttempt(int attempt) {
    }

    R execute(CommandContext context);
}
