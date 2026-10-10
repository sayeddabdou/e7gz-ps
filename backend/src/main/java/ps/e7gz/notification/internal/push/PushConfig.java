package ps.e7gz.notification.internal.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The push adapter of the worker, which alone sends notifications (arch 12 section 1.3). */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableConfigurationProperties(PushProperties.class)
class PushConfig {

    @Bean
    PushSender pushSender(PushProperties properties, RestClient.Builder builder, ObjectMapper json, Clock clock) {
        return switch (properties.provider()) {
            case LOG -> new LoggingPushSender();
            case FCM -> fcm(properties.fcm(), builder, json, clock);
        };
    }

    private static PushSender fcm(PushProperties.Fcm fcm, RestClient.Builder builder, ObjectMapper json, Clock clock) {
        if (fcm.serviceAccount() == null || fcm.serviceAccount().isBlank()) {
            throw new IllegalStateException(
                    "e7gz.push.provider is fcm but e7gz.push.fcm.service-account (E7GZ_FCM_SERVICE_ACCOUNT) is missing");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(fcm.connectTimeout());
        requestFactory.setReadTimeout(fcm.readTimeout());
        RestClient http = builder.clone().baseUrl(fcm.baseUrl().toString()).requestFactory(requestFactory).build();
        FcmAccessTokens tokens = new FcmAccessTokens(
                FcmAccessTokens.ServiceAccount.parse(fcm.serviceAccount(), json),
                builder.clone().requestFactory(requestFactory).build(), json, clock);
        return new FcmPushSender(http, tokens, json);
    }
}
