package ps.e7gz.trust.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.trust.api.BanCascadeBookings;
import ps.e7gz.trust.api.TrustConsequenceUnavailableException;
import ps.e7gz.trust.api.TrustReconciler.OutstandingQualification;
import ps.e7gz.trust.api.TrustReconciler.UnsupportedBan;

/**
 * The operational Ban consequence of replay (arch 07 sections 4, 5.2-5.6; FR-TRS-14..25; E7-71), on the reconciling
 * command's transaction, through the database's own producers. Every key that makes it exactly-once is the database's:
 * a qualification and stage are consumed once ({@code trust.consume_ban_qualification}), at most one ACTIVE instance
 * exists ({@code ux_one_active_ban}), and a Ban's cascade is registered and completed once, by the command that created
 * it ({@code trust.register_ban_cascade_targets}, {@code cascade_execution} key {@code BAN_CASCADE:{banId}},
 * {@code trust.complete_ban_cascade}). Replaying the same history finds nothing outstanding and writes nothing.
 *
 * <p>Order, at the one command instant:
 * <ol>
 *   <li>an ACTIVE Ban already past its {@code endsAt} is EXPIRED first (its natural end owns its interval; the database
 *       refuses any other lifecycle step on it);</li>
 *   <li>a Ban left without replay-valid support by a correction in this command is CORRECTED (FR-TRS-24);</li>
 *   <li>each outstanding qualification, in canonical order: with no Ban in force, a new instance activated now -
 *       never back-dated (FR-TRS-18) - with its cascade (FR-TRS-20, FR-TRS-21); with one in force, the same instance
 *       extended to {@code MAX(endsAt, now + stage duration)} with its {@code BAN_EXTENDED} audit, the cascade never
 *       repeated (FR-TRS-22).</li>
 * </ol>
 * T10 ({@code BAN_EXPIRY:{banId}}, arch 08 section 1) is scheduled at activation; an extension leaves it at the old end
 * and {@link BanExpiryHandler} moves it on when it fires.
 */
@Component
class JdbcBanReconciliation implements BanReconciliation {

    static final String BAN_EXPIRY = "BAN_EXPIRY";
    static final String BAN_SUBJECT = "BAN_INSTANCE";
    static final String ACTIVATION_REASON = "PENALTY_THRESHOLD_REACHED";

    static final String QUALIFICATION = """
            INSERT INTO trust.ban_qualification (id, user_id, qualification_at, penalty_stage, triggering_event_id)
            VALUES (:id, :user, :at, CAST(:stage AS smallint), :event)
            ON CONFLICT ON CONSTRAINT uq_ban_qualification_trigger DO NOTHING
            """;

    static final String FIND_QUALIFICATION = """
            SELECT id FROM trust.ban_qualification
             WHERE triggering_event_id = :event AND penalty_stage = CAST(:stage AS smallint)
            """;

    /** The instance's derived facts are the guard's ({@code trust.guard_ban_instance}); these values only match it. */
    static final String ACTIVATE = """
            INSERT INTO trust.ban_instance (id, user_id, qualification_id, penalty_stage, activated_at, ends_at,
                                            duration, status, activation_reason, latest_qualification_id,
                                            last_command_at)
            VALUES (:id, :user, :qualification, CAST(:stage AS smallint), :now,
                    CAST(:now AS timestamptz) + trust.penalty_duration(CAST(:stage AS smallint)),
                    trust.penalty_duration(CAST(:stage AS smallint)), 'ACTIVE', :reason, :qualification, :now)
            RETURNING ends_at
            """;

    static final String REGISTER = "SELECT trust.register_ban_cascade_targets(:ban, :correlation)";

    static final String TARGETS = """
            SELECT booking_id FROM trust.ban_cascade_booking_target WHERE ban_instance_id = :ban ORDER BY booking_id
            """;

    static final String CASCADE_EXECUTION = """
            INSERT INTO trust.cascade_execution (id, cascade_type, cascade_key, subject_user_id, source_id, executed_at,
                                                 correlation_id)
            VALUES (:id, 'BAN', :key, :user, :ban, :now, :correlation)
            """;

    static final String COMPLETE = "SELECT trust.complete_ban_cascade(:ban)";

    static final String CONSUME = "SELECT trust.consume_ban_qualification(:ban, :qualification, :type, :correlation)";

    static final String IN_FORCE = """
            SELECT id, penalty_stage, ends_at FROM trust.ban_instance
             WHERE user_id = :user AND status = 'ACTIVE' AND activated_at <= :now AND ends_at > :now
            """;

    /** The guard computes the new end ({@code MAX}) and duration; the stage must equal the qualification's. */
    static final String EXTEND = """
            UPDATE trust.ban_instance
               SET latest_qualification_id = :qualification, penalty_stage = CAST(:stage AS smallint)
             WHERE id = :ban AND status = 'ACTIVE'
            RETURNING ends_at
            """;

