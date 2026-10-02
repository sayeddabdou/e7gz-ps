package ps.e7gz.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.identity.internal.TestCustomers;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.identity.internal.TestAccounts;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-14 - the access JWT and the API chain (arch 10 section 2.3, arch 09 section 1.1): exactly the identity claims,
 * 15 minutes, HS512 with our key only; each route group accepts only its own identity type; account state is read per
 * request, so a DISABLED venue account is refused although its token is still valid. Failures use the E7-12 envelope.
 */
@AutoConfigureMockMvc
@Import(SubjectProbe.class)
class AccessTokenSecurityTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    SecretKey accessTokenKey;

    @Autowired
    DataSource appDataSource;

    JdbcClient db;

    UUID customerId;
    UUID venueId;
    UUID adminId;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        customerId = TestCustomers.insert(db, false, "x").id();
        venueId = TestAccounts.venue(db, "venue-" + UUID.randomUUID(), "x");
        adminId = TestAccounts.admin("admin-" + UUID.randomUUID() + "@example.com", "x");
    }

    // ------------------------------------------------------------------------------------------------ the token

    @Test
    void theAccessTokenCarriesExactlyTheIdentityClaimsAndLastsFifteenMinutes() throws Exception {
        String token = accessTokens.issue(AuthSubjectType.CUSTOMER, customerId);

        JsonNode header = part(token, 0);
        JsonNode claims = part(token, 1);
        Set<String> names = new HashSet<>();
        claims.fieldNames().forEachRemaining(names::add);

        assertThat(header.path("alg").asText()).isEqualTo("HS512");
        assertThat(names).containsExactlyInAnyOrder("sub", "typ", "jti", "iat", "exp", "ver");
        assertThat(claims.path("sub").asText()).isEqualTo(customerId.toString());
        assertThat(claims.path("typ").asText()).isEqualTo("CUSTOMER");
        assertThat(claims.path("ver").asInt()).isEqualTo(1);
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(15 * 60);
        assertThat(UUID.fromString(claims.path("jti").asText())).isNotNull();
    }

    @Test
    void noCapabilityOrAccountStateTravelsInTheToken() throws Exception {
        String claims = part(accessTokens.issue(AuthSubjectType.VENUE, venueId), 1).toString().toLowerCase();

        for (String capability : new String[] {"verified", "role", "banned", "disabled", "status", "enabled",
                "permission", "capabilit", "authorit", "scope"}) {
            assertThat(claims).as(capability).doesNotContain(capability);
        }
    }

    @Test
    void everyTokenHasItsOwnJti() throws Exception {
        String first = part(accessTokens.issue(AuthSubjectType.CUSTOMER, customerId), 1).path("jti").asText();
        String second = part(accessTokens.issue(AuthSubjectType.CUSTOMER, customerId), 1).path("jti").asText();

        assertThat(first).isNotEqualTo(second);
    }

    // ------------------------------------------------------------------------------------------------ audiences

    @ParameterizedTest
    @EnumSource(AuthSubjectType.class)
    void eachRouteGroupAcceptsOnlyItsOwnIdentityType(AuthSubjectType type) throws Exception {
        String token = accessTokens.issue(type, idOf(type));

        for (AuthSubjectType route : AuthSubjectType.values()) {
            ResultActions result = probe(route, token);
            if (route == type) {
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.type").value(type.name()))
                        .andExpect(jsonPath("$.id").value(idOf(type).toString()));
            } else {
                result.andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                        .andExpect(jsonPath("$.meta.correlationId").isNotEmpty());
            }
        }
    }

    @Test
    void aCustomerTokenIsRefusedOnVenueAndAdminProductionRoutes() throws Exception {
        String token = accessTokens.issue(AuthSubjectType.CUSTOMER, customerId);

        mvc.perform(get("/api/v1/venue/resources").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/areas").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------------------------------------ account state

    @Test
    void aDisabledVenueAccountIsRefusedAtOnceAlthoughItsTokenIsStillValid() throws Exception {
        String token = accessTokens.issue(AuthSubjectType.VENUE, venueId);
        probe(AuthSubjectType.VENUE, token).andExpect(status().isOk());

        TestAccounts.setVenueStatus(db, venueId, VenueAccountStatus.DISABLED);
        probe(AuthSubjectType.VENUE, token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        TestAccounts.setVenueStatus(db, venueId, VenueAccountStatus.ENABLED);
        probe(AuthSubjectType.VENUE, token).andExpect(status().isOk());
    }

    @Test
    void anInactiveAdminIsRefusedAtOnce() throws Exception {
        String token = accessTokens.issue(AuthSubjectType.ADMIN, adminId);
        TestAccounts.setAdminActive(adminId, false);

        probe(AuthSubjectType.ADMIN, token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anUnverifiedCustomerIsAuthenticatedButAnUnknownOneIsNot() throws Exception {
        probe(AuthSubjectType.CUSTOMER, accessTokens.issue(AuthSubjectType.CUSTOMER, customerId))
                .andExpect(status().isOk());

        probe(AuthSubjectType.CUSTOMER, accessTokens.issue(AuthSubjectType.CUSTOMER, UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ------------------------------------------------------------------------------------------------ bad tokens

    @Test
    void noTokenIsUnauthenticatedNotForbidden() throws Exception {
        mvc.perform(get(SubjectProbe.VENUE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.meta.correlationId").isNotEmpty());
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        AccessTokens earlier = new AccessTokens(new NimbusJwtEncoder(new ImmutableSecret<>(accessTokenKey)),
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-16)));

        probe(AuthSubjectType.CUSTOMER, earlier.issue(AuthSubjectType.CUSTOMER, customerId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aTokenFromAnotherKeyOrAlgorithmIsRefused() throws Exception {
        SecretKey otherKey = new SecretKeySpec(
                "another-key-another-key-another-key-another-key-another-key-64by".getBytes(StandardCharsets.US_ASCII),
                "HmacSHA512");
        probe(AuthSubjectType.CUSTOMER, sign(otherKey, MacAlgorithm.HS512, c -> { }))
                .andExpect(status().isUnauthorized());
        probe(AuthSubjectType.CUSTOMER, sign(accessTokenKey, MacAlgorithm.HS256, c -> { }))
                .andExpect(status().isUnauthorized());
        probe(AuthSubjectType.CUSTOMER, "not.a.jwt").andExpect(status().isUnauthorized());

        String valid = accessTokens.issue(AuthSubjectType.CUSTOMER, customerId);
        String[] parts = valid.split("\\.");
        String unsigned = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + ".";
        probe(AuthSubjectType.CUSTOMER, unsigned).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutARequiredClaimOrWithAnotherVersionIsRefused() throws Exception {
        probe(AuthSubjectType.CUSTOMER, sign(accessTokenKey, MacAlgorithm.HS512, c -> c.claim("ver", 2)))
                .andExpect(status().isUnauthorized());
        probe(AuthSubjectType.CUSTOMER, sign(accessTokenKey, MacAlgorithm.HS512, c -> c.claims(m -> m.remove("typ"))))
                .andExpect(status().isUnauthorized());
        probe(AuthSubjectType.CUSTOMER, sign(accessTokenKey, MacAlgorithm.HS512, c -> c.claim("typ", "OWNER")))
                .andExpect(status().isUnauthorized());
        probe(AuthSubjectType.CUSTOMER, sign(accessTokenKey, MacAlgorithm.HS512, c -> c.claims(m -> m.remove("jti"))))
                .andExpect(status().isUnauthorized());
        probe(AuthSubjectType.CUSTOMER, sign(accessTokenKey, MacAlgorithm.HS512, c -> c.claims(m -> m.remove("exp"))))
                .andExpect(status().isUnauthorized());
        // The same builder with nothing removed is accepted: the refusals above are about the claim, not the builder.
        probe(AuthSubjectType.CUSTOMER, sign(accessTokenKey, MacAlgorithm.HS512, c -> { }))
                .andExpect(status().isOk());
    }

    @Test
    void aBearerHeaderIsIgnoredOnPublicRoutes() throws Exception {
        // An expired access token must not block public discovery (or the refresh that replaces it).
        mvc.perform(get("/api/v1/customer/areas").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ResultActions probe(AuthSubjectType route, String token) throws Exception {
        String path = switch (route) {
            case CUSTOMER -> SubjectProbe.CUSTOMER;
            case VENUE -> SubjectProbe.VENUE;
            case ADMIN -> SubjectProbe.ADMIN;
        };
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private UUID idOf(AuthSubjectType type) {
        return switch (type) {
            case CUSTOMER -> customerId;
            case VENUE -> venueId;
            case ADMIN -> adminId;
        };
    }

    /** A customer token like {@link AccessTokens#issue}'s, changed by {@code change}, signed as given. */
    private String sign(SecretKey key, MacAlgorithm algorithm, Consumer<JwtClaimsSet.Builder> change) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(customerId.toString())
                .claim("typ", "CUSTOMER")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(AccessTokens.LIFETIME))
                .claim("ver", 1);
        change.accept(claims);
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims.build())).getTokenValue();
    }

    private JsonNode part(String jwt, int index) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]));
    }

}
