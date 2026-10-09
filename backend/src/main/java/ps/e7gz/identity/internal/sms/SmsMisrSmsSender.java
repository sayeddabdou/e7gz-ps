package ps.e7gz.identity.internal.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * SMS Misr OTP API adapter: {@code POST {baseUrl}/OTP/}, form-encoded, with {@code environment}, {@code username},
 * {@code password}, {@code sender} (Sender Token), {@code mobile}, {@code template} (Template Token) and {@code otp}.
 * SMS Misr puts the code into our approved template and sends it.
 *
 * <p>The result codes below are SMS Misr's published OTP API codes; confirm them against the account's API document
 * when the account is opened. Anything other than {@link #SUCCESS} is a failed send.
 *
 * <p>Nothing secret leaves this class in a log line or exception: no OTP, no credentials, no tokens, and the phone
 * number masked (arch 10 section 2.2, section 6).
 */
class SmsMisrSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(SmsMisrSmsSender.class);

    static final String OTP_PATH = "/OTP/";
    static final String SUCCESS = "4901";

    /** For logs and alerts only. */
    static final Map<String, String> RESULT_CODES = Map.of(
            "4901", "success",
            "4903", "invalid username or password",
            "4904", "invalid sender token",
            "4905", "invalid mobile number",
            "4906", "insufficient credit",
            "4907", "server is being updated",
            "4908", "invalid OTP value",
            "4909", "invalid template token",
            "4912", "invalid environment");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final SmsProperties.SmsMisr account;

    /** @param client a client whose base URL is {@link SmsProperties.SmsMisr#baseUrl()} */
    SmsMisrSmsSender(RestClient client, SmsProperties.SmsMisr account) {
        this.client = Objects.requireNonNull(client, "client");
        this.account = Objects.requireNonNull(account, "account");
    }

    @Override
    public void sendOtp(String phoneE164, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("environment", account.environment().code());
        form.add("username", account.username());
        form.add("password", account.password());
        form.add("sender", account.sender());
        form.add("mobile", mobile(phoneE164));
        form.add("template", account.template());
        form.add("otp", code);

        String body;
        try {
            body = client.post()
                    .uri(OTP_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // Neither the exception's message nor the exception itself: an HTTP error's message is the response body,
            // which may echo the request, and the outbox dispatcher logs a failure with its whole cause chain on every
            // retry (E7-41). The HTTP status, or the exception's class, is all that leaves this class.
            String failure = e instanceof RestClientResponseException response
                    ? "HTTP " + response.getStatusCode().value()
                    : e.getClass().getSimpleName();
            log.error("SMS Misr OTP send to {} failed: {}", masked(phoneE164), failure);
            throw new SmsDeliveryException("SMS Misr could not be reached (" + failure + ")", null, null);
        }

        String result = resultCode(body);
        if (!SUCCESS.equals(result)) {
            log.error("SMS Misr refused OTP send to {}: code {} ({})", masked(phoneE164), result,
                    RESULT_CODES.getOrDefault(result, "unknown"));
            throw new SmsDeliveryException("SMS Misr refused the OTP send with code " + result, result, null);
        }
        log.info("SMS Misr accepted OTP send to {}", masked(phoneE164));
    }

    /** SMS Misr takes the international number without '+': {@code +201012345678} becomes {@code 201012345678}. */
    static String mobile(String phoneE164) {
        if (phoneE164 == null || !phoneE164.matches("\\+[1-9][0-9]{7,14}")) {
            throw new IllegalArgumentException("Destination must be an E.164 number");
        }
        return phoneE164.substring(1);
    }

    /** {@code +201012345678} becomes {@code +2010*****678}. */
    static String masked(String phoneE164) {
        if (phoneE164 == null || phoneE164.length() < 8) {
            return "***";
        }
        int keepStart = 5;
        int keepEnd = 3;
        return phoneE164.substring(0, keepStart) + "*".repeat(phoneE164.length() - keepStart - keepEnd)
                + phoneE164.substring(phoneE164.length() - keepEnd);
    }

    /** The {@code code} field of the JSON response, or {@code "unreadable"}. */
    private static String resultCode(String body) {
        if (body == null || body.isBlank()) {
            return "unreadable";
        }
        try {
            JsonNode code = JSON.readTree(body).path("code");
            return code.isMissingNode() || code.isNull() ? "unreadable" : code.asText();
        } catch (Exception e) {
            return "unreadable";
        }
    }
}
