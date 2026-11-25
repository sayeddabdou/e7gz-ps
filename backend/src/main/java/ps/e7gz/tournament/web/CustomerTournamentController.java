package ps.e7gz.tournament.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import ps.e7gz.tournament.internal.CreatedTournament;
import ps.e7gz.tournament.internal.TournamentCreation;
import ps.e7gz.tournament.internal.TournamentRequest;

/**
 * Customer App - Tournaments (arch 09 section 3.4). This controller holds the Player-hosted request of E7-91 (O16 part
 * 1); the other Customer tournament routes are the Sprint 6 contract's (E7-88) and E7-93's. The Customer DTOs are their
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

    private final TournamentCreation creation;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    public CustomerTournamentController(TournamentCreation creation, Idempotency idempotency,
                                        ResponseMetaFactory meta) {
        this.creation = creation;
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
