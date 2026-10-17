package ps.e7gz.booking.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * TEST-ONLY (E7-113 Phase 3D): a real {@link CommandJobHandler} with a gate around its fire, so a race test controls
 * when the deadline worker's fire command commits. Type, category, lock set and fire are the real handler's; nothing
 * of the decision is replaced. The worker still takes the lease ({@code job_lease}) before {@link #fire} and records the
 * outcome ({@code job_finish}) after it, in the same transaction (E7-40, arch 08 section 2.2).
 *
 * <p>When {@linkplain #gated() gated}, the first fire runs the real handler, records its backend pid, signals
 * {@link #awaitReached} and then waits - inside the worker's open transaction, holding every lock of its
 * {@code begin_command} - until {@link #release}. The wait is bounded: on timeout it throws, the fire rolls back and
 * the locks are freed, so a broken test fails instead of hanging. Every fire is counted, gated or not, so a test can
 * prove the handler never ran.
 */
final class GatedCommandJobHandler implements CommandJobHandler {

    private final CommandJobHandler delegate;
    private final boolean gated;
    private final Duration timeout;
    private final AtomicInteger fires = new AtomicInteger();
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int firePid;

    private GatedCommandJobHandler(CommandJobHandler delegate, boolean gated, Duration timeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gated = gated;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** Pauses after the real fire, before {@code job_finish} and COMMIT, until {@link #release}. */
    static GatedCommandJobHandler pausingAfterFire(CommandJobHandler delegate, Duration timeout) {
        return new GatedCommandJobHandler(delegate, true, timeout);
    }

    /** No pause: only counts the fires of the real handler. */
    static GatedCommandJobHandler counting(CommandJobHandler delegate) {
        return new GatedCommandJobHandler(delegate, false, Duration.ZERO);
    }

    @Override
    public String type() {
        return delegate.type();
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return delegate.eligibilityCategory();
    }

    @Override
    public LockSet lockSet(ScheduledJob job) {
        return delegate.lockSet(job);
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        int fire = fires.incrementAndGet();
        JobOutcome outcome = delegate.fire(context, job);
        if (gated && fire == 1) {
            firePid = LockWaits.backendPid(context);
            reached.countDown();
            try {
                if (!release.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException("the gated fire of job " + job.id() + " was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted inside the gated fire of job " + job.id(), e);
            }
        }
        return outcome;
    }

    boolean gated() {
        return gated;
    }

    /** How many times the real handler's fire ran (a fire that rolled back included). */
    int fires() {
        return fires.get();
    }

    /** The backend pid of the fire command's connection; set once the gated fire is reached. */
    int firePid() {
        return firePid;
    }

    /** Waits, bounded, until the gated fire holds its transaction open; fails fast if the worker ended first. */
    void awaitReached(Future<?> worker) {
        LockWaits.await(reached, timeout, worker, "the worker's gated fire");
    }

    /** Lets the gated fire return, so the worker records the outcome and commits. Safe to call more than once. */
    void release() {
        release.countDown();
    }
}
