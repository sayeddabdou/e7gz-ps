package ps.e7gz.identity.internal.sms;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chooses the {@link SmsSender} from {@code e7gz.sms.provider}. Switching provider is configuration only; the OTP flow
 * does not change. A misconfigured SMS Misr account stops the application at startup instead of failing at the first
 * registration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmsProperties.class)
class SmsConfig {

    @Bean
    SmsSender smsSender(SmsProperties properties, RestClient.Builder restClientBuilder) {
        return switch (properties.provider()) {
            case LOG -> new LoggingSmsSender();
            case SMS_MISR -> smsMisr(properties.smsMisr(), restClientBuilder);
        };
    }

    private static SmsSender smsMisr(SmsProperties.SmsMisr account, RestClient.Builder builder) {
        List<String> missing = account.missingSettings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("e7gz.sms.provider is sms-misr but these settings are missing: " + missing);
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(account.connectTimeout());
        requestFactory.setReadTimeout(account.readTimeout());
        RestClient client = builder.clone()
                .baseUrl(account.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new SmsMisrSmsSender(client, account);
    }
}
