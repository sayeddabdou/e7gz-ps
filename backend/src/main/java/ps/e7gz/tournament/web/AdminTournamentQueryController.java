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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
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
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.tournament.internal.TournamentReads;
import ps.e7gz.tournament.internal.TournamentRow;

/**
 * Platform Admin — reading tournaments (arch 09 §5 {@code GET /admin/tournaments}; FR-ADM-08). Sprint 6 contract
 * (E7-88), implemented by E7-126 (S6-E5) on {@link TournamentReads}. The Admin cancel is {@code AdminTournamentController}
 * (E7-93). {@code /{tournamentId}} is added by the PO decision of 26 Nov 2026 (E7-88: the Admin's detail screen,
 * E7-97). Admin DTOs only (arch 09 §1.1).
 */
@RestController
@RequestMapping("/api/v1/admin/tournaments")
@Tag(name = "admin-tournaments")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated
public class AdminTournamentQueryController {

    private final TournamentReads reads;
    private final ResponseMetaFactory meta;

    public AdminTournamentQueryController(TournamentReads reads, ResponseMetaFactory meta) {
        this.reads = reads;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListTournaments",
            summary = "Tournaments of every venue, newest first (arch 09 §5, FR-ADM-08)",
            description = "Ordered by createdAt, newest first; cursor paging (arch 09 §1.2). Every filter is optional. "
                    + "Implemented by E7-126.")
    @ApiError(status = 400, codes = ApiErrorCode.VALIDATION_FAILED)
    public DataResponse<PageView<AdminTournamentListing>> list(
            @Parameter(description = "Only this venue's tournaments.") @RequestParam(required = false) UUID venueId,
            @Parameter(description = "Only this state.") @RequestParam(required = false) TournamentStatus status,
            @Parameter(description = "Only PLAYER or VENUE tournaments.")
            @RequestParam(required = false) TournamentHostType hostType,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        TournamentReads.Page<TournamentReads.Item> page = reads.adminList(venueId, status, hostType, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminTournamentListing::of).toList(),
                page.nextCursor()));
    }

    @GetMapping("/{tournamentId}")
    @Operation(operationId = "adminGetTournament",
            summary = "One tournament with its participants (FR-ADM-08; PO decision 26 Nov 2026)",
            description = "Any tournament, any state. An INELIGIBLE participant shows as INELIGIBLE only, like on the "
                    + "other surfaces (arch 09 §3.4). Implemented by E7-126.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<AdminTournamentDetail> get(@PathVariable UUID tournamentId) {
        return meta.data(AdminTournamentDetail.of(reads.adminDetail(tournamentId)));
    }

    @Schema(name = "AdminTournamentListing")
    public record AdminTournamentListing(
            @NotNull @Schema(example = "3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f") UUID tournamentId,
            @NotNull UUID venueId,
            @NotNull @Schema(example = "Level Up Nasr City") String venueName,
            @NotNull TournamentHostType hostType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The Player Creator of a PLAYER tournament; null for a venue's own.")
            UUID creatorUserId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "omar_k")
            String creatorUsername,
            @NotNull TournamentStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            TournamentTerminalReason terminalReason,
            @NotNull Activity activity,
            @NotNull TournamentVisibility visibility,
            @NotNull Integer capacity,
            @NotNull Integer capacityOccupiedCount,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @NotNull Instant createdAt) {

        static AdminTournamentListing of(TournamentReads.Item item) {
            TournamentRow t = item.row();
            return new AdminTournamentListing(t.id(), t.venueId(), item.venueName(), t.hostType(), t.creatorUserId(),
                    item.creatorUsername(), t.status(), t.terminalReason(), t.activity(), t.visibility(), t.capacity(),
                    t.capacityOccupied(), t.startAt(), t.endAt(), t.createdAt());
        }
    }

    @Schema(name = "AdminTournamentDetail")
    public record AdminTournamentDetail(
            @NotNull UUID tournamentId,
            @NotNull UUID venueId,
            @NotNull String venueName,
            @NotNull TournamentHostType hostType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID creatorUserId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String creatorUsername,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Boolean creatorParticipates,
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
            @NotNull @Valid MoneyView venueCost,
            @NotNull @Valid MoneyView creationFee,
            @NotNull @Valid MoneyView entryFee,
            @NotNull @Valid MoneyView prize,
            @NotNull @Schema(example = "[\"Room 2\", \"Room 3\"]") List<String> roomNames,
            @NotNull List<AdminTournamentParticipant> participants) {

        static AdminTournamentDetail of(TournamentReads.Detail detail) {
            TournamentReads.Item item = detail.item();
            TournamentRow t = item.row();
            return new AdminTournamentDetail(t.id(), t.venueId(), item.venueName(), t.hostType(), t.creatorUserId(),
                    item.creatorUsername(), t.creatorParticipates(), t.status(), t.terminalReason(), t.activity(),
                    t.playMode(), t.visibility(), t.capacity(), t.capacityOccupied(), t.startAt(), t.endAt(),
                    t.durationMinutes(), t.checkpointAt(), item.approvalExpiresAt(), t.createdAt(), t.businessDate(),
                    MoneyView.of(t.venueCost()), MoneyView.of(t.creationFee()), MoneyView.of(t.entryFee()),
                    MoneyView.of(t.prize()), item.rooms().stream().map(TournamentReads.Room::name).toList(),
                    detail.participants().stream().map(p -> new AdminTournamentParticipant(p.userId(), p.username(),
                            p.status(), p.joinedAt())).toList());
        }
    }

    @Schema(name = "AdminTournamentParticipant")
    public record AdminTournamentParticipant(
            @NotNull UUID userId,
            @NotNull @Schema(example = "omar_k") String username,
            @NotNull TournamentParticipantStatus status,
            @NotNull Instant joinedAt) {
    }
}
