package ps.e7gz.common.error;

import static java.util.Map.entry;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Maps a database refusal to its {@link ApiErrorCode} (arch 09 §1.5 "Database guard exceptions", ADR-026 §4).
 *
 * <p>The match is exact, on two structured diagnostics the PostgreSQL driver reports — the SQLSTATE and the
 * CONSTRAINT field — never on message text:
 * <ul>
 *   <li>a Product guard raises {@code P0001} with a stable {@code gd_…} identifier in CONSTRAINT (migration
 *       {@code V202609291200__guard_error_identifiers.sql}, physical model v1.1.28);</li>
 *   <li>an exclusion or unique constraint raises {@code 23P01} / {@code 23505} with its own name.</li>
 * </ul>
 * Anything else — an internal invariant guard (no identifier), an identifier that is deliberately unmapped
 * ({@link #UNMAPPED_GUARDS}), an unknown identifier, or {@code 40001} after {@code CommandRunner}'s retries are
 * exhausted — has no code: it is an unexpected failure (HTTP 500 with the correlation ID only).
 */
public final class DatabaseErrorCodes {

    public static final String RAISE_EXCEPTION = "P0001";
    public static final String EXCLUSION_VIOLATION = "23P01";
    public static final String UNIQUE_VIOLATION = "23505";

    /** Product guard identifier → code. Each entry cites the rule the guard re-asserts. */
    static final Map<String, ApiErrorCode> GUARDS = Map.ofEntries(
            // Online Booking creation (Product §21–§22, FR-BOK-11)
            entry("gd_online_booking_lead_time", ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT),          // FR-BOK-02
            entry("gd_online_booking_intake_window", ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED),      // §21, FR-BOK-09
            entry("gd_online_booking_venue_not_active", ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY), // cat. A
            entry("gd_manual_booking_backdated", ApiErrorCode.MANUAL_BOOKING_BACKDATING_NOT_ALLOWED), // FR-MAN-05
            // Resource reservation ledger: the conflict code depends on the reservation type
            entry("gd_reservation_conflict_booking", ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),         // FR-AVL-11
            entry("gd_reservation_conflict_late_confirm", ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT), // FR-LCF-16
            entry("gd_reservation_conflict_tournament", ApiErrorCode.TOURNAMENT_RESOURCE_CONFLICT),  // FR-TRN-08
            entry("gd_reservation_conflict_maintenance", ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION), // FR-OPS-02
            // Attendance windows
            entry("gd_check_in_window", ApiErrorCode.CHECK_IN_WINDOW_CLOSED),                        // FR-ATT-02
            entry("gd_late_present_window", ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE),                // FR-ATT-03
            entry("gd_no_show_window", ApiErrorCode.NO_SHOW_NOT_AVAILABLE),                          // FR-ATT-04
            // Tournament creation and joining
            entry("gd_tournament_venue_not_active", ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY), // cat. A
            entry("gd_tournament_venue_selling_block", ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE),     // FR-AVL-07
            entry("gd_tournament_creation_cutoff", ApiErrorCode.TOURNAMENT_CREATION_CUTOFF_PASSED),  // FR-TRN-03
            entry("gd_tournament_creator_deleted", ApiErrorCode.ACCOUNT_DELETED),                    // FR-ACC-10
            entry("gd_tournament_creator_unverified", ApiErrorCode.PHONE_VERIFICATION_REQUIRED),     // FR-ACC-03
            entry("gd_tournament_creator_not_verified_player", ApiErrorCode.VERIFIED_PLAYER_REQUIRED), // FR-TRN-02
            entry("gd_tournament_creator_banned", ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION),      // FR-ACC-17
            entry("gd_tournament_retry_cooldown", ApiErrorCode.TOURNAMENT_RETRY_COOLDOWN_ACTIVE),    // FR-TRN-11
            entry("gd_tournament_capacity_full", ApiErrorCode.TOURNAMENT_CAPACITY_REACHED),          // FR-TRN-12
            // Identity
            entry("gd_username_cooldown", ApiErrorCode.USERNAME_COOLDOWN_ACTIVE),                    // FR-ACC-07
            entry("gd_otp_resend_too_soon", ApiErrorCode.OTP_RESEND_TOO_SOON),                       // FR-ACC-04
            entry("gd_otp_hourly_limit", ApiErrorCode.OTP_HOURLY_LIMIT_REACHED),                     // FR-ACC-04
            // Areas and Venues
            entry("gd_area_has_active_venues", ApiErrorCode.AREA_HAS_ACTIVE_VENUES),                 // FR-VEN-47
            entry("gd_venue_area_inactive_assignment", ApiErrorCode.AREA_INACTIVE),                  // FR-VEN-47
            entry("gd_venue_area_inactive_status", ApiErrorCode.AREA_INACTIVE),                      // FR-VEN-47
            // Maintenance
            entry("gd_maintenance_overlaps_commitment", ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION), // FR-OPS-02
            entry("gd_maintenance_owner_password_required", ApiErrorCode.OWNER_PASSWORD_REQUIRED),   // FR-VEN-03
            // Reviews
            entry("gd_review_author_deleted", ApiErrorCode.ACCOUNT_DELETED),                         // FR-ACC-10
            entry("gd_review_author_unverified", ApiErrorCode.PHONE_VERIFICATION_REQUIRED),          // FR-REV-01
            entry("gd_review_already_exists", ApiErrorCode.REVIEW_ALREADY_EXISTS),                   // FR-REV-03
            entry("gd_review_cooldown", ApiErrorCode.REVIEW_COOLDOWN_ACTIVE),                        // FR-REV-07
            entry("gd_review_report_open", ApiErrorCode.REVIEW_REPORT_ALREADY_OPEN),                 // FR-REV-11
            // Idempotency
            entry("gd_idempotency_key_reused", ApiErrorCode.IDEMPOTENCY_KEY_REUSED));                // ADR-015

    /**
     * Identifiers the migration sets that deliberately map to no code: each can fire only when the application skipped
     * the decision that comes first under the command's locks, so it is a defect — HTTP 500 with the correlation ID and
     * an error log (arch 09 §1.5: "a guard exception the application did not anticipate is a defect"). Proven against
     * the database in {@code GuardErrorIdentifiersTest} ("reachability"):
     * <ul>
     *   <li>{@code gd_online_booking_area_inactive} — an inactive Area never holds an ACTIVE Venue, even under
     *       concurrency (FR-VEN-47), and the booking command locks its Venue before checking FR-BOK-11 condition 5
     *       (Venue ACTIVE) ahead of condition 7 (arch 06 §1.3). A booking that passes condition 5 therefore never meets an
     *       inactive Area; one that fails it is refused as {@code VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY} first.</li>
     *   <li>{@code gd_reservation_conflict_closure} — a Closure locks its Venue and Resources before writing its
     *       CLOSURE rows, so a racing commitment loses as {@code SLOT_NO_LONGER_AVAILABLE}; a commitment already there
     *       must be cancelled (FR-VEN-30), abort the save ({@code AFFECTED_SET_CHANGED_REFRESH_PREVIEW}, FR-VEN-26) or block
     *       it ({@code TOURNAMENT_CONFLICT_BLOCKS_SAVE}, FR-VEN-32) first. The identifier cannot tell which of the three
     *       was skipped, so no single code would be true.</li>
     *   <li>{@code gd_reservation_conflict} — defensive default for a reservation type outside the CHECK list.</li>
     * </ul>
     */
    static final Set<String> UNMAPPED_GUARDS = Set.of(
            "gd_online_booking_area_inactive", "gd_reservation_conflict_closure", "gd_reservation_conflict");

    /** Exclusion / unique constraint name → code, per SQLSTATE. */
    static final Map<String, Map<String, ApiErrorCode>> CONSTRAINTS = Map.of(
            EXCLUSION_VIOLATION, Map.of(
                    "ex_no_double_booking", ApiErrorCode.SLOT_NO_LONGER_AVAILABLE,      // arch 09 §1.5, FR-AVL-11
                    "ex_customer_no_overlap", ApiErrorCode.OVERLAPPING_COMMITMENT),     // FR-BOK-04
            UNIQUE_VIOLATION, Map.of(
                    "ux_user_username_normalized", ApiErrorCode.USERNAME_TAKEN,         // FR-ACC-06
                    "uq_user_account_phone_e164", ApiErrorCode.PHONE_TAKEN));           // FR-ACC-01

    private DatabaseErrorCodes() {
    }

    /** The code for a database refusal anywhere in the cause chain, or empty when it is an unexpected failure. */
    public static Optional<ApiErrorCode> translate(Throwable failure) {
        return serverError(failure).flatMap(error -> codeFor(error.getSQLState(), error.getConstraint()));
    }

    /** The code for a SQLSTATE and CONSTRAINT diagnostic pair, or empty. */
    public static Optional<ApiErrorCode> codeFor(String sqlState, String constraint) {
        if (sqlState == null || constraint == null) {
            return Optional.empty();
        }
        if (RAISE_EXCEPTION.equals(sqlState)) {
            return Optional.ofNullable(GUARDS.get(constraint));
        }
        return Optional.ofNullable(CONSTRAINTS.getOrDefault(sqlState, Map.of()).get(constraint));
    }

    /** The server diagnostics of the first PostgreSQL error in the cause chain (Spring keeps the driver's cause). */
    public static Optional<ServerErrorMessage> serverError(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return Optional.of(psql.getServerErrorMessage());
            }
            if (t instanceof SQLException sql && sql.getNextException() != null && seen.add(sql.getNextException())) {
                Optional<ServerErrorMessage> next = serverError(sql.getNextException());
                if (next.isPresent()) {
                    return next;
                }
            }
        }
        return Optional.empty();
    }
}
