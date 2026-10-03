package ps.e7gz.inventory.sweep.api;

/**
 * The nightly integrity sweep (arch 11 section 4, NFR-26, NFR-28). One call runs every registered
 * {@link InvariantCheck} in one read-only snapshot, logs each violation and hands it to every
 * {@link InvariantViolationReporter}. It changes no business data.
 *
 * <p>This is the entry point the worker's scheduled job calls (E7-40, {@code technical.scheduled_job}); nothing in
 * this module schedules or triggers it, so it never runs by itself on any profile.
 */
public interface InvariantSweep {

    SweepReport run();
}
