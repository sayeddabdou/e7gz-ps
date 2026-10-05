package ps.e7gz.seed;

/**
 * The seeded logins (PO decision 5 Oct 2026, E7-28): one documented dev-only password for every seeded account and one
 * Owner Password (PIN, PCR-006) for every seeded venue. They live only in the seed source set, never in the application
 * jar, and {@link SeedGuard} refuses any database that is not on this machine - the same standing as the
 * {@code docker compose} credentials. The production rule (no default credentials, Sprint 0 review S-5) is unchanged.
 */
public final class SeedCredentials {

    /** Platform Admin, venue accounts and Customers: 8+ characters with a letter and a digit (PasswordRules). */
    public static final String PASSWORD = "Dev12345";
    /** Every seeded venue's Owner Password: exactly four digits (PCR-006). */
    public static final String OWNER_PIN = "1234";
    public static final String ADMIN_EMAIL = "admin@e7gz.local";
    public static final String ADMIN_NAME = "Seed Admin";

    private SeedCredentials() {
    }
}
