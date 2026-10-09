package ps.e7gz.identity.internal.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Sprint 0 review, E7-41: the development SMS log masks the phone to its last 3 digits (arch 10 section 6); it keeps
 * the code, which is what it is for on a developer machine (PO decision 9 Oct 2026).
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingSmsSenderTest {

    @Test
    void theLogLineShowsOnlyTheLastThreeDigitsOfThePhone(CapturedOutput output) {
        new LoggingSmsSender().sendOtp("+201012345678", "482913");

        assertThat(output.getOut()).contains("**********678").contains("482913")
                .doesNotContain("+201012345678").doesNotContain("1012345");
    }

    @Test
    void theMaskKeepsTheLastThreeDigitsOnly() {
        assertThat(LoggingSmsSender.masked("+201012345678")).isEqualTo("**********678");
        assertThat(LoggingSmsSender.masked("123")).isEqualTo("***");
        assertThat(LoggingSmsSender.masked(null)).isEqualTo("***");
    }
}
