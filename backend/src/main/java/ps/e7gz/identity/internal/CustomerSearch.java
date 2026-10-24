package ps.e7gz.identity.internal;

import java.util.Locale;
import java.util.Optional;

/**
 * The Platform Admin's customer search, one rule for every admin list (Sprint 4 contract, E7-65 section 2 "Lists"):
 * {@code q} matches part of the full name, username or email, or the exact phone in any form registration accepts
 * ({@link PhoneNumbers}); trimmed and case-insensitive. {@code strpos}, not {@code LIKE}, so {@code %} and {@code _}
 * in {@code q} are plain characters.
 */
final class CustomerSearch {

    /** A predicate on {@code iam.user_account u}: bind {@link Terms#text()} to {@code :q}, the phone to {@code :phone}. */
    static final String MATCHES = """
            (strpos(lower(u.full_name), :q) > 0
             OR strpos(lower(u.username), :q) > 0
             OR strpos(lower(u.email), :q) > 0
             OR u.phone_e164 = CAST(:phone AS text))""";

    private CustomerSearch() {
    }

    /** @param phone the E.164 form of {@code q}, or {@code null} when {@code q} is not a phone number */
    record Terms(String text, String phone) {
    }

    /** Empty for a missing or blank {@code q}: no search. */
    static Optional<Terms> of(String q) {
        if (q == null || q.isBlank()) {
            return Optional.empty();
        }
        String stripped = q.strip();
        return Optional.of(new Terms(stripped.toLowerCase(Locale.ROOT), PhoneNumbers.toE164(stripped).orElse(null)));
    }
}
