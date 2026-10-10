package ps.e7gz.notification.internal.webpush;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Venue Dashboard Web Push settings ({@code e7gz.web-push}; E7-44, PO decisions 10 Oct 2026).
 *
 * @param provider             {@code log} (default: local development and tests) writes venue pushes to the worker log
 *                             and needs no keys; {@code vapid} sends them (E7-101 sets it in staging and production)
 * @param vapid                the server's own Web Push signing keys (RFC 8292); no provider account
 * @param ttl                  how long a push service keeps a push for a browser that is offline
 * @param allowedEndpointHosts the push services a subscription endpoint may point at; {@code *.} matches any
 *                             subdomain. The worker POSTs to the endpoint the Dashboard sent, so nothing else is called
 *                             (arch 10). Defaults: Chrome / Edge-Chromium / Opera (FCM), Firefox, Edge (WNS), Safari.
 * @param connectTimeout       TCP connect timeout
 * @param readTimeout          response timeout
 */
@ConfigurationProperties("e7gz.web-push")
public record WebPushProperties(
        @DefaultValue("log") Provider provider,
        @DefaultValue Vapid vapid,
        @DefaultValue("1h") Duration ttl,
        @DefaultValue({"fcm.googleapis.com", "updates.push.services.mozilla.com", "*.notify.windows.com",
                "web.push.apple.com"}) List<String> allowedEndpointHosts,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {

    /**
     * The development public key served when {@code E7GZ_VAPID_PUBLIC_KEY} is not set. Its private half was never
     * kept, so nothing can be sent with it: the {@code vapid} provider refuses to start with it.
     */
    public static final String DEV_PUBLIC_KEY =
            "BGZ958V3CWVr6vpUWx61GhoDuaj-RB9Vc8rmoIimFmbVbXzbF9N9PcKeBBmWb767gQlGHfIlP2Njl-IGgz3TyZM";

    /** The development contact; the real address is chosen with the PO in E7-101. */
    public static final String DEV_SUBJECT = "mailto:dev@e7gz.local";

    public WebPushProperties {
        String publicKey = vapid.publicKey();
        try {
            P256.publicKey(publicKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "e7gz.web-push.vapid.public-key (E7GZ_VAPID_PUBLIC_KEY) is not a URL-safe Base64 P-256 public key");
        }
        if (provider == Provider.VAPID && DEV_PUBLIC_KEY.equals(publicKey.strip())) {
            throw new IllegalArgumentException("e7gz.web-push.provider is vapid but E7GZ_VAPID_PUBLIC_KEY is not set: "
                    + "the development key cannot send");
        }
        allowedEndpointHosts = allowedEndpointHosts.stream().map(host -> host.strip().toLowerCase(Locale.ROOT))
                .toList();
    }

    public enum Provider {
        /** Writes venue pushes to the worker log. Local development only. */
        LOG,
        /** Sends Web Push signed with the VAPID keys (RFC 8291, RFC 8292). */
        VAPID
    }

    /**
     * @param publicKey  URL-safe Base64 of the 65-byte uncompressed P-256 point; the Dashboard subscribes with it
     * @param privateKey URL-safe Base64 of the 32-byte P-256 private key, from the secret store only (E7-101); the
     *                   worker alone uses it
     * @param subject    a {@code mailto:} or {@code https:} contact the push services may use
     */
    public record Vapid(
            @DefaultValue(DEV_PUBLIC_KEY) String publicKey,
            String privateKey,
            @DefaultValue(DEV_SUBJECT) String subject) {

        @Override
        public String toString() {
            return "Vapid[publicKey=" + publicKey + ", privateKey=<redacted>, subject=" + subject + "]";
        }
    }

    /** True when the endpoint's host is one of {@link #allowedEndpointHosts}. */
    public boolean allowsEndpointHost(String host) {
        if (host == null) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedEndpointHosts) {
            if (allowed.startsWith("*.") ? candidate.endsWith(allowed.substring(1)) : candidate.equals(allowed)) {
                return true;
            }
        }
        return false;
    }
}
