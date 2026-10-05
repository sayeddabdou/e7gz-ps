package ps.e7gz.support.fixtures;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.codes.VenueAccountStatus;

/**
 * The venue's shared {@code iam.venue_account}, ENABLED, in English. The hashes are stored as given: pass a real
 * Argon2id hash (the context's {@code PasswordEncoder}) when the test logs in or checks the Owner Password. Without
 * {@link #ownerPasswordHash} no {@code iam.venue_owner_secret} row is written.
 */
public final class VenueAccountBuilder {

    private UUID venueId;
    private String loginIdentifier;
    private String passwordHash = "not-used-here";
    private String ownerPasswordHash;
    private VenueAccountStatus status = VenueAccountStatus.ENABLED;
    private VenueAccountLanguage language = VenueAccountLanguage.en;

    VenueAccountBuilder() {
    }

    public VenueAccountBuilder forVenue(UUID venueId) {
        this.venueId = venueId;
        return this;
    }

    public VenueAccountBuilder forVenue(Fixtures.Venue venue) {
        return forVenue(venue.id());
    }

    public VenueAccountBuilder login(String loginIdentifier) {
        this.loginIdentifier = loginIdentifier;
        return this;
    }

    public VenueAccountBuilder passwordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        return this;
    }

    public VenueAccountBuilder ownerPasswordHash(String ownerPasswordHash) {
        this.ownerPasswordHash = ownerPasswordHash;
        return this;
    }

    public VenueAccountBuilder disabled() {
        this.status = VenueAccountStatus.DISABLED;
        return this;
    }

    public VenueAccountBuilder language(VenueAccountLanguage language) {
        this.language = language;
        return this;
    }

    public Fixtures.VenueAccount create() {
        return create(FixtureDb.owner());
    }

    public Fixtures.VenueAccount create(JdbcClient db) {
        UUID venue = venueId != null ? venueId : new VenueBuilder().create(db).id();
        UUID id = FixtureDb.id();
        String login = loginIdentifier != null ? loginIdentifier : "venue-" + FixtureDb.unique();
        db.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, :hash, :status, :language)""")
                .param("id", id).param("venue", venue).param("login", login).param("hash", passwordHash)
                .param("status", status.name()).param("language", language.name()).update();
        if (ownerPasswordHash != null) {
            db.sql("""
                            INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at,
                                                                version)
                            VALUES (:account, :hash, now() - interval '1 day', 0)""")
                    .param("account", id).param("hash", ownerPasswordHash).update();
        }
        return new Fixtures.VenueAccount(id, venue, login);
    }
}
