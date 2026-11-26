package ps.e7gz.tournament.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
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
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentResourceRemovalCause;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.pricing.api.PriceCalculator;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.tournament.api.LiveTournament;
import ps.e7gz.tournament.api.TournamentCascades;

/**
 * The Tournament aggregate, early (E7-73, S4-E5; roadmap section 7 dependency note, arch 04 section 4.4): a Tournament
 * with its 1-4 Resource assignments and holds, its append-only history, its timers and - when the Player Creator plays -
 * the Creator's participant row. Enough for the Sprint 5 cascades to find and cancel Tournaments; creation is here so
 * the cascades can be tested against real Tournaments, and E7-91 builds the creation flow on it. No approval, join,
 * leave, checkpoint, start or completion transitions yet (E7-92, E7-93).
 *
 * <p>Explicit SQL on the caller's command transaction ({@link CommandContext#jdbc()}), inside
 * {@code tournament.begin_command} (arch 04 section 2.1). The database is the authority: its row guards refuse a bad
 * row at once, and its deferred aggregate checks ({@code tournament.assert_tournament_aggregate},
 * {@code assert_tournament_history_chain}, {@code assert_participant_audit}) refuse at {@code COMMIT} a Tournament that
 * lacks a hold, a history row, its audit evidence or a live timer. No rule is checked twice here.
 */
@Component
class Tournaments implements TournamentCascades {

    /** The four timers the aggregate requires while they are due (arch 08 section 1; T7-T9 and completion). */
    static final String APPROVAL_EXPIRY = "TOURNAMENT_APPROVAL_EXPIRY";
    static final String CHECKPOINT = "TOURNAMENT_CHECKPOINT";
    static final String START = "TOURNAMENT_START";
    static final String COMPLETION = "TOURNAMENT_COMPLETION";
    static final List<String> TIMERS = List.of(APPROVAL_EXPIRY, CHECKPOINT, START, COMPLETION);
    static final String SUBJECT_TYPE = "TOURNAMENT";

    static final List<String> LIVE = List.of(TournamentStatus.PENDING_VENUE_APPROVAL.name(),
            TournamentStatus.WAITING_FOR_PLAYERS.name(), TournamentStatus.CONFIRMED.name(),
            TournamentStatus.ACTIVE.name());

    /**
     * The derived columns ({@code status}, {@code created_at}, {@code end_at}, {@code checkpoint_at},
     * {@code approval_expires_at}) are left out: {@code tr_00_prepare_tournament_insert} sets them from the command
     * instant. The Creation Fee snapshot is the configured fee at that instant, read with the same function the guard
     * compares it with (Product section 5.3, FR-ADM-10).
     */
    static final String INSERT_TOURNAMENT = """
            INSERT INTO tournament.tournament (id, venue_id, host_type, creator_user_id, creator_participates, activity,
                                               play_mode, capacity, start_at, nominal_minutes, visibility, invite_code,
                                               venue_cost_total, creation_fee_snapshot, entry_fee, prize, business_date,
                                               operating_session_id)
            VALUES (:id, :venueId, :hostType, :creatorUserId, :creatorParticipates, :activity,
                    :playMode, :capacity, :startAt, :nominalMinutes, :visibility, :inviteCode,
                    :venueCostTotal, config.effective_numeric('tournament.creation_fee', tournament.command_now()),
                    :entryFee, :prize, :businessDate, :operatingSessionId)
            RETURNING status, created_at, end_at, checkpoint_at, approval_expires_at, creation_fee_snapshot
            """;

    static final String INSERT_RESOURCE = """
            INSERT INTO tournament.tournament_resource (id, tournament_id, resource_id, console_type_snapshot,
                                                        hourly_price_snapshot, reservation_id)
            VALUES (:id, :tournamentId, :resourceId, :consoleType, :hourlyPrice, :reservationId)
            """;

    /** {@code sequence}, {@code from_status} and {@code occurred_at} are set by {@code tr_prepare_tournament_history}. */
    static final String INSERT_HISTORY = """
            INSERT INTO tournament.tournament_history (id, tournament_id, to_status, terminal_reason, actor_type,
                                                       actor_id, correlation_id)
            VALUES (:id, :tournamentId, :toStatus, :terminalReason, :actorType, :actorId, :correlationId)
            RETURNING occurred_at
            """;

    /** {@code status}, {@code joined_at} and {@code status_changed_at} are set by {@code tr_guard_tournament_participant}. */
    static final String INSERT_PARTICIPANT = """
            INSERT INTO tournament.tournament_participant (id, tournament_id, user_id)
            VALUES (:id, :tournamentId, :userId)
            RETURNING status_changed_at
            """;

