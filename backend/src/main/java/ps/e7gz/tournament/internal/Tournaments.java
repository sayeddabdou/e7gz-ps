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
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentResourceRemovalCause;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.TournamentVisibility;
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

/**
 * The Tournament aggregate, early (E7-73, S4-E5; roadmap section 7 dependency note, arch 04 section 4.4): a Tournament
 * with its 1-4 Resource assignments and holds, its append-only history, its timers and - when the Player Creator plays -
 * the Creator's participant row. Enough for the Sprint 5 cascades to find and cancel Tournaments; creation is here so
 * the cascades can be tested against real Tournaments, and E7-91 builds the creation flow on it. E7-92 adds the
 * Venue's answer to a Player-hosted request - approve (its Approval Holds become Tournament Holds) and reject - and the
 * approval expiry (T7). E7-93 adds the lifecycle - the checkpoint (T8), the start and the completion (T9) - and
 * cancellations with an Owner Password check or a reason on their audit row; the participant rows are
 * {@link TournamentParticipants}'. The published system cascades ({@link ps.e7gz.tournament.api.TournamentCascades})
 * are {@link TournamentSystemCascades}', built on this class (E7-94).
 *
 * <p>Explicit SQL on the caller's command transaction ({@link CommandContext#jdbc()}), inside
 * {@code tournament.begin_command} (arch 04 section 2.1). The database is the authority: its row guards refuse a bad
 * row at once, and its deferred aggregate checks ({@code tournament.assert_tournament_aggregate},
 * {@code assert_tournament_history_chain}, {@code assert_participant_audit}) refuse at {@code COMMIT} a Tournament that
 * lacks a hold, a history row, its audit evidence or a live timer. No rule is checked twice here.
 */
@Component
class Tournaments {

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
            RETURNING sequence
            """;

    /** {@code status}, {@code joined_at} and {@code status_changed_at} are set by {@code tr_guard_tournament_participant}. */
    static final String INSERT_PARTICIPANT = """
            INSERT INTO tournament.tournament_participant (id, tournament_id, user_id)
            VALUES (:id, :tournamentId, :userId)
            RETURNING status_changed_at
            """;

    static final String CURRENT = """
            SELECT t.id, t.venue_id, t.host_type, t.status, t.terminal_reason, t.creator_user_id, t.start_at, t.end_at,
                   t.approval_expires_at, t.version, t.checkpoint_at, t.capacity, t.visibility, t.creator_participates
              FROM tournament.tournament t
             WHERE t.id = :id
            """;

    /** PENDING_VENUE_APPROVAL -> WAITING_FOR_PLAYERS (FR-TRN-10); the guard owns the deadline and the revalidation. */
    static final String APPROVE = """
            UPDATE tournament.tournament
               SET status = 'WAITING_FOR_PLAYERS', version = version + 1
             WHERE id = :id AND version = :version
            """;

    /** PENDING_VENUE_APPROVAL -> REJECTED / VENUE_REJECTED_TOURNAMENT ({@code ck_tournament_rejected_reason}). */
    static final String REJECT = """
            UPDATE tournament.tournament
               SET status = 'REJECTED', terminal_reason = 'VENUE_REJECTED_TOURNAMENT', version = version + 1
             WHERE id = :id AND version = :version
            """;

    /** Every assignment with the hold it points at now (an Approval Hold while the request is pending). */
    static final String ASSIGNMENTS = """
            SELECT tr.id, tr.resource_id, tr.reservation_id
              FROM tournament.tournament_resource tr
             WHERE tr.tournament_id = :id
             ORDER BY tr.resource_id
            """;

    /** Points an assignment at its new Tournament Hold, once ({@code tournament.guard_tournament_resource}). */
    static final String REPOINT = """
            UPDATE tournament.tournament_resource SET reservation_id = :reservationId WHERE id = :id
            """;

    /** One forward step of the lifecycle (T8, T9, completion); the guards own the instants and the revalidation. */
    static final String ADVANCE = """
            UPDATE tournament.tournament
               SET status = :status, version = version + 1
             WHERE id = :id AND version = :version
            """;

    static final String CONDITION_IN_FORCE = """
            SELECT tournament.highest_current_cancellation_reason(:id, :from, CAST(:at AS timestamptz))
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

