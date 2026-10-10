package ps.e7gz.support.boundary;

import java.time.Duration;
import java.time.Instant;

/**
 * The three instants every timer is tested at (arch 08 section 9, EC-X-002): one second before its deadline, exactly
 * at it, one second after. FR-AVL-15: {@code now < deadline} permits; {@code now >= deadline} means the deadline owns
 * the state.
 */
public enum Boundary {

    ONE_SECOND_BEFORE(-1),
    AT(0),
    ONE_SECOND_AFTER(1);

    private final Duration offset;

    Boundary(int seconds) {
        this.offset = Duration.ofSeconds(seconds);
    }

    /** "Now" on this side of {@code deadline}. */
    public Instant now(Instant deadline) {
        return deadline.plus(offset);
    }

    /** The deadline that puts {@code now} on this side of it: what a test stores to hit the boundary exactly. */
    public Instant deadline(Instant now) {
        return now.minus(offset);
    }

    /** {@code now < deadline}: the action the deadline closes is still permitted. */
    public boolean beforeDeadline() {
        return offset.isNegative();
    }

    /** From the deadline to "now": -1 s, 0 or +1 s. */
    public Duration offset() {
        return offset;
    }
}
