package ps.e7gz.identity.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.WebOrigins;
import ps.e7gz.identity.internal.AuthSessions;

/**
 * The browser session of the Venue Dashboard and Platform Admin (Sprint 0 review S-4, PO decision 2 Oct 2026, E7-22).
 * The refresh token lives only in an {@code HttpOnly; Secure; SameSite=Strict} cookie scoped to that audience's
 * {@code /auth} path, which page scripts can never read; the JSON body carries only the access token, which the page
 * keeps in memory. The Customer app is unchanged (tokens in the JSON body, arch 09 section 2).
 *
 * <p>Refresh and logout act on the cookie the browser attaches by itself, so they also require proof that the page
 * itself made the call: an allowed {@code Origin} ({@link WebOrigins}) and the {@link WebOrigins#CSRF_HEADER} header,
 * which a cross-site form or image cannot send. Either one missing is 403 {@code FORBIDDEN}, and nothing is read or
 * changed.
 */
@Component
public class WebSessions {

    static final String VENUE_COOKIE = "e7gz_venue_refresh";
    static final String ADMIN_COOKIE = "e7gz_admin_refresh";
    static final String VENUE_COOKIE_PATH = "/api/v1/venue/auth";
    static final String ADMIN_COOKIE_PATH = "/api/v1/admin/auth";

    private final AuthSessions sessions;
    private final WebOrigins origins;

    public WebSessions(AuthSessions sessions, WebOrigins origins) {
        this.sessions = sessions;
        this.origins = origins;
    }

    /** Logs in and sets the refresh cookie; returns the access token for the body. */
    public String login(AuthSubjectType audience, String loginIdentifier, String password, HttpServletRequest request,
                        HttpServletResponse response) {
        AuthSessions.TokenPair tokens = sessions.login(audience, loginIdentifier, password, request.getRemoteAddr());
        setCookie(response, audience, tokens.refreshToken(), AuthSessions.refreshLifetime(audience));
        return tokens.accessToken();
    }

    /**
     * Rotates the cookie's refresh token. Any refusal after the CSRF check (no cookie, unknown, expired, revoked,
     * reused, inactive account) is 401 {@code UNAUTHENTICATED} and clears the cookie.
     */
    public String refresh(AuthSubjectType audience, HttpServletRequest request, HttpServletResponse response) {
        requireOwnPage(request);
        String refreshToken = cookie(request, audience);
        if (refreshToken == null) {
            clearCookie(response, audience);
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        }
        AuthSessions.TokenPair tokens;
        try {
            tokens = sessions.refresh(audience, refreshToken);
        } catch (ApiException e) {
            clearCookie(response, audience);
            throw e;
        }
        setCookie(response, audience, tokens.refreshToken(), AuthSessions.refreshLifetime(audience));
        return tokens.accessToken();
    }

    /** Revokes the cookie's session, if any, and clears the cookie. */
    public void logout(AuthSubjectType audience, HttpServletRequest request, HttpServletResponse response) {
        requireOwnPage(request);
        String refreshToken = cookie(request, audience);
        if (refreshToken != null) {
            sessions.logout(audience, refreshToken);
        }
        clearCookie(response, audience);
    }

    private void requireOwnPage(HttpServletRequest request) {
        if (!origins.isAllowed(request.getHeader(HttpHeaders.ORIGIN))
                || !WebOrigins.CSRF_HEADER_VALUE.equals(request.getHeader(WebOrigins.CSRF_HEADER))) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
    }

    private static String cookie(HttpServletRequest request, AuthSubjectType audience) {
        Cookie cookie = WebUtils.getCookie(request, cookieName(audience));
        return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
    }

    private static void setCookie(HttpServletResponse response, AuthSubjectType audience, String value,
                                  Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(cookieName(audience), value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(cookiePath(audience))
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static void clearCookie(HttpServletResponse response, AuthSubjectType audience) {
        setCookie(response, audience, "", Duration.ZERO);
    }

    static String cookieName(AuthSubjectType audience) {
        return switch (audience) {
            case VENUE -> VENUE_COOKIE;
            case ADMIN -> ADMIN_COOKIE;
            case CUSTOMER -> throw new IllegalArgumentException("The Customer app has no cookie session");
        };
    }

    static String cookiePath(AuthSubjectType audience) {
        return switch (audience) {
            case VENUE -> VENUE_COOKIE_PATH;
            case ADMIN -> ADMIN_COOKIE_PATH;
            case CUSTOMER -> throw new IllegalArgumentException("The Customer app has no cookie session");
        };
    }
}