    List<LiveTournament> liveAtVenue(CommandContext command, UUID venueId, Instant from, Instant to) {
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

    List<LiveTournament> liveOnResources(CommandContext command, Collection<UUID> resourceIds, Instant from,
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

    /** By SYSTEM, released at the command instant; writes no event (see {@link TournamentSystemCascades#cancel}). */
    boolean cancel(CommandContext command, UUID tournamentId, TournamentTerminalReason reason) {
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
        return cancelled(command, tournamentId, reason, actor, command.commandNow()).isPresent();
    }

    /**
     * As {@link #cancel(CommandContext, UUID, TournamentTerminalReason, AuditActor)}, with the holds released at
     * {@code releaseAt} - the approval deadline for T7 (E7-92: the boundary, never the worker's time, arch 08 section
     * 2.2) - and the new history row's sequence returned for the creator's notification.
     *
     * @return the sequence of the {@code CANCELLED} history row; empty, changing nothing, when already terminal
     */
    Optional<Integer> cancelled(CommandContext command, UUID tournamentId, TournamentTerminalReason reason,
                                AuditActor actor, Instant releaseAt) {
        return cancelled(command, tournamentId, reason, actor, releaseAt, AuditDetail.NONE);
    }

    /**
     * As {@link #cancelled(CommandContext, UUID, TournamentTerminalReason, AuditActor, Instant)}, with the Owner Password
     * check and the actor's reason on the transition's audit row (E7-93: a Venue cancelling a CONFIRMED Tournament, an
     * Operational Termination, a Platform Admin's cancellation).
     */
    Optional<Integer> cancelled(CommandContext command, UUID tournamentId, TournamentTerminalReason reason,
                                AuditActor actor, Instant releaseAt, AuditDetail detail) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(tournamentId, "tournamentId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(releaseAt, "releaseAt");
        Current current = current(command, tournamentId)
                .orElseThrow(() -> new IllegalArgumentException("No Tournament " + tournamentId));
        if (!LIVE.contains(current.status().name())) {
            return Optional.empty();
        }
        UUID correlationId = CorrelationId.require();
        requireOne(command.jdbc().sql(CANCEL)
                .param("id", tournamentId)
                .param("reason", reason.name())
                .param("version", current.version())
                .update(), tournamentId);
        int sequence = transition(command, tournamentId, current.venueId(), current.status(),
                TournamentStatus.CANCELLED, reason, actor, correlationId, detail);
        releaseHolds(command, tournamentId, releaseAt, reason.name());
        supersedeTimers(command, tournamentId, TIMERS);
        return Optional.of(sequence);
    }

    // ------------------------------------------------------------------------------------------------ the lifecycle

    /**
     * T8, the checkpoint decided for the minimum (FR-TRN-13, FR-TRN-15; E7-93): {@code WAITING_FOR_PLAYERS -> CONFIRMED}
     * by SYSTEM, its timer withdrawn (or, leased, finished by its worker). The guard confirms only at or after
     * {@code checkpointAt}, with at least 8 Active Participants as of {@code checkpointAt}, and after revalidating every
     * Resource and its hold ({@code tournament.assert_resources_operational}).
     */
    int confirm(CommandContext command, Current waiting) {
        return advance(command, waiting, TournamentStatus.WAITING_FOR_PLAYERS, TournamentStatus.CONFIRMED, CHECKPOINT);
    }

    /** T9, start (FR-TRN-17): {@code CONFIRMED -> ACTIVE} by SYSTEM at or after {@code startAt}, Resources revalidated. */
    int start(CommandContext command, Current confirmed) {
        return advance(command, confirmed, TournamentStatus.CONFIRMED, TournamentStatus.ACTIVE, START);
    }

    /**
     * Completion (FR-TRN-30): {@code ACTIVE -> COMPLETED} by SYSTEM at or after {@code endAt}, every hold still active
     * released at {@code endAt} - the boundary, not the worker's time - and the completion timer withdrawn. The stored
     * venue cost becomes Dashboard revenue on its {@code businessDate} from this state alone: nothing is recomputed.
     */
    int complete(CommandContext command, Current active) {
        int sequence = advance(command, active, TournamentStatus.ACTIVE, TournamentStatus.COMPLETED, COMPLETION);
        releaseHolds(command, active.id(), active.endAt(), TournamentStatus.COMPLETED.name());
        return sequence;
    }

    private int advance(CommandContext command, Current current, TournamentStatus from, TournamentStatus to,
                        String timer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(current, "current");
        if (current.status() != from) {
            throw new IllegalStateException("Tournament " + current.id() + " is " + current.status() + ", not " + from);
        }
        UUID correlationId = CorrelationId.require();
        requireOne(command.jdbc().sql(ADVANCE)
                .param("id", current.id())
                .param("status", to.name())
                .param("version", current.version())
                .update(), current.id());
        int sequence = transition(command, current.id(), current.venueId(), from, to, null, AuditActor.system(),
                correlationId);
        supersedeTimers(command, current.id(), List.of(timer));
        return sequence;
    }

    /**
     * The one reason a cancellation from {@code from} must carry at {@code at} by the frozen priority (FR-TRN-31, arch
     * 06 section 3.2) - an Owner Review, an Emergency, a disabled Venue Account while pending, fewer than 8 Active
     * Participants at or after the checkpoint, a revoked Creator - or empty when no condition is in force. The
     * database's own resolver, evaluated as of the boundary when {@code at} is past it
     * ({@code tournament.highest_current_cancellation_reason}); its guards accept no other reason.
     */
    Optional<TournamentTerminalReason> conditionInForce(CommandContext command, UUID tournamentId,
                                                        TournamentStatus from, Instant at) {
        return command.jdbc().sql(CONDITION_IN_FORCE)
                .param("id", tournamentId)
                .param("from", from.name())
                .param("at", utc(at))
                .query(String.class).list().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .map(TournamentTerminalReason::valueOf);
    }

    /** Every hold the Tournament ever had; the ledger releases the ones still active and skips the rest. */
    private void releaseHolds(CommandContext command, UUID tournamentId, Instant releaseAt, String releaseReason) {
        for (UUID reservationId : command.jdbc().sql(RESERVATIONS).param("id", tournamentId).query(UUID.class)
                .list()) {
            ledger.release(command, reservationId, releaseAt, releaseReason);
        }
    }

    // ------------------------------------------------------------------------------------------------ the answer

    /**
     * The Venue approves a Player-hosted request (FR-TRN-10, FR-TRN-09; arch 05 section 3.2, E7-92):
     * {@code PENDING_VENUE_APPROVAL -> WAITING_FOR_PLAYERS} by the Venue Account, and in the same transaction every
     * Approval Hold is released and a {@code TOURNAMENT_HOLD} on the same Resource and interval takes its place - so
     * there is no moment, committed or not, in which a room of the Tournament is unheld for another booking. The
     * approval expiry (T7) is withdrawn; the checkpoint, start and completion timers stay.
     *
     * <p>The database decides: the guard refuses at or after {@code approvalExpiresAt}, without an ENABLED Venue
     * Account, or when a Resource or hold is no longer operationally eligible (arch 05 section 4 row 7,
     * {@code tournament.assert_resources_operational}); {@code guard_tournament_resource} lets an assignment change
     * its hold once, from a released Approval Hold to an active Tournament Hold; the deferred aggregate check refuses a
     * WAITING Tournament without a standard hold on every Resource or with a live approval timer. The caller has
     * checked that it is still pending and inside its window, so a refusal here is a broken invariant.
     *
     * @return the sequence of the new history row
     */
    int approve(CommandContext command, Current pending, UUID venueAccountId) {
        Objects.requireNonNull(command, "command");
        requirePending(pending);
        UUID correlationId = CorrelationId.require();
        // first the state, while the Approval Holds are still active: the guard revalidates them
        requireOne(command.jdbc().sql(APPROVE)
                .param("id", pending.id())
                .param("version", pending.version())
                .update(), pending.id());
        Instant now = command.commandNow();
        for (HeldAssignment assignment : command.jdbc().sql(ASSIGNMENTS).param("id", pending.id())
                .query((rs, row) -> new HeldAssignment(rs.getObject("id", UUID.class),
                        rs.getObject("resource_id", UUID.class), rs.getObject("reservation_id", UUID.class)))
                .list()) {
            if (!ledger.release(command, assignment.reservationId(), now,
                    TournamentStatus.WAITING_FOR_PLAYERS.name())) {
                throw new IllegalStateException("Approval Hold " + assignment.reservationId() + " of Tournament "
                        + pending.id() + " was not active");
            }
            UUID hold = ledger.create(command, new NewReservation(assignment.resourceId(),
                    ReservationOwner.tournamentHold(pending.id()), pending.startAt(), pending.endAt()));
            command.jdbc().sql(REPOINT).param("id", assignment.id()).param("reservationId", hold).update();
        }
        int sequence = transition(command, pending.id(), pending.venueId(), TournamentStatus.PENDING_VENUE_APPROVAL,
                TournamentStatus.WAITING_FOR_PLAYERS, null, AuditActor.venue(venueAccountId), correlationId);
        supersedeTimers(command, pending.id(), List.of(APPROVAL_EXPIRY));
        return sequence;
    }

    /**
     * The Venue rejects a Player-hosted request (FR-TRN-10): {@code REJECTED} with {@code VENUE_REJECTED_TOURNAMENT} -
     * never a cancellation reason ({@code ck_tournament_rejected_reason}) - by the Venue Account, every Approval Hold
     * released at the command instant and every timer withdrawn. The 5-minute retry cooldown for the same Venue and
     * interval follows from this history row ({@code tr_00_prepare_tournament_insert}, FR-TRN-11). The database also
     * requires the creator's {@code TournamentRejected} outbox row in this transaction
     * ({@code tournament.assert_rejection_notification_outbox}); the caller writes it with the returned sequence.
     *
     * @return the sequence of the new history row
     */
    int reject(CommandContext command, Current pending, UUID venueAccountId) {
        Objects.requireNonNull(command, "command");
        requirePending(pending);
        UUID correlationId = CorrelationId.require();
        requireOne(command.jdbc().sql(REJECT)
                .param("id", pending.id())
                .param("version", pending.version())
                .update(), pending.id());
        int sequence = transition(command, pending.id(), pending.venueId(), TournamentStatus.PENDING_VENUE_APPROVAL,
                TournamentStatus.REJECTED, TournamentTerminalReason.VENUE_REJECTED_TOURNAMENT,
                AuditActor.venue(venueAccountId), correlationId);
        for (UUID reservationId : command.jdbc().sql(RESERVATIONS).param("id", pending.id()).query(UUID.class)
                .list()) {
            ledger.release(command, reservationId, command.commandNow(),
                    TournamentTerminalReason.VENUE_REJECTED_TOURNAMENT.name());
        }
        supersedeTimers(command, pending.id(), TIMERS);
        return sequence;
    }

    private static void requirePending(Current pending) {
        Objects.requireNonNull(pending, "pending");
        if (pending.status() != TournamentStatus.PENDING_VENUE_APPROVAL) {
            throw new IllegalStateException("Tournament " + pending.id() + " is " + pending.status());
        }
    }

    /** The row is locked by begin_command, so its version cannot move under this command. */
    private static void requireOne(int updated, UUID tournamentId) {
        if (updated != 1) {
            throw new IllegalStateException("Tournament " + tournamentId + " changed under its own lock");
        }
    }

    /**
     * Withdraws the Tournament's pending timers of these types. A timer a worker holds right now is left to it: that is
     * the job being fired (T7), which the worker marks DONE in this same transaction.
     */
    private void supersedeTimers(CommandContext command, UUID tournamentId, List<String> types) {
        for (String timer : types) {
            jobs.supersede(command, timer + ":" + tournamentId);
        }
    }

    /**
     * FR-TRN-27, an ACTIVE Tournament only: the assignment of {@code resourceId} marked removed at
     * {@link CommandContext#commandNow()} with {@code cause}, its hold released with the cause as release reason. The
     * Tournament row - its stored venue cost among it (FR-TRN-28) - and the other assignments are not touched. The
     * decision around it is {@link TournamentSystemCascades#loseResource}'s.
     *
     * @return {@code false}, changing nothing, when the Resource is not (or no longer) assigned to it
     */
    boolean removeResource(CommandContext command, UUID tournamentId, UUID resourceId,
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

    /** The Resources still assigned ({@code removed_at IS NULL}), in id order. */
    List<UUID> liveResources(CommandContext command, UUID tournamentId) {
        return command.jdbc().sql(LIVE_RESOURCES).param("id", tournamentId).query(UUID.class).list();
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
     *
     * @return the history row's {@code sequence} (1 for the creation row)
     */
    private int transition(CommandContext command, UUID tournamentId, UUID venueId, TournamentStatus from,
                           TournamentStatus to, TournamentTerminalReason reason, AuditActor actor,
                           UUID correlationId) {
        return transition(command, tournamentId, venueId, from, to, reason, actor, correlationId, AuditDetail.NONE);
    }

    private int transition(CommandContext command, UUID tournamentId, UUID venueId, TournamentStatus from,
                           TournamentStatus to, TournamentTerminalReason reason, AuditActor actor,
                           UUID correlationId, AuditDetail detail) {
        int sequence = command.jdbc().sql(INSERT_HISTORY)
                .param("id", UUID.randomUUID())
                .param("tournamentId", tournamentId)
                .param("toStatus", to.name())
                .param("terminalReason", reason == null ? null : reason.name())
                .param("actorType", actor.actorType().name())
                .param("actorId", actor.actorId())
                .param("correlationId", correlationId)
                .query(Integer.class).single();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (reason != null) {
            payload.put("terminalReason", reason.name());
        }
        AuditRecord.Builder record = AuditRecord.builder(TournamentAuditAction.TOURNAMENT_STATUS_TRANSITION, actor,
                        tournamentId, correlationId)
                .stateChange(from == null ? null : from.name(), to.name())
                .payload(payload)
                .reason(detail.reason())
                .contextVenue(venueId);
        if (detail.ownerPassword() != null) {
            record.ownerPasswordCheck(detail.ownerPassword());
        }
        audit.write(command, record.build());
        return sequence;
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
        String reason = rs.getString("terminal_reason");
        OffsetDateTime approval = rs.getObject("approval_expires_at", OffsetDateTime.class);
        boolean creatorPlays = rs.getBoolean("creator_participates");
        return new Current(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                TournamentHostType.valueOf(rs.getString("host_type")), TournamentStatus.valueOf(rs.getString("status")),
                reason == null ? null : TournamentTerminalReason.valueOf(reason),
                rs.getObject("creator_user_id", UUID.class), instant(rs, "start_at"), instant(rs, "end_at"),
                approval == null ? null : approval.toInstant(), rs.getLong("version"), instant(rs, "checkpoint_at"),
                rs.getInt("capacity"), TournamentVisibility.valueOf(rs.getString("visibility")),
                !rs.wasNull() && creatorPlays);
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

    /**
     * The Tournament as this command reads it.
     *
     * @param terminalReason      {@code null} while live
     * @param creatorUserId       {@code null} for a Venue-hosted Tournament
     * @param approvalExpiresAt   {@code null} for a Venue-hosted Tournament
     * @param checkpointAt        {@code startAt - 1 hour} (FR-TRN-03)
     * @param creatorParticipates whether the Player Creator plays; {@code false} for a Venue-hosted Tournament
     */
    record Current(UUID id, UUID venueId, TournamentHostType hostType, TournamentStatus status,
                   TournamentTerminalReason terminalReason, UUID creatorUserId, Instant startAt, Instant endAt,
                   Instant approvalExpiresAt, long version, Instant checkpointAt, int capacity,
                   TournamentVisibility visibility, boolean creatorParticipates) {

        boolean live() {
            return LIVE.contains(status.name());
        }

        boolean playerHosted() {
            return hostType == TournamentHostType.PLAYER;
        }
    }

    /**
     * What the {@code TOURNAMENT_STATUS_TRANSITION} audit row carries besides the transition (arch 10 section 8.1): the
     * Owner Password check of a protected cancellation and the actor's reason. The database reads both from that very
     * row ({@code tournament.assert_tournament_history_chain}): a Venue cancelling a CONFIRMED Tournament needs a
     * successful check, an Operational Termination the check and a reason (FR-TRN-23, FR-TRN-24).
     *
     * @param ownerPassword {@code null} when the action has no Owner Password check
     * @param reason        {@code null} when the actor gave none
     */
    record AuditDetail(OwnerPasswordCheck ownerPassword, String reason) {

        static final AuditDetail NONE = new AuditDetail(null, null);
    }

    private record HeldAssignment(UUID id, UUID resourceId, UUID reservationId) {
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
