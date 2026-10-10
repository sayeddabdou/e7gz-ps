package ps.e7gz.notification.internal.webpush;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ps.e7gz.notification.internal.push.PushSender.PushMessage;

/**
 * Sends Web Push to a Venue Dashboard browser (ADR-014, arch 01 section 14 A6): the message is encrypted for that
 * browser (RFC 8291) and the request is signed with the server's VAPID key (RFC 8292). The payload is
 * {@code {title, body, data}}; the Dashboard's service worker shows it and opens the Booking or Tournament in
 * {@code data} (FR-NTF-04).
 */
public class VapidWebPushSender implements WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(VapidWebPushSender.class);
    /** RFC 8292 section 2: at most 24 hours. */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    private final RestClient http;
    private final VapidKeys keys;
    private final WebPushProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    VapidWebPushSender(RestClient http, VapidKeys keys, WebPushProperties properties, ObjectMapper json, Clock clock) {
        this.http = http;
        this.keys = keys;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public WebPushResult send(Subscription subscription, PushMessage message) {
        URI endpoint;
        byte[] body;
        try {
            endpoint = URI.create(subscription.endpoint());
            if (!"https".equals(endpoint.getScheme()) || !properties.allowsEndpointHost(endpoint.getHost())) {
                return new WebPushResult.Rejected("WEB_PUSH_ENDPOINT_NOT_ALLOWED");
            }
            body = WebPushEncryption.encrypt(payload(message), P256.publicKey(subscription.p256dh()),
                    P256.decode(subscription.auth()));
        } catch (IllegalArgumentException malformed) {
            return new WebPushResult.Gone("WEB_PUSH_SUBSCRIPTION_MALFORMED");
        }
        try {
            String authorization = "vapid t=" + token(audience(endpoint)) + ", k=" + keys.publicKey();
            return http.post()
                    .uri(endpoint)
                    .header("Authorization", authorization)
                    .header("Content-Encoding", "aes128gcm")
                    .header("TTL", Long.toString(properties.ttl().toSeconds()))
                    .header("Urgency", "high")                       // a venue has 10 minutes to answer (R2)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(body)
                    .exchange((request, response) -> classify(response.getStatusCode().value(),
                            response.getHeaders().getFirst("Location")), true);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("Web Push send failed: {}", e.getClass().getSimpleName());
            return new WebPushResult.Transient("WEB_PUSH_UNREACHABLE");
        }
    }

    static WebPushResult classify(int status, String location) {
        if (status >= 200 && status < 300) {
            return new WebPushResult.Sent(location == null || location.isBlank() ? "web-push-" + status : location);
        }
        return switch (status) {
            case 404, 410 -> new WebPushResult.Gone("WEB_PUSH_GONE");
            case 400 -> new WebPushResult.Rejected("WEB_PUSH_BAD_REQUEST");
            case 413 -> new WebPushResult.Rejected("WEB_PUSH_TOO_LARGE");
            case 401, 403 -> {
                log.error("A push service refused our VAPID credentials ({}): check E7GZ_VAPID_* (Sev-2)", status);
                yield new WebPushResult.Transient("WEB_PUSH_VAPID_REFUSED");
            }
            case 429 -> new WebPushResult.Transient("WEB_PUSH_THROTTLED");
            default -> new WebPushResult.Transient("WEB_PUSH_HTTP_" + status);
        };
    }

    private byte[] payload(PushMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", message.title());
        payload.put("body", message.body());
        payload.put("data", message.data());
        try {
            return json.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unwritable push payload", e);
        }
    }

    /** RFC 8292 section 2: {@code aud} is the push resource's origin. */
    private static String audience(URI endpoint) {
        return endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":"
                + endpoint.getPort());
    }

    private String token(String audience) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(audience)
                .expirationTime(Date.from(clock.instant().plus(TOKEN_LIFETIME)))
                .subject(keys.subject())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(),
                claims);
        try {
            jwt.sign(new ECDSASigner(keys.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("VAPID signing failed", e);
        }
        return jwt.serialize();
    }
}
