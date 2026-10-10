package ps.e7gz.support.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;
import ps.e7gz.common.command.CommandContext;

/**
 * Moves a stored deadline inside a command, so the command's own instant falls on an exact side of it (first done by
 * E7-47's {@code withDeadline}). The write runs on the command's connection, through the same guards, and rolls back
 * with the command.
 */
public final class Deadlines {

    /** {@code schema.table.column}, lower-case identifiers only: it is spliced into the SQL. */
    private static final Pattern COLUMN = Pattern.compile("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*");

    private Deadlines() {
    }

    /** Sets {@code column} ({@code schema.table.column}) of the row with this {@code id} to {@code at}. */
    public static void move(CommandContext context, String column, UUID id, Instant at) {
        if (!COLUMN.matcher(column).matches()) {
            throw new IllegalArgumentException("Not a schema.table.column: " + column);
        }
        int dot = column.lastIndexOf('.');
        int changed = context.jdbc()
                .sql("UPDATE " + column.substring(0, dot) + " SET " + column.substring(dot + 1)
                        + " = :at WHERE id = :id")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("id", id)
                .update();
        assertThat(changed).as("%s of %s moved", column, id).isEqualTo(1);
    }

    /**
     * Waits until the database clock has passed {@code instant}. A deadline cannot be stored before the row that holds
     * it was written (e.g. {@code ck_reservation_release_chronology}): to put "now" one second after the deadline of a
     * row made a moment ago, first wait until the row is a second old.
     */
    public static void awaitDatabaseClockPast(Instant instant) {
        while (!databaseNow().isAfter(instant)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** The database clock ({@code clock_timestamp()}), the one command instants are sampled from. */
    public static Instant databaseNow() {
        return PackDb.OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /**
     * Puts the command's own instant on {@code side} of {@code column}: the deadline becomes
     * {@code commandNow - side.offset()}. Returns the deadline stored.
     */
    public static Instant moveTo(CommandContext context, Boundary side, String column, UUID id) {
        Instant deadline = side.deadline(context.commandNow());
        move(context, column, id, deadline);
        return deadline;
    }
}
