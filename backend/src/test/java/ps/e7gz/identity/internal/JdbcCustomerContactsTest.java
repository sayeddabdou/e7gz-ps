package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.identity.api.CustomerContacts;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * {@link CustomerContacts} (E7-47): the username and E.164 phone of a Customer; an anonymised account keeps its username
 * and has no phone ({@code ck_user_anonymised_pii}); an unknown User is empty. E7-49: the exact-username lookup of the
 * venue booking search (PO decision 11 Oct 2026, E7-49, D3 amendment).
 */
class JdbcCustomerContactsTest extends IntegrationTest {

    @Autowired
    CustomerContacts contacts;

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    @Test
    void aCustomerHasItsUsernameAndE164Phone() {
        Fixtures.User user = Fixtures.aUser().verified().create();

        assertThat(find(user.id()))
                .contains(new CustomerContacts.Contact(user.id(), user.username(), user.phoneE164()));
    }

    @Test
    void anAnonymisedAccountHasNoPhone() {
        UUID id = UUID.randomUUID();
        String username = CustomerRegistrationHttpTest.randomUsername();
        JdbcClient.create(appDataSource).sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, phone_verified_at, email_verified, referral_code,
                                                      anonymised_at, joined_at)
                        VALUES (:id, '', :username, 'anonymised:' || CAST(:id AS text), '', '', true,
                                now() - interval '1 day', false, :referral, now(), now() - interval '2 days')""")
                .param("id", id).param("username", username).param("referral", ReferralCodes.newCode())
                .update();

        assertThat(find(id)).contains(new CustomerContacts.Contact(id, username, null));
    }

    @Test
    void anUnknownUserIsEmpty() {
        assertThat(find(UUID.randomUUID())).isEmpty();
    }

    // -------------------------------------- findByUsername: PO decision 11 Oct 2026, E7-49, D3 amendment (exact username)

    @Test
    void theExactUsernameFindsItsCustomerWithUsernameLoginsTrimAndCaseRule() {
        Fixtures.User user = Fixtures.aUser().verified().create();

        assertThat(byUsername(user.username())).contains(user.id());
        assertThat(byUsername(user.username().toUpperCase(Locale.ROOT))).contains(user.id());
        assertThat(byUsername("  " + user.username() + " ")).as("trimmed like login (btrim)").contains(user.id());
    }

    @Test
    void aPartOfAUsernameFindsNobody() {
        Fixtures.User user = Fixtures.aUser().verified().create();
        String username = user.username();

        assertThat(byUsername(username.substring(0, username.length() - 1))).isEmpty();
        assertThat(byUsername(username.substring(1))).isEmpty();
        assertThat(byUsername(username + "x")).isEmpty();
        assertThat(byUsername("%")).isEmpty();
    }

    @Test
    void aPhoneNumberFindsNobody() {
        Fixtures.User user = Fixtures.aUser().verified().create();

        assertThat(byUsername(user.phoneE164())).as("never the login's phone half").isEmpty();
        assertThat(byUsername("0" + user.phoneE164().substring(3))).isEmpty();
    }

    @Test
    void anAnonymisedAccountsUsernameAndABlankOneFindNobody() {
        UUID id = UUID.randomUUID();
        String username = CustomerRegistrationHttpTest.randomUsername();
        JdbcClient.create(appDataSource).sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, phone_verified_at, email_verified, referral_code,
                                                      anonymised_at, joined_at)
                        VALUES (:id, '', :username, 'anonymised:' || CAST(:id AS text), '', '', true,
                                now() - interval '1 day', false, :referral, now(), now() - interval '2 days')""")
                .param("id", id).param("username", username).param("referral", ReferralCodes.newCode())
                .update();

        assertThat(byUsername(username)).as("FR-ACC-10").isEmpty();
        assertThat(byUsername("   ")).isEmpty();
        assertThat(byUsername("")).isEmpty();
    }

    private Optional<UUID> byUsername(String username) {
        return runner.run(new Command<Optional<UUID>>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public Optional<UUID> execute(CommandContext context) {
                return contacts.findByUsername(context, username);
            }
        });
    }

    private Optional<CustomerContacts.Contact> find(UUID userId) {
        return runner.run(new Command<Optional<CustomerContacts.Contact>>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public Optional<CustomerContacts.Contact> execute(CommandContext context) {
                return contacts.find(context, userId);
            }
        });
    }
}
