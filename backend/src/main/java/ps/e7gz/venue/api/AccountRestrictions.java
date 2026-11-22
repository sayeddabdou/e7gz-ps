package ps.e7gz.venue.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Venue Account's restriction history and its Platform Attendance Protection candidates (arch 07 section 7.5,
 * FR-ATT-14..26; E7-78). {@code iam.venue_account_restriction_interval} and
 * {@code attendance.attendance_protection_candidate} belong to the Attendance module (arch 03 section 1, module 11),
 * which implements this port - Attendance already depends on Venue, so Venue declares it (CLAUDE.md section 3).
 * Every method runs on the disable or re-enable command's own transaction.
 */
public interface AccountRestrictions {

    /**
     * Opens the Venue's restriction interval at the command instant (FR-ATT-21: admin actor, reason and correlation
     * ID). One per disable; the history is immutable apart from its one close.
     *
     * @return the interval id
     */
    UUID open(CommandContext command, UUID venueId, UUID adminId, String reason, UUID correlationId);

    /**
     * Closes the Venue's open interval at the command instant, once. An account disabled before E7-78 has no open
     * interval: nothing is closed.
     *
     * @return whether an interval was closed
     */
    boolean close(CommandContext command, UUID venueId);

    /**
     * The disable overlaps these ACTIVE Online Bookings (FR-ATT-20, FR-ATT-25, FR-ATT-26). For each: one protection
     * candidate on the interval with the evidence held at disable time (the effective protection Trust event comes only
     * when its attendance resolves), one {@code ActiveSessionAttendanceRecordingAffected} event for the customer's
     * notification, and one Platform Admin operational alert. Neither notification says the booking was cancelled
     * (FR-NTF-07): it continues.
     */
    void activeSessionsAffected(CommandContext command, UUID venueId, UUID intervalId, List<ActiveSession> sessions);

    /** One ACTIVE Online Booking the disable overlaps. */
    record ActiveSession(UUID bookingId, UUID customerId, UUID resourceId, Instant startAt, Instant endAt) {

        public ActiveSession {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
        }
    }
}