    static final String CURRENT = """
            SELECT t.id, t.venue_id, t.host_type, t.status, t.start_at, t.end_at, t.version
              FROM tournament.tournament t
             WHERE t.id = :id
            """;

    static final String CANCEL = """
            UPDATE tournament.tournament
               SET status = 'CANCELLED', terminal_reason = :reason, version = version + 1
             WHERE id = :id AND version = :version
            """;

    /** Every hold the Tournament ever had; the ledger releases the ones still active and skips the rest. */
    static final String RESERVATIONS = """
            SELECT tr.reservation_id
              FROM tournament.tournament_resource tr
             WHERE tr.tournament_id = :id
             ORDER BY tr.resource_id
            """;

    static final String LIVE_AT_VENUE = """
            SELECT t.id, t.venue_id, t.host_type, t.status, t.start_at, t.end_at
              FROM tournament.tournament t
             WHERE t.venue_id = :venueId
               AND t.status IN (:live)
               AND tstzrange(t.start_at, t.end_at, '[)') && tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)')
             ORDER BY t.start_at, t.id
            """;

    static final String LIVE_ON_RESOURCES = """
            SELECT t.id, t.venue_id, t.host_type, t.status, t.start_at, t.end_at
              FROM tournament.tournament t
             WHERE t.status IN (:live)
               AND tstzrange(t.start_at, t.end_at, '[)') && tstzrange(CAST(:from AS timestamptz), CAST(:to AS timestamptz), '[)')
               AND EXISTS (SELECT 1 FROM tournament.tournament_resource tr
                            WHERE tr.tournament_id = t.id AND tr.removed_at IS NULL AND tr.resource_id IN (:resourceIds))
             ORDER BY t.start_at, t.id
            """;

    static final String LIVE_RESOURCES = """
            SELECT tr.resource_id
              FROM tournament.tournament_resource tr
             WHERE tr.tournament_id = :id AND tr.removed_at IS NULL
             ORDER BY tr.resource_id
            """;

    private final ReservationLedger ledger;
    private final AuditWriter audit;
    private final ScheduledJobs jobs;
    private final ResourceCatalog resources;
    private final HourlyPrices prices;

    Tournaments(ReservationLedger ledger, AuditWriter audit, ScheduledJobs jobs, ResourceCatalog resources,
                HourlyPrices prices) {
        this.ledger = ledger;
        this.audit = audit;
        this.jobs = jobs;
        this.resources = resources;
        this.prices = prices;
    }

    // ------------------------------------------------------------------------------------------------ creation

    /**
     * Creates the Tournament in the caller's command, which must have locked {@link NewTournament#lockSet()}: the row,
     * one hold per Resource ({@code TOURNAMENT_APPROVAL_HOLD} for a Player-hosted request, {@code TOURNAMENT_HOLD} for a
     * Venue-hosted Tournament; arch 06 section 3.1) and its assignment with the price and console snapshots, the first
     * history row and its audit event (actor the Creator or the Venue Account), the Creator's participant row when the
     * Creator plays, and the timers due from its state. Runs with a correlation ID open.
     *
     * @throws IllegalArgumentException when a Resource does not exist
     * @throws ApiException             {@code RESOURCE_NOT_ELIGIBLE} when a Resource has no price for the activity and
     *                                  play mode (FR-VEN-45: creation is blocked where the configuration is missing);
     *                                  every other refusal is the database's
     */
    Created create(CommandContext command, NewTournament tournament) {
        return create(command, tournament, HoldGate.NONE);
    }

    /**
     * As {@link #create(CommandContext, NewTournament)}, with {@code gate} asked about each Resource after the
     * Tournament row was accepted by its insert guards and before the Resource is held (E7-91). That is the order of
     * arch 06 section 3.4: the creator, the cutoff and the one-pending rule first, then every assigned Resource
     * (FR-TRN-33), then the holds (FR-TRN-09). A gate that throws rolls the whole command back.
     */
    Created create(CommandContext command, NewTournament tournament, HoldGate gate) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(tournament, "tournament");
        Objects.requireNonNull(gate, "gate");
        UUID correlationId = CorrelationId.require();

        // FR-TRN-28, arch 03 section 7: the venue cost is snapshot now, at request creation (player) or creation
        // (venue), from the historical hourly prices: half-up per line item, summed; it is never recomputed
        List<Snapshot> snapshots = new ArrayList<>();
        Money venueCost = Money.ZERO;
        for (UUID resourceId : tournament.resourceIds()) {
            Snapshot snapshot = snapshot(command, tournament, resourceId);
            snapshots.add(snapshot);
            venueCost = venueCost.plus(PriceCalculator.linePrice(snapshot.hourlyPrice(), tournament.nominalMinutes()));
        }

