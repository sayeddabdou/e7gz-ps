package ps.e7gz.tournament.internal;

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
import ps.e7gz.common.codes.TournamentParticipantIneligibleReason;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.trust.api.ActiveBans;

/**
 * A Tournament's participant rows (E7-93, S6-E3; arch 06 section 3.7, ADR-023, FR-TRN-12, FR-TRN-13, FR-TRN-26): the
 * two counts, and every participant transition with the audit evidence the database requires for it at {@code COMMIT}
 * ({@code tournament.assert_participant_audit}): Join and Leave by the Customer, Remove by the Organizer, INELIGIBLE and
 * the restoration by SYSTEM - each at the row's {@code status_changed_at}, which is the command instant.
 *
 * <p><b>Two counts, two purposes</b> (ADR-023 item 2). {@link #activeParticipantCount} is the checkpoint's truth:
 * JOINED, account active, no Ban in force at the instant. {@link #capacityOccupiedCount} is the Join's: JOINED plus every
 * {@code INELIGIBLE/BAN} row whose account is active, while the instant is before {@code startAt}. Neither is ever used
 * for the other's purpose. Both are the database's canonical functions, read on the command's transaction.
 *
 * <p>Every method runs inside a command whose lock set names the Tournament ({@code tournament.begin_command} then locks
 * its whole participant set, its people, Venue and Resources), so no two commands change the same participant set at
 * once: a Join cannot overfill capacity, and a restored BAN-only row cannot evict a later Participant. The guard
 * {@code tournament.guard_tournament_participant} decides every row rule and derives the timestamps and the
 * INELIGIBLE cause; nothing here repeats it.
 */
@Component
class TournamentParticipants {

    static final String ACTIVE_COUNT = """
            SELECT tournament.active_participant_count(:id, CAST(:at AS timestamptz))
            """;

    static final String CAPACITY_OCCUPIED = """
            SELECT tournament.capacity_occupied_count(:id, CAST(:at AS timestamptz))
            """;

    static final String ROW = """
            SELECT p.id, p.user_id, p.status, p.ineligible_reason
              FROM tournament.tournament_participant p
             WHERE p.tournament_id = :tournamentId AND p.user_id = :userId
            """;

    static final String BAN_ONLY = """
            SELECT p.id, p.user_id, p.status, p.ineligible_reason
              FROM tournament.tournament_participant p
             WHERE p.tournament_id = :tournamentId AND p.status = 'INELIGIBLE' AND p.ineligible_reason = 'BAN'
             ORDER BY p.user_id
            """;

    /** The User's rows in every non-terminal Tournament, in Tournament id order (the lock order). */
    static final String LIVE_ROWS_OF_USER = """
            SELECT p.tournament_id
              FROM tournament.tournament_participant p
              JOIN tournament.tournament t ON t.id = p.tournament_id
             WHERE p.user_id = :userId AND t.status IN (:live)
             ORDER BY p.tournament_id
            """;

    /** {@code status}, {@code joined_at} and {@code status_changed_at} are the guard's. */
    static final String INSERT = """
            INSERT INTO tournament.tournament_participant (id, tournament_id, user_id)
            VALUES (:id, :tournamentId, :userId)
            """;

    /**
     * The guard stamps {@code status_changed_at}, {@code left_at} / {@code removed_at} and, for INELIGIBLE, the cause
     * (ACCOUNT_DELETED before BAN); every other status carries none ({@code ck_tournament_participant_cause_shape}).
     */
    static final String SET_STATUS = """
            UPDATE tournament.tournament_participant SET status = :status, ineligible_reason = NULL
             WHERE id = :id
            RETURNING ineligible_reason
            """;

    /** The guard keeps every other column and refuses this unless the account is anonymised. */
    static final String RECLASSIFY_DELETED = """
            UPDATE tournament.tournament_participant SET ineligible_reason = 'ACCOUNT_DELETED'
             WHERE id = :id AND status = 'INELIGIBLE' AND ineligible_reason = 'BAN'
            RETURNING ineligible_reason
            """;

    private final AuditWriter audit;
    private final ActiveBans bans;

    TournamentParticipants(AuditWriter audit, ActiveBans bans) {
        this.audit = audit;
        this.bans = bans;
    }

