package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandContext;

/**
 * The canonical Trust outcomes of an Online Booking (arch 07 sections 2 and 7, FR-TRS-02), written in the command that
 * ends the booking - the one writer of them (E7-61's generalisation of E7-59's {@code NoShowOutcomes}; E7-69, E7-70 and
 * E7-72 reuse it). The database requires the outcome to match the booking's resolved attendance
 * ({@code trust.assert_booking_outcome_event}: same result, {@code occurredAt} = the attendance's {@code resolved_at})
 * and a projection recomputed after it ({@code trust.assert_trust_event_projection_current}) by commit. The command must
 * name the Customer in its lock set, so {@code begin_command} has locked them with every Booking
 * ({@code trust.command_now}). Each outcome is {@code effectiveAt = startAt} (FR-TRS-05) and {@code occurredAt} = the
 * command instant, the shared database command instant the database requires ({@code trust.prepare_trust_event_insert}).
 */
public interface BookingOutcomes {

    /**
     * Appends {@code BOOKING_NO_SHOW} for the Customer's Online Booking - the command instant is the no-show instant
     * (E7-59) - and recomputes the Customer's Trust Projection by full replay. The attendance record must already hold
     * {@code ABSENT_NO_SHOW} resolved at that instant.
     */
    void recordNoShow(CommandContext command, UUID customerId, UUID bookingId, Instant startAt);

    /**
     * Appends the completion outcome of the Customer's Online Booking at T5 (E7-61, arch 07 section 7.3) - ATTENDED is
     * {@code BOOKING_ATTENDED}, ATTENDANCE_UNCONFIRMED is {@code BOOKING_ATTENDANCE_UNCONFIRMED} - and recomputes the
     * Customer's Trust Projection by full replay. Replay alone decides their effect: {@code BOOKING_ATTENDED} resets the
     * consecutive no-show counter and counts toward Verified Player; {@code BOOKING_ATTENDANCE_UNCONFIRMED} does
     * neither (FR-ATT-03). The attendance record must already hold {@code result} resolved at the command instant.
     *
     * @throws IllegalArgumentException for any other result: no completion resolves to it
     */
    void recordCompletion(CommandContext command, UUID customerId, UUID bookingId, Instant startAt,
                          AttendanceResult result);
}
