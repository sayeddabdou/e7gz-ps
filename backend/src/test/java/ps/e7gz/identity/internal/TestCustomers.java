package ps.e7gz.identity.internal;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;

/** E7-14 test data: a Customer row written directly, verified or not, with a known password hash. */
public final class TestCustomers {

    private TestCustomers() {
    }

    public record Customer(UUID id, String username, String localPhone, String phoneE164) {
    }

    public static Customer insert(JdbcClient db, boolean phoneVerified, String passwordHash) {
        return insert(db, phoneVerified, passwordHash, null);
    }

    /** @param username the username to store, or null for a random one */
    public static Customer insert(JdbcClient db, boolean phoneVerified, String passwordHash, String username) {
        UUID id = UUID.randomUUID();
        if (username == null) {
            username = "u" + id.toString().replace("-", "").substring(0, 12);
        }
        String local = randomLocalPhone();
        String phone = PhoneNumbers.toE164(local).orElseThrow();
        db.sql("""
                        INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                      phone_verified, phone_verified_at, email_verified, referral_code,
                                                      joined_at)
                        VALUES (:id, 'Test', :username, :phone, 't@example.com', :hash, :verified,
                                CASE WHEN :verified THEN now() END, false, :referral, now())""")
                .param("id", id)
                .param("username", username)
                .param("phone", phone)
                .param("hash", passwordHash)
                .param("verified", phoneVerified)
                .param("referral", ReferralCodes.newCode())
                .update();
        return new Customer(id, username, local, phone);
    }

    /** E7-119: an active push device of the customer, as {@code POST /customer/notifications/devices} leaves it. */
    public static UUID insertPushDevice(JdbcClient db, UUID userId) {
        UUID id = UUID.randomUUID();
        db.sql("""
                        INSERT INTO notification.device_token (id, user_id, token, platform, last_seen_at, created_at)
                        VALUES (:id, :user, :token, 'ANDROID', now(), now())""")
                .param("id", id).param("user", userId).param("token", "test-token-" + id).update();
        return id;
    }

    /** E7-119: how many of the customer's push devices still receive push. */
    public static long activePushDevices(JdbcClient db, UUID userId) {
        return db.sql("SELECT count(*) FROM notification.device_token WHERE user_id = :user AND revoked_at IS NULL")
                .param("user", userId).query(Long.class).single();
    }

    /** A random Egyptian mobile number in local form ({@code 010XXXXXXXX}). */
    public static String randomLocalPhone() {
        return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
