package ps.e7gz.identity.internal;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandContext;

/**
 * Refresh tokens (arch 10 section 2.2–2.3): an opaque 256-bit CSPRNG value that only the client holds; the database
 * keeps its SHA-256 in {@code iam.auth_refresh_token}. Rotated on every use; a rotated token presented again revokes
 * everything issued after it. Rows are revoked, never deleted.
 */
final class RefreshTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private RefreshTokens() {
    }

    /** Arch 10 section 2.3: 30 days (customer), 7 days (venue), 12 hours (admin). */
    static Duration lifetime(AuthSubjectType type) {
        return switch (type) {
            case CUSTOMER -> Duration.ofDays(30);
            case VENUE -> Duration.ofDays(7);
            case ADMIN -> Duration.ofHours(12);
        };
    }

    /** A new raw token: 32 random bytes, Base64url without padding (43 characters). */
    static String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        return Sha256.hex(rawToken);
    }

    /** One stored token, as rotation and logout decide on it. */
    record Row(UUID id, UUID subjectId, AuthSubjectType subjectType, Instant expiresAt, UUID rotatedTo,
               Instant revokedAt) {
    }

    /** The row for a hash, locked until the command commits: rotation and logout decide on it without a race. */
    static Optional<Row> findForUpdate(CommandContext ctx, String tokenHash) {
        return ctx.jdbc().sql("""
                        SELECT id, subject_id, subject_type, expires_at, rotated_to, revoked_at
                          FROM iam.auth_refresh_token
                         WHERE token_hash = :hash
                           FOR UPDATE
                        """)
                .param("hash", tokenHash)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subject_id", UUID.class),
                        AuthSubjectType.valueOf(rs.getString("subject_type")),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("rotated_to", UUID.class),
                        instant(rs.getObject("revoked_at", OffsetDateTime.class))))
                .optional();
    }

    /** Issues a token at the command instant, valid for its type's lifetime. */
    static void insert(CommandContext ctx, UUID id, AuthSubjectType type, UUID subjectId, String tokenHash) {
        ctx.jdbc().sql("""
                        INSERT INTO iam.auth_refresh_token (id, subject_id, subject_type, token_hash, issued_at, expires_at)
                        VALUES (:id, :subjectId, :subjectType, :hash, :issuedAt, :expiresAt)
                        """)
                .param("id", id)
                .param("subjectId", subjectId)
                .param("subjectType", type.name())
                .param("hash", tokenHash)
                .param("issuedAt", OtpChallenges.utc(ctx.commandNow()))
                .param("expiresAt", OtpChallenges.utc(ctx.commandNow().plus(lifetime(type))))
                .update();
    }

    /**
     * Revokes every still-valid token issued after {@code tokenId} by rotation ({@code rotated_to} chain). Repeated
     * until a pass finds nothing left: a rotation that committed while this statement waited on its row appears only
     * to the next statement (READ COMMITTED), and is revoked there. Once the last valid token is revoked this
     * transaction holds its row lock, so it cannot be rotated before the commit.
     */
    static void revokeDescendants(CommandContext ctx, UUID tokenId) {
        int revoked;
        do {
            revoked = ctx.jdbc().sql("""
                            WITH RECURSIVE chain(id) AS (
                                SELECT t.rotated_to FROM iam.auth_refresh_token t
                                 WHERE t.id = :id AND t.rotated_to IS NOT NULL
                                UNION
                                SELECT t.rotated_to FROM iam.auth_refresh_token t JOIN chain c ON t.id = c.id
                                 WHERE t.rotated_to IS NOT NULL)
                            UPDATE iam.auth_refresh_token SET revoked_at = :now
                             WHERE id IN (SELECT id FROM chain) AND revoked_at IS NULL
                            """)
                    .param("id", tokenId)
                    .param("now", OtpChallenges.utc(ctx.commandNow()))
                    .update();
        } while (revoked > 0);
    }

    /**
     * Whether a customer may hold a session, with the User row share-locked until commit: anonymisation
     * ({@code iam.anonymise_user}) locks the row for update and then revokes every customer token, so a token issued
     * here is either refused after it or revoked by it (FR-ACC-10).
     */
    static boolean customerMayHoldSession(CommandContext ctx, UUID userId) {
        return ctx.jdbc().sql("SELECT anonymised_at IS NULL FROM iam.user_account WHERE id = :id FOR SHARE")
                .param("id", userId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
