package ps.e7gz.notification.internal.webpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** E7-44: Web Push message encryption is exactly RFC 8291 (checked against its Appendix A example). */
class WebPushEncryptionTest {

    /** RFC 8291 Appendix A. */
    private static final String PLAINTEXT = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
            + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
            + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void theRfc8291ExampleEncryptsToTheRfcMessage() {
        KeyPair applicationServer = new KeyPair(P256.publicKey(AS_PUBLIC), P256.privateKey(AS_PRIVATE));

        byte[] body = WebPushEncryption.encrypt(P256.decode(PLAINTEXT), P256.publicKey(UA_PUBLIC),
                P256.decode(AUTH_SECRET), applicationServer, P256.decode(SALT));

        assertThat(P256.base64Url(body)).isEqualTo(BODY);
    }

    @Test
    void everyMessageUsesANewKeyAndSaltAndDecryptsForTheBrowser() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(P256.PARAMETERS);
        KeyPair browser = generator.generateKeyPair();
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 7);
        byte[] text = "{\"title\":\"طلب حجز جديد\"}".getBytes(StandardCharsets.UTF_8);

        byte[] first = WebPushEncryption.encrypt(text, (ECPublicKey) browser.getPublic(), auth);
        byte[] second = WebPushEncryption.encrypt(text, (ECPublicKey) browser.getPublic(), auth);

        assertThat(Arrays.copyOf(first, 16)).isNotEqualTo(Arrays.copyOf(second, 16));
        assertThat(WebPushTestBrowser.decrypt(first, browser, auth)).isEqualTo(text);
        assertThat(WebPushTestBrowser.decrypt(second, browser, auth)).isEqualTo(text);
    }

    @Test
    void aMessageLongerThanOneRecordOrAWrongAuthSecretIsRefused() {
        ECPublicKey browser = P256.publicKey(UA_PUBLIC);
        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[WebPushEncryption.RECORD_SIZE], browser,
                new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[10], browser, new byte[15]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAreCheckedBeforeTheyAreStored() {
        assertThat(SubscriptionKeys.isP256dh(UA_PUBLIC)).isTrue();
        assertThat(SubscriptionKeys.isP256dh("BAAA")).isFalse();
        // 65 bytes starting 0x04 but not a point on the curve.
        byte[] offCurve = new byte[65];
        offCurve[0] = 0x04;
        offCurve[64] = 1;
        assertThat(SubscriptionKeys.isP256dh(P256.base64Url(offCurve))).isFalse();
        assertThat(SubscriptionKeys.isP256dh("not base64 !")).isFalse();
        assertThat(SubscriptionKeys.isAuth(AUTH_SECRET)).isTrue();
        assertThat(SubscriptionKeys.isAuth(SALT + "AA")).isFalse();
    }
}
