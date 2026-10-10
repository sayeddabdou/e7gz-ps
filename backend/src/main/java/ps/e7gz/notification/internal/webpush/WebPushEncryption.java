package ps.e7gz.notification.internal.webpush;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Message encryption for Web Push (RFC 8291) in the {@code aes128gcm} content coding (RFC 8188): ECDH between a fresh
 * application-server key pair and the browser's {@code p256dh} key, HKDF-SHA-256 with the browser's {@code auth}
 * secret, then one AES-128-GCM record. The push service never sees the text.
 */
final class WebPushEncryption {

    /** RFC 8188 record size; the whole message is one record. */
    static final int RECORD_SIZE = 4096;
    private static final int TAG = 16;
    private static final int AUTH_SECRET = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushEncryption() {
    }

    /** Encrypts with a new application-server key pair and salt, as every message must. */
    static byte[] encrypt(byte[] plaintext, ECPublicKey userAgentPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, userAgentPublic, authSecret, newKeyPair(), salt);
    }

    /** RFC 8291 section 3.4; the key pair and salt are parameters only so the RFC's Appendix A can be checked. */
    static byte[] encrypt(byte[] plaintext, ECPublicKey userAgentPublic, byte[] authSecret, KeyPair applicationServer,
                          byte[] salt) {
        if (authSecret.length != AUTH_SECRET) {
            throw new IllegalArgumentException("The auth secret must be 16 bytes");
        }
        if (plaintext.length + 1 + TAG > RECORD_SIZE) {
            throw new IllegalArgumentException("The message is too long for one Web Push record");
        }
        try {
            byte[] uaPublic = P256.encode(userAgentPublic);
            byte[] asPublic = P256.encode((ECPublicKey) applicationServer.getPublic());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(applicationServer.getPrivate());
            agreement.doPhase(userAgentPublic, true);
            byte[] ecdhSecret = agreement.generateSecret();

            // section 3.3: IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" || 0x00 || ua_public || as_public, 32)
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
            byte[] ikm = expand(hmac(authSecret, ecdhSecret), keyInfo, 32);
            // RFC 8188 section 2.2-2.3
            byte[] prk = hmac(salt, ikm);
            byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
            byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG * 8, nonce));
            byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
            padded[plaintext.length] = 0x02;                                 // last-record delimiter, no padding
            byte[] ciphertext = cipher.doFinal(padded);

            ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length);
            body.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(ciphertext);
            return body.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push encryption failed", e);
        }
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256.PARAMETERS, RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This JVM has no P-256", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** HKDF-Expand (RFC 5869) for at most one block, which is all Web Push needs. */
    private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        return Arrays.copyOf(hmac(prk, concat(info, new byte[] {0x01})), length);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        ByteBuffer out = ByteBuffer.allocate(length);
        for (byte[] part : parts) {
            out.put(part);
        }
        return out.array();
    }
}
