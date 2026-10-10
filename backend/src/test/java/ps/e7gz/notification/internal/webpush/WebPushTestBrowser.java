package ps.e7gz.notification.internal.webpush;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The browser's side of RFC 8291, for tests: decrypts an {@code aes128gcm} Web Push body with its own key pair. */
final class WebPushTestBrowser {

    private WebPushTestBrowser() {
    }

    static byte[] decrypt(byte[] body, KeyPair browser, byte[] authSecret) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt();                                                         // record size
        byte[] serverPublic = new byte[in.get()];
        in.get(serverPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browser.getPrivate());
        agreement.doPhase(P256.publicKey(serverPublic), true);
        byte[] browserPublic = P256.encode((ECPublicKey) browser.getPublic());
        byte[] info = join("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), browserPublic, serverPublic);
        byte[] ikm = expand(hmac(authSecret, agreement.generateSecret()), info, 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        int end = padded.length - 1;
        while (padded[end] == 0) {
            end--;
        }
        if (padded[end] != 0x02) {
            throw new IllegalStateException("Not the last record");
        }
        return Arrays.copyOf(padded, end);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] expand(byte[] prk, byte[] info, int length) throws Exception {
        return Arrays.copyOf(hmac(prk, join(info, new byte[] {1})), length);
    }

    private static byte[] join(byte[]... parts) {
        ByteBuffer out = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        for (byte[] part : parts) {
            out.put(part);
        }
        return out.array();
    }
}
