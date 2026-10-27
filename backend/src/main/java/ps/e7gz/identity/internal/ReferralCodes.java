package ps.e7gz.identity.internal;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Every User receives a Referral Code (Spec v5 section 5.7, FR-PTS-06); {@code iam.user_account.referral_code} is
 * required and unique. 8 characters from an alphabet without look-alikes (no 0/O, 1/I/L), about 8.5 x 10^11 codes -
 * the format E7-13 chose, kept by the Product Owner (27 Oct 2026, E7-67). A friend enters it at registration
 * ({@code referred_by_code}); the database records the referral and its reward follows the friend's OTP verification.
 */
final class ReferralCodes {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ReferralCodes() {
    }

    static String newCode() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    /**
     * A friend's code as typed at registration: trimmed and upper-cased, so "k7m3qx9p " is K7M3QX9P; blank is none.
     * Whose code it is, if anyone's, is the database's to decide ({@code loyalty.initialize_user}).
     */
    static String entered(String code) {
        return code == null || code.isBlank() ? null : code.strip().toUpperCase(Locale.ROOT);
    }
}