    static final String EXPIRE_DUE = """
            UPDATE trust.ban_instance SET status = 'EXPIRED'
             WHERE user_id = :user AND status = 'ACTIVE' AND ends_at <= :now
            RETURNING id, penalty_stage, ends_at
            """;

    /** The replay-invalidating input of this command (FR-TRS-24): its correction or reversal, at the command instant. */
    static final String CORRECTION_EVENT = """
            SELECT id FROM trust.trust_event
             WHERE subject_user_id = :user AND occurred_at = :now
               AND event_type IN ('ATTENDANCE_CORRECTED', 'NO_SHOW_REVERSED')
             ORDER BY event_sequence DESC LIMIT 1
            """;

    static final String CORRECT = """
            UPDATE trust.ban_instance SET status = 'CORRECTED' WHERE id = :ban AND status = 'ACTIVE'
            RETURNING penalty_stage, ends_at
            """;

    private final BanCascadeBookings cascade;
    private final AuditWriter audit;
    private final Outbox outbox;
    private final ScheduledJobs jobs;

    JdbcBanReconciliation(BanCascadeBookings cascade, AuditWriter audit, Outbox outbox, ScheduledJobs jobs) {
        this.cascade = cascade;
        this.audit = audit;
        this.outbox = outbox;
        this.jobs = jobs;
    }

    @Override
    public void reconcile(CommandContext command, UUID userId, List<OutstandingQualification> outstanding,
                          List<UnsupportedBan> unsupported) {
        Objects.requireNonNull(userId, "userId");
        expireDue(command, userId);
        for (UnsupportedBan ban : unsupported) {
            correct(command, userId, ban);
        }
        for (OutstandingQualification qualification : outstanding) {
            UUID qualificationId = qualification(command, userId, qualification);
            Optional<InForce> current = inForce(command, userId);
            if (current.isPresent()) {
                extend(command, userId, current.get(), qualification, qualificationId);
            } else {
                activate(command, userId, qualification, qualificationId);
            }
        }
    }

    /**
     * Every ACTIVE Ban of the User whose {@code endsAt} is at or before the command instant becomes EXPIRED, with its
     * audit and {@code BanExpired} (FR-TRS-23); its T10 job is withdrawn unless a worker is firing it now. @return how
     * many expired
     */
    int expireDue(CommandContext command, UUID userId) {
        Instant now = command.commandNow();
        List<Expired> expired = command.jdbc().sql(EXPIRE_DUE).param("user", userId).param("now", utc(now))
                .query((rs, n) -> new Expired(rs.getObject("id", UUID.class), rs.getInt("penalty_stage"),
                        instant(rs, "ends_at")))
                .list();
        for (Expired ban : expired) {
            audit.write(command, AuditRecord.builder(TrustAuditAction.BAN_EXPIRED, AuditActor.system(), userId,
                            CorrelationId.require())
                    .payload(Map.of("banInstanceId", ban.id(), "penaltyStage", ban.stage(),
                            "endsAt", ban.endsAt().toString()))
                    .stateChange("ACTIVE", "EXPIRED")
                    .build());
            outbox.publish(command, BanEvents.expired(userId, ban.id(), ban.stage(), ban.endsAt()));
            jobs.supersede(command, expiryKey(ban.id()));
        }
        return expired.size();
    }

    private UUID qualification(CommandContext command, UUID userId, OutstandingQualification due) {
        command.jdbc().sql(QUALIFICATION)
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("at", utc(due.qualificationAt()))
                .param("stage", due.penaltyStage())
                .param("event", due.effectEventId())
                .update();
        return command.jdbc().sql(FIND_QUALIFICATION).param("event", due.effectEventId())
                .param("stage", due.penaltyStage()).query(UUID.class).single();
    }

    private Optional<InForce> inForce(CommandContext command, UUID userId) {
        return command.jdbc().sql(IN_FORCE).param("user", userId).param("now", utc(command.commandNow()))
                .query((rs, n) -> new InForce(rs.getObject("id", UUID.class), rs.getInt("penalty_stage"),
                        instant(rs, "ends_at")))
                .optional();
    }

