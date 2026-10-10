package ps.e7gz.notification.internal.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * OAuth 2.0 access tokens for the FCM HTTP v1 API from a Google service account (the JWT-bearer grant, RFC 7523): a JWT
 * signed RS256 with the account's private key is exchanged at its {@code token_uri} for a one-hour access token, kept
 * until a minute before it expires. Neither the key nor a token is ever logged.
 */
final class FcmAccessTokens {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration LIFETIME = Duration.ofHours(1);
    private static final Duration MARGIN = Duration.ofMinutes(1);

    /** The parts of the service-account JSON this needs. */
    record ServiceAccount(String projectId, String clientEmail, PrivateKey privateKey, String tokenUri) {

        /** {@code base64Json} is Base64 of the service-account JSON file Firebase gives. */
        static ServiceAccount parse(String base64Json, ObjectMapper json) {
            try {
                JsonNode node = json.readTree(Base64.getDecoder().decode(base64Json.strip()));
                String projectId = required(node, "project_id");
                String email = required(node, "client_email");
                String pem = required(node, "private_key");
                String tokenUri = node.path("token_uri").asText("https://oauth2.googleapis.com/token");
                byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", ""));
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                return new ServiceAccount(projectId, email, key, tokenUri);
            } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
                // Not the cause's message: it may quote the key.
                throw new IllegalStateException("e7gz.push.fcm.service-account is not a Base64 service-account JSON");
            }
        }

        private static String required(JsonNode node, String field) {
            String value = node.path(field).asText("");
            if (value.isBlank()) {
                throw new IllegalArgumentException("missing " + field);
            }
            return value;
        }

        @Override
        public String toString() {
            return "ServiceAccount[projectId=" + projectId + ", clientEmail=" + clientEmail + ", <key redacted>]";
        }
    }

    private final ServiceAccount account;
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;

    private String token;
    private Instant validUntil = Instant.MIN;

    FcmAccessTokens(ServiceAccount account, RestClient http, ObjectMapper json, Clock clock) {
        this.account = Objects.requireNonNull(account, "account");
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    /** A valid access token, fetched when the kept one is missing or about to expire. */
    synchronized String current() {
        Instant now = clock.instant();
        if (token == null || !now.isBefore(validUntil)) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", assertion(now));
            JsonNode answer = http.post().uri(account.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (answer == null || answer.path("access_token").asText("").isBlank()) {
                throw new IllegalStateException("The OAuth token endpoint returned no access token");
            }
            token = answer.get("access_token").asText();
            long seconds = answer.path("expires_in").asLong(LIFETIME.toSeconds());
            validUntil = now.plusSeconds(seconds).minus(MARGIN);
        }
        return token;
    }

    /** The signed JWT: {@code iss} = the account, {@code aud} = its token endpoint, one hour. */
    String assertion(Instant now) {
        Map<String, Object> header = Map.of("alg", "RS256", "typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", account.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", account.tokenUri());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(LIFETIME).getEpochSecond());
        try {
            Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
            String signingInput = url.encodeToString(json.writeValueAsBytes(header)) + "."
                    + url.encodeToString(json.writeValueAsBytes(claims));
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(account.privateKey());
            rsa.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + url.encodeToString(rsa.sign());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign the FCM OAuth assertion", e);
        }
    }

    String projectId() {
        return account.projectId();
    }
}
