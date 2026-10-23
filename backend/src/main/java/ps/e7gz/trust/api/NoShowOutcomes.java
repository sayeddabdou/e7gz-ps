package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The canonical Trust outcome of a committed no-show (arch 07 sections 2 and 7.1, FR-TRS-02, FR-ATT-04), written in
 * the no-show's own command (E7-59): the database requires exactly one {@code BOOKING_NO_SHOW} event for every
 * recorded no-show ({@code attendance.assert_attendance_row}) and a projection recomputed after it
 * ({@code trust.assert_trust_event_projection_current}) by commit. The command must name the Customer in its lock set,
 * so {@code begin_command} has locked them with every Booking ({@code trust.command_now}).
 */
public interface NoShowOutcomes {

    /**
     * Appends {@code BOOKING_NO_SHOW} for the Customer's Online Booking - {@code effectiveAt = startAt} (FR-TRS-05),
     * {@code occurredAt} = the command instant, which is the no-show instant - and recomputes the Customer's Trust
     * Projection by full replay. The attendance record must already hold {@code ABSENT_NO_SHOW} resolved at that
     * instant ({@code trust.assert_booking_outcome_event}).
     */
    void recordNoShow(CommandContext command, UUID customerId, UUID bookingId, Instant startAt);
}
