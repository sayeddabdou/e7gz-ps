package ps.e7gz.notification.internal.webpush;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Venue Web Push settings for every profile (the api serves the public key and checks subscription endpoints) and the
 * sender of the worker, which alone sends notifications (arch 12 section 1.3).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebPushProperties.class)
class WebPushConfig {

    @Bean
    @Profile("worker")
    WebPushSender webPushSender(WebPushProperties properties, RestClient.Builder builder, ObjectMapper json,
                                Clock clock) {
        return switch (properties.provider()) {
            case LOG -> new LoggingWebPushSender();
            case VAPID -> {
                VapidKeys keys = VapidKeys.from(properties.vapid());
                SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
                requestFactory.setConnectTimeout(properties.connectTimeout());
                requestFactory.setReadTimeout(properties.readTimeout());
                yield new VapidWebPushSender(builder.clone().requestFactory(requestFactory).build(), keys, properties,
                        json, clock);
            }
        };
    }
}
