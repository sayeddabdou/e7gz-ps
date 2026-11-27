package ps.e7gz.tournament.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;

/**
 * The Player Creator's events about a request's outcome (E7-92; {@link IntegrationEvents#TOURNAMENT_APPROVED}): one per
 * transition, in the transition's own command, so its correlation ID is the history row's. The payload carries what
 * {@code tournament.assert_rejection_notification_outbox} checks on a rejection - recipient, actor and history
 * sequence - and the same for the other outcomes, so E7-89 reads one shape. Never any text: E7-89 maps the event and
 * its reason to a notification code (Spec section 52).
 */
final class TournamentAnswerEvents {

    static final String RECIPIENT_CUSTOMER = "CUSTOMER";

    private TournamentAnswerEvents() {
    }

    static NewOutboxEvent approved(Tournaments.Current request, int historySequence) {
        return event(IntegrationEvents.TOURNAMENT_APPROVED, request, ActorType.VENUE, historySequence, null);
    }

    static NewOutboxEvent rejected(Tournaments.Current request, int historySequence) {
        return event(IntegrationEvents.TOURNAMENT_REJECTED, request, ActorType.VENUE, historySequence,
                TournamentTerminalReason.VENUE_REJECTED_TOURNAMENT);
    }

    /** A request cancelled by the system: the approval timeout (T7) or a Venue Account disable (FR-TRN-18). */
    static NewOutboxEvent cancelled(Tournaments.Current request, int historySequence,
                                    TournamentTerminalReason reason) {
        return cancelled(request, historySequence, reason, ActorType.SYSTEM);
    }

    /**
     * E7-93: a Player-hosted Tournament CANCELLED by {@code actor} - the system at a boundary (the checkpoint's
     * {@code MINIMUM_PARTICIPANTS_NOT_REACHED} among them), its Creator, its Venue or a Platform Admin.
     */
    static NewOutboxEvent cancelled(Tournaments.Current tournament, int historySequence,
                                    TournamentTerminalReason reason, ActorType actor) {
        return event(IntegrationEvents.TOURNAMENT_CANCELLED, tournament, Objects.requireNonNull(actor, "actor"),
                historySequence, Objects.requireNonNull(reason, "reason"));
    }

    private static NewOutboxEvent event(String type, Tournaments.Current request, ActorType actor,
                                        int historySequence, TournamentTerminalReason reason) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.creatorUserId(), "a Player-hosted request");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tournamentId", request.id().toString());
        payload.put("venueId", request.venueId().toString());
        payload.put("recipientType", RECIPIENT_CUSTOMER);
        payload.put("recipientId", request.creatorUserId().toString());
        payload.put("actorType", actor.name());
        payload.put("historySequence", String.valueOf(historySequence));
        payload.put("startAt", request.startAt().toString());
        payload.put("endAt", request.endAt().toString());
        if (reason != null) {
            payload.put("terminalReason", reason.name());
        }
        return new NewOutboxEvent(type, Tournaments.SUBJECT_TYPE, request.id(), payload);
    }
}
