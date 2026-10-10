package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.api.VenueContacts;
import ps.e7gz.venue.api.VenueContacts.Contact;

/**
 * E7-46 - {@link VenueContacts}, the Venue read the customer booking detail adds on creation, against the migrated
 * database on the command's own transaction.
 */
class VenueContactsIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueContacts contacts;

    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

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

    private Optional<Contact> find(UUID venueId) {
        return runner.run(new TestCommand<>(ctx -> contacts.find(ctx, venueId)));
    }

    @Test
    void aVenueAnswersItsNameAndPhone() {
        Fixtures.Venue venue = aVenue().active().create();
        String phone = OWNER.sql("SELECT phone FROM venue.venue WHERE id = :id")
                .param("id", venue.id()).query(String.class).single();

        assertThat(find(venue.id())).contains(new Contact(venue.id(), venue.name(), phone));
    }

    @Test
    void anUnknownVenueIsEmpty() {
        assertThat(find(UUID.randomUUID())).isEmpty();
    }
}
