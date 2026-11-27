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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
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
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.error.ApiErrorCode;
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
import ps.e7gz.tournament.internal.AnsweredTournament;
import ps.e7gz.tournament.internal.CreatedTournament;
import ps.e7gz.tournament.internal.TournamentApproval;
import ps.e7gz.tournament.internal.TournamentCancellation;
import ps.e7gz.tournament.internal.TournamentCreation;
import ps.e7gz.tournament.internal.TournamentRequest;

/**
 * Venue Dashboard - Tournaments (arch 09 section 4.2). This controller holds the Venue-hosted creation of E7-91 (O16
 * part 1) and the answer to a Player's request of E7-92 (approve, reject) and the cancel and operational termination
 * of E7-93; the list is the Sprint 6 contract's (E7-88). Every route acts for the signed-in account's own Venue, never one named by the request. The Venue DTOs
 * are their own (arch 09 section 1.1). Creation holds rooms, so the Idempotency-Key is required (ADR-015); it needs no
 * Owner Password (FR-TRN-24 lists only the cancellations).
 */
@RestController
@RequestMapping("/api/v1/venue/tournaments")
@Tag(name = "venue-tournaments")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueTournamentController {

    /** {@code technical.idempotency_record.endpoint} (arch 08 section 6). */
    static final String CREATE_ENDPOINT = "POST /api/v1/venue/tournaments";
    static final String APPROVE_ENDPOINT = "POST /api/v1/venue/tournaments/{tournamentId}/approve";
    static final String REJECT_ENDPOINT = "POST /api/v1/venue/tournaments/{tournamentId}/reject";
    static final String CANCEL_ENDPOINT = "POST /api/v1/venue/tournaments/{tournamentId}/cancel";
    static final String TERMINATE_ENDPOINT = "POST /api/v1/venue/tournaments/{tournamentId}/operational-termination";
    private static final String TOURNAMENT_ID = "tournamentId";

    private final TournamentCreation creation;
    private final TournamentApproval approval;
    private final TournamentCancellation cancellation;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    public VenueTournamentController(TournamentCreation creation, TournamentApproval approval,
                                     TournamentCancellation cancellation, Idempotency idempotency,
                                     ResponseMetaFactory meta) {
        this.creation = creation;
        this.approval = approval;
        this.cancellation = cancellation;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueCreateTournament",
            summary = "Create a venue-hosted tournament: WAITING_FOR_PLAYERS with Tournament Holds (FR-TRN-02, "
                    + "FR-TRN-03, FR-TRN-09)",
            description = "No Verified Player and no approval needed (FR-TRN-02); no Owner Password. 8-32 "
                    + "participants, 1-4 distinct rooms or tables of this venue and the activity, at least 60 "
                    + "minutes in 30-minute steps, playMode exactly for PLAYSTATION (VALIDATION_FAILED on the field "
                    + "otherwise). The whole interval must fit one Operating Session (FR-TRN-04: "
                    + "INTERVAL_NOT_WITHIN_ONE_SESSION), created at least 2 hours before startAt "
                    + "(TOURNAMENT_CREATION_CUTOFF_PASSED). Existing reservations always win (FR-TRN-08): a room with "
                    + "a booking, a hold, maintenance, a closure or an emergency in the interval is 409 "
                    + "TOURNAMENT_RESOURCE_CONFLICT and nothing is changed - a tournament never cancels a booking; a "
                    + "room not ACTIVE or under owner review is RESOURCE_NOT_ELIGIBLE (also when it has no price, "
                    + "FR-VEN-45); a selling block is VENUE_SELLING_BLOCK_ACTIVE / RESOURCE_SELLING_BLOCK_ACTIVE "
                    + "(FR-TRN-33). A venue not ACTIVE in the marketplace is VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY; an "
                    + "account disabled meanwhile is FORBIDDEN (the token itself stops working: 401). A room of "
                    + "another venue is NOT_FOUND. The venue cost is fixed now (FR-TRN-28); entryFee and prize are "
                    + "informational (FR-TRN-29), 0.00 when absent. A private tournament gets its inviteCode at once "
                    + "(FR-TRN-06). Implemented by E7-91.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.TOURNAMENT_RESOURCE_CONFLICT)
    @ApiError(status = 422, codes = {ApiErrorCode.TOURNAMENT_CREATION_CUTOFF_PASSED,
            ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY,
            ApiErrorCode.RESOURCE_NOT_ELIGIBLE, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE,
            ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public ResponseEntity<DataResponse<VenueTournament>> create(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody VenueTournamentCreateRequest request,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        TournamentRequest tournament = TournamentRequest.of(request.activity(), request.playMode(),
                request.resourceIds(), request.capacity(), request.startAt(), request.durationMinutes(),
                request.visibility(), CustomerTournamentController.money(request.entryFee()),
                CustomerTournamentController.money(request.prize()));
        StoredResponse<VenueTournament> response = StoredResponse.of(VenueTournament.class, 201,
                (VenueTournament created) -> new ResultAggregate(CustomerTournamentController.TOURNAMENT_AGGREGATE,
                        created.tournamentId()));
        IdempotentResult<VenueTournament> result = creation.createByVenue(venue.id(), tournament,
                idempotency.request(venue, CREATE_ENDPOINT, idempotencyKey, Map.of(), request),
                response, VenueTournament::of);
        return ResponseEntity.status(result.status()).body(meta.data(result.body()));
    }

    @PostMapping("/{tournamentId}/approve")
    @Operation(operationId = "venueApproveTournament",
            summary = "Approve a player's tournament request: WAITING_FOR_PLAYERS, its Approval Holds become "
                    + "Tournament Holds (FR-TRN-10, FR-TRN-09)",
            description = "Only while the request is PENDING_VENUE_APPROVAL and now < approvalExpiresAt = "
                    + "MIN(createdAt + 10 minutes, checkpointAt). At or after approvalExpiresAt it is DEADLINE_PASSED, "
                    + "also before the timeout has been applied (the boundary is the deadline instant); a request the "
                    + "timeout already cancelled is DEADLINE_PASSED too. A tournament no longer pending (already "
                    + "answered, cancelled, or venue-hosted) is OPERATIONAL_STATE_CHANGED. The holds are converted in "
                    + "the same transaction, so the rooms are never free in between. A private tournament's invite "
                    + "code becomes active now (FR-TRN-07). No Owner Password (FR-TRN-24). A venue account that is not "
                    + "ENABLED is FORBIDDEN. A tournament of another venue is NOT_FOUND. The creator is notified "
                    + "(TournamentApproved). Implemented by E7-92.")
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueTournamentAnswer> approve(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        IdempotentResult<VenueTournamentAnswer> result = approval.approve(venue.id(), tournamentId,
                idempotency.request(venue, APPROVE_ENDPOINT, idempotencyKey, Map.of(TOURNAMENT_ID, tournamentId), null),
                answerResponse(), VenueTournamentAnswer::of);
        return meta.data(result.body());
    }

    @PostMapping("/{tournamentId}/reject")
    @Operation(operationId = "venueRejectTournament",
            summary = "Reject a player's tournament request: REJECTED / VENUE_REJECTED_TOURNAMENT (FR-TRN-10)",
            description = "Only while the request is PENDING_VENUE_APPROVAL and now < approvalExpiresAt; at or after "
                    + "it, or once the timeout cancelled it, DEADLINE_PASSED. A tournament no longer pending is "
                    + "OPERATIONAL_STATE_CHANGED. The Approval Holds are released at once. The creator may not request "
                    + "the same venue and interval again for 5 minutes (FR-TRN-11). No reason text and no Owner "
                    + "Password (FR-TRN-24). A tournament of another venue is NOT_FOUND. The creator is notified "
                    + "(TournamentRejected). Implemented by E7-92.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.DEADLINE_PASSED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueTournamentAnswer> reject(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        IdempotentResult<VenueTournamentAnswer> result = approval.reject(venue.id(), tournamentId,
                idempotency.request(venue, REJECT_ENDPOINT, idempotencyKey, Map.of(TOURNAMENT_ID, tournamentId), null),
                answerResponse(), VenueTournamentAnswer::of);
        return meta.data(result.body());
    }

    @PostMapping("/{tournamentId}/cancel")
    @Operation(operationId = "venueCancelTournament",
            summary = "Cancel a tournament before it starts: CANCELLED / VENUE_CANCELLED (FR-TRN-21, FR-TRN-24)",
            description = "WAITING_FOR_PLAYERS: no Owner PIN. CONFIRMED: the Owner PIN in the body, never a header "
                    + "(ADR-011) - absent or blank is OWNER_PASSWORD_REQUIRED, wrong is OWNER_PASSWORD_INVALID, every "
                    + "attempt audited; a tournament the checkpoint confirms while the request is on its way answers "
                    + "OWNER_PASSWORD_REQUIRED too. A request the venue has not approved is rejected, not cancelled, "
                    + "and an ACTIVE tournament is stopped by operational-termination: both, like a tournament that "
                    + "has ended or one an emergency or owner review is cancelling, are OPERATIONAL_STATE_CHANGED. "
                    + "Every room hold is released. A tournament of another venue is NOT_FOUND. Implemented by E7-93.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID,
            ApiErrorCode.OWNER_PASSWORD_LOCKED})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueTournamentCancellation> cancel(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @RequestBody(required = false) VenueTournamentCancelRequest request,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        VenueTournamentCancelRequest body = request == null ? new VenueTournamentCancelRequest(null) : request;
        // The Owner PIN enters the fingerprint only as an HMAC (Idempotency#request) and is never stored
        return meta.data(cancellation.cancelByVenue(venue.id(), tournamentId, body.ownerPassword(),
                idempotency.request(venue, CANCEL_ENDPOINT, idempotencyKey, Map.of(TOURNAMENT_ID, tournamentId), body),
                cancellationResponse(), VenueTournamentCancellation::of).body());
    }

    @PostMapping("/{tournamentId}/operational-termination")
    @Operation(operationId = "venueTerminateTournament",
            summary = "Tournament Operational Termination: ACTIVE -> CANCELLED / TOURNAMENT_OPERATIONAL_TERMINATION "
                    + "(FR-TRN-23)",
            description = "For a tournament-specific incident that does not need a venue closure. Only while ACTIVE "
                    + "(OPERATIONAL_STATE_CHANGED otherwise). The Owner PIN and a reason are both mandatory: an "
                    + "absent PIN is OWNER_PASSWORD_REQUIRED, a wrong one OWNER_PASSWORD_INVALID, a blank reason "
                    + "VALIDATION_FAILED; the reason is kept in the audit. The remaining room holds are released. A "
                    + "tournament of another venue is NOT_FOUND. Implemented by E7-93.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID,
            ApiErrorCode.OWNER_PASSWORD_LOCKED})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueTournamentCancellation> terminate(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @Valid @RequestBody VenueTournamentTerminationRequest request,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(cancellation.terminate(venue.id(), tournamentId, request.ownerPassword(), request.reason(),
                idempotency.request(venue, TERMINATE_ENDPOINT, idempotencyKey, Map.of(TOURNAMENT_ID, tournamentId),
                        request),
                cancellationResponse(), VenueTournamentCancellation::of).body());
    }

    private static StoredResponse<VenueTournamentCancellation> cancellationResponse() {
        return StoredResponse.of(VenueTournamentCancellation.class, 200,
                (VenueTournamentCancellation c) -> new ResultAggregate(CustomerTournamentController.TOURNAMENT_AGGREGATE,
                        c.tournamentId()));
    }

    /** FR-TRN-24, ADR-011: the Owner PIN travels in the body; a WAITING_FOR_PLAYERS cancel needs none. */
    @Schema(name = "VenueTournamentCancelRequest")
    public record VenueTournamentCancelRequest(
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, nullable = true,
                    description = "4-digit Owner PIN; required only when the tournament is CONFIRMED.")
            String ownerPassword) {

        @Override
        public String toString() {
            return "VenueTournamentCancelRequest[<redacted>]";
        }
    }

    /** FR-TRN-23: the Owner PIN and a mandatory reason. */
    @Schema(name = "VenueTournamentTerminationRequest")
    public record VenueTournamentTerminationRequest(
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword,
            @NotBlank @Size(min = 1, max = 500) @Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL)
            @Schema(example = "Power failure in the tournament hall") String reason) {

        @Override
        public String toString() {
            return "VenueTournamentTerminationRequest[<redacted>]";
        }
    }

    /** The tournament after the venue's cancellation or termination. */
    @Schema(name = "VenueTournamentCancellation")
    public record VenueTournamentCancellation(
            @NotNull UUID tournamentId,
            @NotNull @Schema(description = "CANCELLED.") TournamentStatus status,
            @NotNull @Schema(description = "VENUE_CANCELLED, or TOURNAMENT_OPERATIONAL_TERMINATION.")
            TournamentTerminalReason terminalReason) {

        static VenueTournamentCancellation of(AnsweredTournament cancelled) {
            return new VenueTournamentCancellation(cancelled.id(), cancelled.status(), cancelled.terminalReason());
        }
    }

    private static StoredResponse<VenueTournamentAnswer> answerResponse() {
        return StoredResponse.of(VenueTournamentAnswer.class, 200,
                (VenueTournamentAnswer answer) -> new ResultAggregate(CustomerTournamentController.TOURNAMENT_AGGREGATE,
                        answer.tournamentId()));
    }

    /** The request after the venue's answer. */
    @Schema(name = "VenueTournamentAnswer")
    public record VenueTournamentAnswer(
            @NotNull UUID tournamentId,
            @NotNull @Schema(description = "WAITING_FOR_PLAYERS once approved, REJECTED once rejected.")
            TournamentStatus status,
            @Schema(nullable = true, description = "VENUE_REJECTED_TOURNAMENT once rejected; null once approved.")
            TournamentTerminalReason terminalReason) {

        static VenueTournamentAnswer of(AnsweredTournament answered) {
            return new VenueTournamentAnswer(answered.id(), answered.status(), answered.terminalReason());
        }
    }

    @Schema(name = "VenueTournamentCreateRequest")
    public record VenueTournamentCreateRequest(
            @NotNull Activity activity,
            @Schema(nullable = true, description = "Required for PLAYSTATION, absent otherwise (FR-TRN-04).")
            PlayMode playMode,
            @NotNull @ArraySchema(minItems = 1, maxItems = 4, uniqueItems = true,
                    arraySchema = @Schema(description = "1-4 distinct rooms or tables of this venue (FR-TRN-03)."))
            List<UUID> resourceIds,
            @NotNull @Min(8) @Max(32) @Schema(description = "Participants, 8-32 (FR-TRN-03).", example = "16")
            Integer capacity,
            @NotNull @Schema(description = "At least 2 hours from now (FR-TRN-03).", example = "2026-12-24T16:00:00Z")
            Instant startAt,
            @NotNull @Min(60) @Schema(description = "Nominal minutes: at least 60, in steps of 30 (FR-TRN-03).",
                    example = "180") Integer durationMinutes,
            @NotNull TournamentVisibility visibility,
            @Valid @Schema(nullable = true, description = "Informational only (FR-TRN-29); 0.00 when absent.")
            MoneyView entryFee,
            @Valid @Schema(nullable = true, description = "Informational only (FR-TRN-29); 0.00 when absent.")
            MoneyView prize) {
    }

    /** The venue-hosted tournament as the Dashboard sees it right after creation. */
    @Schema(name = "VenueTournament")
    public record VenueTournament(
            @NotNull UUID tournamentId,
            @NotNull TournamentHostType hostType,
            @NotNull TournamentStatus status,
            @NotNull Activity activity,
            @Schema(nullable = true) PlayMode playMode,
            @NotNull Integer capacity,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotNull Integer durationMinutes,
            @NotNull @Schema(description = "startAt - 1 hour: the minimum of 8 is checked here (FR-TRN-03).")
            Instant checkpointAt,
            @NotNull Instant createdAt,
            @NotNull @Schema(description = "The operating day of the Operating Session it fits in; revenue is "
                    + "recognised on it when the tournament completes (FR-TRN-30).") LocalDate businessDate,
            @NotNull TournamentVisibility visibility,
            @Schema(nullable = true, description = "The private tournament's code, to copy and share (FR-TRN-06); "
                    + "null when public.") String inviteCode,
            @NotNull @Valid @Schema(description = "Fixed at creation (FR-TRN-28); informational (FR-TRN-29).")
            MoneyView venueCost,
            @NotNull @Valid MoneyView entryFee,
            @NotNull @Valid MoneyView prize,
            @NotNull List<VenueTournamentResource> resources) {

        static VenueTournament of(CreatedTournament t) {
            return new VenueTournament(t.id(), t.hostType(), t.status(), t.activity(), t.playMode(), t.capacity(),
                    t.startAt(), t.endAt(), t.durationMinutes(), t.checkpointAt(), t.createdAt(), t.businessDate(),
                    t.visibility(), t.inviteCodeActive() ? t.inviteCode() : null, MoneyView.of(t.venueCostTotal()),
                    MoneyView.of(t.entryFee()), MoneyView.of(t.prize()),
                    t.resources().stream().map(VenueTournamentResource::of).toList());
        }
    }

    @Schema(name = "VenueTournamentResource")
    public record VenueTournamentResource(
            @NotNull UUID resourceId,
            @Schema(nullable = true, description = "PlayStation only.") ConsoleType consoleType,
            @NotNull @Valid @Schema(description = "The hourly price snapshot the venue cost was computed from "
                    + "(FR-TRN-28).") MoneyView hourlyPrice) {

        static VenueTournamentResource of(CreatedTournament.Resource resource) {
            return new VenueTournamentResource(resource.resourceId(),
                    resource.consoleType() == null ? null : ConsoleType.valueOf(resource.consoleType()),
                    MoneyView.of(resource.hourlyPrice()));
        }
    }
}
