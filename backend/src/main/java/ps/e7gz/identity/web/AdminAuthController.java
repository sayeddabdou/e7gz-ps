package ps.e7gz.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.identity.internal.AuthSessions;

/**
 * Platform Admin sessions (arch 10 section 2.1; arch 09 section 2: same token model as the customer). An inactive
 * admin cannot log in, and its tokens stop working at once. Public: no access token.
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@Tag(name = "admin-auth")
public class AdminAuthController {

    static final int MAX_TEXT = 200;

    private final AuthSessions sessions;
    private final ResponseMetaFactory meta;

    public AdminAuthController(AuthSessions sessions, ResponseMetaFactory meta) {
        this.sessions = sessions;
        this.meta = meta;
    }

    @PostMapping("/login")
    @Operation(operationId = "adminLogin", summary = "Log in to Platform Admin (arch 10 section 2.1)")
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    public DataResponse<AdminAuthTokens> login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest http) {
        return meta.data(AdminAuthTokens.of(sessions.login(
                AuthSubjectType.ADMIN, request.email(), request.password(), http.getRemoteAddr())));
    }

    @PostMapping("/refresh")
    @Operation(operationId = "adminRefresh",
            summary = "Exchange the refresh token for a new pair; the old refresh token stops working (arch 10 "
                    + "section 2.3)")
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    public DataResponse<AdminAuthTokens> refresh(@Valid @RequestBody AdminRefreshTokenRequest request) {
        return meta.data(AdminAuthTokens.of(sessions.refresh(AuthSubjectType.ADMIN, request.refreshToken())));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminLogout", summary = "End the session of this refresh token (arch 10 section 2.3)")
    public void logout(@Valid @RequestBody AdminRefreshTokenRequest request) {
        sessions.logout(AuthSubjectType.ADMIN, request.refreshToken());
    }

    @Schema(name = "AdminLoginRequest")
    public record AdminLoginRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(example = "admin@e7gz.example") String email,
            @NotBlank @Size(max = MAX_TEXT) @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
            String password) {

        @Override
        public String toString() {
            return "AdminLoginRequest[<redacted>]";
        }
    }

    /** Refresh and logout. */
    @Schema(name = "AdminRefreshTokenRequest")
    public record AdminRefreshTokenRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String refreshToken) {

        @Override
        public String toString() {
            return "AdminRefreshTokenRequest[<redacted>]";
        }
    }

    /** Arch 09 section 2: {@code {accessToken, refreshToken}}. */
    @Schema(name = "AdminAuthTokens")
    public record AdminAuthTokens(
            @NotNull @Schema(description = "Access JWT, 15 minutes; send as Authorization: Bearer.") String accessToken,
            @NotNull @Schema(description = "Opaque refresh token, 12 hours; rotated on every refresh.")
            String refreshToken) {

        static AdminAuthTokens of(AuthSessions.TokenPair tokens) {
            return new AdminAuthTokens(tokens.accessToken(), tokens.refreshToken());
        }

        @Override
        public String toString() {
            return "AdminAuthTokens[<redacted>]";
        }
    }
}
