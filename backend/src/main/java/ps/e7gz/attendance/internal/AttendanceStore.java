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