        NewTournament.Host host = tournament.host();
        NewTournament.Host.Player player = host instanceof NewTournament.Host.Player p ? p : null;
        Inserted row = command.jdbc().sql(INSERT_TOURNAMENT)
                .param("id", tournament.id())
                .param("venueId", tournament.venueId())
                .param("hostType", (player != null ? TournamentHostType.PLAYER : TournamentHostType.VENUE).name())
                .param("creatorUserId", player != null ? player.creatorUserId() : null)
                .param("creatorParticipates", player != null ? player.creatorParticipates() : null)
                .param("activity", tournament.activity().name())
                .param("playMode", tournament.playMode() == null ? null : tournament.playMode().name())
                .param("capacity", tournament.capacity())
                .param("startAt", utc(tournament.startAt()))
                .param("nominalMinutes", tournament.nominalMinutes())
                .param("visibility", tournament.visibility().name())
                .param("inviteCode", tournament.inviteCode())
                .param("venueCostTotal", venueCost.amount())
                .param("entryFee", tournament.entryFeeAmount())
                .param("prize", tournament.prizeAmount())
                .param("businessDate", tournament.businessDate())
                .param("operatingSessionId", tournament.operatingSessionId())
                .query(Tournaments::inserted)
                .single();

        ReservationOwner owner = row.status() == TournamentStatus.PENDING_VENUE_APPROVAL
                ? ReservationOwner.tournamentApprovalHold(tournament.id())
                : ReservationOwner.tournamentHold(tournament.id());
        List<UUID> reservationIds = new ArrayList<>();
        List<Assignment> assignments = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            gate.beforeHold(command, snapshot.resourceId(), tournament.startAt(), row.endAt());
            UUID reservationId = ledger.create(command,
                    new NewReservation(snapshot.resourceId(), owner, tournament.startAt(), row.endAt()));
            reservationIds.add(reservationId);
            command.jdbc().sql(INSERT_RESOURCE)
                    .param("id", UUID.randomUUID())
                    .param("tournamentId", tournament.id())
                    .param("resourceId", snapshot.resourceId())
                    .param("consoleType", snapshot.consoleType())
                    .param("hourlyPrice", snapshot.hourlyPrice().amount())
                    .param("reservationId", reservationId)
                    .update();
            assignments.add(new Assignment(snapshot.resourceId(), snapshot.consoleType(), snapshot.hourlyPrice()));
        }

        AuditActor actor = player != null
                ? AuditActor.customer(player.creatorUserId())
                : AuditActor.venue(((NewTournament.Host.Venue) host).venueAccountId());
        transition(command, tournament.id(), tournament.venueId(), null, row.status(), null, actor, correlationId);

        if (player != null && player.creatorParticipates()) {
            join(command, tournament.id(), tournament.venueId(), player.creatorUserId(), correlationId);
        }

        if (row.approvalExpiresAt() != null) {
            schedule(command, APPROVAL_EXPIRY, tournament.id(), row.approvalExpiresAt());
        }
        schedule(command, CHECKPOINT, tournament.id(), row.checkpointAt());
        schedule(command, START, tournament.id(), tournament.startAt());
        schedule(command, COMPLETION, tournament.id(), row.endAt());

        return new Created(tournament.id(), row.status(), row.createdAt(), row.approvalExpiresAt(),
                row.checkpointAt(), row.endAt(), venueCost, row.creationFee(), List.copyOf(reservationIds),
                List.copyOf(assignments));
    }

    /** The price and console snapshots of one Resource, from their own modules (the E7-30 rule on reads). */
    private Snapshot snapshot(CommandContext command, NewTournament tournament, UUID resourceId) {
        ResourceCatalog.Entry entry = resources.find(command, resourceId)
                .orElseThrow(() -> new IllegalArgumentException("No Resource " + resourceId));
        // FR-VEN-45: no price, no Tournament - the same public code as an Online Booking without one
        Money hourly = prices.resolve(command, resourceId, tournament.playMode())
                .orElseThrow(() -> new ApiException(ApiErrorCode.RESOURCE_NOT_ELIGIBLE));
        String console = entry.consoleType() == null ? null : entry.consoleType().name();
        return new Snapshot(resourceId, console, hourly);
    }

    /** The participating Player Creator's own row and its Customer audit evidence, at the command instant. */
    private void join(CommandContext command, UUID tournamentId, UUID venueId, UUID userId, UUID correlationId) {
        UUID participantId = UUID.randomUUID();
        command.jdbc().sql(INSERT_PARTICIPANT)
                .param("id", participantId)
                .param("tournamentId", tournamentId)
                .param("userId", userId)
                .query(OffsetDateTime.class).single();
        audit.write(command, AuditRecord.builder(TournamentAuditAction.TOURNAMENT_PARTICIPANT_JOINED,
                        AuditActor.customer(userId), participantId, correlationId)
                .stateChange(null, "JOINED")
                .payload(Map.of("tournamentId", tournamentId))
                .contextVenue(venueId)
                .build());
    }

    private void schedule(CommandContext command, String type, UUID tournamentId, Instant dueAt) {
        jobs.schedule(command, NewJob.of(type, SUBJECT_TYPE, tournamentId, dueAt));
    }

    // ------------------------------------------------------------------------------------------------ cascades

    @Override
    public List<LiveTournament> liveAtVenue(CommandContext command, UUID venueId, Instant from, Instant to) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        requireWindow(from, to);
        return withResources(command, command.jdbc().sql(LIVE_AT_VENUE)
                .param("venueId", venueId)
                .param("live", LIVE)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Tournaments::header)
                .list());
    }

    @Override
    public List<LiveTournament> liveOnResources(CommandContext command, Collection<UUID> resourceIds, Instant from,
                                                Instant to) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceIds, "resourceIds");
        requireWindow(from, to);
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return withResources(command, command.jdbc().sql(LIVE_ON_RESOURCES)
                .param("resourceIds", List.copyOf(resourceIds))
                .param("live", LIVE)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Tournaments::header)
                .list());
    }

    @Override
    public boolean cancel(CommandContext command, UUID tournamentId, TournamentTerminalReason reason) {
        return cancel(command, tournamentId, reason, AuditActor.system());
    }

    /**
     * {@code CANCELLED} with {@code reason} by {@code actor}: the state, one history row and its audit event, every
     * still-active hold released at the command instant, and the Tournament's pending timers superseded. Whether this
     * actor may give this reason from this state, and whether a higher-priority cause wins (FR-TRN-21, FR-TRN-31), is
     * the database's answer. An actor other than SYSTEM is for E7-93's explicit cancellations (and the tests); a Venue
     * cancelling a CONFIRMED Tournament also needs the Owner Password audit, which this method does not write.
     *
     * @return {@code false}, changing nothing, when the Tournament is already terminal
     */
    boolean cancel(CommandContext command, UUID tournamentId, TournamentTerminalReason reason, AuditActor actor) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(tournamentId, "tournamentId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
        Current current = current(command, tournamentId)
                .orElseThrow(() -> new IllegalArgumentException("No Tournament " + tournamentId));
        if (!LIVE.contains(current.status().name())) {
            return false;
        }
        UUID correlationId = CorrelationId.require();
        int updated = command.jdbc().sql(CANCEL)
                .param("id", tournamentId)
                .param("reason", reason.name())
                .param("version", current.version())
                .update();
        if (updated != 1) {
            // The row is locked by begin_command, so its version cannot move under this command.
            throw new IllegalStateException("Tournament " + tournamentId + " changed under its own lock");
        }
        transition(command, tournamentId, current.venueId(), current.status(), TournamentStatus.CANCELLED, reason,
                actor, correlationId);
        for (UUID reservationId : command.jdbc().sql(RESERVATIONS).param("id", tournamentId).query(UUID.class)
                .list()) {
            ledger.release(command, reservationId, command.commandNow(), reason.name());
        }
        for (String timer : TIMERS) {
            jobs.supersede(command, timer + ":" + tournamentId);
        }
        return true;
    }

    @Override
    public boolean removeResource(CommandContext command, UUID tournamentId, UUID resourceId,
                                  TournamentResourceRemovalCause cause) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(tournamentId, "tournamentId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(cause, "cause");
        List<UUID> reservations = command.jdbc().sql("""
                        UPDATE tournament.tournament_resource
                           SET removed_at = :now, removal_cause = :cause
                         WHERE tournament_id = :tournament AND resource_id = :resource AND removed_at IS NULL
                        RETURNING reservation_id""")
                .param("now", utc(command.commandNow())).param("cause", cause.name())
                .param("tournament", tournamentId).param("resource", resourceId)
                .query(UUID.class).list();
        for (UUID reservationId : reservations) {
            ledger.release(command, reservationId, command.commandNow(), cause.name());
        }
        return !reservations.isEmpty();
    }

    /** The Tournament as it is now in this command, or empty when it does not exist. */
    Optional<Current> current(CommandContext command, UUID tournamentId) {
        return command.jdbc().sql(CURRENT).param("id", tournamentId).query(Tournaments::current).optional();
    }

    // ------------------------------------------------------------------------------------------------ shared

    /**
     * One {@code tournament_history} row and its {@code TOURNAMENT_STATUS_TRANSITION} audit event: same actor, same
     * correlation ID, same instant ({@code tournament.assert_tournament_history_chain}). The history row's instant is the
     * database's (the creation instant for the first row, the command instant after it); the audit row is written at
     * the command instant, which is the same instant in both cases.
     */
    private void transition(CommandContext command, UUID tournamentId, UUID venueId, TournamentStatus from,
                            TournamentStatus to, TournamentTerminalReason reason, AuditActor actor,
                            UUID correlationId) {
        command.jdbc().sql(INSERT_HISTORY)
                .param("id", UUID.randomUUID())
                .param("tournamentId", tournamentId)
                .param("toStatus", to.name())
                .param("terminalReason", reason == null ? null : reason.name())
                .param("actorType", actor.actorType().name())
                .param("actorId", actor.actorId())
                .param("correlationId", correlationId)
                .query(OffsetDateTime.class).single();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (reason != null) {
            payload.put("terminalReason", reason.name());
        }
        audit.write(command, AuditRecord.builder(TournamentAuditAction.TOURNAMENT_STATUS_TRANSITION, actor,
                        tournamentId, correlationId)
                .stateChange(from == null ? null : from.name(), to.name())
                .payload(payload)
                .contextVenue(venueId)
                .build());
    }

    private List<LiveTournament> withResources(CommandContext command, List<Header> headers) {
        List<LiveTournament> live = new ArrayList<>(headers.size());
        for (Header h : headers) {
            List<UUID> assigned = command.jdbc().sql(LIVE_RESOURCES).param("id", h.id()).query(UUID.class).list();
            live.add(new LiveTournament(h.id(), h.venueId(), h.hostType(), h.status(), h.startAt(), h.endAt(),
                    assigned));
        }
        return live;
    }

    private static void requireWindow(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from: [" + from + ", " + to + ")");
        }
    }

    private static Inserted inserted(ResultSet rs, int row) throws SQLException {
        OffsetDateTime approval = rs.getObject("approval_expires_at", OffsetDateTime.class);
        return new Inserted(TournamentStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "end_at"), instant(rs, "checkpoint_at"), approval == null ? null : approval.toInstant(),
                Money.of(rs.getBigDecimal("creation_fee_snapshot")));
    }

    private static Header header(ResultSet rs, int row) throws SQLException {
        return new Header(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                TournamentHostType.valueOf(rs.getString("host_type")), TournamentStatus.valueOf(rs.getString("status")),
                instant(rs, "start_at"), instant(rs, "end_at"));
    }

    private static Current current(ResultSet rs, int row) throws SQLException {
        return new Current(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                TournamentStatus.valueOf(rs.getString("status")), rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Asked about each Resource of a new Tournament after its row was inserted and before the Resource is held, inside
     * the creating command (E7-91: the canonical availability predicate, FR-TRN-33).
     */
    @FunctionalInterface
    interface HoldGate {

        /** No question asked: the database alone refuses (the early aggregate tests and the cascades' fixtures). */
        HoldGate NONE = (command, resourceId, startAt, endAt) -> { };

        /** Throws to refuse the whole creation. */
        void beforeHold(CommandContext command, UUID resourceId, Instant startAt, Instant endAt);
    }

    /**
     * What creation wrote, as the database derived it.
     *
     * @param creationFee the Tournament Creation Fee snapshot (Product section 5.3, FR-ADM-10)
     * @param assignments the Resources in request order, with their console and hourly price snapshots
     */
    record Created(UUID id, TournamentStatus status, Instant createdAt, Instant approvalExpiresAt,
                   Instant checkpointAt, Instant endAt, Money venueCostTotal, Money creationFee,
                   List<UUID> reservationIds, List<Assignment> assignments) {
    }

    /** One held Resource and its snapshots ({@code tournament.tournament_resource}); console {@code null} off PlayStation. */
    record Assignment(UUID resourceId, String consoleType, Money hourlyPrice) {
    }

    record Current(UUID id, UUID venueId, TournamentStatus status, long version) {
    }

    private record Snapshot(UUID resourceId, String consoleType, Money hourlyPrice) {
    }

    private record Inserted(TournamentStatus status, Instant createdAt, Instant endAt, Instant checkpointAt,
                            Instant approvalExpiresAt, Money creationFee) {
    }

    private record Header(UUID id, UUID venueId, TournamentHostType hostType, TournamentStatus status, Instant startAt,
                          Instant endAt) {
    }
}
