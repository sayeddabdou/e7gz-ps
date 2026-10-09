package ps.e7gz.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ps.e7gz.support.IntegrationTest;

/** E7-42: the canonical request and its SHA-256, with secrets keyed by HMAC (ADR-015, {@code ck_idempotency_values}). */
class RequestFingerprintTest {

    /** Test input with exact decimals, as a bound {@code BigDecimal} field gives them. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    static final String OTHER_KEY = Base64.getEncoder().encodeToString(
            "another-test-only-fingerprint-k!".getBytes(StandardCharsets.US_ASCII));

    private final RequestFingerprint fingerprint = fingerprint(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY);

    // ------------------------------------------------------------------------------------------------ canonical form

    @Test
    void theFingerprintIsLowerCaseHexSha256OfTheCanonicalText() throws Exception {
        String value = fingerprint.of(tree("{\"b\": 2, \"a\": \"x\"}"));

        assertThat(value).isEqualTo(sha256("{\"a\":\"x\",\"b\":2}")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void memberOrderIsSortedRecursivelyAndWhitespaceIsIgnored() throws Exception {
        String compact = "{\"resourceId\":\"r1\",\"slot\":{\"startAt\":\"2026-10-12T17:00:00Z\",\"minutes\":60},"
                + "\"tags\":[\"x\",{\"k\":1,\"j\":2}]}";
        String reordered = """
                {
                  "tags" : [ "x", { "j" : 2, "k" : 1 } ],
                  "slot" : { "minutes" : 60,   "startAt" : "2026-10-12T17:00:00Z" },
                  "resourceId" : "r1"
                }
                """;

        assertThat(fingerprint.of(tree(reordered))).isEqualTo(fingerprint.of(tree(compact)));
        assertThat(fingerprint.canonical(tree(reordered)))
                .isEqualTo("{\"resourceId\":\"r1\",\"slot\":{\"minutes\":60,\"startAt\":\"2026-10-12T17:00:00Z\"},"
                        + "\"tags\":[\"x\",{\"j\":2,\"k\":1}]}");
    }

    @Test
    void aChangedNestedValueChangesTheFingerprint() throws Exception {
        assertThat(fingerprint.of(tree("{\"slot\":{\"minutes\":60}}")))
                .isNotEqualTo(fingerprint.of(tree("{\"slot\":{\"minutes\":90}}")));
    }

    @Test
    void arrayOrderIsMeaningful() throws Exception {
        assertThat(fingerprint.of(tree("[1,2]"))).isNotEqualTo(fingerprint.of(tree("[2,1]")));
    }

    @Test
    void aMissingMemberAStringAndANullAreDifferentRequests() throws Exception {
        String absent = fingerprint.of(tree("{\"a\":1}"));
        String nullValue = fingerprint.of(tree("{\"a\":1,\"b\":null}"));
        String text = fingerprint.of(tree("{\"a\":1,\"b\":\"null\"}"));
        String number = fingerprint.of(tree("{\"a\":\"1\"}"));

        assertThat(Set.of(absent, nullValue, text, number)).hasSize(4);
    }

    @Test
    void numbersAreCanonicalDecimalValues() throws Exception {
        assertThat(fingerprint.canonical(tree("[1, 1.0, 1e0, 10, 1.50, 0.0, -0, 100e-2]")))
                .isEqualTo("[1,1,1,10,1.5,0,0,1]");
        assertThat(fingerprint.canonical(tree("[6e1, 1e-7, 1e300]"))).isEqualTo("[60,0.0000001,1E+300]");
        assertThat(fingerprint.of(tree("{\"amount\":150.00}")))
                .isEqualTo(fingerprint.of(tree("{\"amount\":150}")))
                .isNotEqualTo(fingerprint.of(tree("{\"amount\":150.01}")));
    }

    @Test
    void stringsAreKeptExactlyIncludingCaseAndUnicode() throws Exception {
        assertThat(fingerprint.of(tree("{\"t\":\"Room A\"}")))
                .isNotEqualTo(fingerprint.of(tree("{\"t\":\"room a\"}")))
                .isNotEqualTo(fingerprint.of(tree("{\"t\":\"Room A \"}")));
        assertThat(fingerprint.of(tree("{\"t\":\"غرفة\"}")))
                .isEqualTo(fingerprint.of(tree("{\"t\":\"\\u063a\\u0631\\u0641\\u0629\"}")));
    }

    @Test
    void notANumberIsNoJsonValue() {
        assertThatThrownBy(() -> fingerprint.of(JsonNodeFactory.instance.numberNode(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------------ secrets

    @Test
    void theSameSecretIsTheSameRequest() throws Exception {
        assertThat(fingerprint.of(tree("{\"reason\":\"x\",\"ownerPassword\":\"4821\"}")))
                .isEqualTo(fingerprint.of(tree("{ \"ownerPassword\" : \"4821\", \"reason\" : \"x\" }")));
    }

    @Test
    void aChangedSecretIsADifferentRequest() throws Exception {
        assertThat(fingerprint.of(tree("{\"reason\":\"x\",\"ownerPassword\":\"4821\"}")))
                .isNotEqualTo(fingerprint.of(tree("{\"reason\":\"x\",\"ownerPassword\":\"4822\"}")));
        assertThat(fingerprint.of(tree("{\"pin\":\"0000\"}"))).isNotEqualTo(fingerprint.of(tree("{\"pin\":\"9999\"}")));
        assertThat(fingerprint.of(tree("{\"list\":[{\"otp\":\"123456\"}]}")))
                .isNotEqualTo(fingerprint.of(tree("{\"list\":[{\"otp\":\"123457\"}]}")));
    }

    @Test
    void aMissingANullAndAPresentSecretAreDifferentRequests() throws Exception {
        String missing = fingerprint.of(tree("{\"reason\":\"x\"}"));
        String nullSecret = fingerprint.of(tree("{\"reason\":\"x\",\"ownerPassword\":null}"));
        String present = fingerprint.of(tree("{\"reason\":\"x\",\"ownerPassword\":\"4821\"}"));
        String empty = fingerprint.of(tree("{\"reason\":\"x\",\"ownerPassword\":\"\"}"));

        assertThat(Set.of(missing, nullSecret, present, empty)).hasSize(4);
        assertThat(fingerprint.canonical(tree("{\"ownerPassword\":null}"))).isEqualTo("{\"ownerPassword\":null}");
    }

    @Test
    void aSecretTypeIsPartOfItsValue() throws Exception {
        assertThat(fingerprint.of(tree("{\"pin\":4821}"))).isNotEqualTo(fingerprint.of(tree("{\"pin\":\"4821\"}")));
        assertThat(fingerprint.of(tree("{\"ownerPassword\":{\"b\":1,\"a\":2}}")))
                .isEqualTo(fingerprint.of(tree("{\"ownerPassword\":{\"a\":2,\"b\":1}}")));
    }

    @Test
    void aSecretEntersTheCanonicalTextOnlyAsAKeyedHmac() throws Exception {
        String canonical = fingerprint.canonical(tree(
                "{\"reason\":\"x\",\"ownerPassword\":\"4821\",\"nested\":{\"accessToken\":\"eyJ.t\",\"keep\":1}}"));

        assertThat(canonical).doesNotContain("4821", "eyJ.t", sha256("\"4821\""), sha256("4821"))
                .matches("\\{\"nested\":\\{\"accessToken\":\"hmac-sha256:[0-9a-f]{64}\",\"keep\":1},"
                        + "\"ownerPassword\":\"hmac-sha256:[0-9a-f]{64}\",\"reason\":\"x\"}");
    }

    @Test
    void theSameKeyGivesTheSameFingerprintOnEveryReplicaAndAfterARestart() throws Exception {
        JsonNode request = tree("{\"reason\":\"x\",\"ownerPassword\":\"4821\"}");

        assertThat(fingerprint(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY).of(request))
                .isEqualTo(fingerprint.of(request));
    }

    @Test
    void anotherKeyChangesOnlyRequestsThatCarryASecret() throws Exception {
        RequestFingerprint other = fingerprint(OTHER_KEY);

        assertThat(other.of(tree("{\"ownerPassword\":\"4821\"}")))
                .isNotEqualTo(fingerprint.of(tree("{\"ownerPassword\":\"4821\"}")));
        assertThat(other.of(tree("{\"reason\":\"x\"}"))).isEqualTo(fingerprint.of(tree("{\"reason\":\"x\"}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ownerPassword", "owner_password", "password", "accessToken", "refresh-token", "otp",
            "otpCode", "pin", "PIN", "hash", "salt", "apiKey", "clientSecret", "Authorization", "cookie", "cardNumber"})
    void secretNames(String name) {
        assertThat(RequestFingerprint.isSecretName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bookingId", "reasonText", "startAt", "durationMinutes", "playMode", "customerPhone",
            "pinned", "hashtag", "resourceId"})
    void ordinaryNames(String name) {
        assertThat(RequestFingerprint.isSecretName(name)).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------- the key

    @Test
    void theKeyIsRequiredBase64Of32BytesAndNeverEchoed() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> fingerprint(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E7GZ_IDEMPOTENCY_FINGERPRINT_KEY");
        assertThatThrownBy(() -> fingerprint(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fingerprint("not base64 !!")).isInstanceOf(IllegalStateException.class)
                .message().doesNotContain("not base64");
        assertThatThrownBy(() -> fingerprint(shortKey)).isInstanceOf(IllegalStateException.class)
                .message().doesNotContain(shortKey);
        assertThat(new RequestFingerprint.Properties(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY).toString())
                .doesNotContain(IntegrationTest.TEST_IDEMPOTENCY_FINGERPRINT_KEY);
    }

    static RequestFingerprint fingerprint(String key) {
        return new RequestFingerprint(new RequestFingerprint.Properties(key));
    }

    private static JsonNode tree(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
