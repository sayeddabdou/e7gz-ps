package ps.e7gz.common.web;

import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * The 401 / 403 bodies for failures raised inside the Spring Security filter chain, which runs before any controller
 * and so never reaches {@link GlobalExceptionHandler}. E7-14 (login, JWT) registers them on its
 * {@code SecurityFilterChain}:
 * <pre>{@code http.exceptionHandling(e -> e
 *         .authenticationEntryPoint(securityErrors.authenticationEntryPoint())
 *         .accessDeniedHandler(securityErrors.accessDeniedHandler()));}</pre>
 * Until then Spring Boot's default chain is in place and does not use them.
 */
@Component
public class SecurityErrorHandlers {

    private final ErrorResponses responses;

    public SecurityErrorHandlers(ErrorResponses responses) {
        this.responses = responses;
    }

    /** Missing, invalid or expired token: 401 {@code UNAUTHENTICATED}. */
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, failure) -> responses.write(response, ApiErrorCode.UNAUTHENTICATED);
    }

    /** Authenticated but not permitted: 403 {@code FORBIDDEN}. */
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, failure) -> responses.write(response, ApiErrorCode.FORBIDDEN);
    }
}
