package ps.e7gz.tournament.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.trust.api.ActiveBans;

/**
 * Join, Leave and the Organizer's Remove (E7-93, S6-E3; FR-TRN-06, FR-TRN-07, FR-TRN-12, FR-TRN-26, Spec v5 section
 * 37.4, arch 06 section 3.7, arch 09 section 3.4) behind {@code POST /customer/tournaments/{id}/join}, {@code /join-private},
 * {@code /{id}/leave} and {@code /{id}/participants/{userId}/remove}. Each is one {@link CommandRunner} transaction
 * wrapped by {@link Idempotency} (ADR-015: a participation is a commitment to a held room).
 *
 * <p><b>The serialization path.</b> Every command locks the Tournament - {@code begin_command} locks its whole
 * participant set with it, its Venue and Resources - and the acting Customer; a Ban change reaches the same rows under
 * the same locks ({@link BanParticipantReconciler}). Only then is the capacity counted, so two Joins for the last slot
 * cannot both pass: Join evaluates {@link TournamentParticipants#capacityOccupiedCount capacityOccupiedCount(now)} -
 * JOINED plus BAN-only reserved slots - never the checkpoint's Active Participant count (FR-TRN-12, arch 09 section
 * 3.4). The database refuses the same at the insert ({@code gd_tournament_capacity_full}).
 *
 * <p><b>Boundaries.</b> A checkpoint or start already due is settled first ({@link TournamentBoundaries#settle}), as of
 * its own instant: after a checkpoint that confirmed, Join is still open until {@code startAt}; at or after
 * {@code startAt} Join, Leave and Remove are closed (FR-AVL-15).
 *
 * <p><b>Answers, in order.</b> Join: a Tournament that does not exist, is still PENDING_VENUE_APPROVAL (not public, its
 * invite inactive, FR-TRN-07) or is private and asked for by id is {@code NOT_FOUND}; then the Customer -
 * {@code ACCOUNT_DELETED}, {@code PHONE_VERIFICATION_REQUIRED} (FR-ACC-03), {@code ACCOUNT_BANNED_FOR_THIS_ACTION}
 * (FR-ACC-17); then the window - not WAITING_FOR_PLAYERS / CONFIRMED or {@code now >= startAt} is
 * {@code TOURNAMENT_JOIN_WINDOW_CLOSED}; an Organizer's removal is final ({@code FORBIDDEN}); then capacity,
 * {@code TOURNAMENT_CAPACITY_REACHED}. A Customer already JOINED gets the participation back unchanged. Leave and
 * Remove: no participation is {@code NOT_FOUND}; one already left or removed, or a terminal Tournament, is
 * {@code OPERATIONAL_STATE_CHANGED}; at or after {@code startAt} {@code DEADLINE_PASSED}; Remove by anyone but the
 * Organizer - the Player Creator - is {@code FORBIDDEN}.
 */
@Component
public class TournamentParticipation {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final Tournaments tournaments;
    private final TournamentParticipants participants;
    private final TournamentBoundaries boundaries;
    private final CustomerActivation activation;
    private final ActiveBans bans;

