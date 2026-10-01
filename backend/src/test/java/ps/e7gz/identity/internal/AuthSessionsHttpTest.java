package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.SubjectProbe;
import ps.e7gz.identity.api.LoginAccountDirectory;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-14 - login, refresh rotation, reuse detection, logout and the login rate limit over HTTP (the real security
 * chain) against the migrated database (arch 10 section 2, section 7; arch 09 section 2). Venue and admin accounts come
 * are real {@code iam.venue_account} / {@code iam.admin_account} rows read by the production Venue and Admin adapters.
 */
@AutoConfigureMockMvc
@Import(SubjectProbe.class)
class AuthSessionsHttpTest extends IntegrationTest {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    AuthSessions sessions;

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    JdbcClient db;
    String passwordHash;
    /** Each test logs in from its own address, so limiter keys never meet across tests. */
    String ip;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        if (passwordHash == null) {
            passwordHash = passwords.encode(PASSWORD);
        }
        ip = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256) + "."
                + ThreadLocalRandom.current().nextInt(256);
    }

    // ------------------------------------------------------------------------------------------------ customer login

    @Test
    void anUnverifiedCustomerLogsInWithThePhoneInAnyAcceptedForm() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, false, passwordHash);

        for (String phone : new String[] {c.localPhone(), c.phoneE164(), "0020 " + c.localPhone().substring(1),
                "  " + c.phoneE164().substring(1) + " "}) {
            JsonNode tokens = customerLogin(phone, PASSWORD).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString().transform(this::read);
            assertThat(claims(tokens.path("data").path("accessToken").asText()).path("sub").asText())
                    .as(phone).isEqualTo(c.id().toString());
            assertThat(tokens.path("meta").path("correlationId").asText()).isNotEmpty();
        }
        assertThat(db.sql("SELECT phone_verified FROM iam.user_account WHERE id = :id").param("id", c.id())
                .query(Boolean.class).single()).as("login does not verify").isFalse();
    }

    @Test
    void aCustomerLogsInWithTheUsernameTrimmedAndCaseInsensitive() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);

        String access = customerLogin("  " + c.username().toUpperCase() + " ", PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString().transform(this::read)
                .path("data").path("accessToken").asText();

        assertThat(claims(access).path("sub").asText()).isEqualTo(c.id().toString());
        assertThat(claims(access).path("typ").asText()).isEqualTo("CUSTOMER");
    }

    @Test
    void aWrongPasswordAndAnUnknownIdentityGetTheSameAnswerAndNoSession() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);

        JsonNode wrongPassword = customerLogin(c.username(), "Wr0ngPassword").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString().transform(this::read);
        JsonNode unknown = customerLogin("nobody-" + UUID.randomUUID(), PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString().transform(this::read);

        assertThat(wrongPassword.path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(unknown.path("error")).isEqualTo(wrongPassword.path("error"));
        assertThat(wrongPassword.has("data")).isFalse();
        assertThat(sessionsOf(c.id())).isZero();
    }

    @Test
    void anAnonymisedCustomerCannotLogInRefreshOrUseAnAccessTokenIssuedBefore() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        Tokens before = customerTokens(c.username());
        probe(SubjectProbe.CUSTOMER, before.access()).andExpect(status().isOk());

        anonymise(c.id());

        probe(SubjectProbe.CUSTOMER, before.access()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        refresh("customer", before.refresh()).andExpect(status().isUnauthorized());
        customerLogin(c.username(), PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ------------------------------------------------------------------------------------------------ venue / admin

    @Test
    void aDisabledVenueAccountCannotLogIn() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        UUID venue = TestAccounts.venue(db, login, passwordHash);

        String access = read(login("venue", Map.of("loginIdentifier", " " + login.toUpperCase(), "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        assertThat(claims(access).path("typ").asText()).isEqualTo("VENUE");
        assertThat(claims(access).path("sub").asText()).isEqualTo(venue.toString());
        login("venue", Map.of("loginIdentifier", login, "password", "Wr0ngPassword"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        TestAccounts.setVenueStatus(db, venue, VenueAccountStatus.DISABLED);
        login("venue", Map.of("loginIdentifier", login, "password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        login("venue", Map.of("loginIdentifier", login, "password", "Wr0ngPassword"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertThat(sessionsOf(venue)).isEqualTo(1);
    }

    @Test
    void aDisabledVenueAccountCannotRefreshItsSession() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        UUID venue = TestAccounts.venue(db, login, passwordHash);
        String refresh = read(login("venue", Map.of("loginIdentifier", login, "password", PASSWORD))
                .andReturn().getResponse().getContentAsString()).path("data").path("refreshToken").asText();

        TestAccounts.setVenueStatus(db, venue, VenueAccountStatus.DISABLED);

        refresh("venue", refresh).andExpect(status().isUnauthorized());
        assertThat(row(refresh).get("rotated_to")).as("nothing issued").isNull();
    }

    @Test
    void anInactiveAdminCannotLogIn() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        UUID admin = TestAccounts.admin(db, email, passwordHash);
        login("admin", Map.of("email", email, "password", PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        TestAccounts.setAdminActive(db, admin, false);

        login("admin", Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void venueAndAdminTokensFromARealLoginStopWorkingWhenTheAccountIsDisabled() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        UUID venue = TestAccounts.venue(db, login, passwordHash);
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        UUID admin = TestAccounts.admin(db, email, passwordHash);
        String venueAccess = read(login("venue", Map.of("loginIdentifier", login, "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        String adminAccess = read(login("admin", Map.of("email", "  " + email.toUpperCase(), "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        probe(SubjectProbe.VENUE, venueAccess).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(venue.toString()));
        probe(SubjectProbe.ADMIN, adminAccess).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.toString()));
        // Route-group isolation with tokens from the real logins.
        probe(SubjectProbe.ADMIN, venueAccess).andExpect(status().isForbidden());
        probe(SubjectProbe.CUSTOMER, adminAccess).andExpect(status().isForbidden());

        TestAccounts.setVenueStatus(db, venue, VenueAccountStatus.DISABLED);
        TestAccounts.setAdminActive(db, admin, false);

        probe(SubjectProbe.VENUE, venueAccess).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        probe(SubjectProbe.ADMIN, adminAccess).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void reusingARotatedVenueTokenRevokesTheChainEvenAfterTheAccountIsDisabled() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        UUID venue = TestAccounts.venue(db, login, passwordHash);
        String a = read(login("venue", Map.of("loginIdentifier", login, "password", PASSWORD))
                .andReturn().getResponse().getContentAsString()).path("data").path("refreshToken").asText();
        String b = read(refresh("venue", a).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();

        TestAccounts.setVenueStatus(db, venue, VenueAccountStatus.DISABLED);
        refresh("venue", a).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        assertThat(row(b).get("revoked_at")).as("the reuse revoked B although the account is disabled").isNotNull();
        TestAccounts.setVenueStatus(db, venue, VenueAccountStatus.ENABLED);
        refresh("venue", b).andExpect(status().isUnauthorized());
        assertThat(liveSessionsOf(venue)).isZero();
    }

    @Test
    void reusingARotatedAdminTokenRevokesTheChainEvenAfterTheAdminIsDeactivated() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        UUID admin = TestAccounts.admin(db, email, passwordHash);
        String a = read(login("admin", Map.of("email", email, "password", PASSWORD))
                .andReturn().getResponse().getContentAsString()).path("data").path("refreshToken").asText();
        String b = read(refresh("admin", a).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("refreshToken").asText();

        TestAccounts.setAdminActive(db, admin, false);
        refresh("admin", a).andExpect(status().isUnauthorized());

        TestAccounts.setAdminActive(db, admin, true);
        refresh("admin", b).andExpect(status().isUnauthorized());
        assertThat(liveSessionsOf(admin)).isZero();
    }

    @Test
    void anInactiveAccountsCurrentTokenIsRefusedWithoutIssuingAndWorksAgainOnceReEnabled() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        UUID venue = TestAccounts.venue(db, login, passwordHash);
        String a = read(login("venue", Map.of("loginIdentifier", login, "password", PASSWORD))
                .andReturn().getResponse().getContentAsString()).path("data").path("refreshToken").asText();

        TestAccounts.setVenueStatus(db, venue, VenueAccountStatus.DISABLED);
        refresh("venue", a).andExpect(status().isUnauthorized());
        assertThat(sessionsOf(venue)).as("nothing issued").isEqualTo(1);
        assertThat(row(a).get("revoked_at")).as("not a reuse: the session is only refused").isNull();

        TestAccounts.setVenueStatus(db, venue, VenueAccountStatus.ENABLED);
        refresh("venue", a).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------ phone-like usernames

    @Test
    void aUsernameThatLooksLikeAPhoneLogsInAsAUsername() throws Exception {
        String phoneLike = TestCustomers.randomLocalPhone();                     // nobody's phone
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash, phoneLike);
        assertThat(c.localPhone()).isNotEqualTo(phoneLike);

        String access = read(customerLogin(phoneLike, PASSWORD).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).path("data").path("accessToken").asText();
        assertThat(claims(access).path("sub").asText()).isEqualTo(c.id().toString());
        customerLogin("  " + phoneLike + " ", PASSWORD).andExpect(status().isOk());
        // The username rule is only trimmed + case-insensitive: another phone spelling is not this username.
        customerLogin("+2" + phoneLike, PASSWORD).andExpect(status().isUnauthorized());
        // The account's own phone still works.
        customerLogin(c.phoneE164(), PASSWORD).andExpect(status().isOk());
        // The limiter key stays canonical (E.164 of the phone-like input), never the raw identifier.
        assertThat(db.sql("SELECT counter FROM iam.security_rate_limit_counter WHERE limiter_type = 'LOGIN' "
                        + "AND action_class = 'CUSTOMER' AND subject_key_hash = :key")
                .param("key", LoginRateLimiter.subjectKeyHash(PhoneNumbers.toE164(phoneLike).orElseThrow(), ip))
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void whenAPhoneLikeIdentifierIsBothSomeonesPhoneAndAnotherUsernameThePhoneWins() throws Exception {
        TestCustomers.Customer owner = TestCustomers.insert(db, true, passwordHash);
        TestCustomers.Customer namesake = TestCustomers.insert(db, true, passwordHash, owner.localPhone());

        String access = read(customerLogin(owner.localPhone(), PASSWORD).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("data").path("accessToken").asText();

        assertThat(claims(access).path("sub").asText()).isEqualTo(owner.id().toString());
        assertThat(sessionsOf(namesake.id())).isZero();
    }

    @Test
    void anUnknownPhoneAndAKnownPhoneWithAWrongPasswordGetTheSameAnswer() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);

        JsonNode knownPhone = read(customerLogin(c.localPhone(), "Wr0ngPassword").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString());
        JsonNode unknownPhone = read(customerLogin(TestCustomers.randomLocalPhone(), "Wr0ngPassword")
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString());

        assertThat(knownPhone.path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(unknownPhone.path("error")).isEqualTo(knownPhone.path("error"));
        assertThat(fieldNames(unknownPhone)).isEqualTo(fieldNames(knownPhone));
    }

    @Test
    void theCustomerLookupIsOneStatementWhateverTheIdentifier() {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String phoneLike = TestCustomers.randomLocalPhone();
        TestCustomers.Customer namesake = TestCustomers.insert(db, true, passwordHash, phoneLike);
        AtomicInteger statements = new AtomicInteger();
        CustomerAccounts accounts = new CustomerAccounts(countingStatements(appDataSource, statements));

        Map<String, Optional<UUID>> expected = new LinkedHashMap<>();
        expected.put(c.localPhone(), Optional.of(c.id()));                       // known phone
        expected.put(TestCustomers.randomLocalPhone(), Optional.empty());        // unknown phone
        expected.put(phoneLike, Optional.of(namesake.id()));                     // phone-like username, nobody's phone
        expected.put(" " + c.username().toUpperCase(), Optional.of(c.id()));     // username
        expected.put("nobody-" + UUID.randomUUID(), Optional.empty());           // unknown username
        expected.forEach((identifier, id) -> {
            statements.set(0);
            assertThat(accounts.find(identifier).map(LoginAccountDirectory.LoginAccount::id)).as(identifier)
                    .isEqualTo(id);
            assertThat(statements.get()).as(identifier + ": one SQL lookup").isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------------------------------------ refresh tokens

    @Test
    void refreshTokensLiveThirtyDaysSevenDaysAndTwelveHours() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String venue = "venue-" + UUID.randomUUID();
        TestAccounts.venue(db, venue, passwordHash);
        String admin = "admin-" + UUID.randomUUID() + "@example.com";
        TestAccounts.admin(db, admin, passwordHash);

        String customerRefresh = customerTokens(c.username()).refresh();
        String venueRefresh = read(login("venue", Map.of("loginIdentifier", venue, "password", PASSWORD))
                .andReturn().getResponse().getContentAsString()).path("data").path("refreshToken").asText();
        String adminRefresh = read(login("admin", Map.of("email", admin, "password", PASSWORD))
                .andReturn().getResponse().getContentAsString()).path("data").path("refreshToken").asText();

        assertThat(lifetime(customerRefresh)).isEqualTo(Duration.ofDays(30));
        assertThat(lifetime(venueRefresh)).isEqualTo(Duration.ofDays(7));
        assertThat(lifetime(adminRefresh)).isEqualTo(Duration.ofHours(12));
        assertThat(row(customerRefresh).get("subject_type")).isEqualTo("CUSTOMER");
        assertThat(row(venueRefresh).get("subject_type")).isEqualTo("VENUE");
        assertThat(row(adminRefresh).get("subject_type")).isEqualTo("ADMIN");
    }

    @Test
    void theDatabaseKeepsOnlyTheHashOfTheRefreshToken() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String raw = customerTokens(c.username()).refresh();

        assertThat(Base64.getUrlDecoder().decode(raw)).as("256 random bits").hasSize(32);
        Map<String, Object> stored = db.sql("SELECT * FROM iam.auth_refresh_token WHERE subject_id = :id")
                .param("id", c.id()).query().singleRow();
        assertThat(stored.get("token_hash")).isEqualTo(Sha256.hex(raw));
        stored.values().forEach(value -> assertThat(String.valueOf(value)).doesNotContain(raw));
        assertThat(db.sql("SELECT count(*) FROM iam.auth_refresh_token WHERE token_hash = :raw")
                .param("raw", raw).query(Integer.class).single()).isZero();
    }

    @Test
    void aRefreshRotatesTheToken() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String a = customerTokens(c.username()).refresh();

        JsonNode next = read(refresh("customer", a).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).path("data");
        String b = next.path("refreshToken").asText();

        assertThat(b).isNotEqualTo(a);
        assertThat(claims(next.path("accessToken").asText()).path("sub").asText()).isEqualTo(c.id().toString());
        Map<String, Object> old = row(a);
        assertThat(old.get("revoked_at")).isNotNull();
        assertThat(old.get("rotated_to")).isEqualTo(row(b).get("id"));
        assertThat(row(b).get("revoked_at")).isNull();
        refresh("customer", b).andExpect(status().isOk());
        refresh("customer", a).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void reusingTheFirstRotatedTokenRevokesTheCurrentOne() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String a = customerTokens(c.username()).refresh();
        String b = refreshed(a);
        String cToken = refreshed(b);

        refresh("customer", a).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        assertThat(row(cToken).get("revoked_at")).as("the usable descendant is revoked").isNotNull();
        refresh("customer", cToken).andExpect(status().isUnauthorized());
        assertThat(liveSessionsOf(c.id())).isZero();
        assertThat(db.sql("SELECT count(*) FROM iam.auth_refresh_token WHERE subject_id = :id")
                .param("id", c.id()).query(Integer.class).single()).as("history kept").isEqualTo(3);
    }

    @Test
    void reusingAMiddleRotatedTokenRevokesTheCurrentOne() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String a = customerTokens(c.username()).refresh();
        String b = refreshed(a);
        String cToken = refreshed(b);

        refresh("customer", b).andExpect(status().isUnauthorized());

        refresh("customer", cToken).andExpect(status().isUnauthorized());
        assertThat(liveSessionsOf(c.id())).isZero();
    }

    @Test
    void reuseRevokesOnlyItsOwnChain() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String phone = customerTokens(c.username()).refresh();
        String otherDevice = customerTokens(c.username()).refresh();
        refreshed(phone);

        refresh("customer", phone).andExpect(status().isUnauthorized());

        refresh("customer", otherDevice).andExpect(status().isOk());
    }

    @Test
    void concurrentRefreshesOfOneTokenNeverLeaveTwoValidSuccessors() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
            String a = sessions.start(AuthSubjectType.CUSTOMER, c.id()).orElseThrow().refreshToken();
            int threads = 6;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<AuthSessions.TokenPair>> results = new ArrayList<>();
            try {
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return sessions.refresh(AuthSubjectType.CUSTOMER, a);
                    }));
                }
                start.countDown();
                int succeeded = 0;
                for (Future<AuthSessions.TokenPair> result : results) {
                    try {
                        result.get();
                        succeeded++;
                    } catch (ExecutionException e) {
                        assertThat(e.getCause()).isInstanceOf(ApiException.class);
                        assertThat(((ApiException) e.getCause()).code()).isEqualTo(ApiErrorCode.UNAUTHENTICATED);
                    }
                }
                assertThat(succeeded).as("round " + round + ": exactly one rotation").isEqualTo(1);
                assertThat(liveSessionsOf(c.id())).as("round " + round + ": the reuse revoked its successor").isZero();
                assertThat(db.sql("SELECT count(*) FROM iam.auth_refresh_token WHERE subject_id = :id")
                        .param("id", c.id()).query(Integer.class).single()).as("one successor only").isEqualTo(2);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void anExpiredOrRevokedRefreshTokenIsRefused() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String expired = customerTokens(c.username()).refresh();
        db.sql("UPDATE iam.auth_refresh_token SET issued_at = now() - interval '31 days', "
                        + "expires_at = now() - interval '1 day' WHERE token_hash = :hash")
                .param("hash", Sha256.hex(expired)).update();

        refresh("customer", expired).andExpect(status().isUnauthorized());
        assertThat(row(expired).get("rotated_to")).isNull();

        String revoked = customerTokens(c.username()).refresh();
        logout("customer", revoked).andExpect(status().isNoContent());
        refresh("customer", revoked).andExpect(status().isUnauthorized());
        refresh("customer", "never-issued-" + UUID.randomUUID()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void logoutRevokesTheSessionAndKeepsItsHistory() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String a = customerTokens(c.username()).refresh();
        String b = refreshed(a);

        logout("customer", b).andExpect(status().isNoContent());

        assertThat(row(b).get("revoked_at")).isNotNull();
        assertThat(liveSessionsOf(c.id())).isZero();
        refresh("customer", b).andExpect(status().isUnauthorized());
        assertThat(db.sql("SELECT count(*) FROM iam.auth_refresh_token WHERE subject_id = :id")
                .param("id", c.id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void anotherAudiencesRefreshTokenIsNeitherUsedNorTouched() throws Exception {
        String login = "venue-" + UUID.randomUUID();
        TestAccounts.venue(db, login, passwordHash);
        String venueRefresh = read(login("venue", Map.of("loginIdentifier", login, "password", PASSWORD))
                .andReturn().getResponse().getContentAsString()).path("data").path("refreshToken").asText();

        refresh("customer", venueRefresh).andExpect(status().isUnauthorized());
        refresh("admin", venueRefresh).andExpect(status().isUnauthorized());
        logout("customer", venueRefresh).andExpect(status().isNoContent());

        assertThat(row(venueRefresh).get("revoked_at")).isNull();
        assertThat(row(venueRefresh).get("rotated_to")).isNull();
        refresh("venue", venueRefresh).andExpect(status().isOk());
    }

    @Test
    void refreshWorksWithAnExpiredAccessTokenInTheHeader() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String a = customerTokens(c.username()).refresh();

        mvc.perform(post("/api/v1/customer/auth/refresh").header("Authorization", "Bearer expired.or.invalid")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken", a))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------ rate limit

    @Test
    void tenAttemptsFitTheWindowAndTheEleventhIsRateLimited() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        // Every spelling of the phone is one identity: they share one counter.
        String[] spellings = {c.localPhone(), c.phoneE164(), c.phoneE164().substring(1)};
        for (int attempt = 1; attempt <= 9; attempt++) {
            customerLogin(spellings[attempt % spellings.length], "Wr0ngPassword")
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        }
        customerLogin(c.localPhone(), PASSWORD).andExpect(status().isOk());                 // the 10th still fits

        customerLogin(c.localPhone(), PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(sessionsOf(c.id())).as("only the 10th attempt started a session").isEqualTo(1);

        String otherIp = ip;
        ip = "192.0.2." + ThreadLocalRandom.current().nextInt(1, 255);
        assertThat(ip).isNotEqualTo(otherIp);
        customerLogin(c.localPhone(), PASSWORD).andExpect(status().isOk());      // another address, another counter
    }

    @Test
    void theWindowRestartsAfterFifteenMinutes() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        for (int attempt = 1; attempt <= 11; attempt++) {
            customerLogin(c.username(), "Wr0ngPassword");
        }
        customerLogin(c.username(), PASSWORD).andExpect(status().isTooManyRequests());

        String key = LoginRateLimiter.subjectKeyHash(c.username(), ip);
        db.sql("UPDATE iam.security_rate_limit_counter SET window_started_at = now() - interval '15 minutes 1 second' "
                        + "WHERE limiter_type = 'LOGIN' AND action_class = 'CUSTOMER' AND subject_key_hash = :key")
                .param("key", key).update();

        customerLogin(c.username(), PASSWORD).andExpect(status().isOk());
        assertThat(db.sql("SELECT counter FROM iam.security_rate_limit_counter WHERE subject_key_hash = :key")
                .param("key", key).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theLimiterKeyIsAHashAndNeverTheLoginIdentifier() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        customerLogin(c.localPhone(), "Wr0ngPassword");
        String venue = "Venue-" + UUID.randomUUID();
        login("venue", Map.of("loginIdentifier", venue, "password", "Wr0ngPassword"));

        Map<String, Object> customerRow = db.sql("""
                        SELECT * FROM iam.security_rate_limit_counter
                         WHERE limiter_type = 'LOGIN' AND action_class = 'CUSTOMER' AND subject_key_hash = :key""")
                .param("key", LoginRateLimiter.subjectKeyHash(c.phoneE164(), ip)).query().singleRow();
        Map<String, Object> venueRow = db.sql("""
                        SELECT * FROM iam.security_rate_limit_counter
                         WHERE limiter_type = 'LOGIN' AND action_class = 'VENUE' AND subject_key_hash = :key""")
                .param("key", LoginRateLimiter.subjectKeyHash(venue.toLowerCase(), ip)).query().singleRow();

        for (Map<String, Object> row : List.of(customerRow, venueRow)) {
            assertThat((String) row.get("subject_key_hash")).matches("[0-9a-f]{64}");
            assertThat(row.get("counter")).isEqualTo(1);
            assertThat(row.get("window_seconds")).isEqualTo(15 * 60);
            row.values().forEach(value -> assertThat(String.valueOf(value))
                    .doesNotContain(c.phoneE164().substring(1)).doesNotContain(c.localPhone().substring(1))
                    .doesNotContainIgnoringCase(venue).doesNotContain(ip));
        }
        assertThat(db.sql("SELECT count(*) FROM iam.security_rate_limit_counter WHERE subject_key_hash LIKE :raw")
                .param("raw", "%" + c.localPhone().substring(1) + "%").query(Integer.class).single()).isZero();
    }

    @Test
    void theLimiterKeyEncodingIsUnambiguous() {
        assertThat(LoginRateLimiter.subjectKeyHash("a|1", "2")).isNotEqualTo(LoginRateLimiter.subjectKeyHash("a", "1|2"));
        assertThat(LoginRateLimiter.subjectKeyHash("x", "10.0.0.1"))
                .isEqualTo(Sha256.hex("1:x|10.0.0.1"));
    }

    @Test
    void aLimiterStorageFailureNeverLetsALoginThrough() throws Exception {
        TestCustomers.Customer c = TestCustomers.insert(db, true, passwordHash);
        String key = LoginRateLimiter.subjectKeyHash(c.username(), ip);
        String trigger = "e7_14_test_limiter_failure_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection owner = ownerConnection(); Statement sql = owner.createStatement()) {
            sql.execute("""
                    CREATE FUNCTION public.%1$s() RETURNS trigger LANGUAGE plpgsql AS $f$
                    BEGIN RAISE EXCEPTION 'simulated limiter storage failure' USING ERRCODE = '58030'; END $f$;
                    """.formatted(trigger));
            sql.execute(("CREATE TRIGGER %1$s BEFORE INSERT OR UPDATE ON iam.security_rate_limit_counter "
                    + "FOR EACH ROW WHEN (NEW.subject_key_hash = '%2$s') EXECUTE FUNCTION public.%1$s()")
                    .formatted(trigger, key));
            try {
                customerLogin(c.username(), PASSWORD)
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andExpect(jsonPath("$.error").doesNotExist());
                assertThatThrownBy(() -> sessions.login(AuthSubjectType.CUSTOMER, c.username(), PASSWORD, ip))
                        .isInstanceOf(DataAccessException.class);
            } finally {
                sql.execute("DROP TRIGGER %1$s ON iam.security_rate_limit_counter".formatted(trigger));
                sql.execute("DROP FUNCTION public.%1$s()".formatted(trigger));
            }
        }
        assertThat(sessionsOf(c.id())).as("no session was started").isZero();
        customerLogin(c.username(), PASSWORD).andExpect(status().isOk());     // works once storage is back
    }

    // ------------------------------------------------------------------------------------------------ helpers

    record Tokens(String access, String refresh) {
    }

    private Tokens customerTokens(String identifier) throws Exception {
        JsonNode data = read(customerLogin(identifier, PASSWORD).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).path("data");
        return new Tokens(data.path("accessToken").asText(), data.path("refreshToken").asText());
    }

    private ResultActions customerLogin(String usernameOrPhone, String password) throws Exception {
        return login("customer", Map.of("usernameOrPhone", usernameOrPhone, "password", password));
    }

    private ResultActions login(String group, Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/" + group + "/auth/login").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions refresh(String group, String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/" + group + "/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private String refreshed(String refreshToken) throws Exception {
        return read(refresh("customer", refreshToken).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).path("data").path("refreshToken").asText();
    }

    private ResultActions logout(String group, String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/" + group + "/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private ResultActions probe(String path, String accessToken) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + accessToken));
    }

    private Map<String, Object> row(String rawRefreshToken) {
        return db.sql("SELECT * FROM iam.auth_refresh_token WHERE token_hash = :hash")
                .param("hash", Sha256.hex(rawRefreshToken)).query().singleRow();
    }

    private Duration lifetime(String rawRefreshToken) {
        Map<String, Object> row = db.sql("SELECT issued_at, expires_at FROM iam.auth_refresh_token WHERE token_hash = :hash")
                .param("hash", Sha256.hex(rawRefreshToken)).query().singleRow();
        return Duration.between(((Timestamp) row.get("issued_at")).toInstant(),
                ((Timestamp) row.get("expires_at")).toInstant());
    }

    private int sessionsOf(UUID subjectId) {
        return db.sql("SELECT count(*) FROM iam.auth_refresh_token WHERE subject_id = :id")
                .param("id", subjectId).query(Integer.class).single();
    }

    private int liveSessionsOf(UUID subjectId) {
        return db.sql("SELECT count(*) FROM iam.auth_refresh_token WHERE subject_id = :id AND revoked_at IS NULL")
                .param("id", subjectId).query(Integer.class).single();
    }

    /** O19 anonymisation through its producer, as a Platform Admin command (arch 04 section 2.2). */
    private void anonymise(UUID userId) {
        UUID adminId = UUID.randomUUID();
        db.sql("INSERT INTO iam.admin_account (id, email, password_hash, full_name) VALUES (:id, :email, 'x', 'Test')")
                .param("id", adminId).param("email", "a-" + adminId + "@example.com").update();
        runner.run(new AnonymiseUser(adminId, userId));
    }

    record AnonymiseUser(UUID adminId, UUID userId) implements Command<UUID> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, UUID.randomUUID(), LockSet.builder().users(userId).build());
        }

        @Override
        public UUID execute(CommandContext ctx) {
            return ctx.jdbc().sql("SELECT iam.anonymise_user(:id, 'E7-14 test')").param("id", userId)
                    .query(UUID.class).single();
        }
    }

    /** The DataSource, counting every statement prepared on its connections. */
    private static DataSource countingStatements(DataSource target, AtomicInteger counter) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (!(result instanceof Connection connection)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if (m.getName().startsWith("prepare") || m.getName().equals("createStatement")) {
                                    counter.incrementAndGet();
                                }
                                return invoke(connection, m, a);
                            });
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(PostgresTestContainer.jdbcUrl(PostgresTestContainer.DATABASE),
                "postgres", "postgres");
    }

    private JsonNode claims(String jwt) {
        return read(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
