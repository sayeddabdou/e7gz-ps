package ps.e7gz.architecture.timefixture;

import java.time.Clock;
import java.time.Instant;

public class UsesInjectedClock {
    private final Clock clock;

    public UsesInjectedClock(Clock clock) {
        this.clock = clock;
    }

    Instant stamp() {
        return Instant.now(clock);
    }
}
