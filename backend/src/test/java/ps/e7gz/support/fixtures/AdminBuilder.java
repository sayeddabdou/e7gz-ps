package ps.e7gz.support.fixtures;

import java.util.UUID;

/**
 * An active Platform Admin ({@code iam.admin_account}), always written and committed on the admin runtime connection:
 * no other role may write the table (E7-23, Sprint 0 review S-6). The password hash is stored as given.
 */
public final class AdminBuilder {

    private String email;
    private String fullName = "Test Admin";
    private String passwordHash = "not-used-here";
    private boolean active = true;

    AdminBuilder() {
    }

    public AdminBuilder email(String email) {
        this.email = email;
        return this;
    }

    public AdminBuilder fullName(String fullName) {
        this.fullName = fullName;
        return this;
    }

    public AdminBuilder passwordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        return this;
    }

    public AdminBuilder inactive() {
        this.active = false;
        return this;
    }

    public Fixtures.Admin create() {
        UUID id = FixtureDb.id();
        String address = email != null ? email : "admin-" + FixtureDb.unique() + "@example.com";
        FixtureDb.adminRuntime().sql("""
                        INSERT INTO iam.admin_account (id, email, password_hash, full_name, is_active)
                        VALUES (:id, :email, :hash, :fullName, :active)""")
                .param("id", id).param("email", address).param("hash", passwordHash).param("fullName", fullName)
                .param("active", active).update();
        return new Fixtures.Admin(id, address);
    }
}
