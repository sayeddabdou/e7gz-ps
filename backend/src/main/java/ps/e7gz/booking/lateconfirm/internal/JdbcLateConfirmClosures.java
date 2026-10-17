package ps.e7gz.booking.lateconfirm.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmClosures;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;

/** Booking's operational closure of an AWAITING booking's Late Confirmation ({@link LateConfirmClosures}, E7-113). */
@Component
class JdbcLateConfirmClosures implements LateConfirmClosures {

    private final LateConfirmCommands commands;

    JdbcLateConfirmClosures(LateConfirmCommands commands) {
        this.commands = commands;
    }

    @Override
    public void closeForCancellation(CommandContext command, UUID bookingId, BookingTerminalReason reason,
                                     Instant at) {
        commands.closeForCancellation(Objects.requireNonNull(command, "command"), bookingId, reason, at);
    }
}
