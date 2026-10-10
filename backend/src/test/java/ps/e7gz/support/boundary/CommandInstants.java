package ps.e7gz.support.boundary;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.TestCommandContexts;

/**
 * A command context whose {@code commandNow()} is an instant the test chooses, to the nanosecond: for rule code tested
 * without the database clock. It is the only way to hit a boundary set by an immutable snapshot such as
 * {@code booking.start_at} (E7-47, {@code RejectAfterExpiryBoundaryTest}). The database guards still check every write
 * against their own command instant, so a test on a real connection moves the deadline with {@link Deadlines} instead.
 */
public final class CommandInstants {

    private CommandInstants() {
    }

    /** At {@code now}, with no database ({@code jdbc()} is {@code null}): mock the stores the rule reads. */
    public static CommandContext at(Instant now) {
        return TestCommandContexts.at(now, null);
    }

    /** At {@code now}, reading and writing on {@code jdbc}. */
    public static CommandContext at(Instant now, JdbcClient jdbc) {
        return TestCommandContexts.at(now, jdbc);
    }

    /** On {@code side} of {@code deadline}, with no database. */
    public static CommandContext at(Boundary side, Instant deadline) {
        return at(side.now(deadline));
    }
}
