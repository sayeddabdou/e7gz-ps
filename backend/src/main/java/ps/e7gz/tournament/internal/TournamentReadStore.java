package ps.e7gz.tournament.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * The SQL of the tournament reads (E7-126, S6-E5; SPRINT-6-CONTRACT.md section 2), on a read-only command's
 * transaction. Only this module's tables: Venue, User and Resource facts come through their modules' APIs
 * ({@link TournamentReads}). {@code capacityOccupied} is {@code tournament.capacity_occupied_count} at the command
 * instant, the function Join checks (FR-TRN-12, FR-TRN-26, ADR-023). The INELIGIBLE cause is never selected (arch 09
 * section 3.4, PCR-001).
 */
@Component
class TournamentReadStore {

    /** Not yet ended (the {@code ended=false} lists). */
    static final List<String> NOT_ENDED = Tournaments.LIVE;

    /** Ended: COMPLETED, CANCELLED and REJECTED (the {@code ended=true} lists). */
    static final List<String> ENDED = List.of(TournamentStatus.COMPLETED.name(), TournamentStatus.CANCELLED.name(),
            TournamentStatus.REJECTED.name());

    /**
     * Every column a surface shows. {@code accepted}: the Tournament has been WAITING_FOR_PLAYERS at least once - a
     * Venue-hosted one from its creation, a Player-hosted one from the Venue's accept (FR-TRN-07, FR-TRN-10).
     * {@code my_status}: the viewing Customer's own participation, if any.
     */
    private static final String SELECT = """
            SELECT t.id, t.venue_id, t.host_type, t.creator_user_id, t.creator_participates, t.status,
                   t.terminal_reason, t.activity, t.play_mode, t.visibility, t.capacity,
                   tournament.capacity_occupied_count(t.id, CAST(:now AS timestamptz)) AS occupied,
                   t.start_at, t.end_at, t.nominal_minutes, t.checkpoint_at, t.approval_expires_at, t.created_at,
                   t.business_date, t.invite_code, t.venue_cost_total, t.creation_fee_snapshot, t.entry_fee, t.prize,
                   EXISTS (SELECT 1 FROM tournament.tournament_history h
                            WHERE h.tournament_id = t.id AND h.to_status = 'WAITING_FOR_PLAYERS') AS accepted,
                   (SELECT p.status FROM tournament.tournament_participant p
                     WHERE p.tournament_id = t.id AND p.user_id = CAST(:viewer AS uuid)) AS my_status
              FROM tournament.tournament t
            """;

    private static final String ROOMS = """
            SELECT tr.tournament_id, tr.resource_id, tr.console_type_snapshot, tr.hourly_price_snapshot
              FROM tournament.tournament_resource tr
             WHERE tr.tournament_id IN (:ids) AND (CAST(:withRemoved AS boolean) OR tr.removed_at IS NULL)
             ORDER BY tr.tournament_id, tr.resource_id
            """;

    private static final String PARTICIPANTS = """
            SELECT p.user_id, p.status, p.joined_at
              FROM tournament.tournament_participant p
             WHERE p.tournament_id = :id
             ORDER BY p.joined_at, p.user_id
            """;

    /** An assignment of the Tournament, with its snapshots. */
    record RoomRow(UUID tournamentId, UUID resourceId, ConsoleType consoleType, Money hourlyPrice) {
    }

    /** A participant row without its INELIGIBLE cause. */
    record ParticipantRow(UUID userId, TournamentParticipantStatus status, Instant joinedAt) {
    }

    Optional<TournamentRow> find(CommandContext context, UUID tournamentId, UUID viewer) {
        return context.jdbc().sql(SELECT + " WHERE t.id = :id").param("id", tournamentId)
                .param("now", utc(context.commandNow())).param("viewer", viewer)
                .query(TournamentReadStore::row).optional();
    }

