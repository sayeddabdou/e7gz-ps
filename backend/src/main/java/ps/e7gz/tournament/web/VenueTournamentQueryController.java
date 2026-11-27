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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.tournament.internal.TournamentReads;
import ps.e7gz.tournament.internal.TournamentRow;

/**
 * Venue Dashboard — reading the venue's tournaments (arch 09 §4.1 {@code GET /venue/tournaments}; FR-TRN-06,
 * FR-TRN-07, FR-TRN-10, FR-TRN-12). Sprint 6 contract (E7-88), implemented by E7-126 (S6-E5) on {@link TournamentReads}. The
 * writes (create, approve, reject, cancel, operational-termination) are in {@link VenueTournamentController} (E7-91,
 * E7-92, E7-93). {@code /{tournamentId}} (with its participants) is added by the PO decision of 26 Nov 2026 (E7-88:
 * the Dashboard's participants view, E7-96). Only the signed-in venue's own tournaments; Venue DTOs only (arch 09
 * §1.1). New requests reach the Dashboard live through the existing TOURNAMENT signal of the venue stream (E7-44).
 */
@RestController
@RequestMapping("/api/v1/venue/tournaments")
@Tag(name = "venue-tournaments")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated
public class VenueTournamentQueryController {

    private final TournamentReads reads;
    private final ResponseMetaFactory meta;

