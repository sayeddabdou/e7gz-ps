package ps.e7gz.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import ps.e7gz.common.web.SecurityErrorHandlers;

/**
 * The security chain for {@code /api/**} (arch 09 section 1.2): the public operations - registration, phone OTP and
 * customer discovery - need no token; every other API route needs one. Stateless: no session, no CSRF (the API is
 * called with a Bearer token, never a cookie).
 *
 * <p>E7-13 minimum. E7-14 adds JWT authentication here and restricts each route group to its own principal type
 * (arch 10 section 2.3); until then no request can authenticate, so every non-public API route answers 401.
 */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfig {

    /** POST operations without a token (arch 09 section 3.1). E7-14 adds login, refresh and password reset. */
    static final String[] PUBLIC_POSTS = {
            "/api/v1/customer/auth/register",
            "/api/v1/customer/auth/otp/send",
            "/api/v1/customer/auth/verify-phone"};

    /** Public discovery (arch 09 section 3.2), as in the OpenAPI contract. */
    static final String[] PUBLIC_GETS = {
            "/api/v1/customer/areas",
            "/api/v1/customer/venues",
            "/api/v1/customer/venues/*",
            "/api/v1/customer/find-available"};

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, SecurityErrorHandlers errors) throws Exception {
        return http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GETS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errors.authenticationEntryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .build();
    }

    /**
     * API documentation and operations endpoints: denied to everyone (401) until E7-14 decides who may use them.
     * Nothing in the repository calls them at runtime; the contract is generated in tests without the security chain
     * ({@code pnpm api:gen}). Arch 12 section 1 lists {@code /actuator/health/liveness|readiness} for deployment
     * probes: open them here when the deployment task configures the probes.
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
