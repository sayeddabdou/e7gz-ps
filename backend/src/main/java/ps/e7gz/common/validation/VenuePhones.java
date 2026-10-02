package ps.e7gz.common.validation;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A venue's contact phone (FR-DIS-09) in E.164 form (Product Owner decision 2 Oct 2026, E7-23). Accepts an Egyptian
 * mobile number (010, 011, 012, 015 + 8 digits) or landline (Cairo 02 + 8 digits, Alexandria 03 + 7 digits, other
 * governorates a 2-digit code + 7 digits), written with a leading {@code 0}, {@code 20}, {@code +20} or
 * {@code 0020}; spaces, dashes and brackets are ignored. Customer phones stay mobile-only (identity's rule).
 */
public final class VenuePhones {

    private static final Pattern EGYPT = Pattern.compile("(?:\\+20|0020|20|0)(1[0125][0-9]{8}|[2-9][0-9]{7,8})");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-()]");

    private VenuePhones() {
    }

    /** {@code +20…}, or empty when it is not an Egyptian mobile or landline number. */
    public static Optional<String> toE164(String input) {
        if (input == null) {
            return Optional.empty();
        }
        Matcher m = EGYPT.matcher(SEPARATORS.matcher(input.strip()).replaceAll(""));
        return m.matches() ? Optional.of("+20" + m.group(1)) : Optional.empty();
    }
}
