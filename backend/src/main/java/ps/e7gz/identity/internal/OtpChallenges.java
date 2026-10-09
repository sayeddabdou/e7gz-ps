package ps.e7gz.identity.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.OtpPurpose;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;

/**
 * SQL for {@code iam.otp_challenge}. The FR-ACC-04 limits are the database's (arch 10 section 2.4): the insert guard
 * refuses a resend inside 60 s ({@code gd_otp_resend_too_soon}) and a 6th send in an hour
 * ({@code gd_otp_hourly_limit}) and supersedes the open code; {@code ck_otp_validity} fixes the 5-minute validity and
 * {@code ck_otp_attempts} the 5 attempts. Nothing here re-checks them.
 */
final class OtpChallenges {

    private OtpChallenges() {
    }

    /** One challenge as the verification flow reads it. */
    record Challenge(UUID userId, String destination, String codeHash, Instant expiresAt, int attempts,
                     Instant consumedAt, Instant supersededAt) {
    }

    /** Issues a new challenge at the command instant. */
    static void insert(CommandContext ctx, UUID id, UUID userId, OtpPurpose purpose, String destination,
                       String codeHash) {
        ctx.jdbc().sql("""
                        INSERT INTO iam.otp_challenge (id, user_id, purpose, destination, code_hash, issued_at, expires_at)
                        VALUES (:id, :userId, :purpose, :destination, :codeHash, :issuedAt,
                                CAST(:issuedAt AS timestamptz) + interval '5 minutes')
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("purpose", purpose.name())
                .param("destination", destination)
                .param("codeHash", codeHash)
                .param("issuedAt", utc(ctx.commandNow()))
                .update();
    }

    /**
     * Issues a new challenge and, in the same transaction, the outbox event that sends its code by SMS once the command
     * commits (E7-41, FR-NTF-12): the code travels sealed ({@link OtpPayloadCipher}), never in plain text.
     */
    static void issue(CommandContext ctx, Outbox outbox, UUID id, UUID userId, OtpPurpose purpose,
                      String destination, String codeHash, String sealedCode) {
        insert(ctx, id, userId, purpose, destination, codeHash);
        outbox.publish(ctx, new NewOutboxEvent(IntegrationEvents.PHONE_OTP_ISSUED, "OTP_CHALLENGE", id,
                Map.of("challengeId", id, "sealedCode", sealedCode)));
    }

    static Optional<Challenge> find(CommandContext ctx, UUID id, OtpPurpose purpose) {
        return ctx.jdbc().sql("""
                        SELECT user_id, destination, code_hash, expires_at, attempts, consumed_at, superseded_at
                          FROM iam.otp_challenge
                         WHERE id = :id AND purpose = :purpose
                        """)
                .param("id", id)
                .param("purpose", purpose.name())
                .query((rs, n) -> new Challenge(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("destination"),
                        rs.getString("code_hash"),
                        instant(rs.getObject("expires_at", OffsetDateTime.class)),
                        rs.getInt("attempts"),
                        instant(rs.getObject("consumed_at", OffsetDateTime.class)),
                        instant(rs.getObject("superseded_at", OffsetDateTime.class))))
                .optional();
    }

    /** A wrong code: one attempt more (the database allows exactly +1 per verification). */
    static void countFailedAttempt(CommandContext ctx, UUID id) {
        ctx.jdbc().sql("UPDATE iam.otp_challenge SET attempts = attempts + 1 WHERE id = :id").param("id", id).update();
    }

    /** The right code: the attempt counts and the challenge is consumed, once. */
    static void consume(CommandContext ctx, UUID id) {
        ctx.jdbc().sql("UPDATE iam.otp_challenge SET attempts = attempts + 1, consumed_at = :now WHERE id = :id")
                .param("now", utc(ctx.commandNow()))
                .param("id", id)
                .update();
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
