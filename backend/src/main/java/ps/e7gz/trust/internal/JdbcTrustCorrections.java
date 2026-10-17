package ps.e7gz.trust.internal;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.TrustCorrections;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * {@code ATTENDANCE_CORRECTED} and {@code NO_SHOW_REVERSED} on the command's transaction (E7-70). The chain position
 * is read under the command's Customer lock, so two corrections of one booking are serialised and the second supersedes
 * the first; the database assigns the sequence and refuses a branched or out-of-order chain
 * ({@code trust.validate_attendance_correction_event}).
 */
@Component
class JdbcTrustCorrections implements TrustCorrections {

    /** The four outcomes a correction may choose (ck_trust_event_corrected_outcome, FR-TRS-07). */
    private static final Set<AttendanceResult> CORRECTABLE = EnumSet.of(AttendanceResult.ATTENDED,
            AttendanceResult.ATTENDED_INTERRUPTED, AttendanceResult.ATTENDANCE_UNCONFIRMED,
            AttendanceResult.ABSENT_NO_SHOW);

    /** The booking's one original canonical outcome and its current non-superseded correction, if any. */
    static final String CHAIN = """
            SELECT o.id, o.event_type, o.effective_at,
                   (SELECT te.id FROM trust.trust_event te
                     WHERE te.booking_id = o.booking_id AND te.event_type = 'ATTENDANCE_CORRECTED'
                       AND NOT EXISTS (SELECT 1 FROM trust.trust_event s
                                        WHERE s.event_type = 'ATTENDANCE_CORRECTED'
                                          AND s.superseded_correction_event_id = te.id)
                     ORDER BY te.event_sequence DESC LIMIT 1) AS leaf_id
              FROM trust.trust_event o
             WHERE o.booking_id = :booking AND o.subject_user_id = :user
               AND o.event_type IN ('BOOKING_ATTENDED','BOOKING_ATTENDANCE_UNCONFIRMED',
                                    'BOOKING_ATTENDED_INTERRUPTED','BOOKING_NO_SHOW')
            """;

    static final String INSERT_CORRECTION = """
            INSERT INTO trust.trust_event (id, event_type, subject_user_id, occurred_at, effective_at, booking_id,
                                           original_outcome_event_id, superseded_correction_event_id,
                                           corrected_outcome)
            VALUES (:id, 'ATTENDANCE_CORRECTED', :user, :occurredAt, :effectiveAt, :booking, :original, :superseded,
                    :outcome)
            """;

    /** No corrected outcome and no superseded link (ck_trust_event_corrected_outcome). */
    static final String INSERT_REVERSAL = """
            INSERT INTO trust.trust_event (id, event_type, subject_user_id, occurred_at, effective_at, booking_id,
                                           original_outcome_event_id)
            VALUES (:id, 'NO_SHOW_REVERSED', :user, :occurredAt, :effectiveAt, :booking, :original)
            """;

    private final TrustReconciler reconciler;

    JdbcTrustCorrections(TrustReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public UUID correctAttendance(CommandContext command, UUID customerId, UUID bookingId,
                                  AttendanceResult correctedOutcome) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(bookingId, "bookingId");
        if (!CORRECTABLE.contains(Objects.requireNonNull(correctedOutcome, "correctedOutcome"))) {
            throw new IllegalArgumentException("Not a correctable Attendance outcome: " + correctedOutcome);
        }
        Chain chain = chain(command, customerId, bookingId);
        UUID id = UUID.randomUUID();
        command.jdbc().sql(INSERT_CORRECTION)
                .param("id", id)
                .param("user", customerId)
                .param("occurredAt", OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC))
                .param("effectiveAt", chain.effectiveAt())                                    // FR-TRS-05
                .param("booking", bookingId)
                .param("original", chain.originalId())
                .param("superseded", chain.leafId(), Types.OTHER)                              // FR-TRS-09
                .param("outcome", correctedOutcome.name())
                .update();
        reconciler.reconcile(command, customerId);
        return id;
    }

    @Override
    public UUID reverseNoShow(CommandContext command, UUID customerId, UUID bookingId) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(bookingId, "bookingId");
        Chain chain = chain(command, customerId, bookingId);
        if (!"BOOKING_NO_SHOW".equals(chain.originalType())) {
            throw new IllegalStateException("Booking " + bookingId + " has no BOOKING_NO_SHOW to reverse");
        }
        UUID id = UUID.randomUUID();
        command.jdbc().sql(INSERT_REVERSAL)
                .param("id", id)
                .param("user", customerId)
                .param("occurredAt", OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC))
                .param("effectiveAt", chain.effectiveAt())                                    // FR-TRS-05
                .param("booking", bookingId)
                .param("original", chain.originalId())
                .update();
        reconciler.reconcile(command, customerId);
        return id;
    }

    private static Chain chain(CommandContext command, UUID customerId, UUID bookingId) {
        return command.jdbc().sql(CHAIN).param("booking", bookingId).param("user", customerId)
                .query((rs, n) -> new Chain(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getObject("effective_at", OffsetDateTime.class), rs.getObject("leaf_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " has no canonical outcome"));
    }

    private record Chain(UUID originalId, String originalType, OffsetDateTime effectiveAt, UUID leafId) { }
}