    /** FR-TRS-18, FR-TRS-20, FR-TRS-21: a new instance activated now, its exact cascade registered and done once. */
    private void activate(CommandContext command, UUID userId, OutstandingQualification due, UUID qualificationId) {
        Instant now = command.commandNow();
        UUID correlation = CorrelationId.require();
        UUID banId = UUID.randomUUID();
        Instant endsAt = command.jdbc().sql(ACTIVATE)
                .param("id", banId).param("user", userId).param("qualification", qualificationId)
                .param("stage", due.penaltyStage()).param("now", utc(now)).param("reason", ACTIVATION_REASON)
                .query((rs, n) -> instant(rs, "ends_at")).single();
        command.jdbc().sql(REGISTER).param("ban", banId).param("correlation", correlation).query().singleRow();
        List<UUID> targets = command.jdbc().sql(TARGETS).param("ban", banId).query(UUID.class).list();
        command.jdbc().sql(CASCADE_EXECUTION)
                .param("id", UUID.randomUUID()).param("key", "BAN_CASCADE:" + banId).param("user", userId)
                .param("ban", banId).param("now", utc(now)).param("correlation", correlation)
                .update();
        if (!targets.isEmpty()) {
            cascade.cancel(command, userId, targets, now);
        }
        command.jdbc().sql(COMPLETE).param("ban", banId).query().singleRow();
        consume(command, banId, qualificationId, "CREATE", correlation);
        audit.write(command, AuditRecord.builder(TrustAuditAction.BAN_ACTIVATED, AuditActor.system(), userId,
                        correlation)
                .payload(Map.of("banInstanceId", banId, "qualificationId", qualificationId,
                        "triggeringEventId", due.effectEventId(), "penaltyStage", due.penaltyStage(),
                        "endsAt", endsAt.toString()))
                .build());
        outbox.publish(command, BanEvents.activated(userId, banId, due.penaltyStage(), now, endsAt, targets.size()));
        jobs.schedule(command, NewJob.of(BAN_EXPIRY, BAN_SUBJECT, banId, endsAt));
    }

    /** FR-TRS-22: the same instance, the stage advanced, the end at MAX(old, now + duration); never the cascade again. */
    private void extend(CommandContext command, UUID userId, InForce ban, OutstandingQualification due,
                        UUID qualificationId) {
        UUID correlation = CorrelationId.require();
        Instant endsAt = command.jdbc().sql(EXTEND)
                .param("qualification", qualificationId).param("stage", due.penaltyStage()).param("ban", ban.id())
                .query((rs, n) -> instant(rs, "ends_at")).single();
        consume(command, ban.id(), qualificationId, "EXTEND", correlation);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("banInstanceId", ban.id());
        payload.put("triggeringQualificationId", qualificationId);
        payload.put("triggeringEventId", due.effectEventId());
        payload.put("previousPenaltyStage", ban.stage());
        payload.put("newPenaltyStage", due.penaltyStage());
        payload.put("oldEndsAt", ban.endsAt().toString());
        payload.put("newEndsAt", endsAt.toString());
        audit.write(command, AuditRecord.builder(TrustAuditAction.BAN_EXTENDED, AuditActor.system(), userId,
                correlation).payload(payload).build());
        outbox.publish(command, BanEvents.extended(userId, ban.id(), ban.stage(), due.penaltyStage(), ban.endsAt(),
                endsAt));
    }

    /**
     * FR-TRS-24, FR-TRS-25: no consumed qualification of the Ban is replay-valid any more, so it is CORRECTED now (its
     * end the command instant), by SYSTEM, citing this command's correction. Cancelled bookings do not return. A Ban
     * still partly supported would need shortening, which the database allows only in a Platform Admin trust command
     * ({@code trust.require_admin_command}): not available to replay, so refused rather than guessed.
     */
    private void correct(CommandContext command, UUID userId, UnsupportedBan ban) {
        Instant now = command.commandNow();
        if (ban.supportedUntil() != null && ban.supportedUntil().isAfter(now)) {
            throw new TrustConsequenceUnavailableException(userId,
                    "a Platform Admin trust command to shorten Ban " + ban.banInstanceId() + " to "
                            + ban.supportedUntil() + " (FR-TRS-25)", null);
        }
        UUID correction = command.jdbc().sql(CORRECTION_EVENT).param("user", userId).param("now", utc(now))
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalStateException("Ban " + ban.banInstanceId()
                        + " lost its support without a correction in this command"));
        Corrected corrected = command.jdbc().sql(CORRECT).param("ban", ban.banInstanceId())
                .query((rs, n) -> new Corrected(rs.getInt("penalty_stage"), instant(rs, "ends_at"))).single();
        audit.write(command, AuditRecord.builder(TrustAuditAction.BAN_CORRECTED, AuditActor.system(), userId,
                        CorrelationId.require())
                .reason("Corrected trust history no longer supports this Ban (FR-TRS-24)")
                .payload(Map.of("banInstanceId", ban.banInstanceId(), "correctionEventId", correction,
                        "penaltyStage", corrected.stage()))
                .stateChange("ACTIVE", "CORRECTED")
                .build());
        outbox.publish(command, BanEvents.corrected(userId, ban.banInstanceId(), corrected.stage(), corrected.endsAt(),
                correction));
        jobs.supersede(command, expiryKey(ban.banInstanceId()));
    }

    private static void consume(CommandContext command, UUID banId, UUID qualificationId, String type,
                                UUID correlation) {
        command.jdbc().sql(CONSUME).param("ban", banId).param("qualification", qualificationId).param("type", type)
                .param("correlation", correlation).query().singleRow();
    }

    static String expiryKey(UUID banId) {
        return BAN_EXPIRY + ":" + banId;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record InForce(UUID id, int stage, Instant endsAt) { }

    private record Expired(UUID id, int stage, Instant endsAt) { }

    private record Corrected(int stage, Instant endsAt) { }
}
