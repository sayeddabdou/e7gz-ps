package ps.e7gz.trust.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.VerifiedPlayerStatusAt;

/** {@code trust.verified_player_transition} on the command's transaction (E7-69 as-of query). */
@Component
class JdbcVerifiedPlayerStatusAt implements VerifiedPlayerStatusAt {

    static final String STATUS_AT = """
            SELECT v.to_status
              FROM trust.verified_player_transition v
             WHERE v.user_id = :userId
               AND v.occurred_at <= :at
             ORDER BY v.occurred_at DESC, v.id DESC
             LIMIT 1
            """;

    @Override
    public VerifiedPlayerStatus statusAt(CommandContext command, UUID userId, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(at, "at");
        return command.jdbc().sql(STATUS_AT)
                .param("userId", userId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(String.class).optional()
                .map(VerifiedPlayerStatus::valueOf)
                .orElse(VerifiedPlayerStatus.NOT_VERIFIED);
    }
}
