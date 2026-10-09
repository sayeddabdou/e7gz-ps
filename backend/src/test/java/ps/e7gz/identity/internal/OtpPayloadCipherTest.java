package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-41 (PO decision 9 Oct 2026): the OTP travels in the outbox payload sealed with AES-256-GCM, bound to its
 * challenge, with a key from the secret store; nothing about the code or the key shows in an error.
 */
class OtpPayloadCipherTest {

    private static final OtpPayloadCipher CIPHER =
            new OtpPayloadCipher(new OtpPayloadCipher.Properties(IntegrationTest.TEST_OTP_PAYLOAD_KEY));

    @Test
    void aSealedCodeOpensForItsChallengeAndNeverShowsTheCode() {
        UUID challenge = UUID.randomUUID();

        String sealed = CIPHER.seal(challenge, "123456");

        assertThat(sealed).doesNotContain("123456");
        assertThat(Base64.getDecoder().decode(sealed)).hasSize(12 + 6 + 16);       // nonce, code, tag
        assertThat(CIPHER.open(challenge, sealed)).isEqualTo("123456");
        assertThat(CIPHER.seal(challenge, "123456")).as("a fresh nonce every time").isNotEqualTo(sealed);
    }

    @Test
    void itDoesNotOpenForAnotherChallengeAnotherKeyOrAChangedValue() {
        UUID challenge = UUID.randomUUID();
        String sealed = CIPHER.seal(challenge, "654321");
        OtpPayloadCipher otherKey =
                new OtpPayloadCipher(new OtpPayloadCipher.Properties(key("another-32-byte-test-key-for-otp")));
        byte[] changed = Base64.getDecoder().decode(sealed);
        changed[changed.length - 1] ^= 1;

        assertThatThrownBy(() -> CIPHER.open(UUID.randomUUID(), sealed)).isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("654321").doesNotContain(sealed);
        assertThatThrownBy(() -> otherKey.open(challenge, sealed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CIPHER.open(challenge, Base64.getEncoder().encodeToString(changed)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CIPHER.open(challenge, "not base64!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theApplicationDoesNotStartWithoutAProperKey() {
        assertThatThrownBy(() -> new OtpPayloadCipher(new OtpPayloadCipher.Properties(null)))
                .hasMessageContaining("E7GZ_OTP_PAYLOAD_KEY");
        assertThatThrownBy(() -> new OtpPayloadCipher(new OtpPayloadCipher.Properties("  ")))
                .hasMessageContaining("is not set");
        assertThatThrownBy(() -> new OtpPayloadCipher(new OtpPayloadCipher.Properties("not base64!")))
                .hasMessageContaining("Base64").message().doesNotContain("not base64!");
        assertThatThrownBy(() -> new OtpPayloadCipher(new OtpPayloadCipher.Properties(key("only-16-bytes!!!"))))
                .hasMessageContaining("32 bytes");
        assertThat(new OtpPayloadCipher.Properties(IntegrationTest.TEST_OTP_PAYLOAD_KEY).toString())
                .doesNotContain(IntegrationTest.TEST_OTP_PAYLOAD_KEY);
    }

    private static String key(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }
}
