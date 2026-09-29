package ps.e7gz.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** VALIDATION RULES §2.1 (PO, 30 Sep 2026): at least 8 characters, at least one letter and one digit. */
class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"abcdefg1", "Passw0rd1", "1234567a", "pass word 9", "كلمةسر12", "abcdefg٣"})
    void accepted(String password) {
        assertThat(PasswordPolicy.isAcceptable(password)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc1234", "abcdefgh", "12345678", "!!!!!!!!", "        ", "@@@@@@@1"})
    void refused(String password) {
        assertThat(PasswordPolicy.isAcceptable(password)).isFalse();
    }
}
