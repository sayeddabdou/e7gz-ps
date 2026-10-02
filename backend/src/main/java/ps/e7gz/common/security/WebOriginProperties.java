package ps.e7gz.common.security;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The browser origins of the Venue Dashboard and Platform Admin ({@code e7gz.security.cors.allowed-origins}, from
 * {@code E7GZ_CORS_ALLOWED_ORIGINS}; Sprint 0 review S-4, E7-22). They are the only origins that may call the venue and
 * admin routes from a browser (CORS, with credentials) and the only ones the cookie session's refresh and logout accept
 * ({@link WebOrigins}). Exact {@code scheme://host[:port]} values: no wildcard, no path, no trailing slash. Empty by
 * default, so a deployment that sets nothing allows no browser origin at all.
 *
 * @param allowedOrigins e.g. {@code https://venue.e7gz.example,https://admin.e7gz.example}
 */
@ConfigurationProperties("e7gz.security.cors")
public record WebOriginProperties(List<String> allowedOrigins) {

    public WebOriginProperties {
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
        allowedOrigins.forEach(WebOriginProperties::requireExactOrigin);
    }

    private static void requireExactOrigin(String origin) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException e) {
            throw invalid(origin);
        }
        boolean http = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
        if (origin.contains("*") || !http || uri.getHost() == null || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid(origin);
        }
    }

    private static IllegalArgumentException invalid(String origin) {
        return new IllegalArgumentException("e7gz.security.cors.allowed-origins: '" + origin
                + "' is not an exact origin (scheme://host[:port], no wildcard, path or trailing slash)");
    }
}
