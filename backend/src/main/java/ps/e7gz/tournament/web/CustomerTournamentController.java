package ps.e7gz.tournament.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.tournament.internal.AnsweredTournament;
import ps.e7gz.tournament.internal.CreatedTournament;
import ps.e7gz.tournament.internal.TournamentCancellation;
import ps.e7gz.tournament.internal.TournamentParticipation;
import ps.e7gz.tournament.internal.TournamentCreation;
import ps.e7gz.tournament.internal.TournamentRequest;

/**
 * Customer App - Tournaments (arch 09 section 3.4). This controller holds the Player-hosted request of E7-91 (O16 part
 * 1) and join, join-private, leave, the organizer's remove and the creator's cancel of E7-93; the Discover list is the
 * Sprint 6 contract's (E7-88). The Customer DTOs are their
 * own (arch 09 section 1.1). The request moves a commitment (Approval Holds), so the Idempotency-Key is required
 * (ADR-015).
 */
@RestController
@RequestMapping("/api/v1/customer/tournaments")
@Tag(name = "customer-tournaments")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerTournamentController {

    /** {@code technical.idempotency_record.endpoint} (arch 08 section 6). */
    static final String CREATE_ENDPOINT = "POST /api/v1/customer/tournaments";
    static final String TOURNAMENT_AGGREGATE = "TOURNAMENT";

    static final String JOIN_ENDPOINT = "POST /api/v1/customer/tournaments/{tournamentId}/join";
    static final String JOIN_PRIVATE_ENDPOINT = "POST /api/v1/customer/tournaments/join-private";
    static final String LEAVE_ENDPOINT = "POST /api/v1/customer/tournaments/{tournamentId}/leave";
    static final String REMOVE_ENDPOINT =
            "POST /api/v1/customer/tournaments/{tournamentId}/participants/{userId}/remove";
    static final String CANCEL_ENDPOINT = "POST /api/v1/customer/tournaments/{tournamentId}/cancel";
    private static final String TOURNAMENT_ID = "tournamentId";

    private final TournamentCreation creation;
    private final TournamentParticipation participation;
    private final TournamentCancellation cancellation;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    public CustomerTournamentController(TournamentCreation creation, TournamentParticipation participation,
                                        TournamentCancellation cancellation, Idempotency idempotency,
                                        ResponseMetaFactory meta) {
        this.creation = creation;
        this.participation = participation;
        this.cancellation = cancellation;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "customerCreateTournament",
            summary = "Ask a venue to host a tournament: PENDING_VENUE_APPROVAL with Approval Holds (FR-TRN-02, "
                    + "FR-TRN-03, FR-TRN-09)",
            description = "Only an operational Verified Player may ask (FR-TRN-02): otherwise VERIFIED_PLAYER_REQUIRED; "
                    + "a phone not verified, a ban or a deleted account are their own 403s. 8-32 participants, 1-4 "
                    + "distinct rooms or tables of the venue and the activity, at least 60 minutes in 30-minute steps, "
                    + "playMode exactly for PLAYSTATION (VALIDATION_FAILED on the field otherwise). The whole interval "
                    + "must fit one Operating Session (FR-TRN-04: INTERVAL_NOT_WITHIN_ONE_SESSION) and the request "
                    + "must be made at least 2 hours before startAt (TOURNAMENT_CREATION_CUTOFF_PASSED). One pending "
                    + "request per creator anywhere (PENDING_TOURNAMENT_REQUEST_EXISTS) and 5 minutes after a "
                    + "rejection or expiry before asking the same venue for the same interval again "
                    + "(TOURNAMENT_RETRY_COOLDOWN_ACTIVE; FR-TRN-11). Existing reservations always win (FR-TRN-08): "
                    + "a room with a booking, a hold, maintenance, a closure or an emergency in the interval is 409 "
                    + "TOURNAMENT_RESOURCE_CONFLICT and nothing is changed; a room not ACTIVE or under owner review is "
                    + "RESOURCE_NOT_ELIGIBLE (also when the venue has no price for it, FR-VEN-45); a selling block "
                    + "is VENUE_SELLING_BLOCK_ACTIVE / RESOURCE_SELLING_BLOCK_ACTIVE (FR-TRN-33). A venue not ACTIVE "
                    + "or whose account is disabled is VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY; an unknown venue or a room "
                    + "of another venue is NOT_FOUND. The venue cost is fixed now: the hourly price of each room x "
                    + "the duration, half-up to 2 decimals per room, summed (FR-TRN-28); entryFee and prize are "
                    + "informational (FR-TRN-29), 0.00 when absent. approvalExpiresAt = MIN(createdAt + 10 minutes, "
                    + "checkpointAt) (FR-TRN-10). While pending the tournament is not in Discover and nobody may join; "
                    + "a private tournament's inviteCode is null until the venue accepts (FR-TRN-07). Implemented "
                    + "by E7-91.")
    @ApiError(status = 403, codes = {ApiErrorCode.VERIFIED_PLAYER_REQUIRED, ApiErrorCode.PHONE_VERIFICATION_REQUIRED,
            ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION, ApiErrorCode.ACCOUNT_DELETED})
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.TOURNAMENT_RESOURCE_CONFLICT)
    @ApiError(status = 422, codes = {ApiErrorCode.TOURNAMENT_CREATION_CUTOFF_PASSED,
            ApiErrorCode.PENDING_TOURNAMENT_REQUEST_EXISTS, ApiErrorCode.TOURNAMENT_RETRY_COOLDOWN_ACTIVE,
            ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY,
            ApiErrorCode.RESOURCE_NOT_ELIGIBLE, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public ResponseEntity<DataResponse<CustomerTournament>> create(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody CustomerTournamentCreateRequest request,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        TournamentRequest tournament = TournamentRequest.of(request.activity(), request.playMode(),
                request.resourceIds(), request.capacity(), request.startAt(), request.durationMinutes(),
                request.visibility(), money(request.entryFee()), money(request.prize()));
        StoredResponse<CustomerTournament> response = StoredResponse.of(CustomerTournament.class, 201,
                (CustomerTournament created) -> new ResultAggregate(TOURNAMENT_AGGREGATE, created.tournamentId()));
        IdempotentResult<CustomerTournament> result = creation.requestByPlayer(customer.id(), request.venueId(),
                request.creatorParticipates(), tournament,
                idempotency.request(customer, CREATE_ENDPOINT, idempotencyKey, Map.of(), request),
                response, CustomerTournament::of);
        return ResponseEntity.status(result.status()).body(meta.data(result.body()));
    }

    @PostMapping("/{tournamentId}/join")
    @Operation(operationId = "customerJoinTournament",
            summary = "Join a public tournament (FR-TRN-12, FR-TRN-06)",
            description = "Only while the tournament is WAITING_FOR_PLAYERS or CONFIRMED and now < startAt; otherwise "
                    + "TOURNAMENT_JOIN_WINDOW_CLOSED (also after the checkpoint cancelled it). Capacity counts JOINED "
                    + "participants plus the slots a banned participant keeps before the start "
                    + "(capacityOccupiedCount, FR-TRN-26): when they fill it, TOURNAMENT_CAPACITY_REACHED. An "
                    + "unverified phone, a ban or a deleted account are their own 403s (FR-ACC-03, FR-ACC-17). A "
                    + "private tournament is joined only with its code (join-private), and a request the venue has "
                    + "not accepted yet is not joinable (FR-TRN-07): both are NOT_FOUND here. A participant the "
                    + "organizer removed may not join again (FORBIDDEN); one who left may, while a slot is free. "
                    + "Joining again while JOINED returns the participation unchanged. Implemented by E7-93.")
    @ApiError(status = 403, codes = {ApiErrorCode.PHONE_VERIFICATION_REQUIRED,
            ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION, ApiErrorCode.ACCOUNT_DELETED, ApiErrorCode.FORBIDDEN})
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = {ApiErrorCode.TOURNAMENT_JOIN_WINDOW_CLOSED,
            ApiErrorCode.TOURNAMENT_CAPACITY_REACHED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerTournamentParticipation> join(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(participation.join(customer.id(), tournamentId,
                idempotency.request(customer, JOIN_ENDPOINT, idempotencyKey, Map.of(TOURNAMENT_ID, tournamentId),
                        null),
                participationResponse(), CustomerTournamentParticipation::of).body());
    }

    @PostMapping("/join-private")
    @Operation(operationId = "customerJoinPrivateTournament",
            summary = "Join a private tournament with its invite code (FR-TRN-06, FR-TRN-12)",
            description = "The same rules as join. An unknown code, a public tournament's id used as a code, or a "
                    + "request the venue has not accepted yet (the code is inactive, FR-TRN-07) is NOT_FOUND. "
                    + "Implemented by E7-93.")
    @ApiError(status = 403, codes = {ApiErrorCode.PHONE_VERIFICATION_REQUIRED,
            ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION, ApiErrorCode.ACCOUNT_DELETED, ApiErrorCode.FORBIDDEN})
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = {ApiErrorCode.TOURNAMENT_JOIN_WINDOW_CLOSED,
            ApiErrorCode.TOURNAMENT_CAPACITY_REACHED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerTournamentParticipation> joinPrivate(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody CustomerTournamentJoinPrivateRequest request,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(participation.joinPrivate(customer.id(), request.inviteCode(),
                idempotency.request(customer, JOIN_PRIVATE_ENDPOINT, idempotencyKey, Map.of(), request),
                participationResponse(), CustomerTournamentParticipation::of).body());
    }

    @PostMapping("/{tournamentId}/leave")
    @Operation(operationId = "customerLeaveTournament",
            summary = "Leave a tournament before it starts (FR-TRN-12)",
            description = "Until startAt; at or after it DEADLINE_PASSED. Not a participant: NOT_FOUND. Already left "
                    + "or removed, or the tournament has ended: OPERATIONAL_STATE_CHANGED. Falling below 8 before the "
                    + "checkpoint does not cancel the tournament early. Implemented by E7-93.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerTournamentParticipation> leave(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(participation.leave(customer.id(), tournamentId,
                idempotency.request(customer, LEAVE_ENDPOINT, idempotencyKey, Map.of(TOURNAMENT_ID, tournamentId),
                        null),
                participationResponse(), CustomerTournamentParticipation::of).body());
    }

    @PostMapping("/{tournamentId}/participants/{userId}/remove")
    @Operation(operationId = "customerRemoveTournamentParticipant",
            summary = "The organizer removes a participant before the start (FR-TRN-12)",
            description = "Only the creator of a player-hosted tournament, never themself (FORBIDDEN otherwise). Until "
                    + "startAt; at or after it DEADLINE_PASSED. Not a participant: NOT_FOUND; already left or "
                    + "removed: OPERATIONAL_STATE_CHANGED. A removed participant may not join again. Implemented by "
                    + "E7-93.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerTournamentParticipation> remove(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(participation.remove(customer.id(), tournamentId, userId,
                idempotency.request(customer, REMOVE_ENDPOINT, idempotencyKey,
                        Map.of(TOURNAMENT_ID, tournamentId, "userId", userId), null),
                participationResponse(), CustomerTournamentParticipation::of).body());
    }

    @PostMapping("/{tournamentId}/cancel")
    @Operation(operationId = "customerCancelTournament",
            summary = "The creator cancels before the checkpoint: CANCELLED / CREATOR_CANCELLED (FR-TRN-21)",
            description = "Only the creator of a player-hosted tournament (FORBIDDEN otherwise), while it is "
                    + "PENDING_VENUE_APPROVAL or WAITING_FOR_PLAYERS and now < checkpointAt. At or after the checkpoint "
                    + "- CONFIRMED or ACTIVE included - DEADLINE_PASSED: the creator must contact the venue or "
                    + "support. A tournament that has ended, or one an emergency or owner review is cancelling, is "
                    + "OPERATIONAL_STATE_CHANGED. Every room hold is released. Implemented by E7-93.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<CustomerTournamentCancellation> cancel(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @AuthenticationPrincipal AuthenticatedSubject customer) {
        return meta.data(cancellation.cancelByCreator(customer.id(), tournamentId,
                idempotency.request(customer, CANCEL_ENDPOINT, idempotencyKey, Map.of(TOURNAMENT_ID, tournamentId),
                        null),
                StoredResponse.of(CustomerTournamentCancellation.class, 200,
                        (CustomerTournamentCancellation c) -> new ResultAggregate(TOURNAMENT_AGGREGATE,
                                c.tournamentId())),
                CustomerTournamentCancellation::of).body());
    }

    private static StoredResponse<CustomerTournamentParticipation> participationResponse() {
        return StoredResponse.of(CustomerTournamentParticipation.class, 200,
                (CustomerTournamentParticipation p) -> new ResultAggregate(TOURNAMENT_AGGREGATE, p.tournamentId()));
    }

    @Schema(name = "CustomerTournamentJoinPrivateRequest")
    public record CustomerTournamentJoinPrivateRequest(
            @NotBlank @Size(max = 64) @Schema(description = "The code the organizer shared (FR-TRN-06).",
                    example = "K7Q2M9XD") String inviteCode) {
    }

    /** The signed-in customer's participation after the action. */
    @Schema(name = "CustomerTournamentParticipation")
    public record CustomerTournamentParticipation(
            @NotNull UUID tournamentId,
            @NotNull UUID userId,
            @NotNull @Schema(description = "JOINED after join, LEFT after leave, REMOVED after the organizer's "
                    + "remove.") TournamentParticipantStatus participantStatus,
            @NotNull TournamentStatus tournamentStatus) {

        static CustomerTournamentParticipation of(TournamentParticipation.Participation p) {
            return new CustomerTournamentParticipation(p.tournamentId(), p.userId(), p.status(), p.tournamentStatus());
        }
    }

    @Schema(name = "CustomerTournamentCancellation")
    public record CustomerTournamentCancellation(
            @NotNull UUID tournamentId,
            @NotNull @Schema(description = "CANCELLED.") TournamentStatus status,
            @NotNull @Schema(description = "CREATOR_CANCELLED.") TournamentTerminalReason terminalReason) {

        static CustomerTournamentCancellation of(AnsweredTournament cancelled) {
            return new CustomerTournamentCancellation(cancelled.id(), cancelled.status(), cancelled.terminalReason());
        }
    }

    /** An absent informational amount is EGP 0 (FR-TRN-29). */
    static Money money(MoneyView view) {
        return view == null ? null : view.toMoney();
    }

    @Schema(name = "CustomerTournamentCreateRequest")
    public record CustomerTournamentCreateRequest(
            @NotNull @Schema(example = "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e") UUID venueId,
            @NotNull Activity activity,
            @Schema(nullable = true, description = "Required for PLAYSTATION, absent otherwise (FR-TRN-04).")
            PlayMode playMode,
            @NotNull @ArraySchema(minItems = 1, maxItems = 4, uniqueItems = true,
                    arraySchema = @Schema(description = "1-4 distinct rooms or tables of the venue (FR-TRN-03)."))
            List<UUID> resourceIds,
            @NotNull @Min(8) @Max(32) @Schema(description = "Participants, 8-32 (FR-TRN-03).", example = "16")
            Integer capacity,
            @NotNull @Schema(description = "At least 2 hours from now (FR-TRN-03).", example = "2026-12-24T16:00:00Z")
            Instant startAt,
            @NotNull @Min(60) @Schema(description = "Nominal minutes: at least 60, in steps of 30 (FR-TRN-03).",
                    example = "180") Integer durationMinutes,
            @NotNull TournamentVisibility visibility,
            @NotNull @Schema(description = "Organizer + Participant (true) or Organizer Only (false); a participating "
                    + "creator counts in the capacity (FR-TRN-05).") Boolean creatorParticipates,
            @Valid @Schema(nullable = true, description = "Informational only (FR-TRN-29); 0.00 when absent.")
            MoneyView entryFee,
            @Valid @Schema(nullable = true, description = "Informational only (FR-TRN-29); 0.00 when absent.")
            MoneyView prize) {
    }

    /** The tournament as its Player Creator sees it right after the request. */
    @Schema(name = "CustomerTournament")
    public record CustomerTournament(
            @NotNull UUID tournamentId,
            @NotNull UUID venueId,
            @NotNull TournamentHostType hostType,
            @NotNull TournamentStatus status,
            @NotNull Boolean creatorParticipates,
            @NotNull Activity activity,
            @Schema(nullable = true) PlayMode playMode,
            @NotNull Integer capacity,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotNull Integer durationMinutes,
            @NotNull @Schema(description = "startAt - 1 hour: the minimum of 8 is checked here (FR-TRN-03).")
            Instant checkpointAt,
            @NotNull @Schema(description = "The venue must answer before this instant (FR-TRN-10); show the countdown "
                    + "from it, never from the device clock.") Instant approvalExpiresAt,
            @NotNull Instant createdAt,
            @NotNull TournamentVisibility visibility,
            @Schema(nullable = true, description = "null while PENDING_VENUE_APPROVAL: the code is inactive until "
                    + "the venue accepts (FR-TRN-07).") String inviteCode,
            @NotNull @Valid @Schema(description = "Fixed at request creation (FR-TRN-28); informational (FR-TRN-29).")
            MoneyView venueCost,
            @NotNull @Valid MoneyView creationFee,
            @NotNull @Valid MoneyView entryFee,
            @NotNull @Valid MoneyView prize,
            @NotNull List<CustomerTournamentResource> resources) {

        static CustomerTournament of(CreatedTournament t) {
            return new CustomerTournament(t.id(), t.venueId(), t.hostType(), t.status(), t.creatorParticipates(),
                    t.activity(), t.playMode(), t.capacity(), t.startAt(), t.endAt(), t.durationMinutes(),
                    t.checkpointAt(), t.approvalExpiresAt(), t.createdAt(), t.visibility(),
                    t.inviteCodeActive() ? t.inviteCode() : null, MoneyView.of(t.venueCostTotal()),
                    MoneyView.of(t.creationFee()), MoneyView.of(t.entryFee()), MoneyView.of(t.prize()),
                    t.resources().stream().map(CustomerTournamentResource::of).toList());
        }
    }

    @Schema(name = "CustomerTournamentResource")
    public record CustomerTournamentResource(
            @NotNull UUID resourceId,
            @Schema(nullable = true, description = "PlayStation only.") ConsoleType consoleType) {

        static CustomerTournamentResource of(CreatedTournament.Resource resource) {
            return new CustomerTournamentResource(resource.resourceId(),
                    resource.consoleType() == null ? null : ConsoleType.valueOf(resource.consoleType()));
        }
    }
}
