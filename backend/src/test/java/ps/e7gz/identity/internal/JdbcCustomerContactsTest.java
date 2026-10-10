package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
 * and has no phone ({@code ck_user_anonymised_pii}); an unknown User is empty.
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
