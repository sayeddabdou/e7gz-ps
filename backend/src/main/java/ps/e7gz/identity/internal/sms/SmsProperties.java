package ps.e7gz.identity.internal.sms;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SMS configuration ({@code e7gz.sms.*}). Credentials come from environment variables / the secret store only
 * (arch 12 section 3), never from a committed file.
 *
 * @param provider which adapter sends OTPs: {@code log} (local development) or {@code sms-misr}
 * @param smsMisr  the SMS Misr account, used when {@code provider = sms-misr}
 */
@ConfigurationProperties("e7gz.sms")
public record SmsProperties(
        @DefaultValue("log") Provider provider,
        @DefaultValue SmsMisr smsMisr) {

    public enum Provider {
        /** Writes the code to the application log. Local development only. */
        LOG,
        /** SMS Misr OTP API. */
        SMS_MISR
    }

    /**
     * An SMS Misr account (https://smsmisr.com/api/).
     *
     * @param baseUrl        API root; the OTP endpoint is {@code {baseUrl}/OTP/}
     * @param environment    SMS Misr's own switch: {@code LIVE} sends real SMS, {@code TEST} does not
     * @param username       API username
     * @param password       API password
     * @param sender         the Sender Token SMS Misr issues for our approved sender name
     * @param template       the Template Token of our approved OTP message template
     * @param connectTimeout TCP connect timeout
     * @param readTimeout    response timeout; a slow provider must not hold request threads (arch 01 section 7)
     */
    public record SmsMisr(
            @DefaultValue("https://smsmisr.com/api") URI baseUrl,
            @DefaultValue("TEST") Environment environment,
            String username,
            String password,
            String sender,
            String template,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        /** The names of the required settings that are missing. */
        List<String> missingSettings() {
            List<String> missing = new ArrayList<>();
            if (isBlank(username)) {
                missing.add("e7gz.sms.sms-misr.username");
            }
            if (isBlank(password)) {
                missing.add("e7gz.sms.sms-misr.password");
            }
            if (isBlank(sender)) {
                missing.add("e7gz.sms.sms-misr.sender");
            }
            if (isBlank(template)) {
                missing.add("e7gz.sms.sms-misr.template");
            }
            return missing;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        /** Never prints the credentials or tokens (arch 10 section 6). */
        @Override
        public String toString() {
            return "SmsMisr[baseUrl=" + baseUrl + ", environment=" + environment + ", credentials=<redacted>]";
        }
    }

    /** SMS Misr's {@code environment} request parameter. */
    public enum Environment {
        LIVE("1"),
        TEST("2");

        private final String code;

        Environment(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