    public VenueTournamentQueryController(TournamentReads reads, ResponseMetaFactory meta) {
        this.reads = reads;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "venueListTournaments",
            summary = "The venue's tournaments: player requests and its own (arch 09 §4.1)",
            description = "ended=false (default): PENDING_VENUE_APPROVAL, WAITING_FOR_PLAYERS, CONFIRMED and ACTIVE, "
                    + "soonest startAt first, so pending requests with their approvalExpiresAt countdown are on top of "
                    + "the day; ended=true: COMPLETED, CANCELLED and REJECTED, newest startAt first. status narrows to "
                    + "one state. Cursor paging (arch 09 §1.2). Implemented by E7-126.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<VenueTournamentListing>> list(
            @Parameter(description = "false (default): not yet ended; true: ended.")
            @RequestParam(required = false) Boolean ended,
            @Parameter(description = "Only this state.") @RequestParam(required = false) TournamentStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            @AuthenticationPrincipal AuthenticatedSubject venue) {
        TournamentReads.Page<TournamentReads.Item> page = reads.venueList(venue.id(), ended, status, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(VenueTournamentListing::of).toList(),
                page.nextCursor()));
    }

    @GetMapping("/{tournamentId}")
    @Operation(operationId = "venueGetTournament",
            summary = "One of the venue's tournaments, with its participants (FR-TRN-06, FR-TRN-07, FR-TRN-12)",
            description = "A tournament of another venue is NOT_FOUND. Visible in every state, a pending request "
                    + "included (FR-TRN-07: the venue may view it). inviteCode is answered for a PRIVATE tournament "
                    + "once accepted, to copy and share (FR-TRN-06). An INELIGIBLE participant shows as INELIGIBLE "
                    + "only; its cause is never answered (arch 09 §3.4, PCR-001). Implemented by E7-126.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<VenueTournamentDetail> get(@PathVariable UUID tournamentId,
                                                  @AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(VenueTournamentDetail.of(reads.venueDetail(venue.id(), tournamentId)));
    }

    /** One row of the Dashboard's tournament list. */
    @Schema(name = "VenueTournamentListing")
    public record VenueTournamentListing(
            @NotNull @Schema(example = "3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f") UUID tournamentId,
            @NotNull TournamentHostType hostType,
            @NotNull TournamentStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Exactly one once CANCELLED or REJECTED (FR-TRN-31, FR-TRN-32); null otherwise.")
            TournamentTerminalReason terminalReason,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "PlayStation only.")
            PlayMode playMode,
            @NotNull TournamentVisibility visibility,
            @NotNull @Schema(example = "16") Integer capacity,
            @NotNull @Schema(description = "JOINED participants plus the slots a banned participant keeps before the "
                    + "start (FR-TRN-12, FR-TRN-26).", example = "9") Integer capacityOccupiedCount,
            @NotNull @Schema(example = "2026-12-24T16:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-12-24T19:00:00Z") Instant endAt,
            @NotNull Instant checkpointAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The answer deadline while PENDING_VENUE_APPROVAL (FR-TRN-10); show the countdown "
                            + "from it and meta.serverTime, never the device clock. null otherwise.")
            Instant approvalExpiresAt,
            @NotNull LocalDate businessDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The Player Creator of a PLAYER tournament; null for the venue's own.",
                    example = "omar_k") String creatorUsername,
            @NotNull @Schema(example = "[\"Room 2\", \"Room 3\"]") List<String> roomNames,
            @NotNull @Valid @Schema(description = "Fixed at creation (FR-TRN-28).") MoneyView venueCost) {

        static VenueTournamentListing of(TournamentReads.Item item) {
            TournamentRow t = item.row();
            return new VenueTournamentListing(t.id(), t.hostType(), t.status(), t.terminalReason(), t.activity(),
                    t.playMode(), t.visibility(), t.capacity(), t.capacityOccupied(), t.startAt(), t.endAt(),
                    t.checkpointAt(), item.approvalExpiresAt(), t.businessDate(), item.creatorUsername(),
                    item.rooms().stream().map(TournamentReads.Room::name).toList(), MoneyView.of(t.venueCost()));
        }
    }

    /** The Dashboard's tournament page. */
    @Schema(name = "VenueTournamentDetail")
    public record VenueTournamentDetail(
            @NotNull UUID tournamentId,
            @NotNull TournamentHostType hostType,
            @NotNull TournamentStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            TournamentTerminalReason terminalReason,
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) PlayMode playMode,
            @NotNull TournamentVisibility visibility,
            @NotNull Integer capacity,
            @NotNull Integer capacityOccupiedCount,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotNull Integer durationMinutes,
            @NotNull Instant checkpointAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant approvalExpiresAt,
            @NotNull Instant createdAt,
            @NotNull LocalDate businessDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String creatorUsername,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "PLAYER tournaments: Organizer + Participant (true) or Organizer Only (false), "
                            + "FR-TRN-05; null for the venue's own.") Boolean creatorParticipates,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "A PRIVATE tournament's code once accepted (FR-TRN-06, FR-TRN-07); null otherwise.",
                    example = "K7Q2M9XD") String inviteCode,
            @NotNull @Valid MoneyView venueCost,
            @NotNull @Valid MoneyView entryFee,
            @NotNull @Valid MoneyView prize,
            @NotNull List<VenueTournamentRoom> rooms,
            @NotNull List<VenueTournamentParticipant> participants) {

        static VenueTournamentDetail of(TournamentReads.Detail detail) {
            TournamentReads.Item item = detail.item();
            TournamentRow t = item.row();
            return new VenueTournamentDetail(t.id(), t.hostType(), t.status(), t.terminalReason(), t.activity(),
                    t.playMode(), t.visibility(), t.capacity(), t.capacityOccupied(), t.startAt(), t.endAt(),
                    t.durationMinutes(), t.checkpointAt(), item.approvalExpiresAt(), t.createdAt(), t.businessDate(),
                    item.creatorUsername(), t.creatorParticipates(), detail.inviteCode(), MoneyView.of(t.venueCost()),
                    MoneyView.of(t.entryFee()), MoneyView.of(t.prize()),
                    item.rooms().stream().map(r -> new VenueTournamentRoom(r.resourceId(), r.name(), r.consoleType(),
                            MoneyView.of(r.hourlyPrice()))).toList(),
                    detail.participants().stream().map(p -> new VenueTournamentParticipant(p.userId(), p.username(),
                            p.status(), p.joinedAt())).toList());
        }
    }

    @Schema(name = "VenueTournamentRoom")
    public record VenueTournamentRoom(
            @NotNull UUID resourceId,
            @NotNull @Schema(example = "Room 2") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "PlayStation only.")
            ConsoleType consoleType,
            @NotNull @Valid @Schema(description = "The hourly price the venue cost was computed from (FR-TRN-28).")
            MoneyView hourlyPrice) {
    }

    /** A participant as the venue sees it: username only, never a phone or an email (arch 09 §1.1). */
    @Schema(name = "VenueTournamentParticipant")
    public record VenueTournamentParticipant(
            @NotNull UUID userId,
            @NotNull @Schema(example = "omar_k") String username,
            @NotNull @Schema(description = "JOINED, LEFT, REMOVED or INELIGIBLE; the cause of INELIGIBLE is never "
                    + "answered (arch 09 §3.4).") TournamentParticipantStatus status,
            @NotNull Instant joinedAt) {
    }
}
