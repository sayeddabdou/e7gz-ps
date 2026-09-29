package ps.e7gz.identity.internal;

/**
 * The Customer password rule (VALIDATION RULES section 2.1, Product Owner decision of 30 Sep 2026, E7-13): at least
 * 8 characters, with at least one letter and at least one digit. Applies wherever a Customer sets a password -
 * registration now, password reset and change in E7-57 - so there is one rule in one place.
 *
 * <p>Letters and digits of any script count (Arabic letters, Arabic-Indic digits). Length is counted in characters
 * (code points), not bytes.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    public static boolean isAcceptable(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return false;
        }
        return password.codePoints().anyMatch(Character::isLetter)
                && password.codePoints().anyMatch(Character::isDigit);
    }
}
