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

    R execute(CommandContext context);
}
