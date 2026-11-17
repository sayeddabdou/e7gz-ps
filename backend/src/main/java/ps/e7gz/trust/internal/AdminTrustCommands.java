package ps.e7gz.trust.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.BanInstanceStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.loyalty.api.AccountHolders;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * The Platform Admin's trust remediation (arch 09 section 5 "Trust"; FR-TRS-23, FR-TRS-25, FR-TRS-27, FR-AUD-06,
 * FR-NTF-11; Sprint 4 contract E7-65, PO decisions D2, D5, D6; E7-71): the trust adjustment and a Ban's lift,
 * shortening and correction. Each is one admin command ({@code admin.begin_command}, then
 * {@code trust.begin_admin_command}, which the database's Ban and adjustment guards require) under the Customer's lock,
 * wrapped by {@link Idempotency} (the Idempotency-Key record commits with it). The Admin's reason is kept in the audit row
 * the database matches at commit ({@code trust.assert_ban_lifecycle_audit}, {@code trust.assert_admin_trust_event_audit})
 * and the customer is told through the outbox ({@code BanLifted}, {@code BanShortened}, {@code BanCorrected},
 * {@code TrustAdjusted}). Previously cancelled bookings never return (FR-TRS-25).
 *
 * <p>Before a Ban action, a Ban already past its end is EXPIRED (the database refuses any other step on it), so it is
 * then not ACTIVE - {@code ADMIN_ACTION_NOT_ALLOWED} - as is a Ban already LIFTED, CORRECTED or EXPIRED (D5). A Ban of
 * another User, or an unknown User, is {@code NOT_FOUND}.
 *
 * <p><b>Correct</b> (D2, decided for E7-71): {@code CORRECTED} means the corrected trust history no longer supports the
 * Ban, which only the database can prove ({@code trust.assert_ban_lifecycle_audit}: no consumed qualification still
 * current, and the replay-invalidating event in the same command). The history is corrected through the attendance
 * routes (E7-70), and the command that does it corrects - or, when part of the support remains, shortens - the Ban
 * itself. So this route reconciles the Customer under the Admin and answers the Ban as reconciliation left it: CORRECTED
 * when the Admin's command corrected it, otherwise {@code ADMIN_ACTION_NOT_ALLOWED} - the history still supports it, and
 * the Admin lifts or shortens it instead. It never fabricates a trust event to make the database accept it.
 */
@Component
public class AdminTrustCommands {

    static final String BEGIN_ADMIN = "SELECT trust.begin_admin_command(:user, :admin)";

    static final String THRESHOLD = "SELECT trust.penalty_threshold(CAST(:stage AS smallint))";

    static final String ADJUSTMENT = """
            INSERT INTO trust.trust_event (id, event_type, subject_user_id, occurred_at, effective_at, payload)
            VALUES (:id, 'ADMIN_TRUST_ADJUSTMENT', :user, :now, :now, CAST(:payload AS jsonb))
            """;

    /** The standing the projection holds (refreshed by the reconciler in the same command). */
    static final String STANDING = """
            SELECT p.consecutive_no_shows, p.penalty_stage, p.current_threshold
              FROM trust.trust_projection p WHERE p.user_id = :user
            """;

    static final String BAN = """
            SELECT b.id, b.user_id, b.status, b.penalty_stage, q.qualification_at, b.activated_at, b.ends_at
              FROM trust.ban_instance b
              JOIN trust.ban_qualification q ON q.id = b.latest_qualification_id
             WHERE b.id = :ban AND b.user_id = :user
            """;

    static final String IN_FORCE = """
            SELECT b.id FROM trust.ban_instance b
             WHERE b.user_id = :user AND b.status = 'ACTIVE' AND b.activated_at <= :now AND b.ends_at > :now
            """;

    static final String LOCK_BAN = "SELECT id FROM trust.ban_instance WHERE id = :ban FOR UPDATE";

    static final String LIFT = """
            UPDATE trust.ban_instance SET status = 'LIFTED' WHERE id = :ban AND status = 'ACTIVE'
            RETURNING ends_at
            """;

    static final String SHORTEN = """
            UPDATE trust.ban_instance SET ends_at = :endsAt WHERE id = :ban AND status = 'ACTIVE'
            RETURNING ends_at
            """;

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final AccountHolders holders;
    private final JdbcBanReconciliation bans;
    private final TrustReconciler reconciler;
    private final AuditWriter audit;
    private final Outbox outbox;

    AdminTrustCommands(CommandRunner runner, Idempotency idempotency, AccountHolders holders,
                       JdbcBanReconciliation bans, TrustReconciler reconciler, AuditWriter audit, Outbox outbox) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.holders = holders;
        this.bans = bans;
        this.reconciler = reconciler;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** A Ban Instance as the Admin sees it (FR-TRS-17): its stage's historical qualification and its operational times. */
    public record Ban(UUID banId, UUID userId, BanInstanceStatus status, int penaltyStage, Instant qualificationAt,
                      Instant activatedAt, Instant endsAt) {
    }

    /** The Customer's standing after the command (FR-TRS-15); {@code activeBan} is the one in force, or null. */
    public record Standing(UUID userId, int consecutiveNoShows, int penaltyStage, int currentThreshold, Ban activeBan) {
    }

    // --------------------------------------------------------------------------------------------- entry points

    /**
     * {@code POST /admin/users/{userId}/trust-adjustment} (FR-TRS-27): the ADMIN_TRUST_ADJUSTMENT v1 absolute values,
     * effective now (FR-TRS-05), once per Idempotency-Key. {@code counterAfter} at or above the threshold of
     * {@code penaltyStageAfter} ({@code trust.penalty_threshold}) is {@code VALIDATION_FAILED} on {@code counterAfter}.
     */
    public <R> IdempotentResult<R> adjust(UUID adminId, UUID userId, int counterAfter, int penaltyStageAfter,
                                          String reason, IdempotentRequest key, StoredResponse<R> response,
                                          Function<Standing, R> view) {
        return idempotent(adjustment(adminId, userId, counterAfter, penaltyStageAfter, reason), key, response, view);
    }

    /** {@code POST .../bans/{banId}/lift} (FR-TRS-23, FR-TRS-27): LIFTED now, once per Idempotency-Key. */
    public <R> IdempotentResult<R> lift(UUID adminId, UUID userId, UUID banId, String reason, IdempotentRequest key,
                                        StoredResponse<R> response, Function<Ban, R> view) {
        return idempotent(lifting(adminId, userId, banId, reason), key, response, view);
    }

    /**
     * {@code POST .../bans/{banId}/shorten} (FR-TRS-27): the end brought forward to {@code endsAt}, still ACTIVE until
     * then. {@code endsAt} not after now or not before the current end is {@code VALIDATION_FAILED} on {@code endsAt}.
     */
    public <R> IdempotentResult<R> shorten(UUID adminId, UUID userId, UUID banId, Instant endsAt, String reason,
                                           IdempotentRequest key, StoredResponse<R> response, Function<Ban, R> view) {
        return idempotent(shortening(adminId, userId, banId, endsAt, reason), key, response, view);
    }

    /** {@code POST .../bans/{banId}/correct} (FR-TRS-25, D2): see the class comment. */
    public <R> IdempotentResult<R> correct(UUID adminId, UUID userId, UUID banId, String reason,
                                           IdempotentRequest key, StoredResponse<R> response, Function<Ban, R> view) {
        return idempotent(correction(adminId, userId, banId, reason), key, response, view);
    }

    /** The adjustment without an Idempotency-Key record: package-private, for this package's tests. */
    Standing adjust(UUID adminId, UUID userId, int counterAfter, int penaltyStageAfter, String reason) {
        return runner.run(adjustment(adminId, userId, counterAfter, penaltyStageAfter, reason));
    }

    /** The lift without an Idempotency-Key record: package-private, for this package's tests. */
    Ban lift(UUID adminId, UUID userId, UUID banId, String reason) {
        return runner.run(lifting(adminId, userId, banId, reason));
    }

    /** The shortening without an Idempotency-Key record: package-private, for this package's tests. */
    Ban shorten(UUID adminId, UUID userId, UUID banId, Instant endsAt, String reason) {
        return runner.run(shortening(adminId, userId, banId, endsAt, reason));
    }

    /** The correction without an Idempotency-Key record: package-private, for this package's tests. */
    Ban correct(UUID adminId, UUID userId, UUID banId, String reason) {
        return runner.run(correction(adminId, userId, banId, reason));
    }

    // ------------------------------------------------------------------------------------------------- commands

    private Command<Standing> adjustment(UUID adminId, UUID userId, int counterAfter, int penaltyStageAfter,
                                         String reason) {
        String why = reason(reason);
        if (counterAfter < 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "counterAfter");
        }
        if (penaltyStageAfter < 0 || penaltyStageAfter > 3) {                       // ADMIN_TRUST_ADJUSTMENT v1 schema
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "penaltyStageAfter");
        }
        customer(userId);
        return new Body<>(adminId, CorrelationId.require(), userId, context -> {
            Instant now = context.commandNow();
            if (holders.find(context, userId).map(AccountHolders.Holder::deleted).orElse(true)) {
                throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);                       // FR-ACC-10
            }
            int threshold = context.jdbc().sql(THRESHOLD).param("stage", penaltyStageAfter).query(Integer.class)
                    .single();
            if (counterAfter >= threshold) {                                                 // FR-TRS-13, FR-TRS-14
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "counterAfter");
            }
            beginAdmin(context, userId, adminId);
            Standing before = standing(context, userId);
            UUID correlation = CorrelationId.require();
            UUID event = UUID.randomUUID();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schemaVersion", 1);
            payload.put("counterAfter", counterAfter);
            payload.put("penaltyStageAfter", penaltyStageAfter);
            payload.put("adminId", adminId.toString());
            payload.put("correlationId", correlation.toString());
            context.jdbc().sql(ADJUSTMENT).param("id", event).param("user", userId).param("now", utc(now))
                    .param("payload", json(payload)).update();
            Map<String, Object> audited = new LinkedHashMap<>();
            audited.put("trustEventId", event);
            audited.put("counterAfter", counterAfter);
            audited.put("penaltyStageAfter", penaltyStageAfter);
            audited.put("counterBefore", before.consecutiveNoShows());
            audited.put("penaltyStageBefore", before.penaltyStage());
            audit.write(context, AuditRecord.builder(TrustAuditAction.ADMIN_TRUST_ADJUSTMENT, AuditActor.admin(adminId),
                    userId, correlation).reason(why).payload(audited).build());
            bans.expireDue(context, userId);
            reconciler.reconcile(context, userId);
            outbox.publish(context, BanEvents.trustAdjusted(userId, event, counterAfter, penaltyStageAfter));
            return standing(context, userId);
        });
    }

    private Command<Ban> lifting(UUID adminId, UUID userId, UUID banId, String reason) {
        String why = reason(reason);
        ownedBan(userId, banId);
        return new Body<>(adminId, CorrelationId.require(), userId, context -> {
            Ban ban = activeBan(context, adminId, userId, banId);
            Instant endsAt = context.jdbc().sql(LIFT).param("ban", banId)
                    .query((rs, n) -> instant(rs, "ends_at")).single();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("banInstanceId", banId);
            payload.put("penaltyStage", ban.penaltyStage());
            payload.put("previousEndsAt", ban.endsAt().toString());
            audit.write(context, AuditRecord.builder(TrustAuditAction.BAN_LIFTED, AuditActor.admin(adminId), userId,
                            CorrelationId.require())
                    .reason(why).payload(payload).stateChange("ACTIVE", "LIFTED").build());
            outbox.publish(context, BanEvents.lifted(userId, banId, ban.penaltyStage(), ban.endsAt(), endsAt));
            bans.withdrawExpiry(context, banId);
            reconciler.reconcile(context, userId);
            return find(context, userId, banId).orElseThrow();
        });
    }

    private Command<Ban> shortening(UUID adminId, UUID userId, UUID banId, Instant endsAt, String reason) {
        String why = reason(reason);
        if (endsAt == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "endsAt");
        }
        Instant newEnd = endsAt.truncatedTo(ChronoUnit.MICROS);                             // the database's precision
        ownedBan(userId, banId);
        return new Body<>(adminId, CorrelationId.require(), userId, context -> {
            Ban ban = activeBan(context, adminId, userId, banId);
            if (!newEnd.isAfter(context.commandNow()) || !newEnd.isBefore(ban.endsAt())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "endsAt");
            }
            Instant stored = context.jdbc().sql(SHORTEN).param("ban", banId).param("endsAt", utc(newEnd))
                    .query((rs, n) -> instant(rs, "ends_at")).single();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("banInstanceId", banId);
            payload.put("penaltyStage", ban.penaltyStage());
            payload.put("previousEndsAt", ban.endsAt().toString());
            payload.put("newEndsAt", stored.toString());
            audit.write(context, AuditRecord.builder(TrustAuditAction.BAN_SHORTENED, AuditActor.admin(adminId), userId,
                    CorrelationId.require()).reason(why).payload(payload).build());
            outbox.publish(context, BanEvents.shortened(userId, banId, ban.penaltyStage(), ban.endsAt(), stored, null));
            bans.rescheduleExpiry(context, banId, stored);
            reconciler.reconcile(context, userId);
            return find(context, userId, banId).orElseThrow();
        });
    }

    private Command<Ban> correction(UUID adminId, UUID userId, UUID banId, String reason) {
        reason(reason);
        ownedBan(userId, banId);
        return new Body<>(adminId, CorrelationId.require(), userId, context -> {
            activeBan(context, adminId, userId, banId);
            reconciler.reconcile(context, userId);
            Ban after = find(context, userId, banId).orElseThrow();
            if (after.status() != BanInstanceStatus.CORRECTED) {
                throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);                // D2, D5: still supported
            }
            return after;
        });
    }

    // ---------------------------------------------------------------------------------------------------- support

    /**
     * Under the Customer's lock and the Admin's trust command: the Ban, still ACTIVE and in force. A Ban already past its
     * end is EXPIRED first ({@link JdbcBanReconciliation#expireDue}), so it is refused like any ended Ban (D5).
     */
    private Ban activeBan(CommandContext context, UUID adminId, UUID userId, UUID banId) {
        beginAdmin(context, userId, adminId);
        bans.expireDue(context, userId);
        context.jdbc().sql(LOCK_BAN).param("ban", banId).query(UUID.class).single();
        Ban ban = find(context, userId, banId).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (ban.status() != BanInstanceStatus.ACTIVE || ban.activatedAt().isAfter(context.commandNow())) {
            throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
        return ban;
    }

    private static void beginAdmin(CommandContext context, UUID userId, UUID adminId) {
        context.jdbc().sql(BEGIN_ADMIN).param("user", userId).param("admin", adminId).query().singleRow();
    }

    /** {@code begin_command} locks the User, so the User must exist (as a Customer) before the command opens. */
    private void customer(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        if (holders.find(List.of(userId)).isEmpty()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    /** The Ban must be this Customer's: anything else is NOT_FOUND, before an Idempotency-Key record is written. */
    private void ownedBan(UUID userId, UUID banId) {
        Objects.requireNonNull(banId, "banId");
        customer(userId);
        boolean owned = runner.run(new Read<>(context -> find(context, userId, banId).isPresent()));
        if (!owned) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    static Optional<Ban> find(CommandContext context, UUID userId, UUID banId) {
        return context.jdbc().sql(BAN).param("ban", banId).param("user", userId)
                .query((rs, n) -> new Ban(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        BanInstanceStatus.valueOf(rs.getString("status")), rs.getInt("penalty_stage"),
                        instant(rs, "qualification_at"), instant(rs, "activated_at"), instant(rs, "ends_at")))
                .optional();
    }

    /** The projection's standing and the Ban in force at the command instant; a User with no history starts at 0. */
    static Standing standing(CommandContext context, UUID userId) {
        Instant now = context.commandNow();
        Ban active = context.jdbc().sql(IN_FORCE).param("user", userId).param("now", utc(now)).query(UUID.class)
                .optional().flatMap(id -> find(context, userId, id)).orElse(null);
        return context.jdbc().sql(STANDING).param("user", userId)
                .query((rs, n) -> new Standing(userId, rs.getInt("consecutive_no_shows"), rs.getInt("penalty_stage"),
                        rs.getInt("current_threshold"), active))
                .optional()
                .orElseGet(() -> new Standing(userId, 0, 0, context.jdbc().sql(THRESHOLD).param("stage", 0)
                        .query(Integer.class).single(), active));
    }

    /** FR-AUD-06, FR-TRS-27: mandatory, kept in the audit row. */
    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        return reason.strip();
    }

    private <T, R> IdempotentResult<R> idempotent(Command<T> command, IdempotentRequest key,
                                                  StoredResponse<R> response, Function<T, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response, new Mapped<>(command, view)));
    }

    private static String json(Map<String, Object> payload) {
        StringBuilder out = new StringBuilder("{");
        payload.forEach((k, v) -> {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(k).append("\":");
            out.append(v instanceof Number ? v.toString() : "\"" + v + "\"");
        });
        return out.append('}').toString();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** An admin command under the Customer's lock; no Venue in scope: category A, as every admin command naming none. */
    private record Body<T>(UUID adminId, UUID correlationId, UUID userId, Function<CommandContext, T> body)
            implements Command<T> {

        Body {
            Objects.requireNonNull(adminId, "adminId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(userId, "userId");
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().users(userId).build());
        }

        @Override
        public T execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /** A read on its own transaction, locking nothing. */
    private record Read<T>(Function<CommandContext, T> body) implements Command<T> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public T execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /** {@code business}, its result mapped to the response: the same category and lock set. */
    private record Mapped<T, R>(Command<T> business, Function<T, R> view) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return business.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return business.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return view.apply(business.execute(context));
        }
    }
}
