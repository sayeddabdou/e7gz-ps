package ps.e7gz.identity.internal.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClient;

/** E7-13 - the SMS Misr OTP adapter against a mocked SMS Misr API. */
class SmsMisrSmsSenderTest {

    private static final String PHONE = "+201012345678";
    private static final String PASSWORD = "s3cret-pass";

    private final SmsProperties.SmsMisr account = new SmsProperties.SmsMisr(
            URI.create("https://smsmisr.test/api"), SmsProperties.Environment.TEST,
            "e7gz-user", PASSWORD, "sender-token", "template-token", Duration.ofSeconds(5), Duration.ofSeconds(10));

    private MockRestServiceServer server;
    private SmsMisrSmsSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(account.baseUrl().toString());
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new SmsMisrSmsSender(builder.build(), account);
    }

    @Test
    void postsTheOtpFormToTheOtpEndpoint() {
        server.expect(requestTo("https://smsmisr.test/api/OTP/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(CollectionUtils.toMultiValueMap(Map.of(
                        "environment", List.of("2"),
                        "username", List.of("e7gz-user"),
                        "password", List.of(PASSWORD),
                        "sender", List.of("sender-token"),
                        "mobile", List.of("201012345678"),
                        "template", List.of("template-token"),
                        "otp", List.of("482913")))))
                .andRespond(withSuccess("{\"code\":\"4901\",\"SMSID\":\"77\",\"Cost\":\"1\"}",
                        MediaType.APPLICATION_JSON));

        sender.sendOtp(PHONE, "482913");

        server.verify();
    }

    @Test
    void aNumericSuccessCodeIsAlsoAccepted() {
        server.expect(requestTo("https://smsmisr.test/api/OTP/"))
                .andRespond(withSuccess("{\"code\":4901}", MediaType.APPLICATION_JSON));

        sender.sendOtp(PHONE, "482913");

        server.verify();
    }

    @Test
    void aRefusalCodeFailsWithTheProviderCodeAndNoSecrets() {
        server.expect(requestTo("https://smsmisr.test/api/OTP/"))
                .andRespond(withSuccess("{\"code\":\"4906\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sender.sendOtp(PHONE, "482913"))
                .isInstanceOf(SmsDeliveryException.class)
                .satisfies(e -> {
                    assertThat(((SmsDeliveryException) e).providerCode()).isEqualTo("4906");
                    assertThat(e.getMessage()).doesNotContain("482913", PASSWORD, "sender-token", "template-token",
                            "201012345678");
                });
    }

    @Test
    void anUnreadableResponseIsAFailedSend() {
        server.expect(requestTo("https://smsmisr.test/api/OTP/"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> sender.sendOtp(PHONE, "482913"))
                .isInstanceOf(SmsDeliveryException.class)
                .satisfies(e -> assertThat(((SmsDeliveryException) e).providerCode()).isEqualTo("unreadable"));
    }

    @Test
    void anHttpErrorIsAFailedSendWithoutAProviderCode() {
        server.expect(requestTo("https://smsmisr.test/api/OTP/")).andRespond(withServerError());

        assertThatThrownBy(() -> sender.sendOtp(PHONE, "482913"))
                .isInstanceOf(SmsDeliveryException.class)
                .satisfies(e -> {
                    assertThat(((SmsDeliveryException) e).providerCode()).isNull();
                    assertThat(e.getMessage()).doesNotContain("482913", PASSWORD);
                });
    }

    /**
     * E7-41 review: the outbox dispatcher logs a failed consumer's exception with its whole cause chain on every retry.
     * An HTTP error from the client carries the response body in its message, and a provider may echo the request in
     * it, so the failure must carry neither that message nor that exception.
     */
    @Test
    void anHttpErrorEchoingTheRequestLeavesNoSecretInTheFailureOrItsCauses() {
        String echo = "{\"error\":\"bad request\",\"request\":{\"username\":\"e7gz-user\",\"password\":\"" + PASSWORD
                + "\",\"sender\":\"sender-token\",\"template\":\"template-token\",\"mobile\":\"201012345678\","
                + "\"otp\":\"482913\"}}";
        server.expect(requestTo("https://smsmisr.test/api/OTP/"))
                .andRespond(withBadRequest().body(echo).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sender.sendOtp(PHONE, "482913"))
                .isInstanceOf(SmsDeliveryException.class)
                .satisfies(e -> {
                    assertThat(((SmsDeliveryException) e).providerCode()).isNull();
                    StringWriter logged = new StringWriter();
                    e.printStackTrace(new PrintWriter(logged));          // what a log line with the exception prints
                    assertThat(logged.toString()).doesNotContain("482913", PASSWORD, "e7gz-user", "sender-token",
                            "template-token", "201012345678");
                    assertThat(e.getMessage()).contains("400");
                });
    }

    @Test
    void theMobileIsSentWithoutThePlusSign() {
        assertThat(SmsMisrSmsSender.mobile("+201012345678")).isEqualTo("201012345678");
        assertThatThrownBy(() -> SmsMisrSmsSender.mobile("01012345678")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logsShowOnlyAMaskedNumber() {
        assertThat(SmsMisrSmsSender.masked("+201012345678")).isEqualTo("+2010*****678");
    }

    @Test
    void theAccountNeverPrintsItsCredentials() {
        assertThat(account.toString()).doesNotContain(PASSWORD, "e7gz-user", "sender-token", "template-token");
    }
}
