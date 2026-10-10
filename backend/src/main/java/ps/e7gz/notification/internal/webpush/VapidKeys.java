package ps.e7gz.notification.internal.webpush;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * The VAPID key pair and contact the worker signs with (RFC 8292), checked once at start-up: both keys present, a real
 * pair, not the development public key, and a {@code mailto:} or {@code https:} contact that is not the development one
 * (PO decision 10 Oct 2026: the real contact is chosen in E7-101).
 */
record VapidKeys(String publicKey, ECPrivateKey privateKey, String subject) {

    static VapidKeys from(WebPushProperties.Vapid vapid) {
        if (vapid.privateKey() == null || vapid.privateKey().isBlank()) {
            throw new IllegalStateException(
                    "e7gz.web-push.provider is vapid but E7GZ_VAPID_PRIVATE_KEY is missing");
        }
        String subject = vapid.subject() == null ? "" : vapid.subject().strip();
        if (!(subject.startsWith("mailto:") || subject.startsWith("https://"))
                || WebPushProperties.DEV_SUBJECT.equals(subject)) {
            throw new IllegalStateException("e7gz.web-push.provider is vapid but E7GZ_VAPID_SUBJECT is not a real "
                    + "mailto: or https: contact");
        }
        ECPublicKey publicKey = P256.publicKey(vapid.publicKey());
        ECPrivateKey privateKey;
        try {
            privateKey = P256.privateKey(vapid.privateKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("E7GZ_VAPID_PRIVATE_KEY is not a URL-safe Base64 P-256 private key");
        }
        if (!P256.pair(privateKey, publicKey)) {
            throw new IllegalStateException("E7GZ_VAPID_PRIVATE_KEY does not belong to E7GZ_VAPID_PUBLIC_KEY");
        }
        return new VapidKeys(P256.base64Url(P256.encode(publicKey)), privateKey, subject);
    }

    @Override
    public String toString() {
        return "VapidKeys[publicKey=" + publicKey + ", privateKey=<redacted>, subject=" + subject + "]";
    }
}
