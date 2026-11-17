package ps.e7gz.attendance.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandContext;

/**
 * {@code attendance.attendance_record} on the command's transaction (E7-59). One row per booking, written by the
 * booking's first attendance action. Each write adds its evidence only where that column is still empty, so a second
 * writer changes nothing and the caller sees {@code false}; the database keeps the evidence immutable, mutually
 * exclusive ({@code ck_attendance_active_evidence_exclusive}), inside its window and by an account of the booking's
 * venue ({@code attendance.validate_attendance_action_write}).
 */
@Component
class AttendanceStore {

    static final String COLUMNS = """
            booking_id, result, check_in_at, check_in_reversed_at, late_present_at, late_present_reversed_at,
            no_show_at, no_show_reversed_at
            """;

    static final String FIND = "SELECT " + COLUMNS + " FROM attendance.attendance_record WHERE booking_id = :id";

    static final String FIND_MANY = "SELECT " + COLUMNS
            + " FROM attendance.attendance_record WHERE booking_id = ANY(:ids)";

    static final String CHECK_IN = """
            INSERT INTO attendance.attendance_record AS a (id, booking_id, check_in_at, recorded_by_venue_account_id)
            VALUES (:id, :bookingId, :at, :account)
            ON CONFLICT (booking_id) DO UPDATE
               SET check_in_at = EXCLUDED.check_in_at,
                   recorded_by_venue_account_id = EXCLUDED.recorded_by_venue_account_id
             WHERE a.check_in_at IS NULL
            """;

    static final String LATE_PRESENT = """
            INSERT INTO attendance.attendance_record AS a (id, booking_id, late_present_at,
                                                           recorded_by_venue_account_id)
            VALUES (:id, :bookingId, :at, :account)
            ON CONFLICT (booking_id) DO UPDATE
               SET late_present_at = EXCLUDED.late_present_at,
                   recorded_by_venue_account_id = EXCLUDED.recorded_by_venue_account_id
             WHERE a.late_present_at IS NULL
            """;

    /** FR-ATT-07, arch 07 section 7.3: a no-show resolves ABSENT_NO_SHOW at once, at the no-show instant. */
    static final String NO_SHOW = """
            INSERT INTO attendance.attendance_record AS a (id, booking_id, no_show_at, result, resolved_at,
                                                           recorded_by_venue_account_id)
            VALUES (:id, :bookingId, :at, 'ABSENT_NO_SHOW', :at, :account)
            ON CONFLICT (booking_id) DO UPDATE
               SET no_show_at = EXCLUDED.no_show_at, result = EXCLUDED.result, resolved_at = EXCLUDED.resolved_at,
                   recorded_by_venue_account_id = EXCLUDED.recorded_by_venue_account_id
             WHERE a.no_show_at IS NULL AND a.result IS NULL
            """;

    /**
     * E7-61: resolves the booking's result by its default (arch 07 section 7.3) at {@code :at}, on its record or on a
     * new one when no action was ever taken; only an unresolved record changes, so a second resolver changes nothing.
     */
    static final String RESOLVE = """
            INSERT INTO attendance.attendance_record AS a (id, booking_id, result, resolved_at)
            VALUES (:id, :bookingId, :result, :at)
            ON CONFLICT (booking_id) DO UPDATE
               SET result = EXCLUDED.result, resolved_at = EXCLUDED.resolved_at
             WHERE a.result IS NULL
            """;

    /**
     * E7-70: an audited Platform Admin correction replaces a resolved result (FR-ATT-09, FR-ATT-13); the evidence
     * columns never change, and the resolution instant moves forward to the correction instant
     * ({@code attendance.guard_attendance_record_mutation}).
     */
    static final String CORRECT = """
            UPDATE attendance.attendance_record
               SET result = :result, resolved_at = :at
             WHERE booking_id = :bookingId AND result IS NOT NULL AND result <> :result AND resolved_at < :at
            """;

