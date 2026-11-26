package ps.e7gz.tournament.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;

/**
 * Customer — reading tournaments (arch 09 §3.4; FR-TRN-06, FR-TRN-07, FR-TRN-12, FR-TRN-13). Sprint 6 contract (E7-88),
 * stubs answering 501 until they are implemented. The writes (create, join, leave, remove, cancel) are in
 * {@link CustomerTournamentController} (E7-91, E7-93); the reads sit here so the contract does not touch those files.
 * GET {@code /customer/tournaments} is arch 09's public Discover; {@code /mine} and {@code /{tournamentId}} are added by
 * the PO decision of 26 Nov 2026 (E7-88: the app shows its own tournaments and a detail screen, and FR-TRN-07 lets the
 * creator view a request still pending). Customer DTOs only (arch 09 §1.1).
 */
@RestController
@RequestMapping("/api/v1/customer/tournaments")
@Tag(name = "customer-tournaments")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated
public class CustomerTournamentQueryController {

    @GetMapping
    @Operation(operationId = "customerDiscoverTournaments",
            summary = "Public Discover: public tournaments open to join, soonest first (FR-TRN-06, FR-TRN-07)",
            description = "Only PUBLIC tournaments in WAITING_FOR_PLAYERS or CONFIRMED with now < startAt, at venues "
                    + "that are live. A private tournament, a request still PENDING_VENUE_APPROVAL and an ended "
                    + "tournament never appear (FR-TRN-07). Ordered by startAt, then id; cursor paging (arch 09 §1.2). "
                    + "A full tournament is still listed (capacityOccupiedCount = capacity) so the app can show it as "
                    + "full. Sprint 6 contract stub (E7-88).")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<CustomerTournamentListing>> discover(
            @Parameter(description = "Only tournaments at venues of this area.")
            @RequestParam(required = false) UUID areaId,
            @Parameter(description = "Only this activity.") @RequestParam(required = false) Activity activity,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/mine")
    @Operation(operationId = "customerListMyTournaments",
            summary = "The signed-in customer's tournaments: the ones they created or joined (PO decision 26 Nov 2026)",
            description = "Every tournament the customer created (any state, PENDING_VENUE_APPROVAL included, "
                    + "FR-TRN-07) or has a participation in (JOINED, LEFT, REMOVED or INELIGIBLE). ended=false "
                    + "(default): not yet ended, soonest first; ended=true: COMPLETED, CANCELLED and REJECTED, newest "
                    + "first. Cursor paging (arch 09 §1.2).")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<CustomerTournamentListing>> mine(
            @Parameter(description = "false (default): not yet ended; true: ended.")
            @RequestParam(required = false) Boolean ended,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/{tournamentId}")
    @Operation(operationId = "customerGetTournament",
            summary = "One tournament as this customer may see it (FR-TRN-06, FR-TRN-07; PO decision 26 Nov 2026)",
            description = "Visible to its creator and to anyone with a participation in it, in any state. A PUBLIC "
                    + "tournament is also visible to any customer once the venue has accepted it. Anything else - a "
                    + "private tournament the customer is not in, a request still pending for someone else - is "
                    + "NOT_FOUND, never FORBIDDEN, so its existence is not revealed (FR-TRN-07). inviteCode and "
                    + "participants are answered only to the creator (FR-TRN-06, FR-TRN-12: the organizer shares the "
                    + "code and removes participants); null for everyone else. An INELIGIBLE participant shows as "
                    + "INELIGIBLE only: its cause is never answered (arch 09 §3.4, PCR-001).")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<CustomerTournamentDetail> get(@PathVariable UUID tournamentId) {
        throw NotImplementedYet.stub();
    }

    /** One row of Discover or of the customer's own list. */
    @Schema(name = "CustomerTournamentListing")
    public record CustomerTournamentListing(
            @NotNull @Schema(example = "3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f") UUID tournamentId,
            @NotNull @Schema(example = "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e") UUID venueId,
            @NotNull @Schema(example = "Level Up Nasr City") String venueName,
            @NotNull TournamentHostType hostType,
            @NotNull TournamentStatus status,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "PlayStation only.")
            PlayMode playMode,
            @NotNull @Schema(example = "16") Integer capacity,
            @NotNull @Schema(description = "JOINED participants plus the slots a banned participant keeps before the "
                    + "start (FR-TRN-12, FR-TRN-26); equal to capacity when full.", example = "9")
            Integer capacityOccupiedCount,
            @NotNull @Schema(example = "2026-12-24T16:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-12-24T19:00:00Z") Instant endAt,
            @NotNull @Valid @Schema(description = "Informational only (FR-TRN-29).") MoneyView entryFee,
            @NotNull @Valid @Schema(description = "Informational only (FR-TRN-29).") MoneyView prize,
            @NotNull @Schema(description = "The signed-in customer created it.") Boolean createdByMe,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The signed-in customer's own participation; null when they have none.")
            TournamentParticipantStatus myParticipantStatus) {
    }

    /** The tournament detail screen. */
    @Schema(name = "CustomerTournamentDetail")
    public record CustomerTournamentDetail(
            @NotNull UUID tournamentId,
            @NotNull UUID venueId,
            @NotNull String venueName,
            @NotNull TournamentHostType hostType,
            @NotNull TournamentStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Exactly one once CANCELLED or REJECTED (FR-TRN-31, FR-TRN-32); null otherwise.")
            TournamentTerminalReason terminalReason,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) PlayMode playMode,
            @NotNull TournamentVisibility visibility,
            @NotNull Integer capacity,
            @NotNull Integer capacityOccupiedCount,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotNull Integer durationMinutes,
            @NotNull @Schema(description = "startAt - 1 hour: the minimum of 8 is checked here (FR-TRN-14).")
            Instant checkpointAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The venue's answer deadline while PENDING_VENUE_APPROVAL (FR-TRN-10), shown to the "
                            + "creator as a countdown from this instant and meta.serverTime; null otherwise.")
            Instant approvalExpiresAt,
            @NotNull @Valid MoneyView entryFee,
            @NotNull @Valid MoneyView prize,
            @NotNull Boolean createdByMe,
            @NotNull @Schema(description = "Organizer + Participant (true) or Organizer Only (false), FR-TRN-05.")
            Boolean creatorParticipates,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            TournamentParticipantStatus myParticipantStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "To the creator of a PRIVATE tournament once the venue accepted it (FR-TRN-06, "
                            + "FR-TRN-07); null otherwise.", example = "K7Q2M9XD") String inviteCode,
            @NotNull List<CustomerTournamentRoom> rooms,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "To the creator only (FR-TRN-12: the organizer removes participants); null for "
                            + "everyone else.") List<CustomerTournamentParticipant> participants) {
    }

    @Schema(name = "CustomerTournamentRoom")
    public record CustomerTournamentRoom(
            @NotNull UUID resourceId,
            @NotNull @Schema(example = "Room 2") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "PlayStation only.")
            ConsoleType consoleType) {
    }

    /** A participant as the organizer sees it: never a phone or an email (contact visibility, arch 09 §1.1). */
    @Schema(name = "CustomerTournamentParticipant")
    public record CustomerTournamentParticipant(
            @NotNull UUID userId,
            @NotNull @Schema(example = "omar_k") String username,
            @NotNull @Schema(description = "JOINED, LEFT, REMOVED or INELIGIBLE; the cause of INELIGIBLE is never "
                    + "answered (arch 09 §3.4).") TournamentParticipantStatus status,
            @NotNull Instant joinedAt) {
    }
}
