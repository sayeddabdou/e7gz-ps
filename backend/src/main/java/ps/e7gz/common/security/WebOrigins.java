package ps.e7gz.common.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * The browser origins allowed on the venue and admin routes (Sprint 0 review S-4, E7-22; arch 10 section 6). The
 * Customer app is a mobile app and sends no Origin, so the customer routes get no CORS at all.
 */
@Component
@EnableConfigurationProperties(WebOriginProperties.class)
public class WebOrigins {

    /** The custom header a cookie-session call must carry; a cross-site form or image cannot set it (E7-22). */
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_HEADER_VALUE = "e7gz";

    static final List<String> WEB_ROUTES = List.of("/api/v1/venue/**", "/api/v1/admin/**");

    private final List<String> allowed;

    public WebOrigins(WebOriginProperties properties) {
        this.allowed = properties.allowedOrigins();
    }

    /** True only for an exact match with a configured origin; a missing Origin is never allowed. */
    public boolean isAllowed(String origin) {
        return origin != null && allowed.contains(origin);
    }

    /**
     * CORS for the venue and admin routes: exactly the configured origins, with credentials (the session cookie). A
     * cross-origin request from any other origin is refused by {@code processor} before it reaches a controller.
     */
    CorsFilter corsFilter(CorsProcessor processor) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource());
        filter.setCorsProcessor(processor);
        return filter;
    }

    private CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (allowed.isEmpty()) {
            return source;
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowed);
        cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                HttpHeaders.ACCEPT_LANGUAGE, OpenApiConfig.IDEMPOTENCY_KEY, CorrelationId.HEADER, CSRF_HEADER));
        cors.setExposedHeaders(List.of(CorrelationId.HEADER));
        cors.setMaxAge(Duration.ofHours(1));
        WEB_ROUTES.forEach(route -> source.registerCorsConfiguration(route, cors));
        return source;
    }
}
