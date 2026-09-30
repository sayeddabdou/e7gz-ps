package ps.e7gz.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * OTP codes (arch 10 section 2.4, section 2.2): 6 digits from a CSPRNG, stored as SHA-256 with a per-challenge salt,
 * compared in constant time. The plain code exists only in memory between generation and the SMS send.
 *
 * <p>Stored form in {@code iam.otp_challenge.code_hash}: {@code base64(salt) + ":" + base64(sha256(salt || code))}.
 */
final class OtpCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;

    private OtpCodes() {
    }

    static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    static String hash(String code) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        Base64.Encoder base64 = Base64.getEncoder();
        return base64.encodeToString(salt) + ":" + base64.encodeToString(sha256(salt, code));
    }

    /** Constant-time comparison; a malformed stored value never matches. */
    static boolean matches(String code, String stored) {
        if (code == null || stored == null) {
            return false;
        }
        String[] parts = stored.split(":", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            return MessageDigest.isEqual(sha256(base64.decode(parts[0]), code), base64.decode(parts[1]));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] sha256(byte[] salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