    /**
     * Discover candidates (FR-TRN-06, FR-TRN-07): PUBLIC, WAITING_FOR_PLAYERS or CONFIRMED, {@code now < startAt},
     * by startAt then id, strictly after {@code after}. {@code venueIds}, when not null, keeps only those Venues
     * (an Area's); whether a Venue is live is decided by the caller.
     */
    List<TournamentRow> discover(CommandContext context, UUID viewer, Activity activity, List<UUID> venueIds,
                                 TournamentCursor.Position after, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append("""
                 WHERE t.visibility = 'PUBLIC' AND t.status IN ('WAITING_FOR_PLAYERS', 'CONFIRMED')
                   AND t.start_at > CAST(:now AS timestamptz)
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (activity != null) {
            sql.append(" AND t.activity = :activity");
            params.put("activity", activity.name());
        }
        if (venueIds != null) {
            sql.append(" AND t.venue_id IN (:venueIds)");
            params.put("venueIds", venueIds);
        }
        return page(context, sql, params, viewer, after, "t.start_at", true, limit);
    }

    /** The Tournaments {@code customerId} created or has a participation in, in {@code states}. */
    List<TournamentRow> mine(CommandContext context, UUID customerId, List<String> states, boolean ascending,
                   TournamentCursor.Position after, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append("""
                 WHERE (t.creator_user_id = CAST(:viewer AS uuid)
                        OR EXISTS (SELECT 1 FROM tournament.tournament_participant p
                                    WHERE p.tournament_id = t.id AND p.user_id = CAST(:viewer AS uuid)))
                   AND t.status IN (:states)
                """);
        return page(context, sql, new LinkedHashMap<>(Map.of("states", states)), customerId, after, "t.start_at",
                ascending, limit);
    }

    /** The Venue's Tournaments in {@code states}. */
    List<TournamentRow> ofVenue(CommandContext context, UUID venueId, List<String> states, boolean ascending,
                      TournamentCursor.Position after, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE t.venue_id = :venueId AND t.status IN (:states)");
        return page(context, sql, new LinkedHashMap<>(Map.of("venueId", venueId, "states", states)), null, after,
                "t.start_at", ascending, limit);
    }

    /** Every Tournament, newest {@code createdAt} first, each filter optional. */
    List<TournamentRow> all(CommandContext context, UUID venueId, TournamentStatus status, TournamentHostType hostType,
                  TournamentCursor.Position after, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE TRUE");
        Map<String, Object> params = new LinkedHashMap<>();
        if (venueId != null) {
            sql.append(" AND t.venue_id = :venueId");
            params.put("venueId", venueId);
        }
        if (status != null) {
            sql.append(" AND t.status = :status");
            params.put("status", status.name());
        }
        if (hostType != null) {
            sql.append(" AND t.host_type = :hostType");
            params.put("hostType", hostType.name());
        }
        return page(context, sql, params, null, after, "t.created_at", false, limit);
    }

    /**
     * The assignments of {@code tournamentIds}, by Tournament then Resource id: every one with {@code withRemoved},
     * otherwise only those still in play (a Resource lost while ACTIVE is removed, FR-TRN-27).
     */
    List<RoomRow> rooms(CommandContext context, Collection<UUID> tournamentIds, boolean withRemoved) {
        if (tournamentIds.isEmpty()) {
            return List.of();
        }
        return context.jdbc().sql(ROOMS).param("ids", tournamentIds).param("withRemoved", withRemoved)
                .query((rs, n) -> new RoomRow(rs.getObject("tournament_id", UUID.class),
                        rs.getObject("resource_id", UUID.class),
                        rs.getString("console_type_snapshot") == null ? null
                                : ConsoleType.valueOf(rs.getString("console_type_snapshot")),
                        Money.of(rs.getBigDecimal("hourly_price_snapshot"))))
                .list();
    }

    /** Every participant row, oldest Join first. */
    List<ParticipantRow> participants(CommandContext context, UUID tournamentId) {
        return context.jdbc().sql(PARTICIPANTS).param("id", tournamentId)
                .query((rs, n) -> new ParticipantRow(rs.getObject("user_id", UUID.class),
                        TournamentParticipantStatus.valueOf(rs.getString("status")),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Keyset page on {@code (orderColumn, t.id)} in one direction, strictly after {@code after}. */
    private static List<TournamentRow> page(CommandContext context, StringBuilder sql, Map<String, Object> params, UUID viewer,
                                  TournamentCursor.Position after, String orderColumn, boolean ascending,
                                  int limit) {
        String direction = ascending ? "ASC" : "DESC";
        if (after != null) {
            sql.append(" AND (").append(orderColumn).append(", t.id) ").append(ascending ? ">" : "<")
                    .append(" (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid))");
            params.put("afterAt", utc(after.at()));
            params.put("afterId", after.tournamentId());
        }
        sql.append(" ORDER BY ").append(orderColumn).append(' ').append(direction).append(", t.id ").append(direction)
                .append(" LIMIT :limit");
        var statement = context.jdbc().sql(sql.toString()).param("now", utc(context.commandNow()))
                .param("viewer", viewer).param("limit", limit);
        for (Map.Entry<String, Object> param : params.entrySet()) {
            statement = statement.param(param.getKey(), param.getValue());
        }
        return statement.query(TournamentReadStore::row).list();
    }

    private static TournamentRow row(ResultSet rs, int n) throws SQLException {
        String terminal = rs.getString("terminal_reason");
        String playMode = rs.getString("play_mode");
        String myStatus = rs.getString("my_status");
        return new TournamentRow(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                TournamentHostType.valueOf(rs.getString("host_type")), rs.getObject("creator_user_id", UUID.class),
                (Boolean) rs.getObject("creator_participates"), TournamentStatus.valueOf(rs.getString("status")),
                terminal == null ? null : TournamentTerminalReason.valueOf(terminal),
                Activity.valueOf(rs.getString("activity")), playMode == null ? null : PlayMode.valueOf(playMode),
                TournamentVisibility.valueOf(rs.getString("visibility")), rs.getInt("capacity"),
                rs.getInt("occupied"), instant(rs, "start_at"), instant(rs, "end_at"), rs.getInt("nominal_minutes"),
                instant(rs, "checkpoint_at"), instant(rs, "approval_expires_at"), instant(rs, "created_at"),
                rs.getObject("business_date", LocalDate.class), rs.getString("invite_code"),
                money(rs, "venue_cost_total"), money(rs, "creation_fee_snapshot"), money(rs, "entry_fee"),
                money(rs, "prize"), rs.getBoolean("accepted"),
                myStatus == null ? null : TournamentParticipantStatus.valueOf(myStatus));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Money money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return Money.of(value);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
