package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-42: the fingerprint leaves out exactly the members the database calls secret in any stored payload
 * ({@code technical.forbidden_payload_key}), so the Java copy of the name rule cannot drift from the database's.
 */
class IdempotencySecretNamesTest extends IntegrationTest {

    static final List<String> NAMES = List.of(
            // secret by suffix or by exact name
            "ownerPassword", "owner_password", "initialOwnerPassword", "password", "passwd", "passcode", "passphrase",
            "clientSecret", "otp", "otpCode", "accessToken", "refresh-token", "previewToken", "apiKey", "privateKey",
            "Authorization", "cookie", "cvv", "cvc", "cardNumber", "pan", "pin", "PIN", "hash", "salt",
            // ordinary
            "bookingId", "reasonText", "startAt", "durationMinutes", "playMode", "customerPhone", "customerName",
            "pinned", "hashtag", "saltwater", "tokens", "resourceId", "label", "quantity", "options", "data");

    @Autowired
    DataSource appDataSource;

    @Test
    void theFingerprintNameRuleIsTheDatabaseRule() {
        JdbcClient db = JdbcClient.create(appDataSource);
        for (String name : NAMES) {
            boolean database = db.sql("SELECT technical.forbidden_payload_key(jsonb_build_object(:name, 1)) IS NOT NULL")
                    .param("name", name).query(Boolean.class).single();
            assertThat(RequestFingerprint.isSecretName(name)).as(name).isEqualTo(database);
        }
    }
}
