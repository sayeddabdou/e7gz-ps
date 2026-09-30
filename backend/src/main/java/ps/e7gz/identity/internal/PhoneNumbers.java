package ps.e7gz.identity.internal;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises a customer phone number to E.164 ({@code iam.user_account.phone_e164}, FR-ACC-01), so uniqueness and the
 * OTP limits per destination (FR-ACC-04) see one form of each number.
 *
 * <p>Accepts an Egyptian mobile number (010, 011, 012, 015) written as {@code 01XXXXXXXXX}, {@code 201XXXXXXXXX},
 * {@code +201XXXXXXXXX} or {@code 00201XXXXXXXXX}; spaces, dashes and brackets are ignored.
 */
public final class PhoneNumbers {

    private static final Pattern EGYPT_MOBILE = Pattern.compile("(?:\\+20|0020|20|0)(1[0125][0-9]{8})");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-()]");

    private PhoneNumbers() {
    }

    /** The number in E.164 form ({@code +201012345678}), or empty when it is not a valid Egyptian mobile number. */
    public static Optional<String> toE164(String input) {
        if (input == null) {
            return Optional.empty();
        }
        Matcher m = EGYPT_MOBILE.matcher(SEPARATORS.matcher(input.strip()).replaceAll(""));
        return m.matches() ? Optional.of("+20" + m.group(1)) : Optional.empty();
    }
}
