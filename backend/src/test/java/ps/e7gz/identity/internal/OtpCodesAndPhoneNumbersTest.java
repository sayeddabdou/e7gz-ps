package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** E7-13 - OTP codes, phone normalisation and referral codes. */
class OtpCodesAndPhoneNumbersTest {

    @Test
    void codesAreSixDigits() {
        for (int i = 0; i < 200; i++) {
            assertThat(OtpCodes.newCode()).matches("[0-9]{6}");
        }
    }

    @Test
    void theStoredHashMatchesOnlyItsCodeAndHidesIt() {
        String stored = OtpCodes.hash("482913");

        assertThat(stored).doesNotContain("482913");
        assertThat(OtpCodes.matches("482913", stored)).isTrue();
        assertThat(OtpCodes.matches("482914", stored)).isFalse();
        assertThat(OtpCodes.matches("482913", "garbage")).isFalse();
        assertThat(OtpCodes.matches("482913", "!!:!!")).isFalse();
        assertThat(OtpCodes.hash("482913")).as("salted per challenge").isNotEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"01012345678", "+201012345678", "201012345678", "00201012345678", "010 1234-5678",
            " (010) 12345678 "})
    void everyWritingOfAnEgyptianMobileNormalisesToOneE164Form(String input) {
        assertThat(PhoneNumbers.toE164(input)).contains("+201012345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "0101234567", "010123456789", "01312345678", "+441012345678", "abc"})
    void anythingElseIsRejected(String input) {
        assertThat(PhoneNumbers.toE164(input)).isEmpty();
    }

    @Test
    void referralCodesAreEightUnambiguousCharacters() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = ReferralCodes.newCode();
            assertThat(code).matches("[A-HJKMNP-Z2-9]{8}");
            seen.add(code);
        }
        assertThat(seen).hasSizeGreaterThan(495);
    }
}
