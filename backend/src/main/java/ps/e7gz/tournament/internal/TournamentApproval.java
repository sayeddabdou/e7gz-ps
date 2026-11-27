package ps.e7gz.tournament.internal;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.venue.api.VenueAccountVenues;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * The Venue's answer to a Player-hosted request and its approval timeout - T7 (E7-92, S6-E2; FR-TRN-10, FR-TRN-11,
 * Spec v5 sections 37.2 and 37.3, arch 06 section 3.4, arch 08 section 1) - behind
 * {@code POST /venue/tournaments/{id}/approve} and {@code /reject}, and fired by {@link TournamentApprovalExpiryHandler}.
 *
 * <p>Each answer is one {@link CommandRunner} transaction wrapped by {@link Idempotency} (ADR-015: approving turns
 * Approval Holds into Tournament Holds, a commitment): the transition, the hold changes, history, audit, the timer
 * changes, the creator's outbox event and the Idempotency-Key record commit together or not at all. Neither needs the
 * Owner Password (FR-TRN-24 lists only the cancellations; arch 10 section 3.2). The Venue is the signed-in account's own;
 * a Tournament of another Venue is {@code NOT_FOUND} before any idempotency record.
 *
 * <p><b>Order of the answers</b> (as a booking's, E7-47 D7): not found; for approve the account must be ENABLED
 * (FORBIDDEN otherwise, as E7-91); a Tournament no longer pending is {@code OPERATIONAL_STATE_CHANGED} - except one the
 * timeout already cancelled, which is {@code DEADLINE_PASSED}; then the window: at or after {@code approvalExpiresAt} it
 * is {@code DEADLINE_PASSED} even while T7 has not run yet - the boundary is the deadline instant, not the worker
 * (FR-AVL-15; the database's guard refuses the same). Every other rule is the database's.
 *
 * <p><b>T7.</b> At {@code approvalExpiresAt = MIN(created + 10 min, checkpointAt)} a request still pending is
 * {@code CANCELLED} with {@code VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME} by SYSTEM, its holds released at the deadline,
 * its timers withdrawn. A late worker decides the same outcome: the guard evaluates the reason at the deadline
 * ({@code tournament.guard_tournament_due_boundary}). The 5-minute retry cooldown after a rejection or a timeout - and
 * none after an account disable (FR-TRN-18) - follows from the history in the database ({@code gd_tournament_retry_cooldown}).
 */
@Component
public class TournamentApproval {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final VenueEligibility venues;
    private final Outbox outbox;
    private final Tournaments tournaments;
    private final TournamentBoundaries boundaries;

    TournamentApproval(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts,
                       VenueEligibility venues, Outbox outbox, Tournaments tournaments,
                       TournamentBoundaries boundaries) {
        this.boundaries = boundaries;
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.venues = venues;
        this.outbox = outbox;
        this.tournaments = tournaments;
    }

    /** Approve once per Idempotency-Key, or the stored response replayed. */
    public <R> IdempotentResult<R> approve(UUID venueAccountId, UUID tournamentId, IdempotentRequest key,
                                           StoredResponse<R> response, Function<AnsweredTournament, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Answer<>(answer(venueAccountId, tournamentId, true), view)));
    }

    /** Reject once per Idempotency-Key, or the stored response replayed. */
    public <R> IdempotentResult<R> reject(UUID venueAccountId, UUID tournamentId, IdempotentRequest key,
                                          StoredResponse<R> response, Function<AnsweredTournament, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Answer<>(answer(venueAccountId, tournamentId, false), view)));
    }

    /** The same commands without an Idempotency-Key record: package-private, for this package's tests. */
    AnsweredTournament approve(UUID venueAccountId, UUID tournamentId) {
        return runner.run(answer(venueAccountId, tournamentId, true));
    }

    AnsweredTournament reject(UUID venueAccountId, UUID tournamentId) {
        return runner.run(answer(venueAccountId, tournamentId, false));
    }

    /**
     * T7 for {@code job}, inside the worker's command under the Tournament's locks: the boundary rule of arch 08 section
     * 2.2 on the state read now.
     */
    JobOutcome expire(CommandContext context, ScheduledJob job) {
        // E7-93: the boundaries' own rule, shared with every command that reaches a due deadline first
        return boundaries.fire(context, job, Tournaments.APPROVAL_EXPIRY);
    }

    // ------------------------------------------------------------------------------------------------ the command

    private VenueAnswer answer(UUID venueAccountId, UUID tournamentId, boolean approve) {
        Objects.requireNonNull(venueAccountId, "venueAccountId");
        Objects.requireNonNull(tournamentId, "tournamentId");
        UUID venueId = accounts.venueOf(venueAccountId);
        runner.run(new FindTournament(tournamentId))
                .filter(venueId::equals)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return new VenueAnswer(venueAccountId, venueId, tournamentId, approve);
    }

    /** The Venue's answer under the Tournament's locks (begin_command adds its Venue, Resources and people). */
    private final class VenueAnswer implements Command<AnsweredTournament> {

        private final UUID venueAccountId;
        private final UUID venueId;
        private final UUID tournamentId;
        private final boolean approve;

        VenueAnswer(UUID venueAccountId, UUID venueId, UUID tournamentId, boolean approve) {
            this.venueAccountId = venueAccountId;
            this.venueId = venueId;
            this.tournamentId = tournamentId;
            this.approve = approve;
        }

        /** Honouring a request the Venue already received, not new marketplace activity (ADR-022), as a booking's. */
        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).tournaments(tournamentId).build());
        }

        @Override
        public AnsweredTournament execute(CommandContext context) {
            Tournaments.Current request = tournaments.current(context, tournamentId)
                    .filter(t -> t.venueId().equals(venueId))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (approve) {
                requireAccountEnabled(context);
            }
            requireStillPending(context, request);
            if (approve) {
                int sequence = tournaments.approve(context, request, venueAccountId);
                outbox.publish(context, TournamentAnswerEvents.approved(request, sequence));
                return new AnsweredTournament(request.id(), TournamentStatus.WAITING_FOR_PLAYERS, null);
            }
            int sequence = tournaments.reject(context, request, venueAccountId);
            // required in this transaction by tournament.assert_rejection_notification_outbox
            outbox.publish(context, TournamentAnswerEvents.rejected(request, sequence));
            return new AnsweredTournament(request.id(), TournamentStatus.REJECTED,
                    TournamentTerminalReason.VENUE_REJECTED_TOURNAMENT);
        }

        /** The guard accepts only with the Venue Account ENABLED; a disabled one already lost its token (401). */
        private void requireAccountEnabled(CommandContext context) {
            VenueEligibility.Eligibility venue = venues.find(context, venueId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
                throw new ApiException(ApiErrorCode.FORBIDDEN);
            }
        }
    }

    /**
     * Still {@code PENDING_VENUE_APPROVAL} and still inside its window (FR-TRN-10). A request the timeout already
     * cancelled - or one whose deadline has passed while T7 has not run yet - is {@code DEADLINE_PASSED}; any other
     * state (a Venue-hosted Tournament, an earlier answer, a cancellation) is {@code OPERATIONAL_STATE_CHANGED}.
     */
    static void requireStillPending(CommandContext context, Tournaments.Current request) {
        if (request.status() != TournamentStatus.PENDING_VENUE_APPROVAL) {
            throw new ApiException(
                    request.terminalReason() == TournamentTerminalReason.VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME
                            ? ApiErrorCode.DEADLINE_PASSED : ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }
        if (!context.commandNow().isBefore(request.approvalExpiresAt())) {
            throw new ApiException(ApiErrorCode.DEADLINE_PASSED);
        }
    }

    /** A command with its result mapped to the response, in the same transaction and lock set. */
    private record Answer<T, R>(Command<T> command, Function<T, R> view) implements Command<R> {

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
