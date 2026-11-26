package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * The Tournament's boundaries (E7-93, S6-E3; arch 06 sections 3.1 and 3.4, arch 08 section 1, ADR-023 item 5): the
 * approval expiry (T7, E7-92), the checkpoint (T8) at exactly {@code startAt - 1h}, the start and the completion (T9).
 * Each is decided <b>as of its own instant</b>, never as of the worker's: a worker an hour late reaches the same outcome,
 * with holds released at the boundary.
 *
 * <p>{@link #settle} resolves every boundary that is due at the command instant, in order. The timers call it
 * ({@link #fire}); so does every command that changes a participant set or cancels, because once a boundary is due the
 * database lets nothing change its result ({@code tournament.guard_tournament_participant},
 * {@code tournament.guard_tournament_due_boundary}): the boundary owns the next transition, whoever reaches it first.
 * The command must lock the Tournament; the result is the same whichever command settles it, and settling twice changes
 * nothing.
 *
 * <ul>
 *   <li><b>Checkpoint</b> (FR-TRN-13 to FR-TRN-16): first every BAN-only Participant whose Ban was no longer in force
 *       at {@code checkpointAt} is restored; then the {@link TournamentParticipants#activeParticipantCount Active
 *       Participant count} at {@code checkpointAt} and every assigned Resource are what the frozen reason priority
 *       weighs (FR-TRN-31): no condition in force - {@code CONFIRMED}, the guard revalidating every Resource; otherwise
 *       {@code CANCELLED} with the highest reason - {@code MINIMUM_PARTICIPANTS_NOT_REACHED} below 8 - and every hold
 *       released in the same transaction, at {@code checkpointAt}. There is no "Run Anyway" (FR-TRN-14), and a later
 *       drop below 8 never cancels a CONFIRMED Tournament.</li>
 *   <li><b>Start</b> (FR-TRN-17, FR-TRN-26): BAN-only restorations effective before {@code startAt} first (a Ban ending
 *       exactly at {@code startAt} restores nothing), then {@code ACTIVE} after revalidating every Resource, or
 *       {@code CANCELLED} under the condition in force - any Resource under owner review cancels before gameplay.</li>
 *   <li><b>Completion</b> (FR-TRN-30): {@code COMPLETED} at {@code endAt}, the remaining holds released at
 *       {@code endAt}.</li>
 * </ul>
 */
@Component
class TournamentBoundaries {

    private final Tournaments tournaments;
    private final TournamentParticipants participants;
    private final Outbox outbox;

    TournamentBoundaries(Tournaments tournaments, TournamentParticipants participants, Outbox outbox) {
        this.tournaments = tournaments;
        this.participants = participants;
        this.outbox = outbox;
    }

    /**
     * Resolves every boundary of the Tournament that is due at {@link CommandContext#commandNow()}, earliest first, and
     * returns the timers whose boundary this call resolved (empty when none was due, or the Tournament is terminal or
     * missing).
     */
    List<String> settle(CommandContext command, UUID tournamentId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(tournamentId, "tournamentId");
        List<String> resolved = new ArrayList<>();
        Instant now = command.commandNow();
        while (true) {
            Optional<Tournaments.Current> found = tournaments.current(command, tournamentId);
            if (found.isEmpty()) {
                return resolved;
            }
            Tournaments.Current t = found.get();
            if (t.status() == TournamentStatus.PENDING_VENUE_APPROVAL && !now.isBefore(t.approvalExpiresAt())) {
                expireApproval(command, t);
                resolved.add(Tournaments.APPROVAL_EXPIRY);
            } else if (t.status() == TournamentStatus.WAITING_FOR_PLAYERS && !now.isBefore(t.checkpointAt())) {
                checkpoint(command, t);
                resolved.add(Tournaments.CHECKPOINT);
            } else if (t.status() == TournamentStatus.CONFIRMED && !now.isBefore(t.startAt())) {
                start(command, t);
                resolved.add(Tournaments.START);
            } else if (t.status() == TournamentStatus.ACTIVE && !now.isBefore(t.endAt())) {
                tournaments.complete(command, t);
                resolved.add(Tournaments.COMPLETION);
            } else {
                return resolved;
            }
        }
    }

    /**
     * One timer of the Tournament, inside the worker's command under the Tournament's locks: every due boundary is
     * settled (a late checkpoint first, when the start fires before it); the timer is DONE when its own boundary was
     * resolved now, rescheduled to its instant when that is still ahead, and superseded when the Tournament already
     * left the state it guards.
     */
    JobOutcome fire(CommandContext command, ScheduledJob job, String timer) {
        List<String> resolved = settle(command, job.subjectId());
        if (resolved.contains(timer)) {
            return JobOutcome.done();
        }
        Optional<Tournaments.Current> found = tournaments.current(command, job.subjectId());
        if (found.isEmpty()) {
            return JobOutcome.superseded("no such Tournament");
        }
        Tournaments.Current t = found.get();
        TournamentStatus guarded = guardedState(timer);
        if (t.live() && LIFECYCLE.indexOf(t.status()) <= LIFECYCLE.indexOf(guarded)) {
            return JobOutcome.rescheduleAt(dueAt(timer, t));                    // an early wake: not due yet
        }
        return JobOutcome.superseded("no longer before " + guarded + " ends");
    }

    /** The live states in their only order (arch 06 section 3.1). */
    private static final List<TournamentStatus> LIFECYCLE = List.of(TournamentStatus.PENDING_VENUE_APPROVAL,
            TournamentStatus.WAITING_FOR_PLAYERS, TournamentStatus.CONFIRMED, TournamentStatus.ACTIVE);

    /**
     * T7 (FR-TRN-10, E7-92): a request still pending at {@code approvalExpiresAt} is CANCELLED by SYSTEM as of the
     * deadline - {@code VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME}, unless a higher condition was in force at the deadline
     * (FR-TRN-31, Product section 37.14) - its holds released at the deadline, the creator told.
     */
    void expireApproval(CommandContext command, Tournaments.Current request) {
        Instant deadline = request.approvalExpiresAt();
        TournamentTerminalReason reason = tournaments.conditionInForce(command, request.id(),
                        TournamentStatus.PENDING_VENUE_APPROVAL, deadline)
                .orElse(TournamentTerminalReason.VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME);
        cancelAtBoundary(command, request, reason, deadline);
    }

    private void checkpoint(CommandContext command, Tournaments.Current waiting) {
        Instant at = waiting.checkpointAt();
        participants.restoreAsOf(command, waiting, at, false);
        // FR-TRN-13: the minimum is weighed on the Active Participant count as of checkpointAt, never capacity
        // occupancy; the resolver weighs it below the Resource and Venue conditions (FR-TRN-31)
        int active = participants.activeParticipantCount(command, waiting.id(), at);
        Optional<TournamentTerminalReason> condition = tournaments.conditionInForce(command, waiting.id(),
                TournamentStatus.WAITING_FOR_PLAYERS, at);
        if (condition.isEmpty() && active < MINIMUM_PARTICIPANTS) {
            throw new IllegalStateException("Tournament " + waiting.id() + " has " + active
                    + " Active Participants at its checkpoint and no cancellation reason");
        }
        if (condition.isPresent()) {
            cancelAtBoundary(command, waiting, condition.get(), at);
        } else {
            tournaments.confirm(command, reread(command, waiting.id()));
        }
    }

    private void start(CommandContext command, Tournaments.Current confirmed) {
        Instant at = confirmed.startAt();
        participants.restoreAsOf(command, confirmed, at, true);
        Optional<TournamentTerminalReason> condition = tournaments.conditionInForce(command, confirmed.id(),
                TournamentStatus.CONFIRMED, at);
        if (condition.isPresent()) {
            cancelAtBoundary(command, confirmed, condition.get(), at);
        } else {
            tournaments.start(command, reread(command, confirmed.id()));
        }
    }

    /** CANCELLED by SYSTEM with {@code reason}, its holds released at {@code boundary}; the Creator is told. */
    private void cancelAtBoundary(CommandContext command, Tournaments.Current tournament,
                                  TournamentTerminalReason reason, Instant boundary) {
        int sequence = tournaments.cancelled(command, tournament.id(), reason, AuditActor.system(), boundary)
                .orElseThrow(() -> new IllegalStateException("Tournament " + tournament.id() + " ended under its lock"));
        if (tournament.playerHosted()) {
            outbox.publish(command, TournamentAnswerEvents.cancelled(tournament, sequence, reason));
        }
    }

    /** Restorations moved no version; the row is read again so every later write starts from what is stored. */
    private Tournaments.Current reread(CommandContext command, UUID tournamentId) {
        return tournaments.current(command, tournamentId)
                .orElseThrow(() -> new IllegalStateException("Tournament " + tournamentId + " vanished"));
    }

    /** FR-TRN-03, FR-TRN-13: the checkpoint minimum. */
    static final int MINIMUM_PARTICIPANTS = 8;

    private static TournamentStatus guardedState(String timer) {
        return switch (timer) {
            case Tournaments.APPROVAL_EXPIRY -> TournamentStatus.PENDING_VENUE_APPROVAL;
            case Tournaments.CHECKPOINT -> TournamentStatus.WAITING_FOR_PLAYERS;
            case Tournaments.START -> TournamentStatus.CONFIRMED;
            case Tournaments.COMPLETION -> TournamentStatus.ACTIVE;
            default -> throw new IllegalArgumentException("Not a Tournament timer: " + timer);
        };
    }

    private static Instant dueAt(String timer, Tournaments.Current t) {
        return switch (timer) {
            case Tournaments.APPROVAL_EXPIRY -> t.approvalExpiresAt();
            case Tournaments.CHECKPOINT -> t.checkpointAt();
            case Tournaments.START -> t.startAt();
            case Tournaments.COMPLETION -> t.endAt();
            default -> throw new IllegalArgumentException("Not a Tournament timer: " + timer);
        };
    }
}
