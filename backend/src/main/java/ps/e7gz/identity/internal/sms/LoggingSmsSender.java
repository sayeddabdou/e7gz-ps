package ps.e7gz.identity.internal.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development adapter: writes the OTP to the application log instead of sending an SMS, so the whole
 * register / verify flow can be exercised without a provider account (E7-13 step 3).
 *
 * <p>It exists only when {@code e7gz.sms.provider = log}. Every deployed environment sets {@code sms-misr}: a code in
 * a log line is acceptable only on a developer machine (arch 10 section 2.2).
 */
class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    LoggingSmsSender() {
        log.warn("SMS provider is 'log': OTP codes are written to this log and no SMS is sent. Local development only.");
    }

    @Override
    public void sendOtp(String phoneE164, String code) {
        log.info("DEV SMS to {}: OTP {}", phoneE164, code);
    }
}
