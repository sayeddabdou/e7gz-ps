package ps.e7gz.trust.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * The customer's own Account Standing (arch 09 §3.1, FR-TRS-15, FR-ACC-11). Stub of the Sprint 4 contract (E7-65)
 * until E7-71 (bans) and E7-72 (Verified Player) fill it from the trust projection. Open to every customer state,
 * banned and unverified included (FR-ACC-17, FRS §2.11).
 */
@RestController
@RequestMapping("/api/v1/customer/account-standing")
@Tag(name = "customer-standing")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class CustomerAccountStandingController {

    @GetMapping
    @Operation(operationId = "customerGetAccountStanding",
            summary = "My Account Standing: no-show count, penalty stage, the current ban and Verified Player",
            description = "Exactly one current ban, or null (FR-TRS-15). The remaining ban time is currentBan.endsAt "
                    + "against meta.serverTime: no remaining seconds are sent (NFR-10). Verified Player qualification "
                    + "progress and operational status are two separate fields (FR-ACC-11). Implemented by E7-71 and "
                    + "E7-72.")
    public DataResponse<CustomerAccountStanding> get() {
        throw NotImplementedYet.stub();
    }

    @Schema(name = "CustomerAccountStanding")
    public record CustomerAccountStanding(
            @NotNull @Schema(description = "Current consecutive eligible no-shows (FR-TRS-11).", example = "1")
            Integer consecutiveNoShows,
            @NotNull @Schema(description = "Penalty stage of the ladder, from 0 (FR-TRS-13).", example = "0")
            Integer penaltyStage,
            @NotNull @Schema(description = "No-shows in a row that start the next ban at this stage (FR-TRS-13).",
                    example = "3") Integer currentThreshold,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The one ACTIVE ban, or null.") CustomerCurrentBan currentBan,
            @NotNull CustomerVerifiedPlayer verifiedPlayer) {
    }

    /** The ACTIVE Ban Instance (FR-TRS-17): when it started and when it ends, both instants. */
    @Schema(name = "CustomerCurrentBan")
    public record CustomerCurrentBan(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID banId,
            @NotNull @Schema(example = "0") Integer penaltyStage,
            @NotNull @Schema(description = "activatedAt: never back-dated (FR-TRS-18).",
                    example = "2026-11-02T18:00:00Z") Instant startsAt,
            @NotNull @Schema(example = "2026-11-09T18:00:00Z") Instant endsAt) {
    }

    /** Verified Player (FR-ACC-11): progress such as 6 / 10, and the operational status. */
    @Schema(name = "CustomerVerifiedPlayer")
    public record CustomerVerifiedPlayer(
            @NotNull VerifiedPlayerQualification qualification,
            @NotNull @Schema(description = "Completed attended bookings in corrected trust history.", example = "6")
            Integer completedBookings,
            @NotNull @Schema(description = "Completed attended bookings needed to qualify (10, FR-ACC-11).",
                    example = "10") Integer requiredBookings,
            @NotNull @Schema(description = "Only VERIFIED may create a player-hosted tournament.")
            VerifiedPlayerStatus status) {
    }
}
