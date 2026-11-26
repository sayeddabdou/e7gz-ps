package ps.e7gz.tournament.internal;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentVisibility;
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
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.tournament.api.TournamentResourceAvailability;
import ps.e7gz.tournament.api.TournamentResourceAvailability.Blocker;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.SessionSnapshots.Session;
import ps.e7gz.venue.api.VenueAccountVenues;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * Tournament creation, O16 part 1 (E7-91, S6-E1; arch 06 sections 3.1 and 3.4, arch 05 section 3.2, arch 03 sections 7
 * and 14) behind {@code POST /customer/tournaments} (a Verified Player's request, {@code PENDING_VENUE_APPROVAL} with one
 * {@code TOURNAMENT_APPROVAL_HOLD} per Resource) and {@code POST /venue/tournaments} (the Venue's own Tournament,
 * {@code WAITING_FOR_PLAYERS} with {@code TOURNAMENT_HOLD}s). Each is one {@link CommandRunner} transaction wrapped by
 * {@link Idempotency} (ADR-015): the Tournament, its holds, the price snapshots, history, audit, the creator's participant
 * row, the timers, the outbox event and the Idempotency-Key record commit together or not at all.
 *
 * <p><b>Who decides what.</b> Under the command's locks (the creator, the Venue, every Resource and the new id) this
 * class decides what the database cannot: the Venue's account, that each Resource is of the Venue and the Activity,
 * that the whole interval fits one Operating Session (FR-TRN-04) and, per Resource, the canonical availability predicate
 * (FR-TRN-33, FR-TRN-08). The insert guards decide the rest and their refusals are mapped to codes
 * ({@code ps.e7gz.common.error.DatabaseErrorCodes}): Venue Marketplace ACTIVE, the creation cutoff
 * {@code startAt - 2h} (FR-TRN-03), an operational VERIFIED, account-verified, non-banned Player Creator (FR-TRN-02),
 * the 5-minute retry cooldown and one pending request per creator platform-wide (FR-TRN-11). No rule is checked twice.
 *
 * <p><b>Existing reservations always win</b> (FR-TRN-08): a Resource that is not free fails the whole creation with
 * {@code TOURNAMENT_RESOURCE_CONFLICT} and nothing - no booking, no hold - is touched; if a booking races past the
 * predicate, the ledger's cross-class guard refuses the hold with the same code. Nothing here ever cancels anything.
 */
@Component
public class TournamentCreation {

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final VenueAccountVenues accounts;
    private final ResourceCatalog resources;
    private final VenueEligibility venues;
    private final SessionSnapshots sessions;
    private final TournamentResourceAvailability availability;
    private final Outbox outbox;
    private final Tournaments tournaments;
    private final InviteCodes inviteCodes;

