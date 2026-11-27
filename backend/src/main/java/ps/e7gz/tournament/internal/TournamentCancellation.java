package ps.e7gz.tournament.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * Explicit cancellations by the cancellation authority of arch 06 section 3.3 (E7-93, S6-E3; FR-TRN-21, FR-TRN-23,
 * FR-TRN-24, Spec v5 section 37.9) behind {@code POST /customer/tournaments/{id}/cancel},
 * {@code /venue/tournaments/{id}/cancel}, {@code /venue/tournaments/{id}/operational-termination} and
 * {@code /admin/tournaments/{id}/cancel}. Each is one {@link CommandRunner} transaction wrapped by {@link Idempotency}
 * (ADR-015: the holds are released): the state, history, audit, the holds, the timers, the Creator's outbox event and
 * the Idempotency-Key record commit together or not at all.
 *
 * <table>
 *   <caption>Who may cancel what</caption>
 *   <tr><th>Actor</th><th>May cancel</th><th>Owner Password</th></tr>
 *   <tr><td>Creator</td><td>PENDING_VENUE_APPROVAL, WAITING_FOR_PLAYERS - before the checkpoint only</td><td>-</td></tr>
 *   <tr><td>Venue</td><td>WAITING_FOR_PLAYERS, CONFIRMED (a request it has not approved is rejected, not cancelled)</td>
 *       <td>CONFIRMED only</td></tr>
 *   <tr><td>Venue, ACTIVE</td><td>Operational Termination only, with a mandatory reason</td><td>yes</td></tr>
 *   <tr><td>Platform Admin</td><td>any non-completed Tournament, with a reason</td><td>-</td></tr>
 * </table>
 *
 * <p>A boundary already due is settled first ({@link TournamentBoundaries#settle}): at the checkpoint the Tournament
 * belongs to the checkpoint's decision, so a Creator asking at or after {@code checkpointAt} is {@code DEADLINE_PASSED}
 * even while T8 has not run (FR-AVL-15), and a Venue cancelling a Tournament that has just been confirmed needs the
 * Owner Password. A condition-driven reason in force (an Owner Review, an Emergency, a revoked Creator; FR-TRN-31)
 * outranks every explicit cancellation, so one is refused with {@code OPERATIONAL_STATE_CHANGED} - its cascade owns the
 * outcome. A Tournament that is already terminal is {@code OPERATIONAL_STATE_CHANGED} (an Admin's:
 * {@code ADMIN_ACTION_NOT_ALLOWED}). The Venue acts for the signed-in account's own Venue: another Venue's Tournament
 * is {@code NOT_FOUND}.
 *
 * <p><b>The Owner Password</b> (FR-TRN-24, arch 10 section 3.2) is asked by the {@link OwnerPasswordGate} only for the
 * two protected actions; it is checked inside the action's transaction before anything else, before the Idempotency-Key
 * lookup too, and the check is written on the {@code TOURNAMENT_STATUS_TRANSITION} audit row the database reads
 * ({@code tournament.assert_tournament_history_chain}). Which path a Venue cancel takes is read before the command; a
 * Tournament the checkpoint confirms in between answers {@code OWNER_PASSWORD_REQUIRED} on the unprotected path.
 */
@Component
public class TournamentCancellation {

    /** The Owner Password matrix ids (FRS section 2.4; {@code owner-password-actions.tsv}). */
    static final String VENUE_CANCEL_CONFIRMED = "VENUE_CANCEL_CONFIRMED_TOURNAMENT";
    static final String OPERATIONAL_TERMINATION = "TOURNAMENT_OPERATIONAL_TERMINATION";
    static final String CANCEL_WAITING = "CANCEL_WAITING_FOR_PLAYERS_TOURNAMENT";

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final OwnerPasswordGate gate;
    private final Outbox outbox;
    private final Tournaments tournaments;
    private final TournamentBoundaries boundaries;

    TournamentCancellation(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts,
                           OwnerPasswordGate gate, Outbox outbox, Tournaments tournaments,
                           TournamentBoundaries boundaries) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.gate = gate;
        this.outbox = outbox;
        this.tournaments = tournaments;
        this.boundaries = boundaries;
    }

    // ------------------------------------------------------------------------------------------------ the API

    /** The Player Creator cancels before the checkpoint, once per Idempotency-Key. */
    public <R> IdempotentResult<R> cancelByCreator(UUID customerId, UUID tournamentId, IdempotentRequest key,
                                                   StoredResponse<R> response, Function<AnsweredTournament, R> view) {
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(creatorCancel(customerId, tournamentId), view)));
    }

    /** The Venue cancels before ACTIVE; the Owner Password only when the Tournament is CONFIRMED. */
    public <R> IdempotentResult<R> cancelByVenue(UUID venueAccountId, UUID tournamentId, String ownerPassword,
                                                 IdempotentRequest key, StoredResponse<R> response,
                                                 Function<AnsweredTournament, R> view) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(view, "view");
        VenueTarget target = venueTarget(venueAccountId, tournamentId);
        if (target.status() == TournamentStatus.CONFIRMED) {
            ProtectedVenueCancel cancel = new ProtectedVenueCancel(target, gate, this);
            return gate.run(new Protected<>(idempotency.wrap(key, response, new Mapped<>(cancel, view))),
                    venueAccountId, ownerPassword, target.correlationId());
        }
        return runner.run(idempotency.wrap(key, response, new Mapped<>(new VenueCancel(target, this), view)));
    }

    /** Tournament Operational Termination of an ACTIVE Tournament: the Owner Password and a reason (FR-TRN-23). */
    public <R> IdempotentResult<R> terminate(UUID venueAccountId, UUID tournamentId, String ownerPassword,
                                             String reason, IdempotentRequest key, StoredResponse<R> response,
                                             Function<AnsweredTournament, R> view) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(view, "view");
        Termination termination = termination(venueAccountId, tournamentId, reason);
        return gate.run(new ProtectedTermination<>(idempotency.wrap(key, response, new Mapped<>(termination, view))),
                venueAccountId, ownerPassword, termination.target().correlationId());
    }

    /** A Platform Admin cancels any non-completed Tournament, with a reason (FR-TRN-21, FR-ADM-08). */
    public <R> IdempotentResult<R> cancelByAdmin(UUID adminId, UUID tournamentId, String reason,
                                                 IdempotentRequest key, StoredResponse<R> response,
                                                 Function<AnsweredTournament, R> view) {
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(adminCancel(adminId, tournamentId, reason), view)));
    }

    /** The same commands without an Idempotency-Key record: package-private, for this package's tests. */
    AnsweredTournament cancelByCreator(UUID customerId, UUID tournamentId) {
        return runner.run(creatorCancel(customerId, tournamentId));
    }

    AnsweredTournament cancelByVenue(UUID venueAccountId, UUID tournamentId, String ownerPassword) {
        VenueTarget target = venueTarget(venueAccountId, tournamentId);
        if (target.status() == TournamentStatus.CONFIRMED) {
            return gate.run(new ProtectedVenueCancel(target, gate, this), venueAccountId, ownerPassword,
                    target.correlationId());
        }
        return runner.run(new VenueCancel(target, this));
    }

    AnsweredTournament terminate(UUID venueAccountId, UUID tournamentId, String ownerPassword, String reason) {
        Termination termination = termination(venueAccountId, tournamentId, reason);
        return gate.run(new ProtectedTermination<>(termination), venueAccountId, ownerPassword,
                termination.target().correlationId());
    }

    AnsweredTournament cancelByAdmin(UUID adminId, UUID tournamentId, String reason) {
        return runner.run(adminCancel(adminId, tournamentId, reason));
    }

    // ------------------------------------------------------------------------------------------------ the commands

    private CreatorCancel creatorCancel(UUID customerId, UUID tournamentId) {
        Objects.requireNonNull(customerId, "customerId");
        UUID id = find(tournamentId).id();
        return new CreatorCancel(customerId, id);
    }

    private VenueTarget venueTarget(UUID venueAccountId, UUID tournamentId) {
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        UUID venueId = accounts.venueOf(venueAccountId);
        TournamentLookup.Found found = find(tournamentId);
        if (!found.venueId().equals(venueId)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
        return new VenueTarget(venueAccountId, venueId, found.id(), found.status(), CorrelationId.require());
    }

    private Termination termination(UUID venueAccountId, UUID tournamentId, String reason) {
        String why = Objects.requireNonNull(reason, "reason").strip();
        if (why.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        return new Termination(venueTarget(venueAccountId, tournamentId), why, gate, this);
    }

    private AdminCancel adminCancel(UUID adminId, UUID tournamentId, String reason) {
        Objects.requireNonNull(adminId, "adminId");
        String why = Objects.requireNonNull(reason, "reason").strip();
        if (why.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        TournamentLookup.Found found = find(tournamentId);
        return new AdminCancel(adminId, found.id(), found.venueId(), why, CorrelationId.require());
    }

    private TournamentLookup.Found find(UUID tournamentId) {
        return runner.run(TournamentLookup.byId(Objects.requireNonNull(tournamentId, "tournamentId")))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /**
     * The shared core, under the Tournament's locks: settle a due boundary, check the state with {@code allowed},
     * refuse when a condition-driven reason is in force, then cancel with {@code reason} at the command instant.
     */
    private AnsweredTournament cancel(CommandContext context, UUID tournamentId, TournamentTerminalReason reason,
                                      AuditActor actor, Tournaments.AuditDetail detail, StateRule allowed,
                                      ApiErrorCode notLive) {
        boundaries.settle(context, tournamentId);
        Tournaments.Current tournament = tournaments.current(context, tournamentId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (!tournament.live()) {
            throw new ApiException(notLive);
        }
        allowed.require(context, tournament);
        if (tournaments.conditionInForce(context, tournamentId, tournament.status(), context.commandNow())
                .isPresent()) {
            throw new ApiException(notLive);                                    // FR-TRN-31: the cascade's outcome
        }
        int sequence = tournaments.cancelled(context, tournamentId, reason, actor, context.commandNow(), detail)
                .orElseThrow(() -> new IllegalStateException("Tournament " + tournamentId + " ended under its lock"));
        if (tournament.playerHosted()) {
            outbox.publish(context, TournamentAnswerEvents.cancelled(tournament, sequence, reason, actor.actorType()));
        }
        return new AnsweredTournament(tournamentId, TournamentStatus.CANCELLED, reason);
    }

    @FunctionalInterface
    private interface StateRule {
        void require(CommandContext context, Tournaments.Current tournament);
    }

    /** FR-TRN-21: the Creator, while pending or waiting and before the checkpoint; CONFIRMED is past its deadline. */
    private final class CreatorCancel implements Command<AnsweredTournament> {

        private final UUID customerId;
        private final UUID tournamentId;

        CreatorCancel(UUID customerId, UUID tournamentId) {
            this.customerId = customerId;
            this.tournamentId = tournamentId;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().users(customerId).tournaments(tournamentId).build());
        }

        @Override
        public AnsweredTournament execute(CommandContext context) {
            Tournaments.Current tournament = tournaments.current(context, tournamentId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (!tournament.playerHosted() || !customerId.equals(tournament.creatorUserId())) {
                throw new ApiException(ApiErrorCode.FORBIDDEN);
            }
            return cancel(context, tournamentId, TournamentTerminalReason.CREATOR_CANCELLED,
                    AuditActor.customer(customerId), Tournaments.AuditDetail.NONE, (c, t) -> {
                        boolean beforeCheckpoint = c.commandNow().isBefore(t.checkpointAt());
                        if (t.status() == TournamentStatus.CONFIRMED || t.status() == TournamentStatus.ACTIVE
                                || !beforeCheckpoint) {
                            throw new ApiException(ApiErrorCode.DEADLINE_PASSED);
                        }
                    }, ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }
    }

    /** The Venue's Tournament, as read before the command (the gate's path follows {@code status}). */
    record VenueTarget(UUID venueAccountId, UUID venueId, UUID tournamentId, TournamentStatus status,
                       UUID correlationId) {

        CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).tournaments(tournamentId).build());
        }
    }

    /** FR-TRN-21, FR-TRN-24: the Venue cancels WAITING_FOR_PLAYERS with no Owner Password. */
    @OwnerPasswordNotRequired(CANCEL_WAITING)
    record VenueCancel(VenueTarget target, TournamentCancellation owner) implements Command<AnsweredTournament> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return target.scope();
        }

        @Override
        public AnsweredTournament execute(CommandContext context) {
            return owner.cancel(context, target.tournamentId(), TournamentTerminalReason.VENUE_CANCELLED,
                    AuditActor.venue(target.venueAccountId()), Tournaments.AuditDetail.NONE, (c, t) -> {
                        if (t.status() == TournamentStatus.CONFIRMED) {
                            throw new ApiException(ApiErrorCode.OWNER_PASSWORD_REQUIRED);  // confirmed meanwhile
                        }
                        requireVenueCancellable(t);
                    }, ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }

        @Override
        public String toString() {
            return "VenueCancel[tournamentId=" + target.tournamentId() + "]";
        }
    }

    /** FR-TRN-24: the Venue cancels a CONFIRMED Tournament, the Owner Password checked by the gate first. */
    @OwnerProtected(VENUE_CANCEL_CONFIRMED)
    record ProtectedVenueCancel(VenueTarget target, OwnerPasswordGate gate, TournamentCancellation owner)
            implements Command<AnsweredTournament> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return target.scope();
        }

        @Override
        public AnsweredTournament execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verified(context, target.venueAccountId());
            return owner.cancel(context, target.tournamentId(), TournamentTerminalReason.VENUE_CANCELLED,
                    AuditActor.venue(target.venueAccountId()), new Tournaments.AuditDetail(check, null),
                    (c, t) -> requireVenueCancellable(t), ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }

        /** The Tournament and the actor only; the password is the gate's alone (NFR-21). */
        @Override
        public String toString() {
            return "ProtectedVenueCancel[tournamentId=" + target.tournamentId() + "]";
        }
    }

    /**
     * Arch 06 section 3.3: a Venue cancels only after its own approval and before ACTIVE - a pending request is
     * rejected (E7-92), an ACTIVE Tournament is terminated.
     */
    private static void requireVenueCancellable(Tournaments.Current tournament) {
        if (tournament.status() != TournamentStatus.WAITING_FOR_PLAYERS
                && tournament.status() != TournamentStatus.CONFIRMED) {
            throw new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }
    }

    /** FR-TRN-23: ACTIVE -> CANCELLED / TOURNAMENT_OPERATIONAL_TERMINATION, the check and the reason on its audit row. */
    @OwnerProtected(OPERATIONAL_TERMINATION)
    record Termination(VenueTarget target, String reason, OwnerPasswordGate gate, TournamentCancellation owner)
            implements Command<AnsweredTournament> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return target.scope();
        }

        @Override
        public AnsweredTournament execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verified(context, target.venueAccountId());
            return owner.cancel(context, target.tournamentId(),
                    TournamentTerminalReason.TOURNAMENT_OPERATIONAL_TERMINATION,
                    AuditActor.venue(target.venueAccountId()), new Tournaments.AuditDetail(check, reason),
                    (c, t) -> {
                        if (t.status() != TournamentStatus.ACTIVE) {
                            throw new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED);
                        }
                    }, ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }

        @Override
        public String toString() {
            return "Termination[tournamentId=" + target.tournamentId() + "]";
        }
    }

    /** FR-TRN-21, FR-ADM-08: the Platform Admin, any live Tournament, under the Admin's binding. */
    private final class AdminCancel implements Command<AnsweredTournament> {

        private final UUID adminId;
        private final UUID tournamentId;
        private final UUID venueId;
        private final String reason;
        private final UUID correlationId;

        AdminCancel(UUID adminId, UUID tournamentId, UUID venueId, String reason, UUID correlationId) {
            this.adminId = adminId;
            this.tournamentId = tournamentId;
            this.venueId = venueId;
            this.reason = reason;
            this.correlationId = correlationId;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        /** {@code tournament.begin_command} with the Tournament, then {@code admin.begin_command} binds the Admin. */
        @Override
        public CommandScope scope() {
            return CommandScope.adminCascade(adminId, correlationId,
                    LockSet.builder().venues(venueId).tournaments(tournamentId).build());
        }

        @Override
        public AnsweredTournament execute(CommandContext context) {
            return cancel(context, tournamentId, TournamentTerminalReason.ADMIN_CANCELLED, AuditActor.admin(adminId),
                    new Tournaments.AuditDetail(null, reason), (c, t) -> { }, ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        }
    }

    /** The gate's command for a protected Venue cancel: the Idempotency-Key wrapper, carrying the action id. */
    @OwnerProtected(VENUE_CANCEL_CONFIRMED)
    record Protected<R>(Command<R> command) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return command.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return command.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return command.execute(context);
        }
    }

    /** The gate's command for an Operational Termination: the Idempotency-Key wrapper, carrying the action id. */
    @OwnerProtected(OPERATIONAL_TERMINATION)
    record ProtectedTermination<R>(Command<R> command) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return command.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return command.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return command.execute(context);
        }
    }

    /** A command with its result mapped to the response, in the same transaction and lock set. */
    private record Mapped<T, R>(Command<T> command, Function<T, R> view) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return command.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return command.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return view.apply(command.execute(context));
        }
    }
}
