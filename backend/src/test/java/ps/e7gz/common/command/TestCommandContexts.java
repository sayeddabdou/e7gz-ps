package ps.e7gz.common.command;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * TEST-ONLY: a real {@link CommandContext} at an instant the test chooses (E7-45). Production code gets one only from
 * the {@link CommandRunner}; tests reach this through {@code ps.e7gz.support.boundary.CommandInstants}.
 */
public final class TestCommandContexts {

    private TestCommandContexts() {
    }

    public static CommandContext at(Instant commandNow, JdbcClient jdbc) {
        return new CommandContext(commandNow, 1, jdbc);
    }
}
