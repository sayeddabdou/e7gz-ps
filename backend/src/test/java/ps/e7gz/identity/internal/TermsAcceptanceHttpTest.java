package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-124 (PCR-013 items 4-6; PO decisions 15 and 16 Nov 2026): the server keeps proof that a Customer accepted the
 * Terms of Use and the Privacy Policy, which version and when, through the real security chain and database.
 */
@AutoConfigureMockMvc
class TermsAcceptanceHttpTest extends IntegrationTest {

    private static final String REGISTER = "/api/v1/customer/auth/register";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    LegalTextsProperties legal;

    @Autowired
    CommandRunner runner;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    DataSource appDataSource;

    JdbcClient db;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
    }

    // ------------------------------------------------------------------------------------------- the public read

    @Test
    void theLegalTextsAreReadWithoutATokenWithTheConfiguredVersionAndAddresses() throws Exception {
        mvc.perform(get("/api/v1/customer/legal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(legal.termsVersion()))
                .andExpect(jsonPath("$.data.termsUrl").value(legal.termsUrl()))
                .andExpect(jsonPath("$.data.privacyUrl").value(legal.privacyUrl()));
        assertThat(legal.termsVersion()).as("development and tests use a placeholder label").isEqualTo("dev");
    }

    // -------------------------------------------------------------------------------------------- registration

    @Test
    void aSignUpWithoutTheVersionIsRefusedOnTheFieldAndCreatesNoAccount() throws Exception {
        String username = randomUsername();
        register(username, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("acceptedTermsVersion"));
        assertNoAccount(username);

        register(username, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("acceptedTermsVersion"));
        assertNoAccount(username);
    }

    @Test
    void aSignUpWithAVersionThatIsNotTheCurrentOneIsRefusedOnTheFieldAndCreatesNoAccount() throws Exception {
        String username = randomUsername();
        register(username, "2020-01-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("acceptedTermsVersion"));
        assertNoAccount(username);

        register(username, legal.termsVersion().toUpperCase() + "x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("acceptedTermsVersion"));
        assertNoAccount(username);
    }

    @Test
    void aSignUpWithTheCurrentVersionStoresTheVersionAndTheCommandInstant() throws Exception {
        Instant before = dbNow();
        UUID userId = registered(randomUsername());
        Instant after = dbNow();

        Map<String, Object> row = db.sql("""
                        SELECT t.terms_version, t.accepted_at, u.joined_at
                          FROM iam.terms_acceptance t JOIN iam.user_account u ON u.id = t.user_id
                         WHERE t.user_id = :id""")
                .param("id", userId).query().singleRow();
        assertThat(row.get("terms_version")).isEqualTo(legal.termsVersion());
        Instant acceptedAt = instant(row.get("accepted_at"));
        assertThat(acceptedAt).as("the registering command's instant").isEqualTo(instant(row.get("joined_at")))
                .isBetween(before, after);
    }

    @Test
    void theRecordedAcceptanceCannotBeChangedOrRemoved() throws Exception {
        UUID userId = registered(randomUsername());

        assertThatThrownBy(() -> db.sql("UPDATE iam.terms_acceptance SET terms_version = 'other' WHERE user_id = :id")
                .param("id", userId).update())
                .hasMessageContaining("written once at registration and never changed");
        assertThatThrownBy(() -> db.sql("UPDATE iam.terms_acceptance SET accepted_at = accepted_at - interval '1 day'"
                        + " WHERE user_id = :id")
                .param("id", userId).update())
                .hasMessageContaining("written once at registration and never changed");
        assertThatThrownBy(() -> db.sql("DELETE FROM iam.terms_acceptance WHERE user_id = :id")
                .param("id", userId).update());
        assertThatThrownBy(() -> runner.run(new TestCommand<>(ctx -> ctx.jdbc().sql("""
                        INSERT INTO iam.terms_acceptance (user_id, terms_version, accepted_at)
                        VALUES (:id, 'second', :at)""")
                .param("id", userId).param("at", OtpChallenges.utc(ctx.commandNow())).update())))
                .as("a second acceptance").isNotNull();

        assertThat(db.sql("SELECT terms_version FROM iam.terms_acceptance WHERE user_id = :id")
                .param("id", userId).query(String.class).list()).containsExactly(legal.termsVersion());
    }

    /** PCR-013 item 6: an account from before the rule keeps no acceptance; none can be added to it later. */
    @Test
    void noAcceptanceCanBeAddedToAnAccountThatAlreadyExists() {
        UUID older = TestCustomers.insert(db, true, "x").id();

        assertThatThrownBy(() -> runner.run(new TestCommand<>(ctx -> ctx.jdbc().sql("""
                        INSERT INTO iam.terms_acceptance (user_id, terms_version, accepted_at)
                        VALUES (:id, :version, :at)""")
                .param("id", older).param("version", legal.termsVersion())
                .param("at", OtpChallenges.utc(ctx.commandNow())).update())))
                .hasMessageContaining("only by the command that registers the Customer");
        assertThatThrownBy(() -> db.sql("""
                        INSERT INTO iam.terms_acceptance (user_id, terms_version, accepted_at)
                        SELECT id, :version, joined_at FROM iam.user_account WHERE id = :id""")
                .param("id", older).param("version", legal.termsVersion()).update())
                .as("outside a command, even with the joined_at instant")
                .hasMessageContaining("command context");

        assertThat(db.sql("SELECT count(*) FROM iam.terms_acceptance WHERE user_id = :id")
                .param("id", older).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------------------------------- the Admin view

    @Test
    void thePlatformAdminSeesTheAcceptedVersionAndInstantOrNothingForAnOlderAccount() throws Exception {
        UUID userId = registered(randomUsername());
        Instant acceptedAt = instant(db.sql("SELECT accepted_at FROM iam.terms_acceptance WHERE user_id = :id")
                .param("id", userId).query().singleValue());
        UUID older = TestCustomers.insert(db, true, "x").id();
        String admin = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());

        mvc.perform(get("/api/v1/admin/users/{id}", userId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAcceptance.version").value(legal.termsVersion()))
                .andExpect(jsonPath("$.data.termsAcceptance.acceptedAt").value(acceptedAt.toString()));
        mvc.perform(get("/api/v1/admin/users/{id}", older).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.termsAcceptance").value(nullValue()));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    record TestCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private UUID registered(String username) throws Exception {
        String body = register(username, legal.termsVersion())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("data").path("userId").asText());
    }

    private ResultActions register(String username, String acceptedTermsVersion) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("fullName", "Terms Test", "username", username,
                "phone", randomLocalPhone(), "email", username + "@example.com", "password", "Passw0rd1"));
        if (acceptedTermsVersion != null) {
            body.put("acceptedTermsVersion", acceptedTermsVersion);
        }
        return mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private void assertNoAccount(String username) {
        assertThat(db.sql("SELECT count(*) FROM iam.user_account WHERE username = :u")
                .param("u", username).query(Long.class).single()).as("no account created").isZero();
    }

    private Instant dbNow() {
        return instant(db.sql("SELECT clock_timestamp()").query().singleValue());
    }

    private static Instant instant(Object value) {
        return value instanceof OffsetDateTime odt ? odt.toInstant() : ((java.sql.Timestamp) value).toInstant();
    }

    private static String randomUsername() {
        return "terms" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
    }

    private static String randomLocalPhone() {
        return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
