package ps.e7gz.faultinjection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ps.e7gz.faultinjection.FaultInjection.Faults;
import ps.e7gz.faultinjection.FaultInjection.InjectedFault;

/**
 * E7-79: the harness itself, with no database. A fake three-step cascade writes into a list that its "transaction"
 * clears on failure, so each behaviour of {@link FaultInjection} - counting, failing at the chosen step, catching a
 * swallowed fault, catching a partial write - is shown on its own.
 */
class FaultInjectionTest {

    interface Port {
        String call(String value);
    }

    /** A world whose cascade calls the port three times and keeps what it wrote only when all three succeed. */
    static final class World {
        final List<String> written = new ArrayList<>();
        final boolean rollsBack;
        final boolean swallows;

        World(boolean rollsBack, boolean swallows) {
            this.rollsBack = rollsBack;
            this.swallows = swallows;
        }

        void cascade(Faults faults) {
            Port port = faults.wrap(Port.class, value -> value);
            try {
                for (String step : List.of("a", "b", "c")) {
                    written.add(port.call(step));
                }
            } catch (InjectedFault fault) {
                if (swallows) {
                    return;
                }
                if (rollsBack) {
                    written.clear();
                }
                throw fault;
            }
        }
    }

    @Test
    void aCountingRunCountsEveryCallAndNeverFails() {
        Faults faults = Faults.counting();
        Port port = faults.wrap(Port.class, value -> value + "!");

        assertThat(port.call("a")).isEqualTo("a!");
        assertThat(port.call("b")).isEqualTo("b!");
        assertThat(port.toString()).isNotNull();            // Object's own methods are not steps
        assertThat(faults.steps()).isEqualTo(2);
    }

    @Test
    void aFailingRunThrowsAtExactlyItsStep() {
        Faults faults = Faults.failingAt(2);
        Port port = faults.wrap(Port.class, value -> value);

        assertThat(port.call("a")).isEqualTo("a");
        assertThatThrownBy(() -> port.call("b")).isInstanceOf(InjectedFault.class)
                .hasMessage("injected fault at step 2 (Port.call)");
        assertThat(port.call("c")).isEqualTo("c");
    }

    @Test
    void theRealPortsOwnExceptionPassesThroughUnwrapped() {
        Port port = Faults.counting().wrap(Port.class, value -> {
            throw new IllegalArgumentException("refused: " + value);
        });

        assertThatThrownBy(() -> port.call("x")).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("refused: x");
    }

    @Test
    void aCascadeThatRollsBackPassesAtEveryStep() {
        int steps = FaultInjection.atEveryStep(() -> new World(true, false), World::cascade,
                world -> assertThat(world.written).containsExactly("a", "b", "c"), 3,
                (world, step) -> assertThat(world.written).as("step %d", step).isEmpty());

        assertThat(steps).isEqualTo(3);
    }

    @Test
    void aPartialWriteIsCaught() {
        assertThatThrownBy(() -> FaultInjection.atEveryStep(() -> new World(false, false), World::cascade,
                world -> { }, 3, (world, step) -> assertThat(world.written).as("step %d", step).isEmpty()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("step 2");
    }

    @Test
    void aSwallowedFaultIsCaught() {
        assertThatThrownBy(() -> FaultInjection.atEveryStep(() -> new World(true, true), World::cascade,
                world -> { }, 3, (world, step) -> { }))
                .isInstanceOf(AssertionError.class).hasMessageContaining("never be swallowed");
    }

    @Test
    void anotherFailureIsNotTakenForTheInjectedOne() {
        AtomicInteger made = new AtomicInteger();
        assertThatThrownBy(() -> FaultInjection.atEveryStep(() -> made.getAndIncrement(), (index, faults) -> {
            if (index > 0) {
                throw new IllegalStateException("some other defect");    // fails before any step on a faulty run
            }
            new World(true, false).cascade(faults);
        }, index -> { }, 3, (index, step) -> { }))
                .isInstanceOf(AssertionError.class).hasMessageContaining("its injected fault");
    }

    @Test
    void theCauseChainIsSearched() {
        Throwable wrapped = new RuntimeException("transaction rolled back", new InjectedFault(4, "Port", "call"));

        assertThat(FaultInjection.injected(wrapped)).isEqualTo(4);
        assertThat(FaultInjection.injected(new RuntimeException("no fault"))).isZero();
    }

    @Test
    void aCauseChainThatLoopsEnds() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(FaultInjection.injected(first)).isZero();
    }
}
