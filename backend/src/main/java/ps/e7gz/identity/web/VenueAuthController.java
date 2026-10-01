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
 * Venue Dashboard sessions for the one shared venue account (arch 10 section 2.1, FR-VEN-01; arch 09 section 2: same
 * token model as the customer). A DISABLED account cannot log in, and its tokens stop working at once (FR-VEN-08).
 * There is no venue forgot-password flow (FR-ADM-03). Public: no access token.
 */
@RestController
@RequestMapping("/api/v1/venue/auth")
@Tag(name = "venue-auth")
public class VenueAuthController {

    static final int MAX_TEXT = 200;

    private final AuthSessions sessions;
    private final ResponseMetaFactory meta;

    public VenueAuthController(AuthSessions sessions, ResponseMetaFactory meta) {
        this.sessions = sessions;
        this.meta = meta;
    }

    @PostMapping("/login")
    @Operation(operationId = "venueLogin", summary = "Log in to the Venue Dashboard (arch 10 section 2.1, FR-VEN-08)")
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    public DataResponse<VenueAuthTokens> login(@Valid @RequestBody VenueLoginRequest request, HttpServletRequest http) {
        return meta.data(VenueAuthTokens.of(sessions.login(
                AuthSubjectType.VENUE, request.loginIdentifier(), request.password(), http.getRemoteAddr())));
    }

    @PostMapping("/refresh")
    @Operation(operationId = "venueRefresh",
            summary = "Exchange the refresh token for a new pair; the old refresh token stops working (arch 10 "
                    + "section 2.3)")
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    public DataResponse<VenueAuthTokens> refresh(@Valid @RequestBody VenueRefreshTokenRequest request) {
        return meta.data(VenueAuthTokens.of(sessions.refresh(AuthSubjectType.VENUE, request.refreshToken())));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueLogout", summary = "End the session of this refresh token (arch 10 section 2.3)")
    public void logout(@Valid @RequestBody VenueRefreshTokenRequest request) {
        sessions.logout(AuthSubjectType.VENUE, request.refreshToken());
    }

    @Schema(name = "VenueLoginRequest")
    public record VenueLoginRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(example = "levelup.nasrcity") String loginIdentifier,
            @NotBlank @Size(max = MAX_TEXT) @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
            String password) {

        @Override
        public String toString() {
            return "VenueLoginRequest[<redacted>]";
        }
    }

    /** Refresh and logout. */
    @Schema(name = "VenueRefreshTokenRequest")
    public record VenueRefreshTokenRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String refreshToken) {

        @Override
        public String toString() {
            return "VenueRefreshTokenRequest[<redacted>]";
        }
    }

    /** Arch 09 section 2: {@code {accessToken, refreshToken}}. */
    @Schema(name = "VenueAuthTokens")
    public record VenueAuthTokens(
            @NotNull @Schema(description = "Access JWT, 15 minutes; send as Authorization: Bearer.") String accessToken,
            @NotNull @Schema(description = "Opaque refresh token, 7 days; rotated on every refresh.")
            String refreshToken) {

        static VenueAuthTokens of(AuthSessions.TokenPair tokens) {
            return new VenueAuthTokens(tokens.accessToken(), tokens.refreshToken());
        }

        @Override
        public String toString() {
            return "VenueAuthTokens[<redacted>]";
        }
    }
}
