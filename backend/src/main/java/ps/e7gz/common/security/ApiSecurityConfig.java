package ps.e7gz.common.security;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.web.SecurityErrorHandlers;

/**
 * The security chain for {@code /api/**} (arch 09 section 1.1–1.2, arch 10 section 2.3). Stateless: no session, no
 * CSRF, no form login (the API is called with a Bearer token, never a cookie).
 *
 * <ul>
 *   <li>Public operations (registration, phone OTP, login, refresh, logout, public discovery) need no token, and a
 *       Bearer header sent to them is ignored - an expired access token must not stop the refresh that replaces it.</li>
 *   <li>Every other route needs a valid access JWT (HS512, {@link AccessTokenConfig}) whose subject is still active
 *       in the database ({@link SubjectJwtAuthenticationConverter}); otherwise 401 {@code UNAUTHENTICATED}.</li>
 *   <li>Each route group accepts only its own identity type ({@code typ}): a valid token of another type is 403
 *       {@code FORBIDDEN}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfig {

    /** POST operations without a token (arch 09 section 3.1, section 2). Password reset is E7-57's. */
    static final String[] PUBLIC_POSTS = {
            "/api/v1/customer/auth/register",
            "/api/v1/customer/auth/otp/send",
            "/api/v1/customer/auth/verify-phone",
            "/api/v1/customer/auth/login",
            "/api/v1/customer/auth/refresh",
            "/api/v1/customer/auth/logout",
            "/api/v1/venue/auth/login",
            "/api/v1/venue/auth/refresh",
            "/api/v1/venue/auth/logout",
            "/api/v1/admin/auth/login",
            "/api/v1/admin/auth/refresh",
            "/api/v1/admin/auth/logout"};

    /** Public discovery (arch 09 section 3.2), as in the OpenAPI contract. */
    static final String[] PUBLIC_GETS = {
            "/api/v1/customer/areas",
            "/api/v1/customer/venues",
            "/api/v1/customer/venues/*",
            "/api/v1/customer/find-available"};

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, SecurityErrorHandlers errors, JwtDecoder accessTokenDecoder,
                                               ObjectProvider<ActiveSubjectCheck> activeSubjectChecks) throws Exception {
        SubjectJwtAuthenticationConverter subjects =
                new SubjectJwtAuthenticationConverter(activeSubjectChecks.orderedStream());
        return http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GETS).permitAll()
                        .requestMatchers("/api/v1/customer/**").hasAuthority(audience(AuthSubjectType.CUSTOMER))
                        .requestMatchers("/api/v1/venue/**").hasAuthority(audience(AuthSubjectType.VENUE))
                        .requestMatchers("/api/v1/admin/**").hasAuthority(audience(AuthSubjectType.ADMIN))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(ignoredOnPublicRoutes())
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler())
                        .jwt(jwt -> jwt.decoder(accessTokenDecoder).jwtAuthenticationConverter(subjects)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .build();
    }

    private static String audience(AuthSubjectType type) {
        return SubjectAuthentication.authorityFor(type);
    }

    /** The standard {@code Authorization: Bearer} resolver, except on the public operations above. */
    static BearerTokenResolver ignoredOnPublicRoutes() {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher publicRoutes = new OrRequestMatcher(Stream.concat(
                Arrays.stream(PUBLIC_POSTS).<RequestMatcher>map(path -> paths.matcher(HttpMethod.POST, path)),
                Arrays.stream(PUBLIC_GETS).<RequestMatcher>map(path -> paths.matcher(HttpMethod.GET, path))).toList());
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> publicRoutes.matches(request) ? null : standard.resolve(request);
    }

    /**
     * API documentation and operations endpoints: denied to everyone (401). Nothing in the repository calls them at
     * runtime; the contract is generated in tests without the security chain ({@code pnpm api:gen}). Arch 12
     * section 1 lists {@code /actuator/health/liveness|readiness} for deployment probes: open them here when the
     * deployment task configures the probes.
     */
    static final String[] DOCS_AND_OPERATIONS = {
            "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/swagger-ui.html", "/swagger-ui/**",
            "/actuator", "/actuator/**"};

    @Bean
    @Order(2)
    SecurityFilterChain docsAndOperationsSecurityFilterChain(HttpSecurity http, SecurityErrorHandlers errors)
            throws Exception {
        return http.securityMatcher(DOCS_AND_OPERATIONS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a.anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .build();
    }
}
