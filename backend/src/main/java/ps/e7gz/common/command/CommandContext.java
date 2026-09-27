package ps.e7gz.common.command;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a command body sees while it runs inside its transaction.
 */
public final class CommandContext {

    private final Instant commandNow;
    private final int attempt;
    private final JdbcClient jdbc;

    CommandContext(Instant commandNow, int attempt, JdbcClient jdbc) {
        this.commandNow = commandNow;
        this.attempt = attempt;
        this.jdbc = jdbc;
    }

    /**
     * The command instant the database sampled in {@code begin_command} - the same value the guard triggers read
     * through {@code tournament.command_now()} (arch 04 section 2.1). Use it instead of the {@code Clock} inside a command.
     */
    public Instant commandNow() {
        return commandNow;
    }

    /** 1 for the first run, 2 after the first {@code 40001} retry, and so on. */
    public int attempt() {
        return attempt;
    }

    /** Runs SQL on this command's transaction (the app- or admin-runtime connection, whichever the scope needs). */
    public JdbcClient jdbc() {
        return jdbc;
    }
}
