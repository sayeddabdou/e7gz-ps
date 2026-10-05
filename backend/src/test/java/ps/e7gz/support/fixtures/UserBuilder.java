package ps.e7gz.support.fixtures;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A Customer ({@code iam.user_account}) as registration leaves it: phone not verified, unless {@link #verified()}
 * (Account Verified, FR-ACC-02). Phone, username and referral code are unique. The password hash is stored as given:
 * pass a real Argon2id hash when the test logs in. The database triggers add the loyalty accounts and the commitment
 * guard, as for a registered Customer.
 */
public final class UserBuilder {

    private String fullName = "Test Customer";
    private String username;
    private String localPhone;
    private String email = "customer@example.com";
    private String passwordHash = "not-used-here";
    private boolean verified;

    UserBuilder() {
    }

    public UserBuilder verified() {
        this.verified = true;
        return this;
    }

    public UserBuilder unverified() {
        this.verified = false;
        return this;
    }

    public UserBuilder fullName(String fullName) {
        this.fullName = fullName;
        return this;
    }

    public UserBuilder username(String username) {
        this.username = username;
        return this;
    }

    /** Local form, {@code 01XXXXXXXXX}; stored as E.164. */
    public UserBuilder phone(String localPhone) {
        this.localPhone = localPhone;
        return this;
    }

    public UserBuilder email(String email) {
        this.email = email;
        return this;
    }

    public UserBuilder passwordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        return this;
    }

    public Fixtures.User create() {
        return create(FixtureDb.owner());
    }

    public Fixtures.User create(JdbcClient db) {
        UUID id = FixtureDb.id();
        String name = username != null ? username : "u" + FixtureDb.unique();
        String local = localPhone != null ? localPhone : FixtureDb.uniqueLocalMobile();
        String e164 = "+2" + local;
        db.sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, phone_verified_at, email_verified, referral_code,
                                                      joined_at)
                        VALUES (:id, :fullName, :username, :phone, :email, :hash, :verified,
                                CASE WHEN :verified THEN now() END, false, :referral, now())""")
                .param("id", id).param("fullName", fullName).param("username", name).param("phone", e164)
                .param("email", email).param("hash", passwordHash).param("verified", verified)
                .param("referral", "F" + FixtureDb.unique().toUpperCase(Locale.ROOT)).update();
        return new Fixtures.User(id, name, local, e164);
    }
}