    TournamentParticipation(CommandRunner runner, Idempotency idempotency, Tournaments tournaments,
                            TournamentParticipants participants, TournamentBoundaries boundaries,
                            CustomerActivation activation, ActiveBans bans) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.tournaments = tournaments;
        this.participants = participants;
        this.boundaries = boundaries;
        this.activation = activation;
        this.bans = bans;
    }

    // ------------------------------------------------------------------------------------------------ the API

    /** Join a public Tournament by id, once per Idempotency-Key. */
    public <R> IdempotentResult<R> join(UUID customerId, UUID tournamentId, IdempotentRequest key,
                                        StoredResponse<R> response, Function<Participation, R> view) {
        return idempotent(joinById(customerId, tournamentId), key, response, view);
    }

    /** Join a private Tournament by its invite code (FR-TRN-06), once per Idempotency-Key. */
    public <R> IdempotentResult<R> joinPrivate(UUID customerId, String inviteCode, IdempotentRequest key,
                                               StoredResponse<R> response, Function<Participation, R> view) {
        return idempotent(joinByCode(customerId, inviteCode), key, response, view);
    }

    public <R> IdempotentResult<R> leave(UUID customerId, UUID tournamentId, IdempotentRequest key,
                                         StoredResponse<R> response, Function<Participation, R> view) {
        return idempotent(leaving(customerId, tournamentId), key, response, view);
    }

    public <R> IdempotentResult<R> remove(UUID organizerId, UUID tournamentId, UUID participantUserId,
                                          IdempotentRequest key, StoredResponse<R> response,
                                          Function<Participation, R> view) {
        return idempotent(removing(organizerId, tournamentId, participantUserId), key, response, view);
    }

    /** The same commands without an Idempotency-Key record: package-private, for this package's tests. */
    Participation join(UUID customerId, UUID tournamentId) {
        return runner.run(joinById(customerId, tournamentId));
    }

    Participation joinPrivate(UUID customerId, String inviteCode) {
        return runner.run(joinByCode(customerId, inviteCode));
    }

    Participation leave(UUID customerId, UUID tournamentId) {
        return runner.run(leaving(customerId, tournamentId));
    }

    Participation remove(UUID organizerId, UUID tournamentId, UUID participantUserId) {
        return runner.run(removing(organizerId, tournamentId, participantUserId));
    }

    private <T, R> IdempotentResult<R> idempotent(Command<T> command, IdempotentRequest key,
                                                  StoredResponse<R> response, Function<T, R> view) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(key, response, new Mapped<>(command, view)));
    }

    // ------------------------------------------------------------------------------------------------ the commands

    private Join joinById(UUID customerId, UUID tournamentId) {
        Objects.requireNonNull(customerId, "customerId");
        TournamentLookup.Found found = find(TournamentLookup.byId(tournamentId));
        return new Join(customerId, found.id(), false);
    }

    private Join joinByCode(UUID customerId, String inviteCode) {
        Objects.requireNonNull(customerId, "customerId");
        String code = Objects.requireNonNull(inviteCode, "inviteCode").strip();
        TournamentLookup.Found found = find(TournamentLookup.byInviteCode(code));
        return new Join(customerId, found.id(), true);
    }

    private Change leaving(UUID customerId, UUID tournamentId) {
        Objects.requireNonNull(customerId, "customerId");
        return new Change(customerId, find(TournamentLookup.byId(tournamentId)).id(), customerId, false);
    }

    private Change removing(UUID organizerId, UUID tournamentId, UUID participantUserId) {
        Objects.requireNonNull(organizerId, "organizerId");
        Objects.requireNonNull(participantUserId, "participantUserId");
        return new Change(organizerId, find(TournamentLookup.byId(tournamentId)).id(), participantUserId, true);
    }

    /** Before any idempotency record: an unknown Tournament (or invite code) is NOT_FOUND. */
    private TournamentLookup.Found find(TournamentLookup lookup) {
        return runner.run(lookup).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /** A Join, under the Tournament's and the Customer's locks. */
    private final class Join implements Command<Participation> {

        private final UUID customerId;
        private final UUID tournamentId;
        private final boolean byInviteCode;

        Join(UUID customerId, UUID tournamentId, boolean byInviteCode) {
            this.customerId = customerId;
            this.tournamentId = tournamentId;
            this.byInviteCode = byInviteCode;
        }

        /** A new participation is new marketplace activity (ADR-022, FRS section 2.3). */
        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().users(customerId).tournaments(tournamentId).build());
        }

        @Override
        public Participation execute(CommandContext context) {
            Tournaments.Current tournament = visible(context);
            boundaries.settle(context, tournamentId);
            tournament = tournaments.current(context, tournamentId).orElseThrow();

            activation.requirePhoneVerified(context, customerId);                       // FR-ACC-03, FR-ACC-10
            if (bans.bannedAt(context, customerId, context.commandNow())) {
                throw new ApiException(ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION);    // FR-ACC-17
            }
            if (!joinable(tournament, context)) {
                throw new ApiException(ApiErrorCode.TOURNAMENT_JOIN_WINDOW_CLOSED);     // FR-TRN-12, FR-AVL-15
            }
            TournamentParticipants.Row row = participants.row(context, tournamentId, customerId).orElse(null);
            if (row != null && row.status() == TournamentParticipantStatus.JOINED) {
                return Participation.of(tournament, row.userId(), TournamentParticipantStatus.JOINED);
            }
            if (row != null && row.status() != TournamentParticipantStatus.LEFT) {
                // REMOVED: the Organizer's decision stands; INELIGIBLE: the Ban reconciler restores it, never a Join
                throw new ApiException(ApiErrorCode.FORBIDDEN);
            }
            // FR-TRN-12, ADR-023: capacity occupancy, not the checkpoint count, under the participant-set lock
            if (participants.capacityOccupiedCount(context, tournamentId, context.commandNow())
                    >= tournament.capacity()) {
                throw new ApiException(ApiErrorCode.TOURNAMENT_CAPACITY_REACHED);
            }
            if (row == null) {
                participants.insertJoined(context, tournament, customerId);
            } else {
                participants.rejoin(context, tournament, row);
            }
            return Participation.of(tournament, customerId, TournamentParticipantStatus.JOINED);
        }

        /**
         * FR-TRN-06, FR-TRN-07: a public Tournament by id, a private one only by its code; neither while the request is
         * still pending (not public, the code inactive). Not found otherwise, so a private Tournament is never revealed.
         */
        private Tournaments.Current visible(CommandContext context) {
            Tournaments.Current tournament = tournaments.current(context, tournamentId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            boolean reachable = byInviteCode
                    ? tournament.visibility() == TournamentVisibility.PRIVATE
                    : tournament.visibility() == TournamentVisibility.PUBLIC;
            if (!reachable || tournament.status() == TournamentStatus.PENDING_VENUE_APPROVAL) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            return tournament;
        }

        private boolean joinable(Tournaments.Current tournament, CommandContext context) {
            return (tournament.status() == TournamentStatus.WAITING_FOR_PLAYERS
                    || tournament.status() == TournamentStatus.CONFIRMED)
                    && context.commandNow().isBefore(tournament.startAt());
        }
    }

    /** Leave (the Participant) or Remove (the Organizer), under the Tournament's and the actor's locks. */
    private final class Change implements Command<Participation> {

        private final UUID actorId;
        private final UUID tournamentId;
        private final UUID participantUserId;
        private final boolean removal;

        Change(UUID actorId, UUID tournamentId, UUID participantUserId, boolean removal) {
            this.actorId = actorId;
            this.tournamentId = tournamentId;
            this.participantUserId = participantUserId;
            this.removal = removal;
        }

        /** Managing an existing participation (ADR-022). */
        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().users(actorId).tournaments(tournamentId).build());
        }

        @Override
        public Participation execute(CommandContext context) {
            Tournaments.Current tournament = tournaments.current(context, tournamentId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (removal) {
                // FR-TRN-12: the Organizer of a Player-hosted Tournament is its Creator; a Venue-hosted one's is the Venue
                if (!tournament.playerHosted() || !actorId.equals(tournament.creatorUserId())
                        || actorId.equals(participantUserId)) {
                    throw new ApiException(ApiErrorCode.FORBIDDEN);
                }
            }
            boundaries.settle(context, tournamentId);
            tournament = tournaments.current(context, tournamentId).orElseThrow();
            TournamentParticipants.Row row = participants.row(context, tournamentId, participantUserId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (!tournament.live() || row.status() == TournamentParticipantStatus.LEFT
                    || row.status() == TournamentParticipantStatus.REMOVED) {
                throw new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED);
            }
            if (!context.commandNow().isBefore(tournament.startAt())) {
                throw new ApiException(ApiErrorCode.DEADLINE_PASSED);                    // FR-TRN-12, FR-AVL-15
            }
            if (removal) {
                participants.remove(context, tournament, row, AuditActor.customer(actorId));
                return Participation.of(tournament, participantUserId, TournamentParticipantStatus.REMOVED);
            }
            participants.leave(context, tournament, row);
            return Participation.of(tournament, participantUserId, TournamentParticipantStatus.LEFT);
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

    /**
     * One participation after the command.
     *
     * @param tournamentStatus the Tournament's state as this command left it
     */
    public record Participation(UUID tournamentId, UUID userId, TournamentParticipantStatus status,
                                TournamentStatus tournamentStatus) {

        static Participation of(Tournaments.Current tournament, UUID userId, TournamentParticipantStatus status) {
            return new Participation(tournament.id(), userId, status, tournament.status());
        }
    }
}
