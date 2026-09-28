package ps.e7gz.common.command.archfixture;

import java.time.Clock;
import java.time.Instant;

/**
 * Negative control for {@code CommandBoundaryArchitectureTest}: technical code that is not a Command may use a Clock
 * or Instant.now(); E7-15's time rules apply to Command implementations only.
 */
public class InfrastructureClockWorker {

    private final Clock clock;

    public InfrastructureClockWorker(Clock clock) {
        this.clock = clock;
    }

    public Instant leaseUntil() {
        return clock.instant().plusSeconds(30);
    }

    public Instant wallClock() {
        return Instant.now();
    }
}
