package ps.e7gz.notification.internal.webpush;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * NIST P-256 keys in the forms Web Push uses (RFC 8291 section 3.1, RFC 8292 section 3.2): a public key is the 65-byte
 * uncompressed point {@code 0x04 || X || Y}, a private key the 32-byte scalar, both URL-safe Base64 without padding
 * (what {@code PushManager.subscribe} and {@code npx web-push generate-vapid-keys} use).
 */
final class P256 {

    static final ECParameterSpec PARAMETERS = parameters();
    private static final int COORDINATE = 32;

    private P256() {
    }

    /** @throws IllegalArgumentException when it is not URL-safe Base64 of a point on P-256 */
    static ECPublicKey publicKey(String base64Url) {
        return publicKey(decode(base64Url));
    }

    static ECPublicKey publicKey(byte[] uncompressed) {
        if (uncompressed.length != 1 + 2 * COORDINATE || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Not an uncompressed P-256 point");
        }
        ECPoint point = new ECPoint(
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 1 + COORDINATE)),
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 1 + COORDINATE, uncompressed.length)));
        if (!onCurve(point)) {
            throw new IllegalArgumentException("Not a point on P-256");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, PARAMETERS));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a P-256 public key", e);
        }
    }

    /** @throws IllegalArgumentException when it is not URL-safe Base64 of a 32-byte P-256 scalar */
    static ECPrivateKey privateKey(String base64Url) {
        byte[] scalar = decode(base64Url);
        if (scalar.length != COORDINATE) {
            throw new IllegalArgumentException("Not a 32-byte P-256 private key");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), PARAMETERS));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a P-256 private key", e);
        }
    }

    static byte[] encode(ECPublicKey key) {
        byte[] out = new byte[1 + 2 * COORDINATE];
        out[0] = 0x04;
        copyUnsigned(key.getW().getAffineX(), out, 1);
        copyUnsigned(key.getW().getAffineY(), out, 1 + COORDINATE);
        return out;
    }

    /** True when {@code privateKey} is the private half of {@code publicKey} (a signature by one verifies with the other). */
    static boolean pair(ECPrivateKey privateKey, ECPublicKey publicKey) {
        byte[] probe = "e7gz-vapid-key-check".getBytes(StandardCharsets.US_ASCII);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static byte[] decode(String base64Url) {
        if (base64Url == null) {
            throw new IllegalArgumentException("Missing key");
        }
        try {
            return Base64.getUrlDecoder().decode(base64Url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not URL-safe Base64", e);
        }
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** y² = x³ + ax + b (mod p) with both coordinates below p; the JDK's key factory does not check it. */
    private static boolean onCurve(ECPoint point) {
        BigInteger p = ((ECFieldFp) PARAMETERS.getCurve().getField()).getP();
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger right = x.pow(3).add(PARAMETERS.getCurve().getA().multiply(x)).add(PARAMETERS.getCurve().getB())
                .mod(p);
        return y.modPow(BigInteger.TWO, p).equals(right);
    }

    private static void copyUnsigned(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length > COORDINATE ? bytes.length - COORDINATE : 0;
        int length = bytes.length - start;
        System.arraycopy(bytes, start, out, offset + COORDINATE - length, length);
    }

    private static ECParameterSpec parameters() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This JVM has no P-256", e);
        }
    }
}
