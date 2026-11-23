package ps.e7gz.faultinjection;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The fault-injection harness (E7-79; arch 12 section 4 "fault injection" in the launch-bar suite): break a cascade at
 * each of its steps in turn and prove that nothing of it is left half-done (FR-OPS-09, Spec v5 section 43.4: a partial
 * cascade is not a valid Product outcome).
 *
 * <p>A <b>step</b> is one call the command makes through a port the test wraps with {@link Faults#wrap} - the bookings,
 * Tournaments, scheduler, audit, outbox, admin alerts. {@link #atEveryStep} runs the cascade once on a fresh world
 * with nothing failing, to count its steps; then, for every step {@code n}, on another fresh world, it runs the same
 * cascade failing at call {@code n} and checks:
 * <ul>
 *   <li>the command failed with exactly that injected fault - a fault the cascade swallowed and went on from is a
 *       defect, reported as such;</li>
 *   <li>the caller's {@code nothingLeft} check holds: the command's one transaction rolled back whole.</li>
 * </ul>
 * First used by the Emergency cascade (E7-82) and the Owner Review entry (E7-84).
 */
public final class FaultInjection {

    private FaultInjection() {
    }

    /** The exception a {@link Faults#wrap wrapped} port throws at the chosen step. */
    public static final class InjectedFault extends IllegalStateException {

        private final int step;

        InjectedFault(int step, String port, String method) {
            super("injected fault at step " + step + " (" + port + "." + method + ")");
            this.step = step;
        }

        public int step() {
            return step;
        }
    }

    /**
     * One run's step counter: every call through a wrapped port is one step, and the call numbered {@code failAt}
     * throws {@link InjectedFault} instead of reaching the real port.
     */
    public static final class Faults {

        private final AtomicInteger calls = new AtomicInteger();
        private final int failAt;

        private Faults(int failAt) {
            this.failAt = failAt;
        }

        /** Counts every step and never fails: the run that measures the cascade. */
        public static Faults counting() {
            return new Faults(Integer.MAX_VALUE);
        }

        /** Fails at the {@code step}-th call (1-based). */
        public static Faults failingAt(int step) {
            if (step < 1) {
                throw new IllegalArgumentException("steps are counted from 1");
            }
            return new Faults(step);
        }

        /** The steps taken so far. */
        public int steps() {
            return calls.get();
        }

        /**
         * {@code target} behind a proxy of the interface {@code port}: each call except {@link Object}'s own counts
         * one step and reaches {@code target}, unwrapped of reflection, except at the step this run fails at.
         */
        @SuppressWarnings("unchecked")
        public <T> T wrap(Class<T> port, T target) {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(target, "target");
            if (!port.isInterface()) {
                throw new IllegalArgumentException(port.getName() + " is not an interface");
            }
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(target, args);
                }
                int step = calls.incrementAndGet();
                if (step == failAt) {
                    throw new InjectedFault(step, port.getSimpleName(), method.getName());
                }
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (T) Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, handler);
        }
    }

    /**
     * Runs {@code cascade} on a fresh {@code world} once without a fault (then {@code afterSuccess} checks what a
     * complete cascade wrote, and the step count must reach {@code minimumSteps}), then once per step, each time on a
     * fresh world, failing at that step; {@code nothingLeft} then checks that world.
     *
     * @return the number of steps the cascade took
     */
    public static <W> int atEveryStep(Supplier<W> world, BiConsumer<W, Faults> cascade, Consumer<W> afterSuccess,
                                      int minimumSteps, BiConsumer<W, Integer> nothingLeft) {
        W counted = world.get();
        Faults counting = Faults.counting();
        cascade.accept(counted, counting);
        int steps = counting.steps();
        assertThat(steps).as("the cascade's steps").isGreaterThanOrEqualTo(minimumSteps);
        afterSuccess.accept(counted);

        for (int step = 1; step <= steps; step++) {
            W fresh = world.get();
            Throwable thrown = null;
            try {
                cascade.accept(fresh, Faults.failingAt(step));
            } catch (Throwable failure) {
                thrown = failure;
            }
            assertThat(thrown).as("step %d: the injected fault must fail the command, never be swallowed", step)
                    .isNotNull();
            assertThat(injected(thrown)).as("step %d: the command failed with its injected fault, not %s", step,
                    thrown).isEqualTo(step);
            nothingLeft.accept(fresh, step);
        }
        return steps;
    }

    /** The step of the {@link InjectedFault} in {@code failure}'s cause chain; 0 when there is none. */
    static int injected(Throwable failure) {
        // a cause chain can loop back on itself (A caused by B caused by A): stop at the first exception seen twice
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof InjectedFault fault) {
                return fault.step();
            }
        }
        return 0;
    }
}
