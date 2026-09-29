package ps.e7gz.identity.internal.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** E7-13 - the SMS provider is chosen by configuration only. */
class SmsConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(SmsConfig.class);

    @Test
    void theLogSenderIsTheLocalDefault() {
        context.run(ctx -> assertThat(ctx).hasSingleBean(SmsSender.class)
                .getBean(SmsSender.class).isInstanceOf(LoggingSmsSender.class));
    }

    @Test
    void smsMisrIsChosenByConfiguration() {
        context.withPropertyValues(
                        "e7gz.sms.provider=sms-misr",
                        "e7gz.sms.sms-misr.username=u",
                        "e7gz.sms.sms-misr.password=p",
                        "e7gz.sms.sms-misr.sender=s",
                        "e7gz.sms.sms-misr.template=t")
                .run(ctx -> {
                    assertThat(ctx).getBean(SmsSender.class).isInstanceOf(SmsMisrSmsSender.class);
                    assertThat(ctx.getBean(SmsProperties.class).smsMisr().environment())
                            .isEqualTo(SmsProperties.Environment.TEST);
                });
    }

    @Test
    void anIncompleteSmsMisrAccountStopsStartupAndNamesOnlyTheMissingKeys() {
        context.withPropertyValues(
                        "e7gz.sms.provider=sms-misr",
                        "e7gz.sms.sms-misr.username=u",
                        "e7gz.sms.sms-misr.password=very-secret")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause()
                        .hasMessageContaining("e7gz.sms.sms-misr.sender")
                        .hasMessageContaining("e7gz.sms.sms-misr.template")
                        .hasMessageNotContaining("very-secret"));
    }
}
