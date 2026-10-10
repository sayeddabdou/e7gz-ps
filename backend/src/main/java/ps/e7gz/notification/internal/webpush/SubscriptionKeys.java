package ps.e7gz.notification.internal.webpush;

/** Checks of a browser's {@code PushSubscription} keys before they are stored (RFC 8291 section 3.1, 3.2). */
public final class SubscriptionKeys {

    private SubscriptionKeys() {
    }

    /** {@code p256dh}: URL-safe Base64 of an uncompressed point on P-256. */
    public static boolean isP256dh(String value) {
        try {
            P256.publicKey(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code auth}: URL-safe Base64 of 16 random bytes. */
    public static boolean isAuth(String value) {
        try {
            return P256.decode(value).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
