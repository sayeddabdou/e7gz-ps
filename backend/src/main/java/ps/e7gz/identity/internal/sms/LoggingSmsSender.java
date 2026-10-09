package ps.e7gz.identity.internal.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development adapter: writes the OTP to the application log instead of sending an SMS, so the whole
 * register / verify flow can be exercised without a provider account (E7-13 step 3; arch 12 section 3: "code written to
 * a log the tests read").
 *
 * <p>It exists only when {@code e7gz.sms.provider = log}. Every deployed environment sets {@code sms-misr}: a code in
 * a log line is acceptable only on a developer machine (arch 10 section 2.2; PO decision 9 Oct 2026: it keeps the code,
 * and the E7-101 release checklist checks {@code E7GZ_SMS_PROVIDER}). Even here the phone number is masked to its last
 * 3 digits (arch 10 section 6; Sprint 0 review).
 */
class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);
    private static final int KEEP_LAST = 3;

    LoggingSmsSender() {
        log.warn("SMS provider is 'log': OTP codes are written to this log and no SMS is sent. Local development only.");
    }

    @Override
    public void sendOtp(String phoneE164, String code) {
        log.info("DEV SMS to {}: OTP {}", masked(phoneE164), code);
    }

    /** {@code +201012345678} becomes {@code **********678}: the last 3 digits only (arch 10 section 6). */
    static String masked(String phoneE164) {
        if (phoneE164 == null || phoneE164.length() <= KEEP_LAST) {
            return "***";
        }
        return "*".repeat(phoneE164.length() - KEEP_LAST) + phoneE164.substring(phoneE164.length() - KEEP_LAST);
    }
}
