package ps.e7gz.audit.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The exact payload field paths an audit action may persist ({@code audit_event.payload} is "redaction-allowlisted -
 * NEVER a secret", arch 04 section 9). Everything not listed is dropped; nothing is copied wholesale.
 *
 * <p>Path syntax - every path names a <b>leaf</b> value (string, boolean, integer, decimal, UUID, instant, date or
 * enum):
 * <ul>
 *   <li>{@code resourceId} - a top-level field;</li>
 *   <li>{@code window.startAt} - a field of a nested object (the object itself is never allowed as a whole, so an
 *       unknown field inside it cannot pass);</li>
 *   <li>{@code bookingIds[]} - a list of leaf values;</li>
 *   <li>{@code items[].id} - a field of every object in a list.</li>
 * </ul>
 * A path whose segment names a credential or secret (password, passcode, secret, OTP, token, API key, private key,
 * authorization, cookie, card data, PIN, hash, salt) is refused when the allowlist is defined, so such a field can never
 * be allowlisted. The database rejects the same names at any depth ({@code technical.forbidden_payload_key}).
 */
public final class AuditPayloadAllowlist {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\[])?");

    /** Same normalisation and names as {@code technical.forbidden_payload_key} in the physical model. */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(password|passwd|passcode|passphrase|secret|otp|otpcode|token|apikey|privatekey|authorization|cookie|cvv|cvc"
                    + "|cardnumber|pan)$");
    private static final Set<String> SECRET_EXACT = Set.of("pin", "hash", "salt");

    private static final AuditPayloadAllowlist NONE = new AuditPayloadAllowlist(Set.of());

    private final Set<String> paths;

    private AuditPayloadAllowlist(Set<String> paths) {
        this.paths = paths;
    }

    /** An action that records no payload. */
    public static AuditPayloadAllowlist none() {
        return NONE;
    }

    public static AuditPayloadAllowlist of(String... paths) {
        Set<String> checked = new TreeSet<>();
        for (String path : paths) {
            Objects.requireNonNull(path, "path");
            for (String segment : path.split("\\.", -1)) {
                if (!SEGMENT.matcher(segment).matches()) {
                    throw new IllegalArgumentException("Invalid audit payload path '" + path + "'");
                }
                String name = segment.replace("[]", "").replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (SECRET_NAME.matcher(name).find() || SECRET_EXACT.contains(name)) {
                    throw new IllegalArgumentException("Audit payload path '" + path + "' names a credential or secret");
                }
            }
            if (!checked.add(path)) {
                throw new IllegalArgumentException("Duplicate audit payload path '" + path + "'");
            }
        }
        return new AuditPayloadAllowlist(Set.copyOf(checked));
    }

    /** The allowed leaf paths. */
    public Set<String> paths() {
        return paths;
    }

    @Override
    public String toString() {
        return "AuditPayloadAllowlist" + Arrays.toString(new TreeSet<>(paths).toArray());
    }
}
