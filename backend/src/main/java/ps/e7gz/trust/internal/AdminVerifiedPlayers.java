package ps.e7gz.trust.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.codes.VerifiedPlayerTransitionCause;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.trust.api.TrustCustomers;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * The Platform Admin's Verified Players page (Spec v5 section 51.5, FR-ADM-05, arch 07 section 6.2; Sprint 4 contract
 * E7-65, PO decisions D5 and D6 of 17 Oct 2026): the list, and Suspend / Revoke / Restore.
 *
 * <p>Each action is an Admin command ({@code admin.begin_command}, which refuses an unknown or inactive Admin) under the
 * User's lock, once per Idempotency-Key, with a mandatory reason. In order, at the command instant: the USER audit row
 * with the reason, the Admin's Trust input event ({@code VERIFIED_PLAYER_*} with {@code adminId} and
 * {@code correlationId}), the ADMIN transition ({@link VerifiedPlayerTransitions}: a new {@code transition_id}, the
 * Tournament cascade and the admin alert for a revocation, the customer's notification event), then E7-70's
 * reconciliation, which refreshes the projection onto the new status and finds no automatic work - admin states are
 * protected (FR-ACC-15). The database checks the whole set at commit.
 *
 * <p>Suspend and Revoke only from VERIFIED; Restore only from SUSPENDED or REVOKED, to VERIFIED if the User is QUALIFIED
 * now, else NOT_VERIFIED (section 8.8). Any other current status is {@code ADMIN_ACTION_NOT_ALLOWED} (D5). An unknown
 * Customer is {@code NOT_FOUND}; an anonymised one {@code ACCOUNT_DELETED}.
 */
@Component
public class AdminVerifiedPlayers {

    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Every Customer with a Verified Player transition - exactly those whose status is not NOT_VERIFIED or ever was
     * (a QUALIFIED User always has one, {@code assert_projection_operational_status}) - with the latest one's status,
     * cause and instant. Newest status change first, then by user id (keyset paging).
     */
    static final String PAGE = """
            WITH latest AS (
                SELECT DISTINCT ON (x.user_id) x.user_id, x.to_status, x.cause, x.occurred_at
                  FROM trust.verified_player_transition x
                 ORDER BY x.user_id, x.occurred_at DESC, x.id DESC)
            SELECT l.user_id, l.to_status, l.cause, l.occurred_at,
                   COALESCE(p.vp_qualification, 'NOT_QUALIFIED') AS qualification,
                   COALESCE(p.completed_attended_count, 0) AS completed_bookings
              FROM latest l LEFT JOIN trust.trust_projection p ON p.user_id = l.user_id
             WHERE (CAST(:status AS text) IS NULL OR l.to_status = CAST(:status AS text))
               AND (CAST(:afterAt AS timestamptz) IS NULL
                    OR (l.occurred_at, l.user_id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
             ORDER BY l.occurred_at DESC, l.user_id DESC
             LIMIT :limit
            """;

    /** One User's Verified Player, inside a command (after its writes) or not. */
    static final String ONE = """
            SELECT CAST(:user AS uuid) AS user_id, l.to_status, l.cause, l.occurred_at,
                   COALESCE(p.vp_qualification, 'NOT_QUALIFIED') AS qualification,
                   COALESCE(p.completed_attended_count, 0) AS completed_bookings
              FROM (SELECT 1) one
              LEFT JOIN trust.trust_projection p ON p.user_id = :user
              LEFT JOIN LATERAL (SELECT x.to_status, x.cause, x.occurred_at FROM trust.verified_player_transition x
                                  WHERE x.user_id = :user ORDER BY x.occurred_at DESC, x.id DESC LIMIT 1) l ON true
            """;

    static final String INSERT_ADMIN_EVENT = """
            INSERT INTO trust.trust_event (id, event_type, subject_user_id, occurred_at, effective_at, payload)
            SELECT :id, :type, :user, now.at, now.at,
                   jsonb_build_object('adminId', CAST(:admin AS text), 'correlationId', CAST(:correlation AS text))
              FROM (SELECT trust.command_now(:user) AS at) now
            """;

    static final String QUALIFICATION = "SELECT vp_qualification FROM trust.replay_state(:user)";

    private final JdbcClient jdbc;
    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VerifiedPlayerTransitions transitions;
    private final TrustReconciler reconciler;
    private final AuditWriter audit;
    private final TrustCustomers customers;

    AdminVerifiedPlayers(DataSource appDataSource, CommandRunner runner, Idempotency idempotency,
                         VerifiedPlayerTransitions transitions, TrustReconciler reconciler, AuditWriter audit,
                         TrustCustomers customers) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.runner = runner;
        this.idempotency = idempotency;
        this.transitions = transitions;
        this.reconciler = reconciler;
        this.audit = audit;
        this.customers = customers;
    }

    /** The list, filtered exactly by {@code status} when given. A malformed cursor is VALIDATION_FAILED on cursor. */
    public Page list(VerifiedPlayerStatus status, String cursor, Integer limit) {
        Position after = decode(cursor);
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<Row> rows = jdbc.sql(PAGE)
                .param("status", status == null ? null : status.name())
                .param("afterAt", after == null ? null : OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                .param("afterId", after == null ? null : after.userId())
                .param("limit", size + 1)
                .query((rs, n) -> row(rs))
                .list();
        boolean more = rows.size() > size;
        List<Row> page = more ? rows.subList(0, size) : rows;
        Map<UUID, TrustCustomers.Customer> named = customers.of(page.stream().map(Row::userId).toList());
        List<Player> players = page.stream().map(row -> row.named(named.get(row.userId()))).toList();
        return new Page(players, more ? encode(new Position(page.getLast().statusSince(), page.getLast().userId()))
                : null);
    }

    /** {@code POST .../suspend}: VERIFIED -> SUSPENDED (cancels nothing, section 37.8). */
    public <R> IdempotentResult<R> suspend(UUID adminId, UUID userId, String reason, IdempotentRequest key,
                                           StoredResponse<R> response, Function<Player, R> view) {
        return moderate(adminId, userId, reason, key, response, view, VerifiedPlayerAuditAction.VERIFIED_PLAYER_SUSPENDED);
    }

    /** {@code POST .../revoke}: VERIFIED -> REVOKED by the Admin, with the Tournament cascade (section 8.9). */
    public <R> IdempotentResult<R> revoke(UUID adminId, UUID userId, String reason, IdempotentRequest key,
                                          StoredResponse<R> response, Function<Player, R> view) {
        return moderate(adminId, userId, reason, key, response, view, VerifiedPlayerAuditAction.VERIFIED_PLAYER_REVOKED);
    }

    /** {@code POST .../restore}: SUSPENDED or REVOKED -> VERIFIED if QUALIFIED now, else NOT_VERIFIED. */
    public <R> IdempotentResult<R> restore(UUID adminId, UUID userId, String reason, IdempotentRequest key,
                                           StoredResponse<R> response, Function<Player, R> view) {
        return moderate(adminId, userId, reason, key, response, view, VerifiedPlayerAuditAction.VERIFIED_PLAYER_RESTORED);
    }

    /** The action without an Idempotency-Key record: package-private, for this package's tests. */
    Player moderate(UUID adminId, UUID userId, String reason, VerifiedPlayerAuditAction action) {
        TrustCustomers.Customer customer = customer(userId);
        return runner.run(command(adminId, customer, reason(reason), action));
    }

    private <R> IdempotentResult<R> moderate(UUID adminId, UUID userId, String reason, IdempotentRequest key,
                                             StoredResponse<R> response, Function<Player, R> view,
                                             VerifiedPlayerAuditAction action) {
        Objects.requireNonNull(view, "view");
        String why = reason(reason);
        TrustCustomers.Customer customer = customer(userId);
        Command<Player> business = command(adminId, customer, why, action);
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Body<>(business.scope(), context -> view.apply(business.execute(context)))));
    }

    private Command<Player> command(UUID adminId, TrustCustomers.Customer customer, String reason,
                                    VerifiedPlayerAuditAction action) {
        Objects.requireNonNull(adminId, "adminId");
        UUID userId = customer.userId();
        CommandScope scope = CommandScope.admin(adminId, CorrelationId.require(), LockSet.builder().users(userId).build());
        return new Body<>(scope, context -> {
            VerifiedPlayerStatus from = transitions.current(context, userId);              // under the User's lock
            VerifiedPlayerStatus to = switch (action) {
                case VERIFIED_PLAYER_SUSPENDED -> from == VerifiedPlayerStatus.VERIFIED
                        ? VerifiedPlayerStatus.SUSPENDED : null;
                case VERIFIED_PLAYER_REVOKED -> from == VerifiedPlayerStatus.VERIFIED
                        ? VerifiedPlayerStatus.REVOKED : null;
                case VERIFIED_PLAYER_RESTORED -> from == VerifiedPlayerStatus.SUSPENDED
                        || from == VerifiedPlayerStatus.REVOKED ? restoredTo(context, userId) : null;
            };
            if (to == null) {
                throw new ApiException(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);              // D5
            }
            UUID correlation = CorrelationId.require();
            audit.write(context, AuditRecord.builder(action, AuditActor.admin(adminId), userId, correlation)
                    .reason(reason)
                    .stateChange(from.name(), to.name())
                    .build());
            context.jdbc().sql(INSERT_ADMIN_EVENT)
                    .param("id", UUID.randomUUID())
                    .param("type", action.name())
                    .param("user", userId)
                    .param("admin", adminId.toString())
                    .param("correlation", correlation.toString())
                    .update();
            transitions.record(context, userId, from, to, VerifiedPlayerTransitionCause.ADMIN, adminId);
            reconciler.reconcile(context, userId);                                       // arch 07 section 4
            return one(context.jdbc(), userId).named(customer);
        });
    }

    /** Section 8.8: VERIFIED if currently qualified (corrected trust history), otherwise NOT_VERIFIED. */
    private static VerifiedPlayerStatus restoredTo(CommandContext context, UUID userId) {
        boolean qualified = VerifiedPlayerQualification.QUALIFIED.name().equals(context.jdbc()
                .sql(QUALIFICATION).param("user", userId).query(String.class).single());
        return qualified ? VerifiedPlayerStatus.VERIFIED : VerifiedPlayerStatus.NOT_VERIFIED;
    }

    private TrustCustomers.Customer customer(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        TrustCustomers.Customer customer = customers.of(List.of(userId)).get(userId);
        if (customer == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
        if (customer.deleted()) {
            throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);
        }
        return customer;
    }

    /** Section 8.8: mandatory for every moderation action (D6; the route also validates it). */
    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        return reason.strip();
    }

    private static Row one(JdbcClient jdbc, UUID userId) {
        return jdbc.sql(ONE).param("user", userId).query((rs, n) -> row(rs)).single();
    }

    private static Row row(ResultSet rs) throws SQLException {
        String status = rs.getString("to_status");
        String cause = rs.getString("cause");
        OffsetDateTime since = rs.getObject("occurred_at", OffsetDateTime.class);
        return new Row(rs.getObject("user_id", UUID.class),
                VerifiedPlayerQualification.valueOf(rs.getString("qualification")),
                rs.getInt("completed_bookings"),
                status == null ? VerifiedPlayerStatus.NOT_VERIFIED : VerifiedPlayerStatus.valueOf(status),
                cause == null ? null : VerifiedPlayerTransitionCause.valueOf(cause),
                since == null ? null : since.toInstant());
    }

    /** One User's Verified Player as the Admin sees it; cause and since are null before any transition. */
    public record Player(UUID userId, String fullName, String username, VerifiedPlayerQualification qualification,
                         int completedBookings, VerifiedPlayerStatus status, VerifiedPlayerTransitionCause statusCause,
                         Instant statusSince) {
    }

    public record Page(List<Player> items, String nextCursor) {
    }

    private record Row(UUID userId, VerifiedPlayerQualification qualification, int completedBookings,
                       VerifiedPlayerStatus status, VerifiedPlayerTransitionCause cause, Instant statusSince) {

        Player named(TrustCustomers.Customer customer) {
            return new Player(userId, customer == null ? "" : customer.fullName(),
                    customer == null ? "" : customer.username(), qualification, completedBookings, status, cause,
                    statusSince);
        }
    }

    // ----------------------------------------------------------------------------------------------------- cursor

    /** One row's place in the list's {@code (statusSince DESC, userId DESC)} order. */
    record Position(Instant at, UUID userId) {
    }

    static String encode(Position position) {
        String plain = position.at() + "|" + position.userId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = plain.indexOf('|');
            if (bar < 0) {
                throw new IllegalArgumentException("no separator");
            }
            return new Position(Instant.parse(plain.substring(0, bar)), UUID.fromString(plain.substring(bar + 1)));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }

    /** An admin Verified Player command: category B, an existing commitment's operation (ADR-022). */
    private record Body<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
