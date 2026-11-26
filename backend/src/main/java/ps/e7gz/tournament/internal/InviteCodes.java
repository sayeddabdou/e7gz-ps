package ps.e7gz.tournament.internal;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Invite codes of private Tournaments (FR-TRN-06; arch 03 section 14: "a high-entropy random token, and the link is a
 * deep link carrying it"; E7-91). Sixteen characters of Crockford base32 - digits and upper-case letters without
 * I, L, O and U, so a code read aloud or typed is not mistaken - are 80 random bits from a {@link SecureRandom}: not
 * guessable, and a clash with another code ({@code uq_tournament_invite_code}) is negligible.
 */
final class InviteCodes {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int LENGTH = 16;

    private final SecureRandom random;

    InviteCodes(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