    // ------------------------------------------------------------------------------------------------ the counts

    /**
     * ADR-023, the checkpoint's count: JOINED Participants whose account is active and who have no Ban in force at
     * {@code at} ({@code tournament.active_participant_count}). BAN-only reserved slots never count here.
     */
    int activeParticipantCount(CommandContext command, UUID tournamentId, Instant at) {
        return count(command, ACTIVE_COUNT, tournamentId, at);
    }

    /**
     * ADR-023, the Join's count: JOINED plus {@code INELIGIBLE/BAN} with an active account while {@code at < startAt}
     * and the Tournament is pre-start ({@code tournament.capacity_occupied_count}). ACCOUNT_DELETED never occupies.
     */
    int capacityOccupiedCount(CommandContext command, UUID tournamentId, Instant at) {
        return count(command, CAPACITY_OCCUPIED, tournamentId, at);
    }

    private static int count(CommandContext command, String sql, UUID tournamentId, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(tournamentId, "tournamentId");
        Objects.requireNonNull(at, "at");
        return command.jdbc().sql(sql).param("id", tournamentId).param("at", utc(at)).query(Integer.class).single();
    }

    // ------------------------------------------------------------------------------------------------ rows

    Optional<Row> row(CommandContext command, UUID tournamentId, UUID userId) {
        return command.jdbc().sql(ROW).param("tournamentId", tournamentId).param("userId", userId)
                .query((rs, n) -> row(rs)).optional();
    }

    /** The non-terminal Tournaments the User has a row in, in lock order. */
    List<UUID> liveTournamentsOf(CommandContext command, UUID userId) {
        return command.jdbc().sql(LIVE_ROWS_OF_USER).param("userId", userId).param("live", Tournaments.LIVE)
                .query(UUID.class).list();
    }

    /** A first Join: a new JOINED row (the guard checks the state, the window, the User and the capacity). */
    void insertJoined(CommandContext command, Tournaments.Current tournament, UUID userId) {
        UUID participantId = UUID.randomUUID();
        command.jdbc().sql(INSERT).param("id", participantId).param("tournamentId", tournament.id())
                .param("userId", userId).update();
        write(command, TournamentAuditAction.TOURNAMENT_PARTICIPANT_JOINED, AuditActor.customer(userId),
                participantId, tournament, null, TournamentParticipantStatus.JOINED, Map.of());
    }

    /** A return after Leave: the same row JOINED again (the guard requires a free slot for it). */
    void rejoin(CommandContext command, Tournaments.Current tournament, Row row) {
        set(command, row, TournamentParticipantStatus.JOINED);
        write(command, TournamentAuditAction.TOURNAMENT_PARTICIPANT_JOINED, AuditActor.customer(row.userId()),
                row.id(), tournament, row.status(), TournamentParticipantStatus.JOINED, Map.of());
    }

    void leave(CommandContext command, Tournaments.Current tournament, Row row) {
        set(command, row, TournamentParticipantStatus.LEFT);
        write(command, TournamentAuditAction.TOURNAMENT_PARTICIPANT_LEFT, AuditActor.customer(row.userId()),
                row.id(), tournament, row.status(), TournamentParticipantStatus.LEFT, Map.of());
    }

    /** The Organizer's removal; for a Player-hosted Tournament the actor is its Creator. */
    void remove(CommandContext command, Tournaments.Current tournament, Row row, AuditActor organizer) {
        set(command, row, TournamentParticipantStatus.REMOVED);
        write(command, TournamentAuditAction.TOURNAMENT_PARTICIPANT_REMOVED, organizer, row.id(), tournament,
                row.status(), TournamentParticipantStatus.REMOVED, Map.of());
    }

    /** A Ban came into force: INELIGIBLE, the cause the guard's (BAN, or ACCOUNT_DELETED when that is true too). */
    void markIneligible(CommandContext command, Tournaments.Current tournament, Row row) {
        String cause = set(command, row, TournamentParticipantStatus.INELIGIBLE);
        write(command, TournamentAuditAction.TOURNAMENT_PARTICIPANT_INELIGIBLE, AuditActor.system(), row.id(),
                tournament, row.status(), TournamentParticipantStatus.INELIGIBLE, Map.of("ineligibleReason", cause));
    }