    /**
     * E7-70: the Platform Admin's no-show reversal adds its marker (the no-show itself stays recorded, FR-ATT-11) and
     * resolves the chosen result at the reversal instant - the post-endAt default ATTENDANCE_UNCONFIRMED, or the result
     * of the correction written with it.
     */
    static final String REVERSE_NO_SHOW = """
            UPDATE attendance.attendance_record
               SET no_show_reversed_at = :at, result = :result, resolved_at = :at
             WHERE booking_id = :bookingId AND no_show_at IS NOT NULL AND no_show_reversed_at IS NULL
               AND result = 'ABSENT_NO_SHOW'
            """;

    /**
     * E7-70: the Platform Admin reverses a check-in that stands on a booking whose attendance is not resolved yet (arch
     * 07 section 7.4: the booking stays ACTIVE and no-show eligibility re-opens). Only the marker is added.
     */
    static final String REVERSE_CHECK_IN = """
            UPDATE attendance.attendance_record
               SET check_in_reversed_at = :at
             WHERE booking_id = :bookingId AND check_in_at IS NOT NULL AND check_in_reversed_at IS NULL
               AND result IS NULL
            """;

    /**
     * E7-70: the Platform Admin reverses the check-in behind a resolved ATTENDED (arch 07 section 7.4): the marker and
     * the corrected result ATTENDANCE_UNCONFIRMED at the same instant, with the {@code ATTENDANCE_CORRECTED} the caller
     * writes ({@code attendance.assert_attendance_action_audit}).
     */
    static final String REVERSE_CHECK_IN_ATTENDED = """
            UPDATE attendance.attendance_record
               SET check_in_reversed_at = :at, result = 'ATTENDANCE_UNCONFIRMED', resolved_at = :at
             WHERE booking_id = :bookingId AND check_in_at IS NOT NULL AND check_in_reversed_at IS NULL
               AND result = 'ATTENDED' AND resolved_at < :at
            """;

    /** E7-70: the Platform Admin reverses a Mark Present - Late that stands on an unresolved booking. */
    static final String REVERSE_LATE_PRESENT = """
            UPDATE attendance.attendance_record
               SET late_present_reversed_at = :at
             WHERE booking_id = :bookingId AND late_present_at IS NOT NULL AND late_present_reversed_at IS NULL
               AND result IS NULL
            """;

    Optional<Evidence> find(CommandContext command, UUID bookingId) {
        return command.jdbc().sql(FIND)
                .param("id", Objects.requireNonNull(bookingId, "bookingId"))
                .query((rs, n) -> evidence(rs))
                .optional();
    }

