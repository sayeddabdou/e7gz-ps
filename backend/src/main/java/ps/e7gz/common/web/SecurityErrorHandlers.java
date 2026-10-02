package ps.e7gz.common.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.DefaultCorsProcessor;
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

    /**
     * Spring's CORS checks, with a refused cross-origin request answered 403 {@code FORBIDDEN} in the envelope instead
     * of the plain-text "Invalid CORS request" (E7-22).
     */
    public CorsProcessor corsProcessor() {
        return new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(ServerHttpResponse response) throws IOException {
                HttpServletResponse servlet = ((ServletServerHttpResponse) response).getServletResponse();
                responses.write(servlet, ApiErrorCode.FORBIDDEN);
            }
        };
    }
}
