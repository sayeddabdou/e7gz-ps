package ps.e7gz.booking.lateconfirm.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmClosures;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.scheduling.api.ScheduledJobs;

/** Booking's operational closure of an AWAITING booking's Late Confirmation ({@link LateConfirmClosures}, E7-113). */
@Component
class JdbcLateConfirmClosures implements LateConfirmClosures {

    private final LateConfirmCommands commands;
    private final ScheduledJobs jobs;

    JdbcLateConfirmClosures(LateConfirmCommands commands, ScheduledJobs jobs) {
        this.commands = commands;
        this.jobs = jobs;
    }

    @Override
    public void closeForCancellation(CommandContext command, UUID bookingId, BookingTerminalReason reason,
                                     Instant at) {
        commands.closeForCancellation(Objects.requireNonNull(command, "command"), bookingId, reason, at);
    }

    @Override
    public void pauseTimers(CommandContext command, UUID bookingId) {
        Objects.requireNonNull(command, "command");
        jobs.pause(command, LateConfirmWindow.expiryKey(Objects.requireNonNull(bookingId, "bookingId")));
        jobs.pause(command, LateConfirmWindow.warningKey(bookingId));
    }

    @Override
    public void resumeTimers(CommandContext command, UUID bookingId, Instant resumedAt, Instant deadline) {
        commands.resumeTimers(Objects.requireNonNull(command, "command"), Objects.requireNonNull(bookingId, "bookingId"),
                Objects.requireNonNull(resumedAt, "resumedAt"), Objects.requireNonNull(deadline, "deadline"));
    }
}
