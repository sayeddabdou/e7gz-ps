package ps.e7gz.booking.lateconfirm.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.scheduling.api.NewJob;

/**
 * The customer's window of one Late Confirmation and its two jobs (arch 08 sections 1 and 2.6):
 * <ul>
 *   <li>T2 {@code LATE_CONFIRM_EXPIRY} at {@code expiresAt = MIN(initiation + 5 min, startAt)} (FR-LCF-09),
 *       always;</li>
 *   <li>T3 {@code LATE_CONFIRM_WARNING} at {@code expiresAt - 60 s}, only when the window is longer than 60 seconds
 *       (FR-LCF-12): at 60 seconds or less the row is never created, so there is nothing to suppress.</li>
 * </ul>
 * A pure rule of the initiation instant and the booking's start: 19:59 for a 20:00 start gives exactly 60 s and no T3;
 * 19:50 gives 19:55 and one T3 at 19:54.
 */
record LateConfirmWindow(Instant expiresAt, Optional<Instant> warningAt) {

    /** FR-LCF-09. */
    static final Duration WINDOW = Duration.ofMinutes(5);
    /** FR-LCF-12: "about a minute before expiry", and only for a window longer than this. */
    static final Duration WARNING_LEAD = Duration.ofSeconds(60);

    /** T2 and T3 (arch 08 section 1); their handlers are a later phase of E7-58, until then the jobs wait. */
    static final String EXPIRY_JOB = "LATE_CONFIRM_EXPIRY";
    static final String WARNING_JOB = "LATE_CONFIRM_WARNING";
    static final String BOOKING_SUBJECT = "BOOKING";

    LateConfirmWindow {
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(warningAt, "warningAt");
    }

    /** @param initiatedAt the initiation's command instant, strictly before {@code startAt} */
    static LateConfirmWindow of(Instant initiatedAt, Instant startAt) {
        Instant fiveMinutes = initiatedAt.plus(WINDOW);
        Instant expiresAt = fiveMinutes.isBefore(startAt) ? fiveMinutes : startAt;
        boolean warn = Duration.between(initiatedAt, expiresAt).compareTo(WARNING_LEAD) > 0;
        return new LateConfirmWindow(expiresAt, warn ? Optional.of(expiresAt.minus(WARNING_LEAD)) : Optional.empty());
    }

    NewJob expiryJob(UUID bookingId) {
        return NewJob.of(EXPIRY_JOB, BOOKING_SUBJECT, bookingId, expiresAt);
    }

    Optional<NewJob> warningJob(UUID bookingId) {
        return warningAt.map(at -> NewJob.of(WARNING_JOB, BOOKING_SUBJECT, bookingId, at));
    }

    static String expiryKey(UUID bookingId) {
        return NewJob.of(EXPIRY_JOB, BOOKING_SUBJECT, bookingId, Instant.EPOCH).idempotencyKey();
    }

    static String warningKey(UUID bookingId) {
        return NewJob.of(WARNING_JOB, BOOKING_SUBJECT, bookingId, Instant.EPOCH).idempotencyKey();
    }
}
