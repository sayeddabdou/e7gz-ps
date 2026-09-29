package ps.e7gz.identity.internal;

import java.security.SecureRandom;

/**
 * Every User receives a Referral Code (Spec v5 section 5.7, FR-PTS-06); {@code iam.user_account.referral_code} is
 * required and unique. 8 characters from an alphabet without look-alikes (no 0/O, 1/I/L), about 8.5 x 10^11 codes.
 * The referral flow itself (using a code, rewards) is E7-67.
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
}
