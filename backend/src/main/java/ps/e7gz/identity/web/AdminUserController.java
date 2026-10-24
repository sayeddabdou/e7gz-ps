package ps.e7gz.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.identity.internal.AdminUsers;
import ps.e7gz.trust.api.AccountStandings;

/**
 * Platform Admin user management (arch 09 §5 "Users", FR-ADM-04): find a customer and see their standing before a trust
 * or points action. In the Sprint 4 contract by PO decision D4 (17 Oct 2026); implemented by E7-66 (PO decision 19 Oct
 * 2026, PR 2). The detail reads standing, Verified Player, points and wallet through those modules' api packages.
 * Anonymisation and deletion requests are Sprint 7's.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "admin-users")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminUserController {

    private final AdminUsers users;
    private final ResponseMetaFactory meta;

    AdminUserController(AdminUsers users, ResponseMetaFactory meta) {
        this.users = users;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListUsers", summary = "Customers, newest first (FR-ADM-04)",
            description = "q matches part of the full name, username or email, or the exact phone, trimmed and "
                    + "case-insensitive. Cursor paging (arch 09 §1.2).")
    public DataResponse<PageView<AdminUserSummary>> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        AdminUsers.Page page = users.list(q, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminUserSummary::of).toList(), page.nextCursor()));
    }

    @GetMapping("/{userId}")
    @Operation(operationId = "adminGetUser",
            summary = "One customer: profile, standing, active ban, Verified Player, points and wallet (FR-ADM-04)",
            description = "activeBan is the one ACTIVE ban, or null; its banId is what the ban routes take.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    public DataResponse<AdminUserDetail> get(@PathVariable UUID userId) {
        return meta.data(AdminUserDetail.of(users.detail(userId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND))));
    }

    /** The FR-ADM-04 columns. Account status is banned and deleted (anonymised), each a flag. */
    @Schema(name = "AdminUserSummary")
    public record AdminUserSummary(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID userId,
            @NotNull @Schema(example = "Omar Hassan") String fullName,
            @NotNull @Schema(example = "omar_h") String username,
            @NotNull @Schema(example = "+201001234567") String phone,
            @NotNull @Schema(example = "omar@example.com") String email,
            @NotNull Boolean phoneVerified,
            @NotNull VerifiedPlayerStatus verifiedPlayerStatus,
            @NotNull @Schema(description = "Completed attended bookings (FR-ACC-11).", example = "6")
            Integer completedBookings,
            @NotNull @Schema(example = "1") Integer consecutiveNoShows,
            @NotNull @Schema(example = "0") Integer penaltyStage,
            @NotNull @Schema(description = "An ACTIVE ban now.") Boolean banned,
            @NotNull @Schema(description = "Anonymised (FR-ACC-10).") Boolean deleted,
            @NotNull @Schema(example = "2026-09-30T12:00:00Z") Instant joinedAt) {

        /** An anonymised account has no phone any more (FR-ACC-10): it is shown empty, like its name and email. */
        static AdminUserSummary of(AdminUsers.User user) {
            AdminUsers.Account account = user.account();
            return new AdminUserSummary(account.userId(), account.fullName(), account.username(),
                    account.phone() == null ? "" : account.phone(), account.email(), account.phoneVerified(),
                    user.verifiedPlayer().status(), user.verifiedPlayer().completedBookings(),
                    user.standing().consecutiveNoShows(), user.standing().penaltyStage(), user.standing().banned(),
                    account.deleted(), account.joinedAt());
        }
    }

    @Schema(name = "AdminUserDetail")
    public record AdminUserDetail(
            @NotNull AdminUserSummary user,
            @NotNull Boolean emailVerified,
            @NotNull @Schema(example = "3") Integer currentThreshold,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AdminUserActiveBan activeBan,
            @NotNull VerifiedPlayerQualification verifiedPlayerQualification,
            @NotNull @Schema(example = "1200") Integer pointsBalance,
            @NotNull MoneyView walletBalance,
            @NotNull @Schema(example = "K7M3QX9P") String referralCode) {

        static AdminUserDetail of(AdminUsers.Detail detail) {
            AdminUsers.User user = detail.user();
            return new AdminUserDetail(AdminUserSummary.of(user), user.account().emailVerified(),
                    user.standing().currentThreshold(), AdminUserActiveBan.of(user.standing().activeBan()),
                    user.verifiedPlayer().qualification(), detail.balances().points(),
                    MoneyView.of(detail.balances().wallet()), user.account().referralCode());
        }
    }

    @Schema(name = "AdminUserActiveBan")
    public record AdminUserActiveBan(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID banId,
            @NotNull @Schema(example = "0") Integer penaltyStage,
            @NotNull @Schema(example = "2026-11-02T18:00:00Z") Instant activatedAt,
            @NotNull @Schema(example = "2026-11-09T18:00:00Z") Instant endsAt) {

        /** {@code null} when no ban is in force. */
        static AdminUserActiveBan of(AccountStandings.Ban ban) {
            return ban == null ? null
                    : new AdminUserActiveBan(ban.banId(), ban.penaltyStage(), ban.activatedAt(), ban.endsAt());
        }
    }
}
