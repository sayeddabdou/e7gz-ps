package ps.e7gz.common.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * The preview token of a two-call save (arch 03 §5, FR-VEN-26): a digest that binds a preview to its proposal and its
 * exact affected set. Not a secret - the commit recomputes the set under its locks and compares tokens, so the token
 * only proves which set the venue was shown. Shared by the opening-hours change (E7-25, E7-113) and the normal Venue
 * Closure (E7-81; PO decision 19 Nov 2026, E7-77 D2: "reuse the opening-hours preview token mechanism").
 */
public final class PreviewTokens {

    private PreviewTokens() {
    }

    /** {@code v1.} + the unpadded base64url SHA-256 of {@code canonical}, the caller's canonical text of the preview. */
    public static String of(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