    List<Evidence> find(CommandContext command, Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return List.of();
        }
        return command.jdbc().sql(FIND_MANY)
                .param("ids", bookingIds.toArray(UUID[]::new))
                .query((rs, n) -> evidence(rs))
                .list();
    }

    /** Adds the action's evidence at {@code at} by the venue account. @return false when it was already there */
    boolean record(CommandContext command, AttendanceAction action, UUID bookingId, Instant at, UUID venueAccountId) {
        String sql = switch (action) {
            case CHECK_IN -> CHECK_IN;
            case LATE_PRESENT -> LATE_PRESENT;
            case NO_SHOW -> NO_SHOW;
        };
        return command.jdbc().sql(sql)
                .param("id", UUID.randomUUID())
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("at", OffsetDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC))
                .param("account", Objects.requireNonNull(venueAccountId, "venueAccountId"))
                .update() == 1;
    }

    /** Stores {@code result} resolved at {@code at}. @return false when the record was already resolved */
    boolean resolve(CommandContext command, UUID bookingId, AttendanceResult result, Instant at) {
        return command.jdbc().sql(RESOLVE)
                .param("id", UUID.randomUUID())
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("result", Objects.requireNonNull(result, "result").name())
                .param("at", OffsetDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC))
                .update() == 1;
    }

    /** Replaces the resolved result with {@code result} at {@code at}. @return false when nothing was replaced */
    boolean correct(CommandContext command, UUID bookingId, AttendanceResult result, Instant at) {
        return command.jdbc().sql(CORRECT)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("result", Objects.requireNonNull(result, "result").name())
                .param("at", OffsetDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC))
                .update() == 1;
    }

    /** Reverses the standing no-show at {@code at}, resolving {@code result}. @return false when none stands */
    boolean reverseNoShow(CommandContext command, UUID bookingId, AttendanceResult result, Instant at) {
        return command.jdbc().sql(REVERSE_NO_SHOW)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("result", Objects.requireNonNull(result, "result").name())
                .param("at", OffsetDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC))
                .update() == 1;
    }

    /** Adds the check-in's reversal marker on an unresolved record. @return false when none stands there */
    boolean reverseCheckIn(CommandContext command, UUID bookingId, Instant at) {
        return mark(command, REVERSE_CHECK_IN, bookingId, at);
    }

    /** Reverses the check-in behind a resolved ATTENDED, now ATTENDANCE_UNCONFIRMED. @return false when not so */
    boolean reverseAttendedCheckIn(CommandContext command, UUID bookingId, Instant at) {
        return mark(command, REVERSE_CHECK_IN_ATTENDED, bookingId, at);
    }

    /** Adds the Late Present's reversal marker on an unresolved record. @return false when none stands there */
    boolean reverseLatePresent(CommandContext command, UUID bookingId, Instant at) {
        return mark(command, REVERSE_LATE_PRESENT, bookingId, at);
    }

    private static boolean mark(CommandContext command, String sql, UUID bookingId, Instant at) {
        return command.jdbc().sql(sql)
                .param("bookingId", Objects.requireNonNull(bookingId, "bookingId"))
                .param("at", OffsetDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC))
                .update() == 1;
    }

    private static Evidence evidence(ResultSet rs) throws SQLException {
        String result = rs.getString("result");
        return new Evidence(rs.getObject("booking_id", UUID.class),
                result == null ? null : AttendanceResult.valueOf(result),
                instant(rs, "check_in_at"), instant(rs, "check_in_reversed_at"),
                instant(rs, "late_present_at"), instant(rs, "late_present_reversed_at"),
                instant(rs, "no_show_at"), instant(rs, "no_show_reversed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * A booking's attendance record. Recorded evidence is immutable; a Platform Admin reversal only adds its
     * {@code *ReversedAt} marker (FR-ATT-11, arch 07 section 7.4).
     */
    record Evidence(UUID bookingId, AttendanceResult result, Instant checkInAt, Instant checkInReversedAt,
                    Instant latePresentAt, Instant latePresentReversedAt, Instant noShowAt,
                    Instant noShowReversedAt) {

        /** No record yet: no action was taken on the booking. */
        static Evidence none(UUID bookingId) {
            return new Evidence(bookingId, null, null, null, null, null, null, null);
        }

        /** A check-in that stands: recorded and not reversed ("valid check-in", FR-ATT-03). */
        Instant activeCheckIn() {
            return checkInReversedAt == null ? checkInAt : null;
        }

        Instant activeLatePresent() {
            return latePresentReversedAt == null ? latePresentAt : null;
        }

        /** A committed no-show that stands (FR-ATT-06: only a Platform Admin reverses it). */
        Instant activeNoShow() {
            return noShowReversedAt == null ? noShowAt : null;
        }

        /**
         * Whether the action may add its evidence (arch 07 section 7.1). Its own column must still be empty: recorded
         * evidence is immutable, so even an admin-reversed one cannot be recorded again. Then each action's
         * preconditions: check-in - no other evidence standing; Mark Present - Late - no valid check-in and no
         * committed no-show (FR-ATT-03); no-show - no valid check-in and no Mark Present - Late (FR-ATT-04).
         */
        boolean permits(AttendanceAction action) {
            return switch (action) {
                case CHECK_IN -> checkInAt == null && activeLatePresent() == null && activeNoShow() == null;
                case LATE_PRESENT -> latePresentAt == null && activeCheckIn() == null && activeNoShow() == null;
                case NO_SHOW -> noShowAt == null && activeCheckIn() == null && activeLatePresent() == null;
            };
        }
    }
}