    /**
     * E7-94, O19 over O14 (ADR-023): {@code INELIGIBLE/BAN -> INELIGIBLE/ACCOUNT_DELETED} once the account is
     * anonymised - the cause alone, the status and its timestamp unchanged, the BAN-only reserved slot released. The
     * only cause change the guard allows without a status transition; audited by SYSTEM with the new cause.
     */
    void reclassifyDeleted(CommandContext command, Tournaments.Current tournament, Row row) {
        String cause = command.jdbc().sql(RECLASSIFY_DELETED).param("id", row.id()).query(String.class).single();
        write(command, TournamentAuditAction.TOURNAMENT_PARTICIPANT_INELIGIBLE, AuditActor.system(), row.id(),
                tournament, row.status(), TournamentParticipantStatus.INELIGIBLE, Map.of("ineligibleReason", cause));
    }

    /**
     * {@code INELIGIBLE/BAN -> JOINED} by SYSTEM, taking back the slot the row kept reserved (the guard does not ask
     * for a free one). {@code asOf} is the instant the restoration is effective: now, or the checkpoint or start a
     * late worker resolves.
     */
    void restore(CommandContext command, Tournaments.Current tournament, Row row, Instant asOf) {
        set(command, row, TournamentParticipantStatus.JOINED);
        write(command, TournamentAuditAction.TOURNAMENT_PARTICIPANT_RESTORED, AuditActor.system(), row.id(),
                tournament, row.status(), TournamentParticipantStatus.JOINED, Map.of("asOf", asOf.toString()));
    }

    /**
     * ADR-023 item 5, before a boundary is resolved: every BAN-only row whose Ban was no longer in force at the
     * boundary is restored first, so it counts at the checkpoint even when the worker runs late. At {@code startAt} a
     * Ban ending at that very instant still blocks the restoration (Product section 37.11). The same rule as
     * {@code tournament.guard_tournament_due_boundary}, from Trust's own history ({@link ActiveBans}).
     *
     * @param atStart {@code true} for the start boundary, {@code false} for the checkpoint
     * @return how many rows were restored
     */
    int restoreAsOf(CommandContext command, Tournaments.Current tournament, Instant boundary, boolean atStart) {
        int restored = 0;
        for (Row row : command.jdbc().sql(BAN_ONLY).param("tournamentId", tournament.id())
                .query((rs, n) -> row(rs)).list()) {
            boolean stillBanned = atStart
                    ? bans.effectiveThrough(command, row.userId(), boundary)
                    : bans.effectiveAt(command, row.userId(), boundary);
            if (!stillBanned) {
                restore(command, tournament, row, boundary);
                restored++;
            }
        }
        return restored;
    }

    private static String set(CommandContext command, Row row, TournamentParticipantStatus to) {
        return command.jdbc().sql(SET_STATUS).param("id", row.id()).param("status", to.name())
                .query(String.class).list().stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private void write(CommandContext command, TournamentAuditAction action, AuditActor actor, UUID participantId,
                       Tournaments.Current tournament, TournamentParticipantStatus from, TournamentParticipantStatus to,
                       Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tournamentId", tournament.id());
        payload.putAll(extra);
        audit.write(command, AuditRecord.builder(action, actor, participantId, CorrelationId.require())
                .stateChange(from == null ? null : from.name(), to.name())
                .payload(payload)
                .contextVenue(tournament.venueId())
                .build());
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String cause = rs.getString("ineligible_reason");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                TournamentParticipantStatus.valueOf(rs.getString("status")),
                cause == null ? null : TournamentParticipantIneligibleReason.valueOf(cause));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * One participant row as this command reads it.
     *
     * @param ineligibleReason {@code null} unless INELIGIBLE
     */
    record Row(UUID id, UUID userId, TournamentParticipantStatus status,
               TournamentParticipantIneligibleReason ineligibleReason) {

        boolean banOnly() {
            return status == TournamentParticipantStatus.INELIGIBLE
                    && ineligibleReason == TournamentParticipantIneligibleReason.BAN;
        }
    }
}