    TournamentCreation(CommandRunner runner, Idempotency idempotency, VenueAccountVenues accounts,
                       ResourceCatalog resources, VenueEligibility venues, SessionSnapshots sessions,
                       TournamentResourceAvailability availability, Outbox outbox, Tournaments tournaments) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.resources = resources;
        this.venues = venues;
        this.sessions = sessions;
        this.availability = availability;
        this.outbox = outbox;
        this.tournaments = tournaments;
        this.inviteCodes = new InviteCodes(new SecureRandom());
    }

    /** A Verified Player's request to {@code venueId}, once per Idempotency-Key, or the stored response replayed. */
    public <R> IdempotentResult<R> requestByPlayer(UUID customerId, UUID venueId, boolean creatorParticipates,
                                                   TournamentRequest request, IdempotentRequest key,
                                                   StoredResponse<R> response, Function<CreatedTournament, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(playerCommand(customerId, venueId, creatorParticipates, request), view)));
    }

    /** The signed-in Venue Account's own Tournament, once per Idempotency-Key, or the stored response replayed. */
    public <R> IdempotentResult<R> createByVenue(UUID venueAccountId, TournamentRequest request, IdempotentRequest key,
                                                 StoredResponse<R> response, Function<CreatedTournament, R> view) {
        Objects.requireNonNull(view, "view");
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new Mapped<>(venueCommand(venueAccountId, request), view)));
    }

    /** The same commands without an Idempotency-Key record: package-private, for this package's tests. */
    CreatedTournament requestByPlayer(UUID customerId, UUID venueId, boolean creatorParticipates,
                                      TournamentRequest request) {
        return runner.run(playerCommand(customerId, venueId, creatorParticipates, request));
    }

    CreatedTournament createByVenue(UUID venueAccountId, TournamentRequest request) {
        return runner.run(venueCommand(venueAccountId, request));
    }

    private Create playerCommand(UUID customerId, UUID venueId, boolean creatorParticipates,
                                 TournamentRequest request) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(request, "request");
        return new Create(venueId, new NewTournament.Host.Player(customerId, creatorParticipates), request,
                found(venueId, request));
    }

    private Create venueCommand(UUID venueAccountId, TournamentRequest request) {
        Objects.requireNonNull(request, "request");
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        return new Create(venueId, new NewTournament.Host.Venue(venueAccountId), request, found(venueId, request));
    }

    /**
     * The Resources, read first in their own read-only command because the creating command's lock set names them: a
     * Resource that does not exist or is of another Venue is {@code NOT_FOUND} before any idempotency record (as a
     * Manual Booking's, E7-48). A Player naming an unknown Venue therefore gets NOT_FOUND too.
     */
    private List<ResourceCatalog.Entry> found(UUID venueId, TournamentRequest request) {
        return runner.run(new FindResources(resources, venueId, request.resourceIds()));
    }

    /** The creating command; see the class comment for the order of the decisions. */
    private final class Create implements Command<CreatedTournament> {

        private final UUID venueId;
        private final NewTournament.Host host;
        private final TournamentRequest request;
        private final List<ResourceCatalog.Entry> entries;
        private final UUID tournamentId = UUID.randomUUID();

        Create(UUID venueId, NewTournament.Host host, TournamentRequest request, List<ResourceCatalog.Entry> entries) {
            this.venueId = venueId;
            this.host = host;
            this.request = request;
            this.entries = entries;
        }

        /** A new Tournament is new marketplace activity for both host types (ADR-022). */
        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        /** The Venue (with its account), every Resource, the new id and - for a request - the Player Creator. */
        @Override
        public CommandScope scope() {
            LockSet.Builder locks = LockSet.builder().venues(venueId).resources(request.resourceIds())
                    .newTournaments(tournamentId);
            if (host instanceof NewTournament.Host.Player player) {
                locks.users(player.creatorUserId());
            }
            return CommandScope.shared(locks.build());
        }

        @Override
        public CreatedTournament execute(CommandContext context) {
            boolean playerHosted = host instanceof NewTournament.Host.Player;
            requireAccountEnabled(context, playerHosted);
            for (ResourceCatalog.Entry entry : entries) {
                // FR-TRN-33 "activity compatibility"; the Venue was checked by FindResources
                if (entry.activity() != request.activity()) {
                    throw new ApiException(ApiErrorCode.RESOURCE_NOT_ELIGIBLE, "resourceIds");
                }
            }
            Instant endAt = request.endAt();
            // FR-TRN-04: the whole interval inside one current Operating Session; its operating day is the businessDate
            Session session = sessions.containing(context, venueId, request.startAt())
                    .filter(s -> !s.end().isBefore(endAt))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION));

            String inviteCode = request.visibility() == TournamentVisibility.PRIVATE ? inviteCodes.next() : null;
            NewTournament tournament = new NewTournament(tournamentId, venueId, host, request.activity(),
                    request.playMode(), request.capacity(), request.startAt(), request.durationMinutes(),
                    request.visibility(), inviteCode, request.entryFee(), request.prize(), session.id(),
                    session.operatingDay(), request.resourceIds());

            Tournaments.Created created = tournaments.create(context, tournament, this::requireFree);

            CreatedTournament result = view(tournament, created, inviteCode);
            if (playerHosted) {
                outbox.publish(context, requested(result));
            }
            return result;
        }

        /**
         * The Venue's shared account must be ENABLED (the insert guard refuses otherwise, with no identifier). A Player
         * asking a disabled Venue gets the category A answer of an Online Booking (FR-BOK-11 condition 6); a Venue
         * Account disabled between its request and this command is FORBIDDEN, as for a Manual Booking (the token
         * itself stops working: 401).
         */
        private void requireAccountEnabled(CommandContext context, boolean playerHosted) {
            VenueEligibility.Eligibility venue = venues.find(context, venueId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
                throw new ApiException(playerHosted ? ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY
                        : ApiErrorCode.FORBIDDEN);
            }
        }

        /**
         * FR-TRN-33 and FR-TRN-08 for one Resource, at the command instant, by the canonical predicate (arch 05 section
         * 2.1, through {@link TournamentResourceAvailability}) - never re-implemented here. A Resource that is not
         * ACTIVE or is under owner review is not eligible; a selling block is its own code; anything already in the
         * interval - a booking, a hold, Maintenance, a Closure or an emergency's declared interval - wins, and the
         * Tournament is refused. The refused Resource is named in the error's details.
         */
        private void requireFree(CommandContext context, UUID resourceId, Instant startAt, Instant endAt) {
            availability.blocker(context, resourceId, startAt, endAt, context.commandNow())
                    .ifPresent(blocker -> {
                        throw new ApiException(codeOf(blocker), "resourceIds",
                                Map.of("resourceId", resourceId.toString()));
                    });
        }
    }

    /** The predicate's answer (arch 05 section 2.1) as the public code of a new Tournament (arch 09 section 1.5). */
    static ApiErrorCode codeOf(Blocker blocker) {
        return switch (blocker) {
            case VENUE_SELLING_BLOCK -> ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE;
            case RESOURCE_SELLING_BLOCK -> ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE;
            case NOT_ELIGIBLE -> ApiErrorCode.RESOURCE_NOT_ELIGIBLE;
            case ALREADY_RESERVED -> ApiErrorCode.TOURNAMENT_RESOURCE_CONFLICT;
        };
    }

    private static CreatedTournament view(NewTournament tournament, Tournaments.Created created, String inviteCode) {
        NewTournament.Host host = tournament.host();
        NewTournament.Host.Player player = host instanceof NewTournament.Host.Player p ? p : null;
        List<CreatedTournament.Resource> held = new ArrayList<>();
        for (Tournaments.Assignment assignment : created.assignments()) {
            held.add(new CreatedTournament.Resource(assignment.resourceId(), assignment.consoleType(),
                    assignment.hourlyPrice()));
        }
        return new CreatedTournament(tournament.id(), tournament.venueId(),
                player != null ? TournamentHostType.PLAYER : TournamentHostType.VENUE, created.status(),
                player != null ? player.creatorUserId() : null, player != null ? player.creatorParticipates() : null,
                tournament.activity(), tournament.playMode(), tournament.capacity(), tournament.startAt(),
                created.endAt(), tournament.nominalMinutes(), created.checkpointAt(), created.approvalExpiresAt(),
                created.createdAt(), tournament.visibility(), inviteCode, created.venueCostTotal(),
                created.creationFee(), tournament.entryFee(), tournament.prize(), tournament.businessDate(), held);
    }

    /**
     * {@code TournamentRequested} (arch 03 section 14): the Venue has a request to answer before
     * {@code approvalExpiresAt}. E7-89 turns it into the Venue's notification; until then it waits, untouched.
     */
    private static NewOutboxEvent requested(CreatedTournament tournament) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tournamentId", tournament.id().toString());
        payload.put("venueId", tournament.venueId().toString());
        payload.put("creatorUserId", tournament.creatorUserId().toString());
        payload.put("startAt", tournament.startAt().toString());
        payload.put("endAt", tournament.endAt().toString());
        payload.put("approvalExpiresAt", tournament.approvalExpiresAt().toString());
        return new NewOutboxEvent(IntegrationEvents.TOURNAMENT_REQUESTED, Tournaments.SUBJECT_TYPE, tournament.id(),
                payload);
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
