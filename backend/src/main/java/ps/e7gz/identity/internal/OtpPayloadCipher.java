package ps.e7gz.identity.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Seals a phone OTP for the outbox payload (arch 10 section 2.2: never stored in plain text; PO decision 9 Oct 2026):
 * AES-256-GCM with a random 96-bit nonce and the challenge id as associated data, so a sealed code opens only for its
 * own challenge. The key comes from the secret store ({@code E7GZ_OTP_PAYLOAD_KEY}, arch 12 section 3); the application
 * does not start without one. The api profile seals, the worker's dispatcher opens - only to send the SMS. Neither the
 * code nor the key is ever logged or put in an exception message.
 *
 * <p>An outbox payload never changes after insert ({@code technical.guard_outbox}), so the sealed code stays in the row;
 * it is useless after its 5 minutes ({@code ck_otp_validity}).
 */
@Component
@EnableConfigurationProperties(OtpPayloadCipher.Properties.class)
class OtpPayloadCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    OtpPayloadCipher(Properties properties) {
        this.key = key(properties.otpPayloadKey());
    }

    /**
     * The OTP payload key ({@code e7gz.identity.otp-payload-key}): Base64 of 32 random bytes, e.g.
     * {@code openssl rand -base64 32}. From the environment / secret store only.
     */
    @ConfigurationProperties("e7gz.identity")
    record Properties(String otpPayloadKey) {

        @Override
        public String toString() {
            return "Properties[otpPayloadKey=<redacted>]";
        }
    }

    /** Base64 of {@code nonce || ciphertext || tag}. */
    String seal(UUID challengeId, String code) {
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(code, "code");
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(challengeId));
            byte[] sealed = cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is required by every Java platform", e);
        }
    }

    /** The code sealed for this challenge; fails when it was sealed with another key or for another challenge. */
    String open(UUID challengeId, String sealed) {
        Objects.requireNonNull(challengeId, "challengeId");
        try {
            byte[] bytes = Base64.getDecoder().decode(Objects.requireNonNull(sealed, "sealed"));
            if (bytes.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("The sealed OTP is too short");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES));
            cipher.updateAAD(associatedData(challengeId));
            return new String(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Never the value itself: only that it does not open.
            throw new IllegalArgumentException("The sealed OTP of challenge " + challengeId + " does not open with "
                    + "this key (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] associatedData(UUID challengeId) {
        return challengeId.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static SecretKey key(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("e7gz.identity.otp-payload-key (E7GZ_OTP_PAYLOAD_KEY) is not set");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("e7gz.identity.otp-payload-key must be Base64");    // never echo the value
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("e7gz.identity.otp-payload-key must decode to exactly " + KEY_BYTES
                    + " bytes (AES-256)");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
