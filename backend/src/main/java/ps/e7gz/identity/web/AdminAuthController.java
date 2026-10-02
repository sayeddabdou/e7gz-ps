package ps.e7gz.identity.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import ps.e7gz.common.security.WebOrigins;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.ResponseMetaFactory;

/**
 * Platform Admin sessions (arch 10 section 2.1; arch 09 section 2: same token model as the customer). An inactive
 * admin cannot log in, and its tokens stop working at once. Public: no access token. The refresh token travels only in
 * an HttpOnly cookie, never in a body ({@link WebSessions}, Sprint 0 review S-4, E7-22).
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@Tag(name = "admin-auth")
public class AdminAuthController {

    static final int MAX_TEXT = 200;

    static final String SESSION_COOKIE = "Browser session (Sprint 0 review S-4): the refresh token"
            + " (12 hours, rotated on every refresh) is set as an HttpOnly; Secure; "
            + "SameSite=Strict cookie on Path=" + WebSessions.ADMIN_COOKIE_PATH
            + ", which the page never reads. Call with credentials included. Refresh and logout need an allowed "
            + "Origin and the " + WebOrigins.CSRF_HEADER + " header, or they are 403 FORBIDDEN.";
    static final String COOKIE = "The refresh token, sent by the browser itself.";
    static final String CSRF = "Proves the Dashboard/Admin page made the call (CSRF check).";
    static final String SET_COOKIE = "The refresh-token session cookie (a new one on every login and refresh).";

    private final WebSessions sessions;
    private final ResponseMetaFactory meta;

    public AdminAuthController(WebSessions sessions, ResponseMetaFactory meta) {
        this.sessions = sessions;
        this.meta = meta;
    }

    @PostMapping("/login")
    @Operation(operationId = "adminLogin", summary = "Log in to Platform Admin (arch 10 section 2.1)",
            description = SESSION_COOKIE)
    @ApiResponse(responseCode = "200", headers = @Header(name = "Set-Cookie", description = SET_COOKIE,
            schema = @Schema(type = "string")))
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    public DataResponse<AdminAccessToken> login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest http,
                                                HttpServletResponse response) {
        return meta.data(new AdminAccessToken(sessions.login(
                AuthSubjectType.ADMIN, request.email(), request.password(), http, response)));
    }

    @PostMapping("/refresh")
    @Operation(operationId = "adminRefresh",
            summary = "Exchange the session cookie's refresh token for a new access token and cookie; the old refresh "
                    + "token stops working (arch 10 section 2.3)",
            description = SESSION_COOKIE)
    @Parameter(in = ParameterIn.COOKIE, name = WebSessions.ADMIN_COOKIE, required = true, description = COOKIE)
    @Parameter(in = ParameterIn.HEADER, name = WebOrigins.CSRF_HEADER, required = true, description = CSRF,
            schema = @Schema(type = "string", allowableValues = WebOrigins.CSRF_HEADER_VALUE))
    @ApiResponse(responseCode = "200", headers = @Header(name = "Set-Cookie", description = SET_COOKIE,
            schema = @Schema(type = "string")))
    @ApiError(status = 401, codes = ApiErrorCode.UNAUTHENTICATED)
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    public DataResponse<AdminAccessToken> refresh(HttpServletRequest http, HttpServletResponse response) {
        return meta.data(new AdminAccessToken(sessions.refresh(AuthSubjectType.ADMIN, http, response)));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminLogout", summary = "End the session of the cookie's refresh token and clear the "
            + "cookie (arch 10 section 2.3)", description = SESSION_COOKIE)
    @Parameter(in = ParameterIn.COOKIE, name = WebSessions.ADMIN_COOKIE, description = COOKIE)
    @Parameter(in = ParameterIn.HEADER, name = WebOrigins.CSRF_HEADER, required = true, description = CSRF,
            schema = @Schema(type = "string", allowableValues = WebOrigins.CSRF_HEADER_VALUE))
    @ApiResponse(responseCode = "204", headers = @Header(name = "Set-Cookie", description = "Clears the cookie.",
            schema = @Schema(type = "string")))
    @ApiError(status = 403, codes = ApiErrorCode.FORBIDDEN)
    public void logout(HttpServletRequest http, HttpServletResponse response) {
        sessions.logout(AuthSubjectType.ADMIN, http, response);
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

    /** Sprint 0 review S-4: the refresh token is in the session cookie only, never in the body. */
    @Schema(name = "AdminAccessToken")
    public record AdminAccessToken(
            @NotNull @Schema(description = "Access JWT, 15 minutes; send as Authorization: Bearer. Keep it in memory "
                    + "only, never in localStorage, sessionStorage or IndexedDB.") String accessToken) {

        @Override
        public String toString() {
            return "AdminAccessToken[<redacted>]";
        }
    }
}
