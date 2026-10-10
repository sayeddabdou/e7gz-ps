package ps.e7gz.notification.internal.webpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ps.e7gz.notification.internal.push.PushSender.PushMessage;
import ps.e7gz.notification.internal.webpush.WebPushSender.Subscription;
import ps.e7gz.notification.internal.webpush.WebPushSender.WebPushResult;

/**
 * E7-44: Venue Web Push without a real push service. The request is signed with the VAPID key (RFC 8292), the message
 * is encrypted for the browser (RFC 8291) and decrypts to {@code {title, body, data}}, and the push service's answers
 * are classified as arch 08 section 4.3 says. The keys are generated here; none is in the repository.
 */
class VapidWebPushSenderTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc123";
    private static final String CONTACT = "mailto:ops@e7gz.example";
    private static final Pattern VAPID = Pattern.compile("vapid t=([^,]+), k=(.+)");

    private final ObjectMapper json = new ObjectMapper();
    private KeyPair server;
    private KeyPair browser;
    private byte[] auth;
    private Subscription subscription;
    private MockRestServiceServer pushService;
    private VapidWebPushSender sender;

    @BeforeEach
    void setUp() throws Exception {
        server = newKeyPair();
        browser = newKeyPair();
        auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        subscription = new Subscription(ENDPOINT, P256.base64Url(P256.encode((ECPublicKey) browser.getPublic())),
                P256.base64Url(auth));
        WebPushProperties properties = properties(publicKey(server), privateKey(server), CONTACT);
        RestClient.Builder builder = RestClient.builder();
        pushService = MockRestServiceServer.bindTo(builder).build();
        sender = new VapidWebPushSender(builder.build(), VapidKeys.from(properties.vapid()), properties, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void thePushIsSignedWithVapidAndDecryptsInTheBrowserToTheTextAndTheDeepLink() throws Exception {
        AtomicReference<MockClientHttpRequest> sent = new AtomicReference<>();
        pushService.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Encoding", "aes128gcm"))
                .andExpect(header("TTL", "3600"))
                .andExpect(header("Urgency", "high"))
                .andExpect(request -> sent.set((MockClientHttpRequest) request))
                .andRespond(withStatus(HttpStatus.CREATED).header(HttpHeaders.LOCATION, "https://fcm.googleapis.com/m/1"));

        WebPushResult result = sender.send(subscription, new PushMessage("New booking request", "Answer within 10 minutes",
                Map.of("code", "VENUE_NEW_BOOKING_REQUEST", "bookingId", "b-1")));

        assertThat(result).isEqualTo(new WebPushResult.Sent("https://fcm.googleapis.com/m/1"));
        Matcher vapid = VAPID.matcher(sent.get().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertThat(vapid.matches()).isTrue();
        assertThat(vapid.group(2)).isEqualTo(publicKey(server));
        SignedJWT token = SignedJWT.parse(vapid.group(1));
        assertThat(token.verify(new ECDSAVerifier((ECPublicKey) server.getPublic()))).isTrue();
        assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("ES256");
        assertThat(token.getJWTClaimsSet().getAudience()).containsExactly("https://fcm.googleapis.com");
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(CONTACT);
        assertThat(token.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofHours(12)));

        JsonNode payload = json.readTree(WebPushTestBrowser.decrypt(sent.get().getBodyAsBytes(), browser, auth));
        assertThat(payload.path("title").asText()).isEqualTo("New booking request");
        assertThat(payload.path("body").asText()).isEqualTo("Answer within 10 minutes");
        assertThat(payload.path("data").path("bookingId").asText()).isEqualTo("b-1");
        assertThat(payload.path("data").path("code").asText()).isEqualTo("VENUE_NEW_BOOKING_REQUEST");
        pushService.verify();
    }

    @Test
    void thePushServicesAnswersAreClassified() {
        assertThat(VapidWebPushSender.classify(201, null)).isInstanceOf(WebPushResult.Sent.class);
        assertThat(VapidWebPushSender.classify(410, null)).isEqualTo(new WebPushResult.Gone("WEB_PUSH_GONE"));
        assertThat(VapidWebPushSender.classify(404, null)).isEqualTo(new WebPushResult.Gone("WEB_PUSH_GONE"));
        assertThat(VapidWebPushSender.classify(400, null)).isInstanceOf(WebPushResult.Rejected.class);
        assertThat(VapidWebPushSender.classify(413, null)).isInstanceOf(WebPushResult.Rejected.class);
        assertThat(VapidWebPushSender.classify(403, null))
                .isEqualTo(new WebPushResult.Transient("WEB_PUSH_VAPID_REFUSED"));
        assertThat(VapidWebPushSender.classify(429, null)).isEqualTo(new WebPushResult.Transient("WEB_PUSH_THROTTLED"));
        assertThat(VapidWebPushSender.classify(503, null)).isEqualTo(new WebPushResult.Transient("WEB_PUSH_HTTP_503"));
    }

    @Test
    void anEndpointOutsideTheKnownPushServicesIsNeverCalledAndBrokenKeysAreGone() {
        PushMessage message = new PushMessage("t", "b", Map.of());
        assertThat(sender.send(new Subscription("https://internal.example.test/x", subscription.p256dh(),
                subscription.auth()), message)).isEqualTo(new WebPushResult.Rejected("WEB_PUSH_ENDPOINT_NOT_ALLOWED"));
        assertThat(sender.send(new Subscription(ENDPOINT, "BAAA", subscription.auth()), message))
                .isEqualTo(new WebPushResult.Gone("WEB_PUSH_SUBSCRIPTION_MALFORMED"));
        pushService.verify();                                                // nothing was sent
    }

    @Test
    void theVapidProviderRefusesToStartWithoutARealKeyPairAndContact() throws Exception {
        String pub = publicKey(server);
        String priv = privateKey(server);
        assertThatThrownBy(() -> VapidKeys.from(properties(pub, null, CONTACT).vapid()))
                .hasMessageContaining("E7GZ_VAPID_PRIVATE_KEY is missing");
        assertThatThrownBy(() -> VapidKeys.from(properties(pub, privateKey(newKeyPair()), CONTACT).vapid()))
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> VapidKeys.from(properties(pub, priv, WebPushProperties.DEV_SUBJECT).vapid()))
                .hasMessageContaining("E7GZ_VAPID_SUBJECT");
        assertThatThrownBy(() -> VapidKeys.from(properties(pub, priv, "ops@e7gz.example").vapid()))
                .hasMessageContaining("E7GZ_VAPID_SUBJECT");
        assertThatThrownBy(() -> properties(WebPushProperties.DEV_PUBLIC_KEY, priv, CONTACT))
                .hasMessageContaining("E7GZ_VAPID_PUBLIC_KEY is not set");
        assertThatThrownBy(() -> new WebPushProperties(WebPushProperties.Provider.LOG,
                new WebPushProperties.Vapid("not-a-key", null, CONTACT), Duration.ofHours(1), List.of(),
                Duration.ofSeconds(5), Duration.ofSeconds(10))).hasMessageContaining("E7GZ_VAPID_PUBLIC_KEY");
        assertThat(VapidKeys.from(properties(pub, priv, CONTACT).vapid()).toString()).doesNotContain(priv);
    }

    @Test
    void endpointHostsMatchExactlyOrAsASubdomainOfAWildcard() throws Exception {
        WebPushProperties properties = properties(publicKey(server), null, CONTACT);
        assertThat(properties.allowsEndpointHost("fcm.googleapis.com")).isTrue();
        assertThat(properties.allowsEndpointHost("FCM.googleapis.com")).isTrue();
        assertThat(properties.allowsEndpointHost("wns2-par02p.notify.windows.com")).isTrue();
        assertThat(properties.allowsEndpointHost("notify.windows.com.evil.example")).isFalse();
        assertThat(properties.allowsEndpointHost("evilfcm.googleapis.com")).isFalse();
        assertThat(properties.allowsEndpointHost(null)).isFalse();
    }

    private static WebPushProperties properties(String publicKey, String privateKey, String subject) {
        return new WebPushProperties(WebPushProperties.Provider.VAPID,
                new WebPushProperties.Vapid(publicKey, privateKey, subject), Duration.ofHours(1),
                List.of("fcm.googleapis.com", "*.notify.windows.com"), Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(P256.PARAMETERS);
        return generator.generateKeyPair();
    }

    private static String publicKey(KeyPair pair) {
        return P256.base64Url(P256.encode((ECPublicKey) pair.getPublic()));
    }

    private static String privateKey(KeyPair pair) {
        byte[] scalar = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
        byte[] out = new byte[32];
        int start = Math.max(0, scalar.length - 32);
        System.arraycopy(scalar, start, out, 32 - (scalar.length - start), scalar.length - start);
        return P256.base64Url(out);
    }
}
