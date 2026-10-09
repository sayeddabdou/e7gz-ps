package ps.e7gz.messaging.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code request_fingerprint}: lower-case hex SHA-256 of the canonical request ({@code ck_idempotency_values},
 * ADR-015). ADR-015 and arch 08 section 6 fix only "a fingerprint of the request" and the database only its form, so
 * the canonical form below is this class's contract.
 *
 * <p>The input is the request as the server bound it - a JSON tree of the path variables and the request body - never
 * the raw bytes on the wire, so whitespace, member order and equivalent spellings of the same value do not change it.
 * The canonical text, in UTF-8, is:
 * <ul>
 *   <li>an object: its members sorted by name (UTF-16 code units, as RFC 8785), each written once;</li>
 *   <li>an array: its elements in their order (order is meaningful);</li>
 *   <li>a string: JSON-escaped as is (no Unicode normalisation);</li>
 *   <li>a number: its decimal value in plain digits without trailing zeros ({@code 1}, {@code 1.0} and {@code 1e0} are
 *       all {@code 1}; {@code 6e1} is {@code 60}); beyond 100 decimal places, Java's exponent form ({@code 1E+300});</li>
 *   <li>{@code true}, {@code false}, {@code null} as such; no whitespace anywhere;</li>
 *   <li>the value of a <b>secret</b> member (below), unless it is {@code null}: the string
 *       {@code "hmac-sha256:<hex>"}, an HMAC-SHA256 under the server's fingerprint key of the value's own canonical
 *       text. A changed, added or removed secret therefore changes the fingerprint like any other member.</li>
 * </ul>
 *
 * <p>Missing versus {@code null}: the fingerprint follows the request as bound, so it is the bound type that decides.
 * Every write body is a typed record, where an absent member and an explicit {@code null} bind to the same
 * {@code null}: the command cannot tell them apart, so they are one request and replay each other. Any present value,
 * the empty string included, is a different request. (An untyped {@code Map} body would keep the difference, and so
 * would its fingerprint.) No ADR-015 contract gives an absent and a {@code null} secret different meanings.
 *
 * <p>Why the HMAC: the fingerprint is kept for 30 days, and a plain hash over a request whose only unknown is a 4-digit
 * Owner Password could be reversed by trying all 10,000 values. Keyed with a 256-bit server secret
 * ({@code E7GZ_IDEMPOTENCY_FINGERPRINT_KEY}, from the secret store like {@code E7GZ_OTP_PAYLOAD_KEY}), it cannot be
 * tried without the key. Every replica uses the same key, so the fingerprint is the same on every replica and after a
 * restart. A member is a secret by the name rule the database applies to every stored payload
 * ({@code technical.forbidden_payload_key}; {@code IdempotencySecretNamesTest} keeps the two equal).
 *
 * <p>Neither the key, a secret, nor the canonical text is ever logged or put in an exception message.
 */
@Component
@EnableConfigurationProperties(RequestFingerprint.Properties.class)
class RequestFingerprint {

    static final int KEY_BYTES = 32;
    private static final String HMAC = "HmacSHA256";
    /** Domain separation: this key and HMAC are for idempotency fingerprints only. */
    private static final byte[] HMAC_CONTEXT = "e7gz.idempotency.secret.v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final JsonFactory JSON = new JsonFactory();
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_PLAIN_SCALE = 100;

    /** {@code technical.forbidden_payload_key}: the name, lower case, letters and digits only. */
    private static final Pattern SECRET_SUFFIX = Pattern.compile(
            "(password|passwd|passcode|passphrase|secret|otp|otpcode|token|apikey|privatekey|authorization|cookie|cvv"
                    + "|cvc|cardnumber|pan)$");
    private static final Set<String> SECRET_NAMES = Set.of("pin", "hash", "salt");

    private final SecretKeySpec key;

    RequestFingerprint(Properties properties) {
        this.key = key(properties.fingerprintKey());
    }

    /**
     * The fingerprint key ({@code e7gz.idempotency.fingerprint-key}): Base64 of 32 random bytes, e.g.
     * {@code openssl rand -base64 32}. From the environment / secret store only; the same value on every replica.
     */
    @ConfigurationProperties("e7gz.idempotency")
    record Properties(String fingerprintKey) {

        @Override
        public String toString() {
            return "Properties[fingerprintKey=<redacted>]";
        }
    }

    String of(JsonNode request) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(sha256.digest(canonical(request).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    /** The canonical text, secrets already keyed (never logged: it is the request). */
    String canonical(JsonNode request) {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = JSON.createGenerator(out)) {
            write(generator, request, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    static boolean isSecretName(String name) {
        String normalised = name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SECRET_SUFFIX.matcher(normalised).find() || SECRET_NAMES.contains(normalised);
    }

    /** {@code keySecrets}: false inside a secret's own value, which is keyed as a whole. */
    private void write(JsonGenerator out, JsonNode node, boolean keySecrets) throws IOException {
        switch (node.getNodeType()) {
            case OBJECT -> {
                Map<String, JsonNode> members = new TreeMap<>();
                for (Map.Entry<String, JsonNode> member : node.properties()) {
                    members.put(member.getKey(), member.getValue());
                }
                out.writeStartObject();
                for (Map.Entry<String, JsonNode> member : members.entrySet()) {
                    out.writeFieldName(member.getKey());
                    JsonNode value = member.getValue();
                    if (keySecrets && isSecretName(member.getKey()) && !value.isNull() && !value.isMissingNode()) {
                        out.writeString(keyed(value));
                    } else {
                        write(out, value, keySecrets);
                    }
                }
                out.writeEndObject();
            }
            case ARRAY -> {
                out.writeStartArray();
                for (JsonNode element : node) {
                    write(out, element, keySecrets);
                }
                out.writeEndArray();
            }
            case STRING -> out.writeString(node.textValue());
            case NUMBER -> out.writeNumber(number(node));
            case BOOLEAN -> out.writeBoolean(node.booleanValue());
            case NULL, MISSING -> out.writeNull();
            default -> throw new IllegalArgumentException("not a JSON value: " + node.getNodeType());
        }
    }

    /** {@code hmac-sha256:<hex>} of the secret value's canonical text. */
    private String keyed(JsonNode secret) throws IOException {
        StringWriter text = new StringWriter();
        try (JsonGenerator generator = JSON.createGenerator(text)) {
            write(generator, secret, false);
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update(HMAC_CONTEXT);
            return "hmac-sha256:" + HEX.formatHex(mac.doFinal(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is a mandatory JDK algorithm", e);
        }
    }

    private static String number(JsonNode node) {
        if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("not a JSON number");
        }
        BigDecimal value = node.isIntegralNumber() ? new BigDecimal(node.bigIntegerValue()) : node.decimalValue();
        if (value.signum() == 0) {
            return "0";
        }
        // Without trailing zeros a value has one (unscaled, scale) pair, so either spelling below is unique to it.
        // Plain digits for every ordinary number; the exponent form only past 100 places, which keeps the text short.
        BigDecimal stripped = value.stripTrailingZeros();
        return Math.abs(stripped.scale()) <= MAX_PLAIN_SCALE ? stripped.toPlainString() : stripped.toString();
    }

    private static SecretKeySpec key(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "e7gz.idempotency.fingerprint-key (E7GZ_IDEMPOTENCY_FINGERPRINT_KEY) is not set");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("e7gz.idempotency.fingerprint-key must be Base64");   // never echo the value
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("e7gz.idempotency.fingerprint-key must decode to exactly " + KEY_BYTES
                    + " bytes");
        }
        return new SecretKeySpec(bytes, HMAC);
    }
}
