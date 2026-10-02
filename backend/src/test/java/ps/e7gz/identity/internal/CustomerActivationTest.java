package ps.e7gz.identity.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-13 done-when: an unverified user cannot reach booking (403 {@code PHONE_VERIFICATION_REQUIRED}, FR-ACC-03).
 * The booking endpoints do not exist yet, so a stand-in endpoint runs a category-A command that calls the gate the way
 * the booking, tournament and review commands will.
 */
@AutoConfigureMockMvc
@Import(CustomerActivationTest.StandInBooking.class)
class CustomerActivationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DataSource appDataSource;

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class StandInBooking {

        private final CommandRunner runner;
        private final CustomerActivation activation;

        StandInBooking(CommandRunner runner, CustomerActivation activation) {
            this.runner = runner;
            this.activation = activation;
        }

        /** The stand-in route sits outside /api, which the production chains deny (E7-22); open it for this test. */
        @Bean
        @Order(0)
        SecurityFilterChain standInBookingChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/test/e7-13/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .build();
        }

        @PostMapping("/test/e7-13/customers/{userId}/bookings")
        @ResponseStatus(HttpStatus.CREATED)
        void book(@PathVariable UUID userId) {
            runner.run(new Command<Void>() {
                @Override
                public EligibilityCategory eligibilityCategory() {
                    return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
                }

                @Override
                public CommandScope scope() {
                    return CommandScope.shared(LockSet.builder().users(userId).build());
                }

                @Override
                public Void execute(CommandContext ctx) {
                    activation.requirePhoneVerified(ctx, userId);
                    return null;
                }
            });
        }
    }

    @Test
    void anUnverifiedUserIsRefusedWithPhoneVerificationRequired() throws Exception {
        UUID user = insertUser(false);

        mvc.perform(post("/test/e7-13/customers/{id}/bookings", user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PHONE_VERIFICATION_REQUIRED"));
    }

    @Test
    void aVerifiedUserPassesTheGate() throws Exception {
        UUID user = insertUser(true);

        mvc.perform(post("/test/e7-13/customers/{id}/bookings", user)).andExpect(status().isCreated());
    }

    @Test
    void aVerifiedEmailAloneNeverActivates() throws Exception {
        UUID user = insertUser(false);
        JdbcClient.create(appDataSource).sql("UPDATE iam.user_account SET email_verified = true WHERE id = :id")
                .param("id", user).update();

        mvc.perform(post("/test/e7-13/customers/{id}/bookings", user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PHONE_VERIFICATION_REQUIRED"));
    }

    @Test
    void aDeletedAccountIsRefusedWithAccountDeletedEvenIfItWasVerified() throws Exception {
        UUID user = insertDeletedUser();

        mvc.perform(post("/test/e7-13/customers/{id}/bookings", user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DELETED"));
    }

    /**
     * A fixture of an account in its anonymised end state (FR-ACC-10, {@code ck_user_anonymised_pii}), inserted
     * directly: the audited O19 anonymisation flow is E7-103's. Only UPDATEs are guarded, so an INSERT can start here.
     */
    private UUID insertDeletedUser() {
        UUID id = UUID.randomUUID();
        JdbcClient.create(appDataSource).sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, phone_verified_at, email_verified, referral_code,
                                                      anonymised_at, joined_at)
                        VALUES (:id, '', :username, 'anonymised:' || CAST(:id AS text), '', '', true,
                                now() - interval '1 day', false, :referral, now(), now() - interval '2 days')""")
                .param("id", id)
                .param("username", CustomerRegistrationHttpTest.randomUsername())
                .param("referral", ReferralCodes.newCode())
                .update();
        return id;
    }

    private UUID insertUser(boolean phoneVerified) {
        UUID id = UUID.randomUUID();
        JdbcClient.create(appDataSource).sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, phone_verified_at, email_verified, referral_code,
                                                      joined_at)
                        VALUES (:id, 'Test', :username, :phone, 't@example.com', 'x', :verified,
                                CASE WHEN :verified THEN now() END, false, :referral, now())""")
                .param("id", id)
                .param("username", CustomerRegistrationHttpTest.randomUsername())
                .param("phone", CustomerRegistrationHttpTest.e164(CustomerRegistrationHttpTest.randomLocalPhone()))
                .param("verified", phoneVerified)
                .param("referral", ReferralCodes.newCode())
                .update();
        return id;
    }
}
