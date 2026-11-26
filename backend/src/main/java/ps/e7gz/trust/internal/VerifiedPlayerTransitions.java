package ps.e7gz.trust.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.codes.VerifiedPlayerTransitionCause;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.trust.api.VerifiedPlayerAlerts;
import ps.e7gz.trust.api.VerifiedPlayerTournamentCascade;

/**
 * The one writer of {@code trust.verified_player_transition} (E7-72; Spec v5 sections 8.4-8.9, arch 07 section 6.2,
 * FR-ACC-12..15). Every operational change is a NEW immutable row with its own {@code transition_id} - an old row is
 * never updated - at the shared command instant ({@code trust.command_now}: activated now, never back-dated,
 * FR-ACC-13), continuing the User's chain from its latest status.
 *
 * <p>A VERIFIED -> REVOKED change (by an admin or by a trust correction, section 8.9) also, in the same command:
 * <ul>
 *   <li>runs the Tournament cascade once for its {@code transition_id} (FR-TRN-20): the
 *       {@code VP_TOURNAMENT_CASCADE:{transitionId}} {@code cascade_execution} and the row's {@code cascade_executed_at},
 *       and the creator's pending / waiting Player-hosted Tournaments handed to {@link VerifiedPlayerTournamentCascade};
 *       </li>
 *   <li>raises ADMIN_VERIFIED_PLAYER_REVOKED in the admin alert inbox ({@link VerifiedPlayerAlerts}, section 52.6).</li>
 * </ul>
 * SUSPENDED cancels nothing (section 37.8). Each change publishes {@code VerifiedPlayerTransitioned} for the customer's
 * notification. The database is the authority on every rule here ({@code guard_verified_player_transition},
 * {@code assert_verified_player_transition_qualification}, {@code assert_verified_player_tournament_cascade},
 * {@code assert_user_integrity}); this class never works around them.
 */
@Component
class VerifiedPlayerTransitions {

    static final String LATEST = """
            SELECT x.to_status, x.cause FROM trust.verified_player_transition x
             WHERE x.user_id = :user ORDER BY x.occurred_at DESC, x.id DESC LIMIT 1
            """;

    static final String INSERT = """
            INSERT INTO trust.verified_player_transition (id, user_id, transition_id, from_status, to_status,
                                                          occurred_at, cause, admin_id, cascade_executed_at)
            SELECT :id, :user, :transition, :from, :to, now.at, :cause, :admin,
                   CASE WHEN :revocation THEN now.at END
              FROM (SELECT trust.command_now(:user) AS at) now
            RETURNING occurred_at
            """;

    static final String INSERT_CASCADE = """
            INSERT INTO trust.cascade_execution (id, cascade_type, cascade_key, subject_user_id, source_id, executed_at,
                                                 correlation_id)
            VALUES (:id, 'VERIFIED_PLAYER', :key, :user, :source, :at, :correlation)
            """;

    static final String AGGREGATE = "USER";

    private final Outbox outbox;
    private final VerifiedPlayerAlerts alerts;
    private final ObjectProvider<VerifiedPlayerTournamentCascade> tournaments;

    VerifiedPlayerTransitions(Outbox outbox, VerifiedPlayerAlerts alerts,
                              ObjectProvider<VerifiedPlayerTournamentCascade> tournaments) {
        this.outbox = outbox;
        this.alerts = alerts;
        this.tournaments = tournaments;
    }

    /** The User's latest operational status and its cause; empty when the User has no transition (NOT_VERIFIED). */
    Optional<Latest> latest(CommandContext command, UUID userId) {
        return command.jdbc().sql(LATEST).param("user", userId)
                .query((rs, n) -> new Latest(VerifiedPlayerStatus.valueOf(rs.getString("to_status")),
                        VerifiedPlayerTransitionCause.valueOf(rs.getString("cause"))))
                .optional();
    }

    /** The User's current operational status: NOT_VERIFIED without any transition. */
    VerifiedPlayerStatus current(CommandContext command, UUID userId) {
        return latest(command, userId).map(Latest::status).orElse(VerifiedPlayerStatus.NOT_VERIFIED);
    }

    /**
     * Writes {@code from -> to} for the User at the command instant and returns the new {@code transition_id}.
     * {@code adminId} is set exactly when {@code cause} is ADMIN.
     */
    UUID record(CommandContext command, UUID userId, VerifiedPlayerStatus from, VerifiedPlayerStatus to,
                VerifiedPlayerTransitionCause cause, UUID adminId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(cause, "cause");
        boolean revocation = from == VerifiedPlayerStatus.VERIFIED && to == VerifiedPlayerStatus.REVOKED;
        UUID id = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        Instant at = command.jdbc().sql(INSERT)
                .param("id", id)
                .param("user", userId)
                .param("transition", transitionId)
                .param("from", from.name())
                .param("to", to.name())
                .param("cause", cause.name())
                .param("admin", adminId, java.sql.Types.OTHER)
                .param("revocation", revocation)
                .query(OffsetDateTime.class).single().toInstant();
        if (revocation) {
            cascade(command, userId, id, transitionId, at);
            alerts.revoked(command, userId, transitionId, cause);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId.toString());
        payload.put("transitionId", transitionId.toString());
        payload.put("fromStatus", from.name());
        payload.put("toStatus", to.name());
        payload.put("cause", cause.name());
        outbox.publish(command, new NewOutboxEvent(IntegrationEvents.VERIFIED_PLAYER_TRANSITIONED, AGGREGATE, userId,
                payload));
        return transitionId;
    }

    /**
     * Section 8.9, once per {@code transition_id}: the unique cascade key is this transition's own. The Tournament
     * module carries it out (E7-94); a second run for the same transition is refused by the key.
     */
    private void cascade(CommandContext command, UUID userId, UUID id, UUID transitionId, Instant at) {
        tournaments.getObject().cancelPendingHostedTournaments(command, userId, transitionId);
        command.jdbc().sql(INSERT_CASCADE)
                .param("id", UUID.randomUUID())
                .param("key", cascadeKey(transitionId))
                .param("user", userId)
                .param("source", id)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("correlation", CorrelationId.require())
                .update();
    }

    static String cascadeKey(UUID transitionId) {
        return "VP_TOURNAMENT_CASCADE:" + transitionId;
    }

    /** The latest operational transition of a User. */
    record Latest(VerifiedPlayerStatus status, VerifiedPlayerTransitionCause cause) {
    }
}
