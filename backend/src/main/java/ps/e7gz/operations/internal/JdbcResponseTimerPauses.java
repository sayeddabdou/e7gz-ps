package ps.e7gz.operations.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.operations.api.ResponseTimerPauses;

/**
 * {@code operations.booking_timer_pause_history} on the command's transaction. The one permitted mutation of a row is
 * its single close; the guard trigger and {@code ck_pause_history_lifecycle} refuse anything else (an accumulated
 * duration shorter than the pause itself or over the 30-minute cap included).
 */
@Component
class JdbcResponseTimerPauses implements ResponseTimerPauses {

    static final String CLOSE_OPEN = """
            UPDATE operations.booking_timer_pause_history
               SET pause_ended_at = :endedAt, accumulated_duration = make_interval(secs => :seconds)
             WHERE booking_id = :bookingId AND pause_kind = :kind AND pause_ended_at IS NULL
            """;

    @Override
    public void closeOpen(CommandContext command, UUID bookingId, Instant endedAt, Duration accumulated) {
        close(command, bookingId, "VENUE_RESPONSE", endedAt, accumulated);
    }

    @Override
    public void closeOpenLateConfirm(CommandContext command, UUID bookingId, Instant endedAt, Duration accumulated) {
        close(command, bookingId, "LATE_CONFIRM", endedAt, accumulated);
    }

    private static void close(CommandContext command, UUID bookingId, String kind, Instant endedAt,
                              Duration accumulated) {
        Objects.requireNonNull(command, "command");
        int closed = command.jdbc().sql(CLOSE_OPEN)
                .param("endedAt", OffsetDateTime.ofInstant(Objects.requireNonNull(endedAt, "endedAt"), ZoneOffset.UTC))
                .param("seconds", Objects.requireNonNull(accumulated, "accumulated").toNanos() / 1_000_000_000.0)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("kind", kind)
                .update();
        if (closed != 1) {
            throw new IllegalStateException("Booking " + bookingId + " has " + closed
                    + " open " + kind + " pause rows, expected 1");
        }
    }
}
